/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.strimzi.api.kafka.model.InlineLogging;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.common.operator.MockCertManager;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static io.strimzi.operator.cluster.model.AbstractModel.containerEnvVars;
import static io.strimzi.operator.cluster.model.KafkaCluster.ENV_VAR_KAFKA_ZOOKEEPER_CONNECT;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KafkaSetOperatorTest {


    public static final InlineLogging KAFKA_LOG_CONFIG = new InlineLogging();
    public static final InlineLogging ZOOKEEPER_LOG_CONFIG = new InlineLogging();
    static {
        KAFKA_LOG_CONFIG.setLoggers(singletonMap("zookeeper.root.logger", "OFF"));
        ZOOKEEPER_LOG_CONFIG.setLoggers(singletonMap("kafka.root.logger.level", "OFF"));
    }

    private StatefulSet a;
    private StatefulSet b;

    @Before
    public void before() {
        MockCertManager certManager = new MockCertManager();
        KafkaVersion.Lookup versions = new KafkaVersion.Lookup(emptyMap(), emptyMap(), emptyMap(), emptyMap());
        a = KafkaCluster.fromCrd(getResource(), versions).generateStatefulSet(true);
        b = KafkaCluster.fromCrd(getResource(), versions).generateStatefulSet(true);
    }

    private Kafka getResource() {
        String clusterCmName = "foo";
        String clusterCmNamespace = "test";
        int replicas = 3;
        String image = "bar";
        int healthDelay = 120;
        int healthTimeout = 30;
        return new KafkaBuilder(ResourceUtils.createKafkaCluster(clusterCmNamespace, clusterCmName,
                replicas, image, healthDelay, healthTimeout))
                .editSpec()
                    .editKafka()
                        .withNewPersistentClaimStorageStorage()
                            .withSize("123")
                            .withStorageClass("foo")
                            .withDeleteClaim(true)
                            .endPersistentClaimStorageStorage()
                        .withLogging(KAFKA_LOG_CONFIG)
                    .endKafka()
                    .editZookeeper()
                        .withLogging(ZOOKEEPER_LOG_CONFIG)
                    .endZookeeper()
                .endSpec()
            .build();
    }

    private StatefulSetDiff diff() {
        return new StatefulSetDiff(a, b);
    }

    @Test
    public void testNotNeedsRollingUpdateIdentical() {
        assertFalse(KafkaSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNotNeedsRollingUpdateReplicas() {
        a.getSpec().setReplicas(b.getSpec().getReplicas() + 1);
        assertFalse(KafkaSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNeedsRollingUpdateLabels() {
        Map<String, String> labels = new HashMap(b.getMetadata().getLabels());
        labels.put("foo", "bar");
        a.getMetadata().setLabels(labels);
        assertTrue(KafkaSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNeedsRollingUpdateImage() {
        a.getSpec().getTemplate().getSpec().getContainers().get(0).setImage(
                a.getSpec().getTemplate().getSpec().getContainers().get(0).getImage() + "-foo");
        assertTrue(KafkaSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNeedsRollingUpdateReadinessDelay() {
        a.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().setInitialDelaySeconds(
                a.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getInitialDelaySeconds() + 1);
        assertTrue(KafkaSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNeedsRollingUpdateReadinessTimeout() {
        a.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().setTimeoutSeconds(
                a.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getTimeoutSeconds() + 1);
        assertTrue(KafkaSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNeedsRollingUpdateEnvZkConnect() {
        String envVar = ENV_VAR_KAFKA_ZOOKEEPER_CONNECT;
        a.getSpec().getTemplate().getSpec().getContainers().get(1).getEnv().add(new EnvVar(envVar,
                containerEnvVars(a.getSpec().getTemplate().getSpec().getContainers().get(1)).get(envVar) + "-foo", null));
        assertTrue(KafkaSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNeedsRollingUpdateEnvSomeOtherThing() {
        String envVar = "SOME_RANDOM_ENV";
        a.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().add(new EnvVar(envVar,
                "foo", null));
        assertTrue(KafkaSetOperator.needsRollingUpdate(diff()));
    }
}
