# Supporting Kafka upgrades

* Status: **Under discussion**
* Discussion: [GitHub PR](https://github.com/strimzi/strimzi-kafka-operator/pull/623)

## Motivation

The Strimzi Cluster Operator (CO) will need to support upgrading the Kafka 
version used on a cluster-by-cluster basis. 
(Note this is a different thing to supporting Operator upgrades).
The expectation is that a given version of the operator will support some 
defined set of Kafka versions.

## Upgrade

The upgrade procedure for Kafka is [documented here](https://kafka.apache.org/documentation/#upgrade).

## Changes to the `Kafka` CRD

Add `Kafka.spec.kafka.version` to control which version of Kafka to use.
This corresponds to using a different image for Kafka broker pods.
The different image will embed a different Kafka distribution.

From the user's point of view the upgrade procedure is:

1. Edit the `Kafka` resource setting the `version` to the version being upgraded to
   and setting `log.message.format.version` to the version being upgraded from.

    ```
    kind: Kafka
    spec:
      kafka:
        version: 2.1.0
        config:
          log.message.format.version: 2.0.0
          inter.broker.protocol.version: 2.0.0
          # ...
    ```

    This will initiate a reconciliation which upgrades the pods but not the message format or interbroker version.

2. Edit the `Kafka` resource upgrading or removing the `inter.broker.protocol.version`

    ```
    kind: Kafka
    spec:
      kafka:
        version: 2.1.0
        config:
          log.message.format.version: 2.0.0
          # ...
    ```

    This will initiate a rolling update which means the brokers are speaking the new protocol.
    The message format for clients is still on the old version.

3. The user now has to upgrade clients.

4. Once the user has upgraded the clients the `log.message.format.version` can be upgraded, or removed.

    ```
    kind: Kafka
    spec:
      kafka:
        version: 2.1.0
        config:
          # ...
    ```

    This will initiate a final reconciliation (rolling update). The upgrade is now complete.

**Note:** It will be possible to revert the upgrade up to step 3.
Because `log.message.format.version` functions as an assertion that all existing messages are on or below that messages version, downgrading `log.message.format.version` is insufficient to guarantee that older clients don't see messages versions they don't understand.

## New images and changes to the build process

Supporting multiple images requires changes to the build process.

A different `kafka` image itself requires a different `kafka-base`.

Consequently there would logically be different versions of the images for:

* `kafka-connect`
* `kafka-connect-s2i`
* `zookeeper`
* `kafka-mirror-maker`
* `test-client`

The way we identify images has to change too: We either use the tag to embed the Kafka version, or the name. 
I.e. either

  strimzi/kafka-base-2.0.0:latest

or 

  strimzi/kafka-base:master-2.0.0
  strimzi/kafka-base:master-2.1.0
  strimzi/kafka-base:0.9.0-2.0.0
  strimzi/kafka-base:0.9.0-2.1.0

## Changes to the CO

The CO needs to detect when the desired version differs from the current version and perform a rolling upgrade (or downgrade).
In general an upgrade would introduce wire protocol changes. The CO embeds knowledge of which version changes do not.

static class KafkaVersion {
        private KafkaVersion(String version, String protocolVersion, String logVersion) {

        }
        private static final KafkaVersion V1_1_0 = new KafkaVersion("1.1.0", "1.1", "1.1");
	private static final KafkaVersion V2_0_0 = new KafkaVersion("2.0.0", "2.0", "2.0");

	static KafkaVersion version(String version) {

	}
}

class Upgrade {

    private final KafkaVersion from;
    private final KafkaVersion to

    static Upgrade upgrade(KafkaVersion from, KafkaVersion to) {

    }

    boolean protocolUpgrade() {
        return !from.protocolVersion.equals(to.protocolVersion);
    }

    boolean logUpgrade() {
        return !from.logVersion.equals(to.logVersion);
    }

}

The algorithm for upgrading has two parts in the reconciliation loop.

Version → image name
Check image name on SS (or each pod?)
If different, is it upgrade or downgrade?
    Upgrade:
       Set proto version and log version in applied config (if not already set)
       Update image in SS
       Rolling update
       Update proto version in applied config
       Rolling update

config."log.message.format.version" (default based on image name)
Check message version on SS (or each pod?)
If message format version differs is it an upgrade or downgrade?
    Upgrade:
       Rolling update
    Downgrade:
       This basically won't work because `log.message.format.version` in an assertion obout the supported version in clients.

