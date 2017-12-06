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

package io.enmasse.barnabas.authentication.ldap;

import org.apache.directory.api.ldap.model.exception.LdapAuthenticationException;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.api.ldap.model.message.BindRequest;
import org.apache.directory.api.ldap.model.message.BindRequestImpl;
import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;

import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * A {@link SaslServer} implementation for SASL-PLAIN which delegates
 * authentication to an LDAP bind against some "upstream" LDAP server.
 * The LDAP bind can be simple, or SASL-PLAIN
 */
public class LdapBindingSaslServer implements SaslServer {

    private final Bind bind;
    private boolean complete = false;
    private String authorizationId;

    public LdapBindingSaslServer(Bind bind) {
        this.bind = bind;
    }

    @Override
    public String getMechanismName() {
        return "PLAIN";
    }

    @Override
    public byte[] evaluateResponse(byte[] response) throws SaslException {
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

        boolean authenticated = bind.bind(username, password);
        if (authenticated) {
            complete = true;
            this.authorizationId = username;
            return new byte[0];
        } else {
            throw new SaslException("Authentication failed (invalid credentials)");
        }

    }

    @Override
    public boolean isComplete() {
        return complete;
    }

    @Override
    public String getAuthorizationID() {
        return authorizationId;
    }

    @Override
    public byte[] unwrap(byte[] incoming, int offset, int len) throws SaslException {
        if (!complete)
            throw new IllegalStateException("Authentication exchange has not completed");
        return Arrays.copyOfRange(incoming, offset, offset + len);
    }

    @Override
    public byte[] wrap(byte[] outgoing, int offset, int len) throws SaslException {
        if (!complete)
            throw new IllegalStateException("Authentication exchange has not completed");
        return Arrays.copyOfRange(outgoing, offset, offset + len);
    }

    @Override
    public Object getNegotiatedProperty(String propName) {
        if (!complete)
            throw new IllegalStateException("Authentication exchange has not completed");
        return null;
    }

    @Override
    public void dispose() throws SaslException {
        bind.dispose();
    }
}
