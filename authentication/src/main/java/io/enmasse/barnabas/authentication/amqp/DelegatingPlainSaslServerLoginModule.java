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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import java.util.Map;

public class DelegatingPlainSaslServerLoginModule implements LoginModule {
    private final static Logger logger = LoggerFactory.getLogger(DelegatingPlainSaslServerLoginModule.class);
    private static final String USERNAME_CONFIG = "amqpHost";
    private static final String PASSWORD_CONFIG = "amqpPort";

    static {
        DelegatingPlainSaslProvider.initialize();
    }

    @Override
    public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState, Map<String, ?> options) {
        logger.info("initialize()");
    }

    @Override
    public boolean login() throws LoginException {
        logger.info("login()");
        return true;
    }

    @Override
    public boolean logout() throws LoginException {
        logger.info("logout()");
        return true;
    }

    @Override
    public boolean commit() throws LoginException {
        logger.info("commit()");
        return true;
    }

    @Override
    public boolean abort() throws LoginException {
        logger.info("abort()");
        return false;
    }
}

