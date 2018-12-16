/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.ClusterCa;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.ZookeeperCluster;
import io.strimzi.operator.common.operator.MockCertManager;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static io.strimzi.operator.cluster.model.AbstractModel.containerEnvVars;
import static io.strimzi.operator.cluster.model.ZookeeperCluster.ENV_VAR_ZOOKEEPER_METRICS_ENABLED;
import static java.util.Collections.emptyMap;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZookeeperSetOperatorTest {

    //public static final Map<String, Object> METRICS_CONFIG = singletonMap("foo", "bar");
    //public static final Map<String, Object> LOG_ZOOKEEPER_CONFIG = singletonMap("zookeeper.root.logger", "INFO");
    //public static final Map<String, Object> LOG_KAFKA_CONFIG = singletonMap("kafka.root.logger.level", "INFO");

    private StatefulSet a;
    private StatefulSet b;

    @Before
    public void before() {
        MockCertManager certManager = new MockCertManager();
        KafkaVersion.Lookup versions = new KafkaVersion.Lookup(emptyMap(), emptyMap(), emptyMap(), emptyMap());
        a = ZookeeperCluster.fromCrd(getResource(), versions).generateStatefulSet(true);
        b = ZookeeperCluster.fromCrd(getResource(), versions).generateStatefulSet(true);
    }

    private Kafka getResource() {
        String clusterCmName = "foo";
        String clusterCmNamespace = "test";
        int replicas = 3;
        String image = "bar";
        int healthDelay = 120;
        int healthTimeout = 30;
        return ResourceUtils.createKafkaCluster(clusterCmNamespace, clusterCmName, replicas, image, healthDelay, healthTimeout);
    }

    private ClusterCa getInitialSecrets(String clusterName) {
        String clusterCmNamespace = "test";
        return ResourceUtils.createInitialClusterCa(clusterCmNamespace, clusterName);
    }

    private StatefulSetDiff diff() {
        return new StatefulSetDiff(a, b);
    }

    @Test
    public void testNotNeedsRollingUpdateIdentical() {
        assertFalse(ZookeeperSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNeedsRollingUpdateReplicas() {
        a.getSpec().setReplicas(b.getSpec().getReplicas() + 1);
        assertTrue(ZookeeperSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNeedsRollingUpdateLabels() {
        Map<String, String> labels = new HashMap<>(b.getMetadata().getLabels());
        labels.put("foo", "bar");
        a.getMetadata().setLabels(labels);
        assertTrue(ZookeeperSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNeedsRollingUpdateImage() {
        needsRollingUpdateImage(0);
    }

    @Test
    public void testNeedsRollingUpdateStunnelImage() {
        needsRollingUpdateImage(1);
    }

    private void needsRollingUpdateImage(int container) {
        a.getSpec().getTemplate().getSpec().getContainers().get(container).setImage(
                a.getSpec().getTemplate().getSpec().getContainers().get(container).getImage() + "-foo");
        assertTrue(ZookeeperSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNeedsRollingUpdateReadinessDelay() {
        a.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().setInitialDelaySeconds(
                a.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getInitialDelaySeconds() + 1);
        assertTrue(ZookeeperSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNeedsRollingUpdateReadinessTimeout() {
        a.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().setTimeoutSeconds(
                a.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getTimeoutSeconds() + 1);
        assertTrue(ZookeeperSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNeedsRollingUpdateEnvZkMetricsEnabled() {
        String envVar = ENV_VAR_ZOOKEEPER_METRICS_ENABLED;
        a.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().add(new EnvVar(envVar,
                containerEnvVars(a.getSpec().getTemplate().getSpec().getContainers().get(0)).get(envVar) + "-foo", null));
        assertTrue(ZookeeperSetOperator.needsRollingUpdate(diff()));
    }

    @Test
    public void testNeedsRollingUpdateEnvSomeOtherThing() {
        String envVar = "SOME_RANDOM_ENV";
        a.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().add(new EnvVar(envVar,
                "foo", null));
        assertTrue(ZookeeperSetOperator.needsRollingUpdate(diff()));
    }
}
