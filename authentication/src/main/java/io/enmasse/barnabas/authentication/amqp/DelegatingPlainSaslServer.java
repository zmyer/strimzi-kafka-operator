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

package io.enmasse.barnabas.authentication.amqp;

import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.Sasl;
import org.apache.qpid.proton.engine.Transport;
import org.apache.qpid.proton.engine.TransportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.security.auth.login.LoginException;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Properties;
import java.util.function.BooleanSupplier;

import static org.apache.qpid.proton.engine.Sasl.SaslState.PN_SASL_FAIL;
import static org.apache.qpid.proton.engine.Sasl.SaslState.PN_SASL_IDLE;
import static org.apache.qpid.proton.engine.Sasl.SaslState.PN_SASL_PASS;
import static org.apache.qpid.proton.engine.Sasl.SaslState.PN_SASL_STEP;

/**
 * A SaslServer that delegates to another SASL/SCRAM server.
 */
public class DelegatingPlainSaslServer implements SaslServer {

    private final static Logger logger = LoggerFactory.getLogger(DelegatingPlainSaslServer.class);
    public static final String PROP_SASL_HOST_NAME = "barnabas.sasl.delegation.sasl.host";
    public static final String PROP_AMQP_HOST_NAME = "barnabas.sasl.delegation.amqp.host";
    public static final String PROP_AMQP_PORT = "barnabas.sasl.delegation.amqp.port";

    private static final String[] TLS_PROTOCOL_PREFERENCES = new String[]{"TLSv1.2", "TLSv1.1", "TLS", "TLSv1"};
    private static final Symbol AUTHENTICATED_IDENTITY = Symbol.valueOf("authenticated-identity");
    public static final String PREFERRED_USERNAME = "preferred_username";
    public static final String SUB = "sub";
    public static final String GROUPS = "groups";
    public static final String PROP_CONTAINER_ID = "container_id";

    private final String amqpHost;
    private final Integer amqpPort;
    private final boolean useTls = false;
    private final String saslHostname;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private Transport transport;
    private Connection connection;
    private Sasl sasl;

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

    public DelegatingPlainSaslServer(String amqpHost, int amqpPort, String saslHostname) {
        this.saslHostname = saslHostname;
        this.amqpHost = amqpHost;
        this.amqpPort = amqpPort;
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
        return "PLAIN";
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
                    String initialClientMessage = new String(response, StandardCharsets.UTF_8);
                    String[] parts = initialClientMessage.split("\u0000", 3);
                    if (parts.length != 3) {
                        throw new SaslException("Badly formed SASL-PLAIN request: Two NULs expected in request.");
                    }

                    String username = parts[1];
                    if (username.isEmpty()) {
                        throw new SaslException("Badly formed SASL-PLAIN request: empty username");
                    }
                    String authorizationId = parts[0];
                    if (!(authorizationId.isEmpty() || authorizationId.equals(username))) {
                        throw new SaslException("Authentication with authorization id is not supported");
                    }
                    String password = parts[2];
                    if (password.isEmpty()) {
                        throw new SaslException("Badly formed SASL-PLAIN request: empty password");
                    }

                    logger.info("username={}, password={}", username, password);

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

                    chooseSaslMechanismAndSendInit(connection, in, out, username, password, response);

                    debug("forwarding initial client message to upstream {}:{}", this.amqpHost, this.amqpPort);

                    performSaslSteps(connection, in, out);
                    if (sasl.getState() == PN_SASL_PASS) {
                        state(State.SUCCESS);
                        this.authorizationId = username;
                        return new byte[0];
                    } else {
                        state(State.FAILURE);
                        throw new SaslException("Authentication failed (invalid credentials)");
                    }
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

    private void chooseSaslMechanismAndSendInit(Connection connection,
                                                 InputStream in,
                                                 OutputStream out,
                                                 String username, String password,
                                                 byte[] response
    ) throws SaslException, IOException {

        Transport transport = connection.getTransport();
        Sasl sasl = transport.sasl();
        // read from network until we get a sasl-mechanisms
        readFromNetwork(connection, in, () -> sasl.getState() == PN_SASL_IDLE && sasl.getRemoteMechanisms().length == 0);

        debug("remote mechanisms: {}", Arrays.asList(sasl.getRemoteMechanisms()));
        sasl.setRemoteHostname(this.saslHostname);
        sasl.setMechanisms("PLAIN");

        //sasl.plain(username, password);
        sasl.send(response, 0, response.length);

        writeToNetwork(connection, out);
    }

    private void performSaslSteps(Connection connection, InputStream in,
                                  OutputStream out) throws IOException, LoginException {
        Transport transport = connection.getTransport();
        Sasl sasl = transport.sasl();
        do {

            readFromNetwork(connection, in, () ->
                    !(EnumSet.of(PN_SASL_PASS, PN_SASL_FAIL).contains(sasl.getState())
                            || (sasl.getState() == PN_SASL_STEP && sasl.pending() > 0)));

            if (sasl.pending() > 0) {
                byte[] challenge = new byte[sasl.pending()];
                byte[] response = new byte[0];
                if (sasl.getState() == PN_SASL_STEP) {
                    sasl.send(response, 0, response.length);
                    writeToNetwork(connection, out);
                }
            }

        } while (sasl.getState() == PN_SASL_STEP);
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
    @Override
    public boolean isComplete() {
        return state == State.SUCCESS || state == State.FAILURE;
    }

    @Override
    public String getAuthorizationID() {
        if (state != State.SUCCESS)
            throw new IllegalStateException("Authorization is not yet complete");
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
        if (connection != null) {
            connection.close();
        }
        if (transport != null) {
            transport.close();
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                throw new SaslException("Error closing socket during dispose()", e);
            }
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
