/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.strimzi.api.kafka.model.InlineLogging;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.ProbeBuilder;
import io.strimzi.api.kafka.model.TlsSidecar;
import io.strimzi.api.kafka.model.TlsSidecarBuilder;
import io.strimzi.api.kafka.model.TlsSidecarLogLevel;
import io.strimzi.api.kafka.model.ZookeeperClusterSpec;
import io.strimzi.certs.OpenSslCertManager;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.test.TestUtils;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static io.strimzi.test.TestUtils.LINE_SEPARATOR;
import static io.strimzi.test.TestUtils.set;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZookeeperClusterTest {

    private static final KafkaVersion.Lookup VERSIONS = new KafkaVersion.Lookup(emptyMap(), emptyMap(), emptyMap(), emptyMap());
    private final String namespace = "test";
    private final String cluster = "foo";
    private final int replicas = 3;
    private final String image = "image";
    private final int healthDelay = 120;
    private final int healthTimeout = 30;
    private final int tlsHealthDelay = 120;
    private final int tlsHealthTimeout = 30;
    private final Map<String, Object> metricsCmJson = singletonMap("animal", "wombat");
    private final Map<String, Object> configurationJson = emptyMap();
    private final InlineLogging kafkaLogConfigJson = new InlineLogging();
    private final InlineLogging zooLogConfigJson = new InlineLogging();
    {
        kafkaLogConfigJson.setLoggers(Collections.singletonMap("kafka.root.logger.level", "OFF"));
        zooLogConfigJson.setLoggers(Collections.singletonMap("zookeeper.root.logger", "OFF"));
    }
    private final Map<String, Object> zooConfigurationJson = singletonMap("foo", "bar");

    private final TlsSidecar tlsSidecar = new TlsSidecarBuilder()
            .withLivenessProbe(new ProbeBuilder().withInitialDelaySeconds(tlsHealthDelay).withTimeoutSeconds(tlsHealthTimeout).build())
            .withReadinessProbe(new ProbeBuilder().withInitialDelaySeconds(tlsHealthDelay).withTimeoutSeconds(tlsHealthTimeout).build())
            .build();

    private final Kafka ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson, null, null, kafkaLogConfigJson, zooLogConfigJson))
            .editSpec()
                .editZookeeper()
                    .withTlsSidecar(tlsSidecar)
                .endZookeeper()
            .endSpec()
            .build();

    private final ZookeeperCluster zc = ZookeeperCluster.fromCrd(ka, VERSIONS);

    @Rule
    public ResourceTester<Kafka, ZookeeperCluster> resourceTester = new ResourceTester<>(Kafka.class, VERSIONS, ZookeeperCluster::fromCrd);

    @Test
    public void testMetricsConfigMap() {
        ConfigMap metricsCm = zc.generateMetricsAndLogConfigMap(null);
        checkMetricsConfigMap(metricsCm);
        checkOwnerReference(zc.createOwnerReference(), metricsCm);
    }

    private void checkMetricsConfigMap(ConfigMap metricsCm) {
        assertEquals(TestUtils.toJsonString(metricsCmJson), metricsCm.getData().get(AbstractModel.ANCILLARY_CM_KEY_METRICS));
    }

    private Map<String, String> expectedSelectorLabels()    {
        return Labels.fromMap(expectedLabels()).strimziLabels().toMap();
    }

    private Map<String, String> expectedLabels()    {
        return TestUtils.map(Labels.STRIMZI_CLUSTER_LABEL, cluster, "my-user-label", "cromulent", Labels.STRIMZI_NAME_LABEL, ZookeeperCluster.zookeeperClusterName(cluster), Labels.STRIMZI_KIND_LABEL, Kafka.RESOURCE_KIND);
    }

    @Test
    public void testGenerateService() {
        Service headful = zc.generateService();
        checkService(headful);
        checkOwnerReference(zc.createOwnerReference(), headful);
    }

    private void checkService(Service headful) {
        assertEquals("ClusterIP", headful.getSpec().getType());
        assertEquals(expectedSelectorLabels(), headful.getSpec().getSelector());
        assertEquals(2, headful.getSpec().getPorts().size());
        assertEquals(ZookeeperCluster.METRICS_PORT_NAME, headful.getSpec().getPorts().get(0).getName());
        assertEquals(ZookeeperCluster.CLIENT_PORT_NAME, headful.getSpec().getPorts().get(1).getName());
        assertEquals(new Integer(ZookeeperCluster.METRICS_PORT), headful.getSpec().getPorts().get(0).getPort());
        assertEquals(new Integer(ZookeeperCluster.CLIENT_PORT), headful.getSpec().getPorts().get(1).getPort());
        assertEquals("TCP", headful.getSpec().getPorts().get(0).getProtocol());
        assertEquals(zc.getPrometheusAnnotations(), headful.getMetadata().getAnnotations());
    }

    @Test
    public void testGenerateHeadlessService() {
        Service headless = zc.generateHeadlessService();
        checkHeadlessService(headless);
        checkOwnerReference(zc.createOwnerReference(), headless);
    }

    private void checkHeadlessService(Service headless) {
        assertEquals(ZookeeperCluster.headlessServiceName(cluster), headless.getMetadata().getName());
        assertEquals("ClusterIP", headless.getSpec().getType());
        assertEquals("None", headless.getSpec().getClusterIP());
        assertEquals(expectedSelectorLabels(), headless.getSpec().getSelector());
        assertEquals(3, headless.getSpec().getPorts().size());
        assertEquals(ZookeeperCluster.CLIENT_PORT_NAME, headless.getSpec().getPorts().get(0).getName());
        assertEquals(new Integer(ZookeeperCluster.CLIENT_PORT), headless.getSpec().getPorts().get(0).getPort());
        assertEquals(ZookeeperCluster.CLUSTERING_PORT_NAME, headless.getSpec().getPorts().get(1).getName());
        assertEquals(new Integer(ZookeeperCluster.CLUSTERING_PORT), headless.getSpec().getPorts().get(1).getPort());
        assertEquals(ZookeeperCluster.LEADER_ELECTION_PORT_NAME, headless.getSpec().getPorts().get(2).getName());
        assertEquals(new Integer(ZookeeperCluster.LEADER_ELECTION_PORT), headless.getSpec().getPorts().get(2).getPort());
        assertEquals("TCP", headless.getSpec().getPorts().get(0).getProtocol());
    }

    @Test
    public void testGenerateStatefulSet() {
        // We expect a single statefulSet ...
        StatefulSet ss = zc.generateStatefulSet(true);
        checkStatefulSet(ss);
        checkOwnerReference(zc.createOwnerReference(), ss);
    }

    private void checkStatefulSet(StatefulSet ss) {
        assertEquals(ZookeeperCluster.zookeeperClusterName(cluster), ss.getMetadata().getName());
        // ... in the same namespace ...
        assertEquals(namespace, ss.getMetadata().getNamespace());
        // ... with these labels
        assertEquals(expectedLabels(), ss.getMetadata().getLabels());
        assertEquals(expectedSelectorLabels(), ss.getSpec().getSelector().getMatchLabels());

        List<Container> containers = ss.getSpec().getTemplate().getSpec().getContainers();

        assertEquals(2, containers.size());

        // checks on the main Zookeeper container
        assertEquals(new Integer(replicas), ss.getSpec().getReplicas());
        assertEquals(image + "-zk", containers.get(0).getImage());
        assertEquals(new Integer(healthTimeout), containers.get(0).getLivenessProbe().getTimeoutSeconds());
        assertEquals(new Integer(healthDelay), containers.get(0).getLivenessProbe().getInitialDelaySeconds());
        assertEquals(new Integer(healthTimeout), containers.get(0).getReadinessProbe().getTimeoutSeconds());
        assertEquals(new Integer(healthDelay), containers.get(0).getReadinessProbe().getInitialDelaySeconds());
        String expectedConfig = "timeTick=2000" + LINE_SEPARATOR +
                        "autopurge.purgeInterval=1" + LINE_SEPARATOR +
                        "syncLimit=2" + LINE_SEPARATOR +
                        "initLimit=5" + LINE_SEPARATOR +
                        "foo=bar" + LINE_SEPARATOR;
        assertEquals(expectedConfig, AbstractModel.containerEnvVars(containers.get(0)).get(ZookeeperCluster.ENV_VAR_ZOOKEEPER_CONFIGURATION));
        // checks on the TLS sidecar container
        Container tlsSidecarContainer = containers.get(1);
        assertEquals(ZookeeperClusterSpec.DEFAULT_TLS_SIDECAR_IMAGE, tlsSidecarContainer.getImage());
        assertEquals(new Integer(replicas), Integer.valueOf(AbstractModel.containerEnvVars(tlsSidecarContainer).get(ZookeeperCluster.ENV_VAR_ZOOKEEPER_NODE_COUNT)));
        assertEquals(TlsSidecarLogLevel.NOTICE.toValue(), AbstractModel.containerEnvVars(tlsSidecarContainer).get(ModelUtils.TLS_SIDECAR_LOG_LEVEL));
        assertEquals(ZookeeperCluster.CLUSTERING_PORT_NAME, tlsSidecarContainer.getPorts().get(0).getName());
        assertEquals(new Integer(ZookeeperCluster.CLUSTERING_PORT), tlsSidecarContainer.getPorts().get(0).getContainerPort());
        assertEquals(ZookeeperCluster.LEADER_ELECTION_PORT_NAME, tlsSidecarContainer.getPorts().get(1).getName());
        assertEquals(new Integer(ZookeeperCluster.LEADER_ELECTION_PORT), tlsSidecarContainer.getPorts().get(1).getContainerPort());
        assertEquals(ZookeeperCluster.CLIENT_PORT_NAME, tlsSidecarContainer.getPorts().get(2).getName());
        assertEquals(new Integer(ZookeeperCluster.CLIENT_PORT), tlsSidecarContainer.getPorts().get(2).getContainerPort());
        assertEquals(ZookeeperCluster.TLS_SIDECAR_NODES_VOLUME_NAME, tlsSidecarContainer.getVolumeMounts().get(0).getName());
        assertEquals(ZookeeperCluster.TLS_SIDECAR_NODES_VOLUME_MOUNT, tlsSidecarContainer.getVolumeMounts().get(0).getMountPath());
        assertEquals(ZookeeperCluster.TLS_SIDECAR_CLUSTER_CA_VOLUME_NAME, tlsSidecarContainer.getVolumeMounts().get(1).getName());
        assertEquals(ZookeeperCluster.TLS_SIDECAR_CLUSTER_CA_VOLUME_MOUNT, tlsSidecarContainer.getVolumeMounts().get(1).getMountPath());
        assertEquals(new Integer(tlsHealthDelay), tlsSidecarContainer.getReadinessProbe().getInitialDelaySeconds());
        assertEquals(new Integer(tlsHealthTimeout), tlsSidecarContainer.getReadinessProbe().getTimeoutSeconds());
        assertEquals(new Integer(tlsHealthDelay), tlsSidecarContainer.getLivenessProbe().getInitialDelaySeconds());
        assertEquals(new Integer(tlsHealthTimeout), tlsSidecarContainer.getLivenessProbe().getTimeoutSeconds());
    }

    /**
     * Check that a ZookeeperCluster from a statefulset matches the one from a ConfigMap
     */
    @Test
    public void testDeleteClaim() {
        Kafka ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                .editSpec()
                    .editKafka()
                        .withNewEphemeralStorageStorage().endEphemeralStorageStorage()
                    .endKafka()
                .endSpec()
            .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(ka, VERSIONS);
        StatefulSet ss = zc.generateStatefulSet(true);
        assertFalse(ZookeeperCluster.deleteClaim(ss));

        ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                .editSpec()
                    .editKafka()
                        .withNewPersistentClaimStorageStorage().withDeleteClaim(false).endPersistentClaimStorageStorage()
                    .endKafka()
                .endSpec()
            .build();
        zc = ZookeeperCluster.fromCrd(ka, VERSIONS);
        ss = zc.generateStatefulSet(true);
        assertFalse(ZookeeperCluster.deleteClaim(ss));

        ka = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson, configurationJson, zooConfigurationJson))
                .editSpec()
                    .editZookeeper()
                        .withNewPersistentClaimStorageStorage().withDeleteClaim(true).endPersistentClaimStorageStorage()
                    .endZookeeper()
                .endSpec()
            .build();
        zc = ZookeeperCluster.fromCrd(ka, VERSIONS);
        ss = zc.generateStatefulSet(true);
        assertTrue(ZookeeperCluster.deleteClaim(ss));
    }

    // TODO test volume claim templates

    @Test
    public void testPodNames() {

        for (int i = 0; i < replicas; i++) {
            assertEquals(ZookeeperCluster.zookeeperPodName(cluster, i), zc.getPodName(i));
        }
    }

    @Test
    public void testPvcNames() {

        for (int i = 0; i < replicas; i++) {
            assertEquals(zc.VOLUME_NAME + "-" + ZookeeperCluster.zookeeperClusterName(cluster) + "-" + i, zc.getPersistentVolumeClaimName(i));
        }
    }

    @Test
    public void withAffinity() throws IOException {
        resourceTester.assertDesiredResource("-SS.yaml", zc -> zc.generateStatefulSet(true).getSpec().getTemplate().getSpec().getAffinity());
    }

    public void checkOwnerReference(OwnerReference ownerRef, HasMetadata resource)  {
        assertEquals(1, resource.getMetadata().getOwnerReferences().size());
        assertEquals(ownerRef, resource.getMetadata().getOwnerReferences().get(0));
    }

    @Test
    public void testGenerateBrokerSecret() throws CertificateParsingException {
        ClusterCa clusterCa = new ClusterCa(new OpenSslCertManager(), cluster, null, null);
        clusterCa.createOrRenew(namespace, cluster, emptyMap(), null);

        Secret secret = zc.generateNodesSecret(clusterCa, ka);
        assertEquals(set(
                "foo-zookeeper-0.crt",  "foo-zookeeper-0.key",
                "foo-zookeeper-1.crt", "foo-zookeeper-1.key",
                "foo-zookeeper-2.crt", "foo-zookeeper-2.key"),
                secret.getData().keySet());
        X509Certificate cert = Ca.cert(secret, "foo-zookeeper-0.crt");
        assertEquals("CN=foo-zookeeper, O=io.strimzi", cert.getSubjectDN().getName());
        assertEquals(set(
                asList(2, "foo-zookeeper-0.foo-zookeeper-nodes.test.svc.cluster.local"),
                asList(2, "foo-zookeeper-client"),
                asList(2, "foo-zookeeper-client.test"),
                asList(2, "foo-zookeeper-client.test.svc"),
                asList(2, "foo-zookeeper-client.test.svc.cluster.local")),
                new HashSet<Object>(cert.getSubjectAlternativeNames()));

    }

    @Test
    public void testTemplate() {
        Map<String, String> ssLabels = TestUtils.map("l1", "v1", "l2", "v2");
        Map<String, String> ssAnots = TestUtils.map("a1", "v1", "a2", "v2");

        Map<String, String> podLabels = TestUtils.map("l3", "v3", "l4", "v4");
        Map<String, String> podAnots = TestUtils.map("a3", "v3", "a4", "v4");

        Map<String, String> svcLabels = TestUtils.map("l5", "v5", "l6", "v6");
        Map<String, String> svcAnots = TestUtils.map("a5", "v5", "a6", "v6");

        Map<String, String> hSvcLabels = TestUtils.map("l7", "v7", "l8", "v8");
        Map<String, String> hSvcAnots = TestUtils.map("a7", "v7", "a8", "v8");

        Kafka kafkaAssembly = new KafkaBuilder(ResourceUtils.createKafkaCluster(namespace, cluster, replicas,
                image, healthDelay, healthTimeout, metricsCmJson, configurationJson, emptyMap()))
                .editSpec()
                    .editZookeeper()
                        .withNewTemplate()
                            .withNewStatefulset()
                                .withNewMetadata()
                                    .withLabels(ssLabels)
                                    .withAnnotations(ssAnots)
                                .endMetadata()
                            .endStatefulset()
                            .withNewPod()
                                .withNewMetadata()
                                    .withLabels(podLabels)
                                    .withAnnotations(podAnots)
                                .endMetadata()
                            .endPod()
                            .withNewClientService()
                                .withNewMetadata()
                                    .withLabels(svcLabels)
                                    .withAnnotations(svcAnots)
                                .endMetadata()
                            .endClientService()
                            .withNewNodesService()
                                .withNewMetadata()
                                    .withLabels(hSvcLabels)
                                    .withAnnotations(hSvcAnots)
                                .endMetadata()
                            .endNodesService()
                        .endTemplate()
                    .endZookeeper()
                .endSpec()
                .build();
        ZookeeperCluster zc = ZookeeperCluster.fromCrd(kafkaAssembly, VERSIONS);

        // Check StatefulSet
        StatefulSet ss = zc.generateStatefulSet(true);
        assertTrue(ss.getMetadata().getLabels().entrySet().containsAll(ssLabels.entrySet()));
        assertTrue(ss.getMetadata().getAnnotations().entrySet().containsAll(ssAnots.entrySet()));

        // Check Pods
        assertTrue(ss.getSpec().getTemplate().getMetadata().getLabels().entrySet().containsAll(podLabels.entrySet()));
        assertTrue(ss.getSpec().getTemplate().getMetadata().getAnnotations().entrySet().containsAll(podAnots.entrySet()));

        // Check Service
        Service svc = zc.generateService();
        assertTrue(svc.getMetadata().getLabels().entrySet().containsAll(svcLabels.entrySet()));
        assertTrue(svc.getMetadata().getAnnotations().entrySet().containsAll(svcAnots.entrySet()));

        // Check Headless Service
        svc = zc.generateHeadlessService();
        assertTrue(svc.getMetadata().getLabels().entrySet().containsAll(hSvcLabels.entrySet()));
        assertTrue(svc.getMetadata().getAnnotations().entrySet().containsAll(hSvcAnots.entrySet()));
    }
}
