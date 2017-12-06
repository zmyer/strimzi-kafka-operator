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
import org.apache.directory.api.ldap.model.exception.LdapInvalidDnException;
import org.apache.directory.api.ldap.model.message.BindRequest;
import org.apache.directory.api.ldap.model.message.BindRequestImpl;
import org.apache.directory.api.ldap.model.message.BindResponse;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.ldap.client.api.AbstractLdapConnection;
import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.apache.directory.ldap.client.api.SaslCramMd5Request;
import org.apache.directory.ldap.client.api.SaslDigestMd5Request;
import org.apache.directory.ldap.client.api.SaslRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.sasl.SaslException;
import java.io.IOException;

/**
 * A SASL bind using a given SASL mechanism.
 */
public class SaslBind implements Bind {

    private final static Logger logger = LoggerFactory.getLogger(SaslBind.class);

    private final LdapNetworkConnection connection;
    private final String saslMechanism;

    public SaslBind(LdapConnection connection, String saslMechanism) {
        if (!(connection instanceof LdapNetworkConnection)) {
            // As of Aapache Directory API/1.0.0 SASL is not supported in the LdapConnection API
            throw new RuntimeException("Not supported");
        }
        this.connection = (LdapNetworkConnection) connection;
        this.saslMechanism = saslMechanism;
    }

    @Override
    public boolean bind(String username, String password) throws SaslException {

        boolean authenticated = false;
        try {
            try {
                logger.info("binding for user {} using mechanism {}", username, saslMechanism);
                BindResponse response;
                switch (saslMechanism) {

                    case "CRAM-MD5": {
                        SaslCramMd5Request bindRequest = new SaslCramMd5Request();
                        bindRequest.setUsername(username);
                        bindRequest.setAuthorizationId(username);
                        bindRequest.setCredentials(password);
                        response = connection.bind((SaslCramMd5Request) bindRequest);
                        break;
                    }
                    case "DIGEST-MD5": {
                        SaslDigestMd5Request bindRequest = new SaslDigestMd5Request();
                        bindRequest.setUsername(username);
                        bindRequest.setAuthorizationId(username);
                        bindRequest.setCredentials(password);
                        bindRequest.setRealmName("example.com");
                        response = connection.bind((SaslDigestMd5Request) bindRequest);
                        break;
                    }
                    default:
                        throw new SaslException();

                }
                authenticated = response.getLdapResult().isDefaultSuccess();
            } catch (LdapAuthenticationException e) {
                authenticated = false;
            } finally {
                //connection.anonymousBind();
            }
        } catch (LdapException e) {
            throw new SaslException("LDAP SASL-"+saslMechanism+" bind failed", e);
        }
        return authenticated;
    }

    @Override
    public void dispose() throws SaslException {
        try {
            this.connection.close();
        } catch (IOException e) {
            throw new SaslException("Error closing LDAP connection", e);
        }
    }

    public static void main(String[] args) throws Exception {
        LdapNetworkConnection connection = new LdapNetworkConnection("ldap.example.com", 10389);
        try {
            connection.connect();
            SaslBind bind = new SaslBind(connection, "CRAM-MD5");
            boolean authenticated = bind.bind("tom", "tom");
            System.out.println(authenticated);
        } finally {
            connection.close();
        }
    }
}
