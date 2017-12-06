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

public enum Mechanism {
    SCRAM_SHA_256("SHA-256"),
    SCRAM_SHA_512("SHA-512");

    private final String hashFunctionName;

    private Mechanism(String hashFunctionName) {
        this.hashFunctionName = hashFunctionName;
    }

    public String mechanismName() {
        return "SCRAM-" + hashFunctionName;
    }

    public String hashFunctionName() {
        return hashFunctionName;
    }

    static Mechanism fromMechanismName(String name) {
        if (name.startsWith("SCRAM-")) {
            String rest = name.substring("SCRAM-".length());
            for (Mechanism m : Mechanism.values()) {
                if (rest.equals(m.hashFunctionName)) {
                    return m;
                }
            }
        }
        return null;
    }

    static String[] allMechanmismNames() {
        String[] result = new String[Mechanism.values().length];
        int i = 0;
        for (Mechanism m : Mechanism.values()) {
            result[i++] =  m.mechanismName();
        }
        return result;
    }
}
