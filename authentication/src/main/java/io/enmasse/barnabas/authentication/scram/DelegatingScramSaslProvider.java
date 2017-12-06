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

import javax.security.auth.callback.CallbackHandler;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import java.security.Provider;
import java.security.Security;
import java.util.HashMap;

import static java.util.Arrays.asList;

public class DelegatingScramSaslProvider extends Provider {

    static void initialize() {
        Security.insertProviderAt(new DelegatingScramSaslProvider(),0);
        System.err.println("#####################" + asList(Security.getProviders()));
    }

    protected DelegatingScramSaslProvider() {
        super("Delegating SASL/SCRAM Provider", 1.0, "Delegating SASL/SCRAM Provider for Barnabas");
        System.err.println("MyProvider.<init>");
        for (String mech : Mechanism.allMechanmismNames()) {
            put("SaslServerFactory."+mech, DelegatingScramSaslServerFactory.class.getName());
        }
    }
}
