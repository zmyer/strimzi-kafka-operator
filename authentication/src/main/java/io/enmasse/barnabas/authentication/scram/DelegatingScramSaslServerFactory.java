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

import org.apache.kafka.common.security.JaasContext;
import org.apache.kafka.common.security.authenticator.SaslServerCallbackHandler;
import org.apache.kafka.common.security.scram.ScramFormatter;
import org.apache.kafka.common.security.scram.ScramMechanism;
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
 * Factory class for {@link DelegatingScramSaslServer}, so it can be loaded
 * via {@link javax.security.sasl.Sasl#createSaslServer(String, String, String, Map, CallbackHandler)}.
 */
public class DelegatingScramSaslServerFactory implements SaslServerFactory {

    private final static Logger logger = LoggerFactory.getLogger(DelegatingScramSaslServerFactory.class);

    @Override
    public SaslServer createSaslServer(String mechanism, String protocol, String serverName, Map<String, ?> props, CallbackHandler cbh)
            throws SaslException {
        System.err.println("#################### createSaslServer()");
        final ScramMechanism mech = ScramMechanism.forMechanismName(mechanism);
        if (mech == null) {
            logger.warn("Mechanism " + mechanism + " is not supported. Supported mechanisms are: " + Arrays.toString(Mechanism.allMechanmismNames()));
            return null;
        }
        ScramFormatter formatter;
        try {
            formatter = new ScramFormatter(mech);
        } catch (NoSuchAlgorithmException e) {
            throw new SaslException("Hash algorithm not supported: " + mech.hashAlgorithm());
        }

        return new DelegatingScramSaslServer(mech, formatter, props);
    }

    @Override
    public String[] getMechanismNames(Map<String, ?> props) {
        System.err.println("#################### getMechanismNames()");
        return Mechanism.allMechanmismNames();
    }
}
