/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.strimzi.operator.cluster.KafkaUpgradeException;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Represents a Kafka version that's supported by this CO
 */
public class KafkaVersion implements Comparable<KafkaVersion> {
    private static final Map<String, KafkaVersion> MAP = new HashMap<>(1);
    private static final KafkaVersion V1_1_0 = new KafkaVersion("1.1.0", "1.1", "1.1");
    private static final KafkaVersion V1_1_1 = new KafkaVersion("1.1.1", "1.1", "1.1");
    private static final KafkaVersion V2_0_0 = new KafkaVersion("2.0.0", "2.0", "2.0");
    private static final KafkaVersion DEFAULT_VERSION = V2_0_0;

    /** Find the version from the given version string */
    public static KafkaVersion version(String version) {
        KafkaVersion result;
        if (version == null) {
            result = DEFAULT_VERSION;
        } else {
            result = MAP.get(version);
        }
        if (result == null) {
            throw new KafkaUpgradeException(String.format(
                    "Unsupported Kafka.spec.kafka.version: %s. " +
                            "Supported versions are: %s",
                    version, MAP.keySet()));
        }
        return result;
    }

    public static Set<String> supportedVersions() {
        return new TreeSet<>(MAP.keySet());
    }

    private final String version;
    private final String protocolVersion;
    private final String messageVersion;

    private KafkaVersion(String version, String protocolVersion, String messageVersion) {
        this.version = version;
        this.protocolVersion = protocolVersion;
        this.messageVersion = messageVersion;
        if (MAP.put(version, this) != null) {
            throw new RuntimeException("Duplicate version " + version);
        }
    }

    @Override
    public String toString() {
        return version;
    }

    public String version() {
        return version;
    }

    public String protocolVersion() {
        return protocolVersion;
    }

    public String messageVersion() {
        return messageVersion;
    }

    public static void main(String[] args) {
        KafkaVersion kv = version("2.0.0");
        System.out.println(kv);
        for (String kv1 : supportedVersions()) {
            for (String kv2 : supportedVersions()) {
                System.out.print(version(kv1).compareTo(version(kv2)));
                System.out.print(", ");
            }
            System.out.println();
        }

    }

    public String imageName(String templateImageName) {
        // TODO I guess the ENV VARs in the CO are now templates, and this expands the template into a full image name
        return null;
    }

    @Override
    public int compareTo(KafkaVersion o) {
        String[] components = version.split("\\.");
        String[] otherComponents = o.version.split("\\.");
        for (int i = 0; i < Math.min(components.length, otherComponents.length); i++) {
            int x = Integer.parseInt(components[i]);
            int y = Integer.parseInt(otherComponents[i]);
            if (x == y) {
                continue;
            } else if (x < y) {
                return -1;
            } else {
                return 1;
            }
        }
        return components.length - otherComponents.length;
    }

    @Override
    public int hashCode() {
        return version.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KafkaVersion that = (KafkaVersion) o;
        return version.equals(that.version);
    }
}
