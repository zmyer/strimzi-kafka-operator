# Supporting Kafka upgrades

* Status: **Under discussion**
* Discussion: [GitHub PR](https://github.com/strimzi/strimzi-kafka-operator/pull/918)

## Motivation

The Strimzi Cluster Operator (CO) will need to support upgrading the Kafka 
version used on a cluster-by-cluster basis. 
(Note this is a different thing to supporting Operator upgrades).

## Scope

The expectation is that a given version of the operator will support some 
defined set of Kafka versions and the user can selector which should be 
used for their cluster.
When the user upgrages to a later version supported by the CO, the CO will upgrade the cluster in a zero-downtime manner.

Support for downgrading to an earlier version is not a priority.

Upgrading Zookeeper is out of scope. We will assume that the new version of Kafka can use the existing Zookeeper version.

## Upgrade in Kafka

The upgrade procedure for Kafka is [documented here](https://kafka.apache.org/documentation/#upgrade).

## Changes to the `Kafka` CRD

Add `Kafka.spec.kafka.version` to control which version of Kafka to use.
This corresponds to using a different image for Kafka broker pods.
The different image will embed a different Kafka distribution.

The `Kafka.spec.kafka.version` will be a string matching the pattern
`[0-9]+(\.[0-9]+)+`. It will _not_ be constrained by the CRD to the versions 
supported by the operator since that would require coupling CRD versions to Kafka/Operator versions.

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

3. The user now has to upgrade clients. This isn't something the CO can do.

4. Once the user has upgraded the clients they can update (or remove) `log.message.format.version`:

    ```
    kind: Kafka
    spec:
      kafka:
        version: 2.1.0
        config:
          # ...
    ```

    This will initiate a final reconciliation (rolling update). The upgrade is now complete.

**Note:** It will be possible to revert the upgrade upto and including step 3.
Because `log.message.format.version` functions as an assertion that all existing messages are on or below that message version, downgrading `log.message.format.version` is insufficient to guarantee that older clients don't see messages versions they don't understand.
This is the main reason why the CO can't fully support version downgrade.

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
  strimzi/kafka-base-2.0.0:0.9.0

or 

  strimzi/kafka-base:master-2.0.0
  strimzi/kafka-base:0.9.0-2.0.0

## Changes to the CO

### Default images for each version

The CO will query a new environment variable, `STRIMZI_IMAGE_MAP` to discover a mapping of Kafka version to default image. For example:

    STRIMZI_IMAGE_MAP=2.0.0=strimzi/kafka:0.9.0-kafka-2.0.0,\
                      2.1.0=strimzi/kafka:0.9.0-kafka-2.1.0

### Metadata about protocol and message versions

The CO needs to detect when the desired version differs from the current version and perform a rolling upgrade (or downgrade).
In general, an upgrade could introduce both wire protocol changes and message format changes.
The CO embeds knowledge of the native wire protocol and message format verions 
corresponding to the supported Kafka versions.

```
class KafkaVersion {
    private KafkaVersion(String version, String protocolVersion, String messageVersion) {
        // ...
    }
    private static final KafkaVersion V1_1_0 = new KafkaVersion("1.1.0", "1.1", "1.1");
    private static final KafkaVersion V2_0_0 = new KafkaVersion("2.0.0", "2.0", "2.0");

    /** Find the version from the given version string */
    static KafkaVersion version(String version) {
        // ...
    }
}

class Upgrade {

    private final KafkaVersion from;
    private final KafkaVersion to

    public Upgrade(KafkaVersion from, KafkaVersion to) {
	// ...
    }

    boolean requiresProtocolUpgrade() {
        return !from.protocolVersion.equals(to.protocolVersion);
    }

    boolean requiresMessageUpgrade() {
        return !from.messageVersion.equals(to.messageVersion);
    }
}
```

### Reconciliation loop

The changes to the reconciliation loop are limited to support for the `version` 
in the CR and what to do when it is observed to change.
The other changes the user makes in the CR as part of the upgrade process are already handled by the CO, since they're just `config` changes.

Detecting a changed version will be done using an annotation on the Kafka `StatefulSet`. Comparing the existing SS's `strimzi.io/kafka-version` annotation with the `Kafka.spec.kafka.version`

1. If `oldVersion == newVersion` then no upgrade is being performed and we are done.
2. Otherwise, compute `upgrade = new Upgrade(oldVersion, newVersion)` and determine whether it's an upgrade or downgrade.
    1. If Upgrade:
        1. If `oldVersion.messageVersion() != kafka.spec.kafka.config."log.message.format.version"` then abort with an error
        2. Set proto version and log version in applied Kafka config (if not already set explicitly)
        2. Update image in SS
        3. Rolling update
        4. If `upgrade.requiresProtocolUpgrade()`:
            1. Update proto version in applied Kafka config
            2. Rolling update
    3. If Downgrade
        1. If `upgrade.requiresProtocolUpgrade()`:
            1. Update proto version in applied Kafka config
            2. Rolling update
        2. Set proto version and log version in applied Kafka config (if not already set explicitly)
        3. Update image in SS
        4. Rolling update



## Limitations & Consequences

### Validating versions and images

In the described design we don't actually know the version of Kafka present in a given image.
Instead we are trusting the metadata provided by the user via `Kafka.spec.kafka.image` and `Kafka.spec.kafka.version`, or via the CO config which provides the mapping of Kafka versions to images.

### Retrofitting versions

Since the CO will embed (hardcode) information about Kafka versions it follows that a given version of the CO can only support some predefined range of Kafka versions.
That means we can't, say, produce a new image for a new Kafka version to work with an existing version of Strimzi.
This could be addressed in the future by having this information passed to the CO from the environment. That would then allow a new Kafka version to be retrofitted to an existing CO by:

1. releasing a new image 
2. reconfiguring the CO
3. redeploying the CO.

## Validation of `Kafka.spec.kafka.config`

Since different Kafka versions support different configuration parameters, supporting multiple versions makes it harder to perform deeper validation of `Kafka.spec.kafka.config`.

We could support this as extra metadata (in addition to protocol and message verion) hard coded into the CO, but that makes 'Retrofitting versions' harder.

It would be better if the CO could introspect a given image to discover:

1. The version of Kafka it supports
2. The configuration parameters, and their metadata.

One way to achieve this would be for images to have an alternative entry point, which instead of starting the broker
