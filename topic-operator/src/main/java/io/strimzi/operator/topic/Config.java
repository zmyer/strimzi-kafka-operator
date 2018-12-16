/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class Config {

    private static abstract class Type<T> {

        abstract T parse(String s);
    }

    /** A java string */
    private static final Type<? extends String> STRING = new Type<String>() {
        @Override
        public String parse(String s) {
            return s;
        }
    };

    /** A java Long */
    private static final Type<? extends Long> LONG = new Type<Long>() {
        @Override
        public Long parse(String s) {
            return Long.parseLong(s);
        }
    };

    /** A Java Integer */
    private static final Type<? extends Integer> POSITIVE_INTEGER = new Type<Integer>() {
        @Override
        Integer parse(String s) {
            int value = Integer.parseInt(s);
            if (value <= 0) {
                throw new IllegalArgumentException("The value must be greater than zero");
            }
            return value;
        }
    };

    /**
     * A time duration.
     */
    private static final Type<? extends Long> DURATION = new Type<Long>() {

        @Override
        public Long parse(String s) {
            return Long.parseLong(s);
        }
    };

    /**
     * A kubernetes selector.
     */
    private static final Type<? extends LabelPredicate> LABEL_PREDICATE = new Type<LabelPredicate>() {
        @Override
        public LabelPredicate parse(String s) {
            return LabelPredicate.fromString(s);
        }
    };

    static class Value<T> {
        public final String key;
        public final String defaultValue;
        public final boolean required;
        private final Type<? extends T> type;
        private Value(String key, Type<? extends T> type, String defaultValue) {
            this.key = key;
            this.type = type;
            if (defaultValue != null) {
                type.parse(defaultValue);
            }
            this.defaultValue = defaultValue;
            this.required = false;
        }
        private Value(String key, Type<? extends T> type, boolean required) {
            this.key = key;
            this.type = type;
            this.defaultValue = null;
            this.required = required;
        }
    }

    public static final String TC_RESOURCE_LABELS = "STRIMZI_RESOURCE_LABELS";
    public static final String TC_KAFKA_BOOTSTRAP_SERVERS = "STRIMZI_KAFKA_BOOTSTRAP_SERVERS";
    public static final String TC_NAMESPACE = "STRIMZI_NAMESPACE";
    public static final String TC_ZK_CONNECT = "STRIMZI_ZOOKEEPER_CONNECT";
    public static final String TC_ZK_SESSION_TIMEOUT_MS = "STRIMZI_ZOOKEEPER_SESSION_TIMEOUT_MS";
    public static final String TC_ZK_CONNECTION_TIMEOUT_MS = "TC_ZK_CONNECTION_TIMEOUT_MS";
    public static final String TC_PERIODIC_INTERVAL_MS = "STRIMZI_FULL_RECONCILIATION_INTERVAL_MS";
    public static final String TC_REASSIGN_THROTTLE = "STRIMZI_REASSIGN_THROTTLE";
    public static final String TC_REASSIGN_VERIFY_INTERVAL_MS = "STRIMZI_REASSIGN_VERIFY_INTERVAL_MS";
    public static final String TC_TOPIC_METADATA_MAX_ATTEMPTS = "STRIMZI_TOPIC_METADATA_MAX_ATTEMPTS";

    public static final String TC_TLS_ENABLED = "STRIMZI_TLS_ENABLED";
    public static final String TC_TLS_TRUSTSTORE_LOCATION = "STRIMZI_TRUSTSTORE_LOCATION";
    public static final String TC_TLS_TRUSTSTORE_PASSWORD = "STRIMZI_TRUSTSTORE_PASSWORD";
    public static final String TC_TLS_KEYSTORE_LOCATION = "STRIMZI_KEYSTORE_LOCATION";
    public static final String TC_TLS_KEYSTORE_PASSWORD = "STRIMZI_KEYSTORE_PASSWORD";

    private static final Map<String, Value<?>> CONFIG_VALUES = new HashMap<>();

    /** A comma-separated list of key=value pairs for selecting Resources that describe topics. */
    public static final Value<LabelPredicate> LABELS = new Value<>(TC_RESOURCE_LABELS, LABEL_PREDICATE, "strimzi.io/kind=topic");

    /** A comma-separated list of kafka bootstrap servers. */
    public static final Value<String> KAFKA_BOOTSTRAP_SERVERS = new Value<>(TC_KAFKA_BOOTSTRAP_SERVERS, STRING, true);

    /** The kubernetes namespace in which to operate. */
    public static final Value<String> NAMESPACE = new Value<>(TC_NAMESPACE, STRING, true);

    /** The zookeeper connection string. */
    public static final Value<String> ZOOKEEPER_CONNECT = new Value<>(TC_ZK_CONNECT, STRING, true);

    /** The zookeeper session timeout. */
    public static final Value<Long> ZOOKEEPER_SESSION_TIMEOUT_MS = new Value<>(TC_ZK_SESSION_TIMEOUT_MS, DURATION, "20000");

    /** The zookeeper connection timeout. */
    public static final Value<Long> ZOOKEEPER_CONNECTION_TIMEOUT_MS = new Value<>(TC_ZK_CONNECTION_TIMEOUT_MS, DURATION, "20000");

    /** The period between full reconciliations. */
    public static final Value<Long> FULL_RECONCILIATION_INTERVAL_MS = new Value<>(TC_PERIODIC_INTERVAL_MS, DURATION, "900000");

    /** The interbroker throttled rate to use when a topic change requires partition reassignment. */
    public static final Value<Long> REASSIGN_THROTTLE = new Value<>(TC_REASSIGN_THROTTLE, LONG, Long.toString(Long.MAX_VALUE));

    /**
     * The interval between verification executions (as in {@code kafka-reassign-partitions.sh --verify ...})
     * when a topic change requires partition reassignment.
     */
    public static final Value<Long> REASSIGN_VERIFY_INTERVAL_MS = new Value<>(TC_REASSIGN_VERIFY_INTERVAL_MS, DURATION, "120000");

    /** The maximum number of retries for getting topic metadata from the Kafka cluster */
    public static final Value<Integer> TOPIC_METADATA_MAX_ATTEMPTS = new Value<>(TC_TOPIC_METADATA_MAX_ATTEMPTS, POSITIVE_INTEGER, "6");

    /** If the connection with Kafka has to be encrypted by TLS protocol */
    public static final Value<String> TLS_ENABLED = new Value<>(TC_TLS_ENABLED, STRING, "false");
    /** The truststore with CA certificate for Kafka broker/server authentication */
    public static final Value<String> TLS_TRUSTSTORE_LOCATION = new Value<>(TC_TLS_TRUSTSTORE_LOCATION, STRING, "");
    /** The password for the truststore with CA certificate for Kafka broker/server authentication */
    public static final Value<String> TLS_TRUSTSTORE_PASSWORD = new Value<>(TC_TLS_TRUSTSTORE_PASSWORD, STRING, "");
    /** The keystore with private key and certificate for client authentication against Kafka broker */
    public static final Value<String> TLS_KEYSTORE_LOCATION = new Value<>(TC_TLS_KEYSTORE_LOCATION, STRING, "");
    /** The password for keystore with private key and certificate for client authentication against Kafka broker */
    public static final Value<String> TLS_KEYSTORE_PASSWORD = new Value<>(TC_TLS_KEYSTORE_PASSWORD, STRING, "");

    static {
        Map<String, Value<?>> configValues = CONFIG_VALUES;
        addConfigValue(configValues, LABELS);
        addConfigValue(configValues, KAFKA_BOOTSTRAP_SERVERS);
        addConfigValue(configValues, NAMESPACE);
        addConfigValue(configValues, ZOOKEEPER_CONNECT);
        addConfigValue(configValues, ZOOKEEPER_SESSION_TIMEOUT_MS);
        addConfigValue(configValues, ZOOKEEPER_CONNECTION_TIMEOUT_MS);
        addConfigValue(configValues, FULL_RECONCILIATION_INTERVAL_MS);
        addConfigValue(configValues, REASSIGN_THROTTLE);
        addConfigValue(configValues, REASSIGN_VERIFY_INTERVAL_MS);
        addConfigValue(configValues, TOPIC_METADATA_MAX_ATTEMPTS);
        addConfigValue(configValues, TLS_ENABLED);
        addConfigValue(configValues, TLS_TRUSTSTORE_LOCATION);
        addConfigValue(configValues, TLS_TRUSTSTORE_PASSWORD);
        addConfigValue(configValues, TLS_KEYSTORE_LOCATION);
        addConfigValue(configValues, TLS_KEYSTORE_PASSWORD);
    }

    static void addConfigValue(Map<String, Value<?>> configValues, Value<?> cv) {
        if (configValues.put(cv.key, cv) != null) {
            throw new RuntimeException();
        }
    }

    private final Map<String, Object> map;

    public Config(Map<String, String> map) {
        this.map = new HashMap<>(map.size());
        for (Map.Entry<String, String> entry : map.entrySet()) {
            final Value<?> configValue = CONFIG_VALUES.get(entry.getKey());
            if (configValue == null) {
                throw new IllegalArgumentException("Unknown config key " + entry.getKey());
            }
            this.map.put(configValue.key, get(map, configValue));
        }
        // now add all those config (with default value) that weren't in the given map
        Map<String, Value> x = new HashMap<>(CONFIG_VALUES);
        x.keySet().removeAll(map.keySet());
        for (Value<?> value : x.values()) {
            this.map.put(value.key, get(map, value));
        }
    }

    public static Collection<Value<?>> keys() {
        return Collections.unmodifiableCollection(CONFIG_VALUES.values());
    }

    public static Set<String> keyNames() {
        return Collections.unmodifiableSet(CONFIG_VALUES.keySet());
    }

    private <T> T get(Map<String, String> map, Value<T> value) {
        if (!CONFIG_VALUES.containsKey(value.key)) {
            throw new IllegalArgumentException("Unknown config value: " + value.key + " probably needs to be added to Config.CONFIG_VALUES");
        }
        final String s = map.getOrDefault(value.key, value.defaultValue);
        if (s != null) {
            return (T) value.type.parse(s);
        } else {
            if (value.required) {
                throw new IllegalArgumentException("Config value: " + value.key + " is mandatory");
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Value<T> value, T defaultValue) {
        return (T) this.map.getOrDefault(value.key, defaultValue);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Value<T> value) {
        return (T) this.map.get(value.key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Config config = (Config) o;
        return Objects.equals(map, config.map);
    }

    @Override
    public int hashCode() {
        return Objects.hash(map);
    }

    // TODO Generate documentation about the env vars
}
