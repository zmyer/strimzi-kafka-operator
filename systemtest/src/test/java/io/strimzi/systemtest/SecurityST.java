/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.test.ClusterOperator;
import io.strimzi.test.Namespace;
import io.strimzi.test.OpenShiftOnly;
import io.strimzi.test.StrimziExtension;
import io.strimzi.test.TestUtils;
import io.strimzi.test.k8s.KubeClusterException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static io.strimzi.api.kafka.model.KafkaResources.clientsCaCertificateSecretName;
import static io.strimzi.api.kafka.model.KafkaResources.clientsCaKeySecretName;
import static io.strimzi.api.kafka.model.KafkaResources.clusterCaCertificateSecretName;
import static io.strimzi.api.kafka.model.KafkaResources.clusterCaKeySecretName;
import static io.strimzi.api.kafka.model.KafkaResources.kafkaStatefulSetName;
import static io.strimzi.api.kafka.model.KafkaResources.zookeeperStatefulSetName;
import static io.strimzi.test.StrimziExtension.REGRESSION;
import static io.strimzi.test.TestUtils.map;
import static io.strimzi.test.TestUtils.waitFor;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(StrimziExtension.class)
@Namespace(SecurityST.NAMESPACE)
@ClusterOperator
@Tag(REGRESSION)
class SecurityST extends AbstractST {

    public static final String NAMESPACE = "security-cluster-test";
    private static final Logger LOGGER = LogManager.getLogger(SecurityST.class);
    private static final String OPENSSL_RETURN_CODE = "Verify return code: 0 (ok)";
    private static final String TLS_PROTOCOL = "Protocol  : TLSv1";
    private static final String SSL_TIMEOUT = "Timeout   : 300 (sec)";
    public static final String STRIMZI_IO_FORCE_RENEW = "strimzi.io/force-renew";

    @Test
    void testCertificates() {
        LOGGER.info("Running testCertificates {}", CLUSTER_NAME);
        resources().kafkaEphemeral(CLUSTER_NAME, 2)
            .editSpec().editZookeeper().withReplicas(2).endZookeeper().endSpec().done();
        String commandForKafkaBootstrap = "echo -n | openssl s_client -connect my-cluster-kafka-bootstrap:9093 -showcerts" +
                        " -CAfile /opt/kafka/cluster-ca-certs/ca.crt" +
                        " -verify_hostname my-cluster-kafka-bootstrap";

        String outputForKafkaBootstrap =
                kubeClient.execInPodContainer(kafkaPodName(CLUSTER_NAME, 0), "kafka",
                        "/bin/bash", "-c", commandForKafkaBootstrap).out();
        checkKafkaCertificates(outputForKafkaBootstrap);

        String commandForZookeeperClient = "echo -n | openssl s_client -connect my-cluster-zookeeper-client:2181 -showcerts" +
                " -CAfile /opt/kafka/cluster-ca-certs/ca.crt" +
                " -verify_hostname my-cluster-zookeeper-client" +
                " -cert /opt/kafka/broker-certs/my-cluster-kafka-0.crt" +
                " -key /opt/kafka/broker-certs/my-cluster-kafka-0.key";
        String outputForZookeeperClient =
                kubeClient.execInPodContainer(kafkaPodName(CLUSTER_NAME, 0), "kafka",
                        "/bin/bash", "-c", commandForZookeeperClient).out();
        checkZookeeperCertificates(outputForZookeeperClient);

        IntStream.rangeClosed(0, 1).forEach(podId -> {
            String commandForKafkaPort9091 = "echo -n | " + generateOpenSSLCommandWithCerts(kafkaPodName(CLUSTER_NAME, podId), "my-cluster-kafka-brokers", "9091");
            String commandForKafkaPort9093 = "echo -n | " + generateOpenSSLCommandWithCAfile(kafkaPodName(CLUSTER_NAME, podId), "my-cluster-kafka-brokers", "9093");

            String outputForKafkaPort9091 =
                    kubeClient.execInPodContainer(kafkaPodName(CLUSTER_NAME, podId), "kafka", "/bin/bash", "-c", commandForKafkaPort9091).out();
            String outputForKafkaPort9093 =
                    kubeClient.execInPodContainer(kafkaPodName(CLUSTER_NAME, podId), "kafka", "/bin/bash", "-c", commandForKafkaPort9093).out();

            checkKafkaCertificates(outputForKafkaPort9091, outputForKafkaPort9093);

            String commandForZookeeperPort2181 = "echo -n | " + generateOpenSSLCommandWithCerts(zookeeperPodName(CLUSTER_NAME, podId), "my-cluster-zookeeper-nodes", "2181");
            String commandForZookeeperPort2888 = "echo -n | " + generateOpenSSLCommandWithCerts(zookeeperPodName(CLUSTER_NAME, podId), "my-cluster-zookeeper-nodes", "2888");
            String commandForZookeeperPort3888 = "echo -n | " + generateOpenSSLCommandWithCerts(zookeeperPodName(CLUSTER_NAME, podId), "my-cluster-zookeeper-nodes", "3888");

            String outputForZookeeperPort2181 =
                    kubeClient.execInPodContainer(kafkaPodName(CLUSTER_NAME, podId), "kafka", "/bin/bash", "-c",
                            commandForZookeeperPort2181).out();

            String outputForZookeeperPort3888 =
                    kubeClient.execInPodContainer(kafkaPodName(CLUSTER_NAME, podId), "kafka", "/bin/bash", "-c",
                            commandForZookeeperPort3888).out();
            checkZookeeperCertificates(outputForZookeeperPort2181, outputForZookeeperPort3888);

            try {
                String outputForZookeeperPort2888 =
                        kubeClient.execInPodContainer(kafkaPodName(CLUSTER_NAME, podId), "kafka", "/bin/bash", "-c",
                                commandForZookeeperPort2888).out();
                checkZookeeperCertificates(outputForZookeeperPort2888);
            } catch (KubeClusterException e) {
                if (e.result != null && e.result.exitStatus() == 104) {
                    LOGGER.info("The connection for {} was forcibly closed because of new zookeeper leader", zookeeperPodName(CLUSTER_NAME, podId));
                } else {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private String generateOpenSSLCommandWithCAfile(String podName, String hostname, String port) {
        return "openssl s_client -connect " + podName + "." + hostname + ":" + port +
                " -showcerts -CAfile /opt/kafka/cluster-ca-certs/ca.crt " +
                "-verify_hostname " + podName + "." + hostname + "." + NAMESPACE + ".svc.cluster.local";
    }

    private String generateOpenSSLCommandWithCerts(String podName, String hostname, String port) {
        return generateOpenSSLCommandWithCAfile(podName, hostname, port) +
                " -cert /opt/kafka/broker-certs/my-cluster-kafka-0.crt" +
                " -key /opt/kafka/broker-certs/my-cluster-kafka-0.key";
    }

    private void checkKafkaCertificates(String... kafkaCertificates) {
        String kafkaCertificateChain = "s:/O=io.strimzi/CN=my-cluster-kafka\n" +
                "   i:/O=io.strimzi/CN=cluster-ca";
        String kafkaBrokerCertificate = "Server certificate\n" +
                "subject=/O=io.strimzi/CN=my-cluster-kafka\n" +
                "issuer=/O=io.strimzi/CN=cluster-ca";
        for (String kafkaCertificate : kafkaCertificates) {
            assertThat(kafkaCertificate, containsString(kafkaCertificateChain));
            assertThat(kafkaCertificate, containsString(kafkaBrokerCertificate));
            assertThat(kafkaCertificate, containsString(TLS_PROTOCOL));
            assertThat(kafkaCertificate, containsString(SSL_TIMEOUT));
            assertThat(kafkaCertificate, containsString(OPENSSL_RETURN_CODE));
        }
    }

    private void checkZookeeperCertificates(String... zookeeperCertificates) {
        String zookeeperCertificateChain = "s:/O=io.strimzi/CN=my-cluster-zookeeper\n" +
                "   i:/O=io.strimzi/CN=cluster-ca";
        String zookeeperNodeCertificate = "Server certificate\n" +
                "subject=/O=io.strimzi/CN=my-cluster-zookeeper\n" +
                "issuer=/O=io.strimzi/CN=cluster-ca";
        for (String zookeeperCertificate : zookeeperCertificates) {
            assertThat(zookeeperCertificate, containsString(zookeeperCertificateChain));
            assertThat(zookeeperCertificate, containsString(zookeeperNodeCertificate));
            assertThat(zookeeperCertificate, containsString(TLS_PROTOCOL));
            assertThat(zookeeperCertificate, containsString(SSL_TIMEOUT));
            assertThat(zookeeperCertificate, containsString(OPENSSL_RETURN_CODE));
        }
    }



    @Test
    @OpenShiftOnly
    public void testAutoRenewCaCertsTriggeredByAnno() throws InterruptedException {
        createCluster();
        String userName = "alice";
        resources().tlsUser(CLUSTER_NAME, userName).done();
        waitFor("", 1_000, 60_000, () -> {
            return client.secrets().inNamespace(NAMESPACE).withName("alice").get() != null;
        },
            () -> {
                LOGGER.error("Couldn't find user secret {}", client.secrets().inNamespace(NAMESPACE).list().getItems());
            });

        AvailabilityVerifier mp = waitForInitialAvailability(userName);

        // Get all pods, and their resource versions
        Map<String, String> zkPods = StUtils.ssSnapshot(client, NAMESPACE, zookeeperStatefulSetName(CLUSTER_NAME));
        Map<String, String> kafkaPods = StUtils.ssSnapshot(client, NAMESPACE, kafkaStatefulSetName(CLUSTER_NAME));

        LOGGER.info("Triggering CA cert renewal by adding the annotation");
        Map<String, String> initialCaCerts = new HashMap<>();
        List<String> secrets = asList(clusterCaCertificateSecretName(CLUSTER_NAME)/*,
                // TODO why doesn't the clients CA cert get renewed?
                clientsCaCertificateSecretName(CLUSTER_NAME)*/);
        for (String secretName : secrets) {
            Secret secret = client.secrets().inNamespace(NAMESPACE).withName(secretName).get();
            String value = secret.getData().get("ca.crt");
            assertNotNull("ca.crt in " + secretName + " should not be null", value);
            initialCaCerts.put(secretName, value);
            Secret annotated = new SecretBuilder(secret)
                    .editMetadata()
                        .addToAnnotations(STRIMZI_IO_FORCE_RENEW, "true")
                    .endMetadata()
                .build();
            LOGGER.info("Patching secret {} with {}", secretName, STRIMZI_IO_FORCE_RENEW);
            client.secrets().inNamespace(NAMESPACE).withName(secretName).patch(annotated);
        }

        Map<String, String> eoPod = StUtils.depSnapshot(client, NAMESPACE, KafkaResources.entityOperatorDeploymentName(CLUSTER_NAME));

        LOGGER.info("Wait for zk to rolling restart (2)..");
        StUtils.waitTillSsHasRolled(client, NAMESPACE, zookeeperStatefulSetName(CLUSTER_NAME), zkPods);
        LOGGER.info("Wait for kafka to rolling restart (2)...");
        StUtils.waitTillSsHasRolled(client, NAMESPACE, kafkaStatefulSetName(CLUSTER_NAME), kafkaPods);

        LOGGER.info("Checking the certificates have been replaced");
        for (String secretName : secrets) {
            Secret secret = client.secrets().inNamespace(NAMESPACE).withName(secretName).get();
            assertNotNull(secret, "Secret " + secretName + " should exist");
            assertNotNull(secret.getData(), "CA cert in " + secretName + " should have non-null 'data'");
            String value = secret.getData().get("ca.crt");
            assertNotEquals("CA cert in " + secretName + " should have changed",
                    initialCaCerts.get(secretName), value);
        }

        waitForAvailability(mp);

        AvailabilityVerifier.Result result = mp.stop(30_000);
        LOGGER.info("Producer/consumer stats during cert renewal {}", result);
    }
    
    private AvailabilityVerifier waitForInitialAvailability(String userName) {
        AvailabilityVerifier mp = new AvailabilityVerifier(client, NAMESPACE, CLUSTER_NAME, userName);
        mp.start();

        TestUtils.waitFor("Some messages sent received", 1_000, 30_000,
            () -> {
                AvailabilityVerifier.Result stats = mp.stats();
                LOGGER.info("{}", stats);
                return stats.sent() > 0
                        && stats.received() > 0;
            });
        return mp;
    }

    private void waitForAvailability(AvailabilityVerifier mp) {
        LOGGER.info("Checking producers and consumers still functioning");
        AvailabilityVerifier.Result stats = mp.stats();
        long received = stats.received();
        long sent = stats.sent();
        TestUtils.waitFor("Some messages received after update",
            1_000, 30_000,
            () -> {
                AvailabilityVerifier.Result stats1 = mp.stats();
                LOGGER.info("{}", stats1);
                return stats1.sent() > sent
                        && stats1.received() > received;
            });
    }

    private void createCluster() {
        LOGGER.info("Creating a cluster");
        resources().kafkaEphemeral(CLUSTER_NAME, 3)
                .editSpec()
                    .editKafka()
                        .editListeners()
                            .withNewKafkaListenerExternalRouteExternal()
                            .endKafkaListenerExternalRouteExternal()
                        .endListeners()
                        .withConfig(singletonMap("default.replication.factor", 3))
                        .withNewPersistentClaimStorageStorage()
                            .withSize("2Gi")
                            .withDeleteClaim(true)
                        .endPersistentClaimStorageStorage()
                    .endKafka()
                    .editZookeeper()
                        .withReplicas(3)
                        .withNewPersistentClaimStorageStorage()
                            .withSize("2Gi")
                            .withDeleteClaim(true)
                        .endPersistentClaimStorageStorage()
                    .endZookeeper()
                .endSpec()
                .done();
    }

    @Test
    @OpenShiftOnly
    public void testAutoRenewCaCertsTriggerByExpiredCertificate() throws InterruptedException {
        // 1. Create the Secrets already, and a certificate that's already expired
        String clusterCaKey = createSecret("cluster-ca.key", clusterCaKeySecretName(CLUSTER_NAME), "ca.key");
        String clusterCaCert = createSecret("cluster-ca.crt", clusterCaCertificateSecretName(CLUSTER_NAME), "ca.crt");
        String clientsCaKey = createSecret("clients-ca.key", clientsCaKeySecretName(CLUSTER_NAME), "ca.key");
        String clientsCaCert = createSecret("clients-ca.crt", clientsCaCertificateSecretName(CLUSTER_NAME), "ca.crt");

        // 2. Now create a cluster
        createCluster();
        String userName = "alice";
        resources().tlsUser(CLUSTER_NAME, userName).done();

        AvailabilityVerifier mp = waitForInitialAvailability(userName);

        // Wait until the certificates have been replaced
        waitForCertToChange(clusterCaCert, clusterCaCertificateSecretName(CLUSTER_NAME));

        // Wait until the pods are all up and ready
        waitForClusterStability();

        waitForAvailability(mp);

        AvailabilityVerifier.Result result = mp.stop(30_000);
        LOGGER.info("Producer/consumer stats during cert renewal {}", result);
    }

    private void waitForClusterStability() {
        LOGGER.info("Waiting for cluster stability");
        Map<String, String>[] zkPods = new Map[1];
        Map<String, String>[] kafkaPods = new Map[1];
        Map<String, String>[] eoPods = new Map[1];
        AtomicInteger count = new AtomicInteger();
        zkPods[0] = StUtils.ssSnapshot(client, NAMESPACE, zookeeperStatefulSetName(CLUSTER_NAME));
        kafkaPods[0] = StUtils.ssSnapshot(client, NAMESPACE, kafkaStatefulSetName(CLUSTER_NAME));
        eoPods[0] = StUtils.depSnapshot(client, NAMESPACE, entityOperatorDeploymentName(CLUSTER_NAME));
        TestUtils.waitFor("Cluster stable and ready", 1_000, 1_200_000, () -> {
            Map<String, String> zkSnapshot = StUtils.ssSnapshot(client, NAMESPACE, zookeeperStatefulSetName(CLUSTER_NAME));
            Map<String, String> kafkaSnaptop = StUtils.ssSnapshot(client, NAMESPACE, kafkaStatefulSetName(CLUSTER_NAME));
            Map<String, String> eoSnapshot = StUtils.depSnapshot(client, NAMESPACE, entityOperatorDeploymentName(CLUSTER_NAME));
            boolean zkSameAsLast = zkSnapshot.equals(zkPods[0]);
            boolean kafkaSameAsLast = kafkaSnaptop.equals(kafkaPods[0]);
            boolean eoSameAsLast = eoSnapshot.equals(eoPods[0]);
            if (!zkSameAsLast) {
                LOGGER.info("ZK Cluster not stable");
            }
            if (!kafkaSameAsLast) {
                LOGGER.info("Kafka Cluster not stable");
            }
            if (!eoSameAsLast) {
                LOGGER.info("EO not stable");
            }
            if (zkSameAsLast
                && kafkaSameAsLast
                && eoSameAsLast) {
                int c = count.getAndIncrement();
                LOGGER.info("All stable for {} polls", c);
                return c > 60;
            }
            zkPods[0] = zkSnapshot;
            kafkaPods[0] = kafkaSnaptop;
            count.set(0);
            return false;
        });
    }

    private void waitForCertToChange(String originalCert, String secretName) {
        waitFor("Cert to be replaced", 1_000, 1_200_000, () -> {
            Secret secret = client.secrets().inNamespace(NAMESPACE).withName(secretName).get();
            if (secret != null && secret.getData() != null && secret.getData().containsKey("ca.crt")) {
                String currentCert = new String(Base64.getDecoder().decode(secret.getData().get("ca.crt")), StandardCharsets.US_ASCII);
                boolean changed = !originalCert.equals(currentCert);
                if (changed) {
                    LOGGER.info("Certificate in Secret {} has changed, was {}, is now {}", secretName, originalCert, currentCert);
                }
                return changed;
            } else {
                return false;
            }
        });
    }

    private String createSecret(String resourceName, String secretName, String keyName) {
        String certAsString = TestUtils.readResource(getClass(), resourceName);
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                    .withName(secretName)
                    .addToLabels(map(
                        "strimzi.io/cluster", CLUSTER_NAME,
                        "strimzi.io/kind", "Kafka"))
                .endMetadata()
                .withData(singletonMap(keyName, Base64.getEncoder().encodeToString(certAsString.getBytes(StandardCharsets.US_ASCII))))
            .build();
        client.secrets().inNamespace(NAMESPACE).create(secret);
        return certAsString;
    }

    /**
     * Test the case where the cluster is initial created with manual CA
     */
    @Test
    public void testManualCaCertFromScratch() {
        // TODO
    }

    /**
     * Test the case where the cluster is initial created with manual CA and we transition to auto CA
     */
    @Test
    public void testAutoCaCertFromManual() {
        // TODO
    }

    /**
     * Test the case where the cluster is initial auto CA and we transition to manual CA
     */
    @Test
    public void testManualCaCertFromAuto() {
        // TODO
    }

    @Test
    public void testManualCaCertRenewal() {

    }
}
