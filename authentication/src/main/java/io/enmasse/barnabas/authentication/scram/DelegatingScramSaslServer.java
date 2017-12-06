/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.enmasse.barnabas.authentication.scram;

import org.apache.kafka.common.security.scram.ScramFormatter;
import org.apache.kafka.common.security.scram.ScramMechanism;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.EndpointState;
import org.apache.qpid.proton.engine.Sasl;
import org.apache.qpid.proton.engine.Transport;
import org.apache.qpid.proton.engine.TransportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.security.auth.callback.CallbackHandler;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.apache.kafka.common.security.scram.ScramMessages.ClientFirstMessage;
import static org.apache.qpid.proton.engine.Sasl.SaslState.PN_SASL_FAIL;
import static org.apache.qpid.proton.engine.Sasl.SaslState.PN_SASL_IDLE;
import static org.apache.qpid.proton.engine.Sasl.SaslState.PN_SASL_PASS;
import static org.apache.qpid.proton.engine.Sasl.SaslState.PN_SASL_STEP;

/**
 * A SaslServer that delegates to another SASL/SCRAM server.
 */
public class DelegatingScramSaslServer implements SaslServer {

    private final static Logger logger = LoggerFactory.getLogger(DelegatingScramSaslServer.class);
    public static final String PROP_SASL_HOST_NAME = "barnabas.sasl.delegation.sasl.host";
    public static final String PROP_AMQP_HOST_NAME = "barnabas.sasl.delegation.amqp.host";
    public static final String PROP_AMQP_PORT = "barnabas.sasl.delegation.amqp.port";

    private static final String[] TLS_PROTOCOL_PREFERENCES = new String[]{"TLSv1.2", "TLSv1.1", "TLS", "TLSv1"};
    private static final Symbol AUTHENTICATED_IDENTITY = Symbol.valueOf("authenticated-identity");
    public static final String PREFERRED_USERNAME = "preferred_username";
    public static final String SUB = "sub";
    public static final String GROUPS = "groups";
    public static final String PROP_CONTAINER_ID = "container_id";

    private final ScramMechanism mechanism;
    private final ScramFormatter formatter;
    private final String amqpHost;
    private final Integer amqpPort;
    private final boolean useTls = false;
    private final String saslHostname;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private Transport transport;
    private Connection connection;
    private org.apache.qpid.proton.engine.Sasl sasl;

    private SSLContext sslContext;
    // These come from reading the first client message
    private String authorizationId;
    private String container;
    private String saslName;
    private String username;

    /** Enumerate the possible states we can be in */
    private enum State {
        /** Initial state */
        INITIAL,
        /** We've passed our client's initial response, containing the
         * username and client nonce, to the upstream AMQP server */
        AWAITING_CHALLENGE,
        /**
         * We've received the upstream AMQP server's challenge, containing the
         * salt and iteration count, and have passed it to the client and are
         * awaiting the clients proof.
         */
        AWAITING_PROOF,
        /**
         * We have received the clients proof, and have forwarded it upstream to validate
         */
        EVALUATING_PROOF,
        /**
         * The upstream AMQP server has successfully validated the proof
         */
        SUCCESS,
        /**
         * The upstream AMQP server has rejected the proof.
         */
        FAILURE
    }


    private State state;

    public DelegatingScramSaslServer(ScramMechanism mechanism,
                                     ScramFormatter formatter,
                                     Map<String, ?> props) {
        logger.info("<init>(), props="+props);
        this.mechanism = mechanism;
        this.formatter = formatter;
        final Properties properties = System.getProperties();
        this.saslHostname = Objects.requireNonNull(getStringProperty(properties, PROP_SASL_HOST_NAME, null));
        this.amqpHost = Objects.requireNonNull(getStringProperty(properties, PROP_AMQP_HOST_NAME, null));
        this.amqpPort = Objects.requireNonNull(getIntegerProperty(properties, PROP_AMQP_PORT, null));
        state(State.INITIAL);
    }

    private static String getStringProperty(Properties props, String prop, String defaultValue) {
        Object p;
        if ((p = props.get(prop)) instanceof String) {
            return (String) p;
        } else if (p != null) {
            logger.warn("property {} was not a String", prop);
        }
        return defaultValue;
    }

    private static Integer getIntegerProperty(Properties props, String prop, Integer defaultValue) {
        Object p;
        if ((p = props.get(prop)) instanceof Integer) {
            return (Integer) p;
        } else if (p  instanceof String) {
            return new Integer(((String) p).trim());
        } else if (p != null) {
            logger.warn("property {} was not an Integer, nor parseable as an Integer", prop);
        }
        return defaultValue;
    }

    @Override
    public String getMechanismName() {
        return mechanism.mechanismName();
    }

    private void state(State state) {
        debug("state {} -> {}", this.state, state);
        this.state = state;
    }

    @Override
    public byte[] evaluateResponse(byte[] response) throws SaslException {
        logger.info("evaluateResponse()");
        try {
            switch (state) {
                case INITIAL:
                    // Extract saslname, username, authId from client request
                    debug("initial client message: {}", new String(response, "UTF-8"));
                    ClientFirstMessage clientFirstMessage = new ClientFirstMessage(response);
                    saslName = clientFirstMessage.saslName();
                    username = formatter.username(saslName);
                    authorizationId = clientFirstMessage.authorizationId();
                    debug("initial client message has saslName: {}, username: {}, authorizationId: {}",
                            saslName, username, authorizationId);
                    // Create a client for the upstream server with this info
                    transport = Proton.transport();
                    connection = Proton.connection();
                    transport.bind(connection);
                    sasl = transport.sasl();
                    sasl.client();

                    socket = createSocket();

                    in = socket.getInputStream();
                    out = socket.getOutputStream();

                    transport.open();

                    // write Headers
                    writeToNetwork(connection, out);

                    Mechanism mechanism = chooseSaslMechanismAndSendInit(connection, in, out, response);

                    state(State.AWAITING_CHALLENGE);

                    debug("forwarding initial client message to upstream {}:{}", this.amqpHost, this.amqpPort);
                    do {

                        readFromNetwork(connection, in, () ->
                                !(EnumSet.of(PN_SASL_PASS, PN_SASL_FAIL).contains(sasl.getState())
                                        || (sasl.getState() == PN_SASL_STEP && sasl.pending() > 0)));

                        if (sasl.pending() > 0) {
                            // reply with the upstream server's response
                            // (which should contain the salt and iterCount)
                            final byte[] challenge = readPending();
                            debug("received challenge from upstream: {}", new String(challenge, "UTF-8"));
                            state(State.AWAITING_PROOF);
                            return challenge;
                        }

                    } while (sasl.getState() == PN_SASL_STEP);

                    // If we get here it means we we're no longer in a SASL exchange upstream
                    // which can happen if we've violated the protocol
                    throw new SaslException("Upstream server ended the SASL exchange prematurely");
                case AWAITING_PROOF:
                    state(State.EVALUATING_PROOF);
                    // Pass the client's proof directly to the upstream server
                    debug("final client message: {}", new String(response, "UTF-8"));
                    debug("forwarding final client request to upstream {}:{}", this.amqpHost, this.amqpPort);

                    sasl.send(response, 0, response.length);
                    writeToNetwork(connection, out);

                    readFromNetwork(connection, in, () ->
                            !(EnumSet.of(PN_SASL_PASS, PN_SASL_FAIL).contains(sasl.getState())
                                    || (sasl.getState() == PN_SASL_STEP && sasl.pending() > 0)));
                    final byte[] serverResponse = readPending();

                    final String utf8 = new String(serverResponse, "UTF8");
                    debug("received upstream response: '{}'", utf8);
                    if (state == State.EVALUATING_PROOF && utf8.startsWith("v=")) {
                        state(State.SUCCESS);
                        // This is pointless because we have no way to pass this info back to Kafka
                        //debug("opening AMQP connection");
                        //performConnectionOpen(connection, in, out);
                        //getUserAndRolesFromConnection(connection);
                        //success = true;
                        authorizationId = username;
                        return serverResponse;
                    } else {
                        state(State.FAILURE);
                        logger.info("login for user {} failed with outcome {}", this.username, sasl.getOutcome());
                        throw new SaslException("Authentication failed (invalid credentials)");
                    }

                    // Pass the response back to the client.
                    // We cannot authenticate the upstream server
                    // (that would require access to the salted password)
                    // but we assume our client will authenticate the
                    // upstream server and disconnect if it's invalid.

                default:
                    throw new SaslException("evaluateResponse called when in state " + state);
            }
        } catch (Exception e) {
            throw new SaslException("", e);
        }
    }

    private byte[] readPending() {
        byte[] challenge = new byte[sasl.pending()];
        sasl.recv(challenge, 0, challenge.length);
        return challenge;
    }

    private Mechanism chooseSaslMechanismAndSendInit(Connection connection,
                                                         InputStream in,
                                                         OutputStream out,
                                                         byte[] response) throws SaslException, IOException {

        Transport transport = connection.getTransport();
        org.apache.qpid.proton.engine.Sasl sasl = transport.sasl();
        // read from network until we get a sasl-mechanisms
        readFromNetwork(connection, in, () -> sasl.getState() == PN_SASL_IDLE && sasl.getRemoteMechanisms().length == 0);

        final List<String> remoteMech = Arrays.asList(sasl.getRemoteMechanisms());
        debug("remote mechanisms: {}", remoteMech);
        Mechanism mechanism = Mechanism.SCRAM_SHA_256;
        boolean found = false;
        for (String m : remoteMech) {
            if (mechanism.mechanismName().equals(m)) {
                found = true;
            }
        }
        if (!found) {
            throw new SaslException("Upstream server supports mechanisms " + remoteMech + " which doesn't include " + mechanism.mechanismName());
        }
        debug("using mechanism {}", mechanism.mechanismName());
        sasl.setRemoteHostname(this.saslHostname);
        sasl.setMechanisms(mechanism.mechanismName());
        sasl.send(response, 0, response.length);

        writeToNetwork(connection, out);
        return mechanism;
    }

    private Socket createSocket() throws IOException, SaslException {
        if(this.useTls) {
            if(sslContext == null) {
                throw new SaslException("Unable to establish SSL connection due to configuration errors");
            }
            return sslContext.getSocketFactory().createSocket(amqpHost, amqpPort);
        } else {
            return new Socket(amqpHost, amqpPort);
        }
    }

    private void writeToNetwork(Connection connection, OutputStream out) throws IOException {
        Transport transport = connection.getTransport();
        while(transport.pending() > 0)
        {
            ByteBuffer outputBuf = transport.head();
            final int size = outputBuf.remaining();
            byte[] tmpBuf = new byte[size];
            outputBuf.get(tmpBuf);
            logger.trace("writing {0} bytes", size);
            out.write(tmpBuf);
            transport.pop(size);
        }
    }

    private void readFromNetwork(Connection connection, InputStream in, BooleanSupplier test) throws IOException, SaslException {
        Transport transport = connection.getTransport();

        while(test.getAsBoolean()) {
            ByteBuffer buf = transport.getInputBuffer();
            byte[] tmpBuf = new byte[buf.remaining()];
            int bytesRead = in.read(tmpBuf);
            logger.trace("read {0} bytes", bytesRead);
            if (bytesRead == -1) {
                throw new SaslException("Unexpected EOS experienced when authenticating using SASL delegation");
            } else {
                buf.put(tmpBuf, 0, bytesRead);
                TransportResult result = transport.processInput();
                if(!result.isOk()) {
                    SaslException e = new SaslException("Unexpected error when authenticating using SASL delegation");
                    e.initCause(result.getException());
                    throw e;
                }
            }

        }

    }

    private boolean isSaslAuthenticated(Connection connection) {
        Transport transport = connection.getTransport();
        final boolean authenticated = sasl.getState() == PN_SASL_PASS && state == State.EVALUATING_PROOF;
        debug("sasl state: {}, my state {} => {}", sasl.getState(), state, authenticated);
        return authenticated;
    }

    private void getUserAndRolesFromConnection(Connection connection) {
        final Map<Symbol, Object> remoteProperties = connection.getRemoteProperties();
        if (remoteProperties != null && remoteProperties.get(AUTHENTICATED_IDENTITY) instanceof Map) {
            Map identity = (Map) remoteProperties.get(AUTHENTICATED_IDENTITY);
            String user;
            if (identity.containsKey(PREFERRED_USERNAME)) {
                user = String.valueOf(identity.get(PREFERRED_USERNAME)).trim();
            } else {
                user = String.valueOf(identity.get(SUB)).trim();
            }
            Set<String> roles = new HashSet<>();
            if (remoteProperties.get(Symbol.valueOf(GROUPS)) instanceof List) {
                roles.addAll((List<String>) remoteProperties.get(Symbol.valueOf(GROUPS)));
            }
            roles.add("all");
            debug("user {}, roles {}", user, roles);
        }
    }

    private void performConnectionOpen(Connection connection, InputStream in, OutputStream out) throws IOException, SaslException {
        connection.setHostname(saslName);
        connection.setContainer(container);
        connection.open();
        writeToNetwork(connection, out);
        readFromNetwork(connection, in, () -> connection.getRemoteState() == EndpointState.UNINITIALIZED);
    }

    @Override
    public boolean isComplete() {
        return state == State.SUCCESS || state == State.FAILURE;
    }

    @Override
    public String getAuthorizationID() {
        return authorizationId;
    }

    @Override
    public byte[] unwrap(byte[] incoming, int offset, int len) throws SaslException {
        if (!isComplete())
            throw new IllegalStateException("Authentication exchange has not completed");
        return Arrays.copyOfRange(incoming, offset, offset + len);
    }

    @Override
    public byte[] wrap(byte[] outgoing, int offset, int len) throws SaslException {
        if (!isComplete())
            throw new IllegalStateException("Authentication exchange has not completed");
        return Arrays.copyOfRange(outgoing, offset, offset + len);
    }

    @Override
    public Object getNegotiatedProperty(String propName) {
        if (!isComplete())
            throw new IllegalStateException("Authentication exchange has not completed");
        return null;
    }

    @Override
    public void dispose() throws SaslException {
        connection.close();
        transport.close();
        try {
            socket.close();
        } catch (IOException e) {
            throw new SaslException("Error closing socket during dispose()", e);
        }
        sasl = null;
        authorizationId = null;
        username = null;
    }

    private void debug(String format, Object... arguments) {
        // Log using our hashcode as context, so we can correlate log messages
        // for a given SASL exchange
        //if (logger.isDebugEnabled()) {
            logger.info(System.identityHashCode(this) + ": " + format, arguments);
        //}
    }
}
