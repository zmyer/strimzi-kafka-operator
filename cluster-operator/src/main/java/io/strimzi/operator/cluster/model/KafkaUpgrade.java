/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

/**
 * Represents the upgrade or downgrade from one Kafka version to another
 */
public class KafkaUpgrade {
    private final KafkaVersion from;
    private final KafkaVersion to;

    public KafkaUpgrade(KafkaVersion from, KafkaVersion to) {
        this.from = from;
        this.to = to;
    }

    /** The version being upgraded from. */
    public KafkaVersion from() {
        return from;
    }

    /** The version being upgraded to. */
    public KafkaVersion to() {
        return to;
    }

    /** true if upgrading from {@link #from()} to {@code to} requires upgrading the inter broker protocol. */
    public boolean requiresProtocolUpgrade() {
        return !from.protocolVersion().equals(to.protocolVersion());
    }

    /** true if upgrading from {@link #from()} to {@code to} requires upgrading the message format. */
    public boolean requiresMessageUpgrade() {
        return !from.messageVersion().equals(to.messageVersion());
    }

}
