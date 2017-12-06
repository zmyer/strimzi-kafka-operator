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

import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.apache.kafka.common.security.JaasContext;
import org.apache.kafka.common.security.authenticator.SaslServerCallbackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.callback.CallbackHandler;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import javax.security.sasl.SaslServerFactory;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;

public class LdapBindingSaslServerFactory implements SaslServerFactory {

    private final static Logger logger = LoggerFactory.getLogger(LdapBindingSaslServerFactory.class);

    public static final int DEFAULT_LDAPS_PORT = 636;

    public static final int DEFAULT_LDAP_PORT = 389;

    @Override
    public SaslServer createSaslServer(String mechanism, String protocol, String serverName, Map<String, ?> props, CallbackHandler cbh) throws SaslException {
        if (cbh instanceof SaslServerCallbackHandler) {
            JaasContext jaasContext = ((SaslServerCallbackHandler) cbh).jaasContext();
            String ldapUrlsString = jaasContext.configEntryOption("ldap_urls", LdapBindingLoginModule.class.getName());
            List<String> ldapUrls = asList(ldapUrlsString.trim().split("\\s*,\\s*"));
            LdapConnection connection = getConnection(ldapUrls);
            try {
                String bindType = jaasContext.configEntryOption("bind", LdapBindingLoginModule.class.getName());
                if (bindType == null) {
                    bindType = "simple";
                } else {
                    bindType.toLowerCase();
                }
                switch (bindType) {
                    case "simple": {
                        String dnFormat = jaasContext.configEntryOption("dn_format", LdapBindingLoginModule.class.getName());
                        if (dnFormat == null || dnFormat.isEmpty()) {
                            throw new SaslException("Missing dn_format configuration parameter for bind=simple");
                        }
                        SimpleBind bind = new SimpleBind(connection, dnFormat);
                        return new LdapBindingSaslServer(bind);
                    }
                    /*case "sasl-plain": {
                        SaslBind bind = new SaslBind(connection, "PLAIN");
                        return new LdapBindingSaslServer(bind);
                    }
                    case "sasl-digest-md5": {
                        SaslBind bind = new SaslBind(connection, "DIGEST-MD5");
                        return new LdapBindingSaslServer(bind);
                    }
                    case "sasl-cram-md5": {
                        SaslBind bind = new SaslBind(connection, "CRAM-MD5");
                        return new LdapBindingSaslServer(bind);
                    }*/
                    default:
                        throw new SaslException("Unsupported 'bind' type " + bindType);
                }
            } catch (Exception e) {
                // Don't close connection in a finally clause because if we return normally
                // then it will be the bind's job to close it.
                try {
                    connection.close();
                } catch (IOException e1) {
                    e.addSuppressed(e1);
                }
                throw e;
            }
        } else {
            throw new SaslException("Cound not obtain JAAS context");
        }

    }

    private LdapConnection getConnection(List<String> ldapUrls) throws SaslException {
        // Shuffle the list, so we hit an random server each time.
        ldapUrls = new ArrayList<>(ldapUrls);
        Collections.shuffle(ldapUrls);
        for (String ldapUrl : ldapUrls) {
            URI uri = URI.create(ldapUrl);
            String scheme = uri.getScheme();
            final boolean ldaps;
            if ("ldap".equals(scheme)) {
                ldaps = false;
            } else if ("ldaps".equals(scheme)) {
                ldaps = true;
            } else {
                throw new SaslException("Cound not obtain scheme from ldap_urls value '" + ldapUrl +"', it shoudld be either 'ldap' or 'ldaps'");
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                throw new SaslException("Cound not obtain host from ldap_urls value '" + ldapUrl);
            }
            int port = uri.getPort();
            if (port == -1) {
                port = ldaps ? DEFAULT_LDAPS_PORT : DEFAULT_LDAP_PORT;
            }
            LdapConnection connection = new LdapNetworkConnection(host, port, ldaps);
            try {
                connection.connect();
                return connection;
            } catch (LdapException e) {
                // continue
            }
        }
        throw new SaslException("Failed to connect to any of the "+ldapUrls.size()+" given ldap servers");
    }

    @Override
    public String[] getMechanismNames(Map<String, ?> props) {
        return new String[]{"PLAIN"};
    }
}
