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
import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;

import javax.security.sasl.SaslException;
import java.io.IOException;

/**
 * A simple bind.
 */
public class SimpleBind implements Bind {
    private final LdapConnection connection;
    private final String dnFormat;

    public SimpleBind(LdapConnection connection, String dnFormat) {
        this.connection = connection;
        this.dnFormat = dnFormat;
    }

    @Override
    public boolean bind(String username, String password) throws SaslException {
        boolean authenticated = false;
        try {
            try {
                String usernameDn = String.format(dnFormat, username);
                connection.bind(usernameDn, password);
                authenticated = true;
            } catch (LdapAuthenticationException e) {
                // not authorised
                authenticated = false;
            } finally {
                connection.anonymousBind();
            }
        } catch (LdapException e) {
            throw new SaslException("LDAP simple bind failed", e);
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
        LdapNetworkConnection connection = new LdapNetworkConnection("localhost", 10389);
        try {
            connection.connect();
            SimpleBind bind = new SimpleBind(connection, "uid=%s,ou=kafka,dc=example,dc=com");
            boolean authenticated = bind.bind("tom", "tom");
            System.out.println(authenticated);
        } finally {
            connection.close();
        }
    }
}
