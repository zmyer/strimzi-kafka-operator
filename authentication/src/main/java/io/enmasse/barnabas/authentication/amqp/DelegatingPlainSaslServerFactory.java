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

import io.enmasse.barnabas.authentication.scram.Mechanism;
import org.apache.kafka.common.security.JaasContext;
import org.apache.kafka.common.security.authenticator.SaslServerCallbackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.callback.CallbackHandler;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import javax.security.sasl.SaslServerFactory;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;

/**
 * Factory class for {@link DelegatingPlainSaslServer}, so it can be loaded
 * via {@link javax.security.sasl.Sasl#createSaslServer(String, String, String, Map, CallbackHandler)}.
 */
public class DelegatingPlainSaslServerFactory implements SaslServerFactory {

    private final static Logger logger = LoggerFactory.getLogger(DelegatingPlainSaslServerFactory.class);
    public static final int DEFAULT_AMQP_PORT = 5672;

    @Override
    public SaslServer createSaslServer(String mechanism, String protocol, String serverName, Map<String, ?> props, CallbackHandler cbh)
            throws SaslException {
        if (cbh instanceof SaslServerCallbackHandler) {
            JaasContext jaasContext = ((SaslServerCallbackHandler) cbh).jaasContext();
            String amqpHost = jaasContext.configEntryOption("amqp_host", DelegatingPlainSaslServerLoginModule.class.getName());
            String portStr = jaasContext.configEntryOption("amqp_port", DelegatingPlainSaslServerLoginModule.class.getName());
            int port;
            if (portStr == null) {
                port = DEFAULT_AMQP_PORT;
            } else {
                port = Integer.parseInt(portStr);
            }
            String realm = jaasContext.configEntryOption("realm", DelegatingPlainSaslServerLoginModule.class.getName());

            logger.info("Creating DelegatingPlainSaslServer with host={}, port={}, realm={}", amqpHost, port, realm);
            return new DelegatingPlainSaslServer(amqpHost, port, realm);
        } else {
            throw new SaslException("Unable to obtain JAAS context");
        }
    }

    @Override
    public String[] getMechanismNames(Map<String, ?> props) {
        return new String[]{"PLAIN"};
    }
}
