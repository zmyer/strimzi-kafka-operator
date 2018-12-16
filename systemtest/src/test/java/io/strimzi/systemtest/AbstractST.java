/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import com.jayway.jsonpath.JsonPath;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Doneable;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Job;
import io.fabric8.kubernetes.api.model.JobBuilder;
import io.fabric8.kubernetes.api.model.JobStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaAssemblyList;
import io.strimzi.api.kafka.KafkaConnectAssemblyList;
import io.strimzi.api.kafka.KafkaTopicList;
import io.strimzi.api.kafka.model.DoneableKafka;
import io.strimzi.api.kafka.model.DoneableKafkaConnect;
import io.strimzi.api.kafka.model.DoneableKafkaTopic;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.KafkaUserScramSha512ClientAuthentication;
import io.strimzi.api.kafka.model.KafkaUserTlsClientAuthentication;
import io.strimzi.systemtest.timemeasuring.Operation;
import io.strimzi.systemtest.timemeasuring.TimeMeasuringSystem;
import io.strimzi.test.TestUtils;
import io.strimzi.test.TimeoutException;
import io.strimzi.test.k8s.KubeClient;
import io.strimzi.test.k8s.KubeClusterException;
import io.strimzi.test.k8s.KubeClusterResource;
import io.strimzi.test.k8s.ProcessResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.strimzi.systemtest.matchers.Matchers.logHasNoUnexpectedErrors;
import static io.strimzi.test.TestUtils.indent;
import static io.strimzi.test.TestUtils.toYamlString;
import static io.strimzi.test.TestUtils.waitFor;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


public class AbstractST {

    static {
        Crds.registerCustomKinds();
    }

    private static final Logger LOGGER = LogManager.getLogger(AbstractST.class);
    protected static final String CLUSTER_NAME = "my-cluster";
    protected static final String ZK_IMAGE = "STRIMZI_DEFAULT_ZOOKEEPER_IMAGE";
    protected static final String KAFKA_IMAGE_MAP = "STRIMZI_KAFKA_IMAGES";
    protected static final String KAFKA_CONNECT_IMAGE_MAP = "STRIMZI_KAFKA_CONNECT_IMAGES";
    protected static final String KAFKA_CONNECT_S2I_IMAGE_MAP = "STRIMZI_KAFKA_CONNECT_S2I_IMAGES";
    protected static final String CONNECT_IMAGE = "STRIMZI_DEFAULT_KAFKA_CONNECT_IMAGE";
    protected static final String S2I_IMAGE = "STRIMZI_DEFAULT_KAFKA_CONNECT_S2I_IMAGE";
    protected static final String TO_IMAGE = "STRIMZI_DEFAULT_TOPIC_OPERATOR_IMAGE";
    protected static final String UO_IMAGE = "STRIMZI_DEFAULT_USER_OPERATOR_IMAGE";
    protected static final String TEST_TOPIC_NAME = "test-topic";
    protected static final String KAFKA_INIT_IMAGE = "STRIMZI_DEFAULT_KAFKA_INIT_IMAGE";
    protected static final String TLS_SIDECAR_ZOOKEEPER_IMAGE = "STRIMZI_DEFAULT_TLS_SIDECAR_ZOOKEEPER_IMAGE";
    protected static final String TLS_SIDECAR_KAFKA_IMAGE = "STRIMZI_DEFAULT_TLS_SIDECAR_KAFKA_IMAGE";
    protected static final String TLS_SIDECAR_EO_IMAGE = "STRIMZI_DEFAULT_TLS_SIDECAR_ENTITY_OPERATOR_IMAGE";

    public static KubeClusterResource cluster = new KubeClusterResource();
    protected static DefaultKubernetesClient client = new DefaultKubernetesClient();
    static KubeClient<?> kubeClient = cluster.client();

    private Resources resources;
    static String operationID;
    static String testClass;
    static String testName;
    Random rng = new Random();

    protected static NamespacedKubernetesClient namespacedClient() {
        return client.inNamespace(kubeClient.namespace());
    }

    static String kafkaClusterName(String clusterName) {
        return KafkaResources.kafkaStatefulSetName(clusterName);
    }

    static String kafkaConnectName(String clusterName) {
        return clusterName + "-connect";
    }

    static String kafkaPodName(String clusterName, int podId) {
        return KafkaResources.kafkaPodName(clusterName, podId);
    }

    static String kafkaServiceName(String clusterName) {
        return KafkaResources.bootstrapServiceName(clusterName);
    }

    static String kafkaHeadlessServiceName(String clusterName) {
        return KafkaResources.brokersServiceName(clusterName);
    }

    static String kafkaMetricsConfigName(String clusterName) {
        return KafkaResources.kafkaMetricsAndLogConfigMapName(clusterName);
    }

    static String zookeeperClusterName(String clusterName) {
        return KafkaResources.zookeeperStatefulSetName(clusterName);
    }

    static String zookeeperPodName(String clusterName, int podId) {
        return KafkaResources.zookeeperPodName(clusterName, podId);
    }

    static String zookeeperServiceName(String clusterName) {
        return zookeeperClusterName(clusterName) + "-client";
    }

    static String zookeeperHeadlessServiceName(String clusterName) {
        return zookeeperClusterName(clusterName) + "-nodes";
    }

    static String zookeeperMetricsConfigName(String clusterName) {
        return KafkaResources.zookeeperMetricsAndLogConfigMapName(clusterName);
    }

    static String zookeeperPVCName(String clusterName, int podId) {
        return "data-" + zookeeperClusterName(clusterName) + "-" + podId;
    }

    static String entityOperatorDeploymentName(String clusterName) {
        return KafkaResources.entityOperatorDeploymentName(clusterName);
    }

    private <T extends CustomResource, L extends CustomResourceList<T>, D extends Doneable<T>>
        void replaceCrdResource(Class<T> crdClass, Class<L> listClass, Class<D> doneableClass, String resourceName, Consumer<T> editor) {
        Resource<T, D> namedResource = Crds.operation(client, crdClass, listClass, doneableClass).inNamespace(kubeClient.namespace()).withName(resourceName);
        T resource = namedResource.get();
        editor.accept(resource);
        namedResource.replace(resource);
    }

    void replaceKafkaResource(String resourceName, Consumer<Kafka> editor) {
        replaceCrdResource(Kafka.class, KafkaAssemblyList.class, DoneableKafka.class, resourceName, editor);
    }

    void replaceKafkaConnectResource(String resourceName, Consumer<KafkaConnect> editor) {
        replaceCrdResource(KafkaConnect.class, KafkaConnectAssemblyList.class, DoneableKafkaConnect.class, resourceName, editor);
    }

    void replaceTopicResource(String resourceName, Consumer<KafkaTopic> editor) {
        replaceCrdResource(KafkaTopic.class, KafkaTopicList.class, DoneableKafkaTopic.class, resourceName, editor);
    }

    String getBrokerApiVersions(String podName) {
        AtomicReference<String> versions = new AtomicReference<>();
        TestUtils.waitFor("kafka-broker-api-versions.sh success", 1_000L, 30_000L, () -> {
            try {
                String output = kubeClient.execInPod(podName,
                        "/opt/kafka/bin/kafka-broker-api-versions.sh", "--bootstrap-server", "localhost:9092").out();
                versions.set(output);
                return true;
            } catch (KubeClusterException e) {
                LOGGER.trace("/opt/kafka/bin/kafka-broker-api-versions.sh: {}", e.getMessage());
                return false;
            }
        });
        return versions.get();
    }

    void waitForZkMntr(Pattern pattern, int... podIndexes) {
        long timeoutMs = 120_000L;
        long pollMs = 1_000L;

        for (int podIndex : podIndexes) {
            String zookeeperPod = zookeeperPodName(CLUSTER_NAME, podIndex);
            String zookeeperPort = String.valueOf(2181 * 10 + podIndex);
            TestUtils.waitFor("mntr", pollMs, timeoutMs, () -> {
                try {
                    String output = kubeClient.execInPod(zookeeperPod,
                        "/bin/bash", "-c", "echo mntr | nc localhost " + zookeeperPort).out();

                    if (pattern.matcher(output).find()) {
                        return true;
                    }
                } catch (KubeClusterException e) {
                    LOGGER.trace("Exception while waiting for ZK to become leader/follower, ignoring", e);
                }
                return false;
                },
                () -> LOGGER.info("zookeeper `mntr` output at the point of timeout does not match {}:{}{}",
                    pattern.pattern(),
                    System.lineSeparator(),
                    indent(kubeClient.execInPod(zookeeperPod, "/bin/bash", "-c", "echo mntr | nc localhost " + zookeeperPort).out()))
            );
        }
    }

    String getValueFromJson(String json, String jsonPath) {
        return JsonPath.parse(json).read(jsonPath).toString();
    }

    String globalVariableJsonPathBuilder(String variable) {
        return "$.spec.containers[*].env[?(@.name=='" + variable + "')].value";
    }

    List<Event> getEvents(String resourceType, String resourceName) {
        return client.events().inNamespace(kubeClient.namespace()).list().getItems().stream()
                .filter(event -> event.getInvolvedObject().getKind().equals(resourceType))
                .filter(event -> event.getInvolvedObject().getName().equals(resourceName))
                .collect(Collectors.toList());
    }

    public void sendMessages(String podName, String clusterName, String topic, int messagesCount) {
        LOGGER.info("Sending messages");
        String command = "sh bin/kafka-verifiable-producer.sh --broker-list " +
                KafkaResources.plainBootstrapAddress(clusterName) + " --topic " + topic + " --max-messages " + messagesCount + "";

        LOGGER.info("Command for kafka-verifiable-producer.sh {}", command);

        kubeClient.execInPod(podName, "/bin/bash", "-c", command);
    }

    public String consumeMessages(String clusterName, String topic, int groupID, int timeout, int kafkaPodID) {
        LOGGER.info("Consuming messages");
        String output = kubeClient.execInPod(kafkaPodName(clusterName, kafkaPodID), "/bin/bash", "-c",
                "bin/kafka-verifiable-consumer.sh --broker-list " +
                        KafkaResources.plainBootstrapAddress(clusterName) + " --topic " + topic + " --group-id " + groupID + " & sleep "
                        + timeout + "; kill %1").out();
        output = "[" + output.replaceAll("\n", ",") + "]";
        LOGGER.info("Output for kafka-verifiable-consumer.sh {}", output);
        return output;

    }

    protected void assertResources(String namespace, String podName, String memoryLimit, String cpuLimit, String memoryRequest, String cpuRequest) {
        Pod po = client.pods().inNamespace(namespace).withName(podName).get();
        assertNotNull(po, "Not found an expected pod  " + podName + " in namespace " + namespace + " but found " +
            client.pods().list().getItems().stream().map(p -> p.getMetadata().getName()).collect(Collectors.toList()));
        Container container = po.getSpec().getContainers().get(0);
        Map<String, Quantity> limits = container.getResources().getLimits();
        assertEquals(memoryLimit, limits.get("memory").getAmount());
        assertEquals(cpuLimit, limits.get("cpu").getAmount());
        Map<String, Quantity> requests = container.getResources().getRequests();
        assertEquals(memoryRequest, requests.get("memory").getAmount());
        assertEquals(cpuRequest, requests.get("cpu").getAmount());
    }

    protected void assertExpectedJavaOpts(String podName, String expectedXmx, String expectedXms, String expectedServer, String expectedXx) {
        List<List<String>> cmdLines = commandLines(podName, "java");
        assertEquals(1, cmdLines.size(), "Expected exactly 1 java process to be running");
        List<String> cmd = cmdLines.get(0);
        int toIndex = cmd.indexOf("-jar");
        if (toIndex != -1) {
            // Just consider arguments to the JVM, not the application running in it
            cmd = cmd.subList(0, toIndex);
            // We should do something similar if the class not -jar was given, but that's
            // hard to do properly.
        }
        assertCmdOption(cmd, expectedXmx);
        assertCmdOption(cmd, expectedXms);
        assertCmdOption(cmd, expectedServer);
        assertCmdOption(cmd, expectedXx);
    }

    private void assertCmdOption(List<String> cmd, String expectedXmx) {
        if (!cmd.contains(expectedXmx)) {
            fail("Failed to find argument matching " + expectedXmx + " in java command line " +
                    cmd.stream().collect(Collectors.joining("\n")));
        }
    }

    private List<List<String>> commandLines(String podName, String cmd) {
        List<List<String>> result = new ArrayList<>();
        ProcessResult pr = kubeClient.execInPod(podName, "/bin/bash", "-c",
                "for pid in $(ps -C java -o pid h); do cat /proc/$pid/cmdline; done"
        );
        for (String cmdLine : pr.out().split("\n")) {
            result.add(asList(cmdLine.split("\0")));
        }
        return result;
    }

    void assertNoCoErrorsLogged(long sinceSeconds) {
        LOGGER.info("Search in strimzi-cluster-operator log for errors in last {} seconds", sinceSeconds);
        String clusterOperatorLog = kubeClient.searchInLog("deploy", "strimzi-cluster-operator", sinceSeconds, "Exception", "Error", "Throwable");
        assertThat(clusterOperatorLog, logHasNoUnexpectedErrors());
    }

    public List<String> listTopicsUsingPodCLI(String clusterName, int zkPodId) {
        String podName = zookeeperPodName(clusterName, zkPodId);
        int port = 2181 * 10 + zkPodId;
        return asList(kubeClient.execInPod(podName, "/bin/bash", "-c",
                "bin/kafka-topics.sh --list --zookeeper localhost:" + port).out().split("\\s+"));
    }

    public String createTopicUsingPodCLI(String clusterName, int zkPodId, String topic, int replicationFactor, int partitions) {
        String podName = zookeeperPodName(clusterName, zkPodId);
        int port = 2181 * 10 + zkPodId;
        return kubeClient.execInPod(podName, "/bin/bash", "-c",
                "bin/kafka-topics.sh --zookeeper localhost:" + port + " --create " + " --topic " + topic +
                        " --replication-factor " + replicationFactor + " --partitions " + partitions).out();
    }

    public String deleteTopicUsingPodCLI(String clusterName, int zkPodId, String topic) {
        String podName = zookeeperPodName(clusterName, zkPodId);
        int port = 2181 * 10 + zkPodId;
        return kubeClient.execInPod(podName, "/bin/bash", "-c",
                "bin/kafka-topics.sh --zookeeper localhost:" + port + " --delete --topic " + topic).out();
    }

    public List<String>  describeTopicUsingPodCLI(String clusterName, int zkPodId, String topic) {
        String podName = zookeeperPodName(clusterName, zkPodId);
        int port = 2181 * 10 + zkPodId;
        return asList(kubeClient.execInPod(podName, "/bin/bash", "-c",
                "bin/kafka-topics.sh --zookeeper localhost:" + port + " --describe --topic " + topic).out().split("\\s+"));
    }

    public String updateTopicPartitionsCountUsingPodCLI(String clusterName, int zkPodId, String topic, int partitions) {
        String podName = zookeeperPodName(clusterName, zkPodId);
        int port = 2181 * 10 + zkPodId;
        return kubeClient.execInPod(podName, "/bin/bash", "-c",
                "bin/kafka-topics.sh --zookeeper localhost:" + port + " --alter --topic " + topic + " --partitions " + partitions).out();
    }

    public Map<String, String> getImagesFromConfig() {
        Map<String, String> images = new HashMap<>();
        for (Container c : client.extensions().deployments().inNamespace(kubeClient.namespace()).withName("strimzi-cluster-operator").get().getSpec().getTemplate().getSpec().getContainers()) {
            for (EnvVar envVar : c.getEnv()) {
                images.put(envVar.getName(), envVar.getValue());
            }
        }
        return images;
    }

    public String getContainerImageNameFromPod(String podName) {
        String clusterOperatorJson = kubeClient.getResourceAsJson("pod", podName);
        return JsonPath.parse(clusterOperatorJson).read("$.spec.containers[*].image").toString().replaceAll("[\"\\[\\]\\\\]", "");
    }

    public String getContainerImageNameFromPod(String podName, String containerName) {
        String clusterOperatorJson = kubeClient.getResourceAsJson("pod", podName);
        return JsonPath.parse(clusterOperatorJson).read("$.spec.containers[?(@.name =='" + containerName + "')].image").toString().replaceAll("[\"\\[\\]\\\\]", "");
    }

    public String  getInitContainerImageName(String podName) {
        String clusterOperatorJson = kubeClient.getResourceAsJson("pod", podName);
        return JsonPath.parse(clusterOperatorJson).read("$.spec.initContainers[-1].image");
    }

    @BeforeEach
    public void createResources(TestInfo testInfo) {
        LOGGER.info("Creating resources before the test");
        resources = new Resources(namespacedClient());
        testName = testInfo.getTestMethod().get().getName();
    }

    @AfterEach
    public void deleteResources() {
        LOGGER.info("Deleting resources after the test");
        resources.deleteResources();
        resources = null;
    }

    Resources resources() {
        return resources;
    }

    String startTimeMeasuring(Operation operation) {
        TimeMeasuringSystem.setTestName(testClass, testName);
        return TimeMeasuringSystem.startOperation(operation);
    }

    /** Get the log of the pod with the given name */
    String podLog(String podName) {
        return namespacedClient().pods().withName(podName).getLog();
    }

    String podLog(String podName, String containerId) {
        return namespacedClient().pods().withName(podName).inContainer(containerId).getLog();
    }

    /** Get the name of the pod for a job */
    String jobPodName(Job job) {
        return podNameWithLabels(job.getSpec().getTemplate().getMetadata().getLabels());
    }

    String userOperatorPodName() {
        return podNameWithLabels(Collections.singletonMap("strimzi.io/name", CLUSTER_NAME + "-entity-operator"));
    }

    String podNameWithLabels(Map<String, String> labels) {
        List<Pod> pods = namespacedClient().pods().withLabels(labels).list().getItems();
        if (pods.size() != 1) {
            fail("There are " + pods.size() +  " pods with labels " + labels);
        }
        return pods.get(0).getMetadata().getName();
    }

    /**
     * Greps logs from a pod which ran kafka-verifiable-producer.sh and
     * kafka-verifiable-consumer.sh
     */
    void checkPings(int messagesCount, Job job) {
        String podName = jobPodName(job);
        String log = podLog(podName);
        Pattern p = Pattern.compile("^\\{.*\\}$", Pattern.MULTILINE);
        Matcher m = p.matcher(log);
        boolean producerSuccess = false;
        boolean consumerSuccess = false;
        while (m.find()) {
            String json = m.group();
            String name2 = getValueFromJson(json, "$.name");
            if ("tool_data".equals(name2)) {
                assertEquals(String.valueOf(messagesCount), getValueFromJson(json, "$.sent"));
                assertEquals(String.valueOf(messagesCount), getValueFromJson(json, "$.acked"));
                producerSuccess = true;
            } else if ("records_consumed".equals(name2)) {
                assertEquals(String.valueOf(messagesCount), getValueFromJson(json, "$.count"));
                consumerSuccess = true;
            }
        }
        if (!producerSuccess || !consumerSuccess) {
            LOGGER.info("log from pod {}:\n----\n{}\n----", podName, indent(log));
        }
        assertTrue(producerSuccess, "The producer didn't send any messages (no tool_data message)");
        assertTrue(consumerSuccess, "The consumer didn't consume any messages (no records_consumed message)");
    }

    /**
     * Waits for a job to complete successfully, {@link org.junit.Assert#fail()}ing
     * if it completes with any failed pods.
     * @throws TimeoutException if the job doesn't complete quickly enough.
     */
    Job waitForJobSuccess(Job job) {
        // Wait for the job to succeed
        try {
            LOGGER.debug("Waiting for Job completion: {}", job);
            waitFor("Job completion", 5000, 150000, () -> {
                Job jobs = namespacedClient().extensions().jobs().withName(job.getMetadata().getName()).get();
                JobStatus status;
                if (jobs == null || (status = jobs.getStatus()) == null) {
                    LOGGER.debug("Poll job is null");
                    return false;
                } else {
                    if (status.getFailed() != null && status.getFailed() > 0) {
                        LOGGER.debug("Poll job failed");
                        fail();
                    } else if (status.getSucceeded() != null && status.getSucceeded() == 1) {
                        LOGGER.debug("Poll job succeeded");
                        return true;
                    } else if (status.getActive() != null && status.getActive() > 0) {
                        LOGGER.debug("Poll job has active");
                        return false;
                    }
                }
                LOGGER.debug("Poll job in indeterminate state");
                return false;
            });
            return job;
        } catch (TimeoutException e) {
            LOGGER.info("Original Job: {}", job);
            try {
                LOGGER.info("Job: {}", indent(toYamlString(namespacedClient().extensions().jobs().withName(job.getMetadata().getName()).get())));
            } catch (Exception | AssertionError t) {
                LOGGER.info("Job not available: {}", t.getMessage());
            }
            try {
                LOGGER.info("Pod: {}", indent(TestUtils.toYamlString(namespacedClient().pods().withName(jobPodName(job)).get())));
            } catch (Exception | AssertionError t) {
                LOGGER.info("Pod not available: {}", t.getMessage());
            }
            try {
                LOGGER.info("Job timeout: Job Pod logs\n----\n{}\n----", indent(podLog(jobPodName(job))));
            } catch (Exception | AssertionError t) {
                LOGGER.info("Pod logs not available: {}", t.getMessage());
            }
            try {
                LOGGER.info("Job timeout: User Operator Pod logs\n----\n{}\n----", indent(podLog(userOperatorPodName(), "user-operator")));
            } catch (Exception | AssertionError t) {
                LOGGER.info("Pod logs not available: {}", t.getMessage());
            }
            throw e;
        }
    }

    String saslConfigs(KafkaUser kafkaUser) {
        Secret secret = namespacedClient().secrets().withName(kafkaUser.getMetadata().getName()).get();

        String password = new String(Base64.getDecoder().decode(secret.getData().get("password")));
        if (password == null) {
            LOGGER.info("Secret {}:\n{}", kafkaUser.getMetadata().getName(), TestUtils.toYamlString(secret));
            throw new RuntimeException("The Secret " + kafkaUser.getMetadata().getName() + " lacks the 'password' key");
        }
        return "sasl.mechanism=SCRAM-SHA-512\n" +
                "sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required \\\n" +
                "username=\"" + kafkaUser.getMetadata().getName() + "\" \\\n" +
                "password=\"" + password + "\";\n";
    }

    private PodSpecBuilder createPodSpecForProducer(ContainerBuilder cb, KafkaUser kafkaUser, boolean tlsListener, String bootstrapServer) {
        PodSpecBuilder podSpecBuilder = new PodSpecBuilder()
                .withRestartPolicy("OnFailure");

        String kafkaUserName = kafkaUser != null ? kafkaUser.getMetadata().getName() : null;
        boolean scramShaUser = kafkaUser != null && kafkaUser.getSpec() != null && kafkaUser.getSpec().getAuthentication() instanceof KafkaUserScramSha512ClientAuthentication;
        boolean tlsUser = kafkaUser != null && kafkaUser.getSpec() != null && kafkaUser.getSpec().getAuthentication() instanceof KafkaUserTlsClientAuthentication;

        String producerConfiguration = "acks=all\n";
        if (tlsListener) {
            if (scramShaUser) {
                producerConfiguration += "security.protocol=SASL_SSL\n";
                producerConfiguration += saslConfigs(kafkaUser);
            } else {
                producerConfiguration += "security.protocol=SSL\n";
            }
            producerConfiguration +=
                    "ssl.truststore.location=/tmp/truststore.p12\n" +
                            "ssl.truststore.type=pkcs12\n";
        } else {
            if (scramShaUser) {
                producerConfiguration += "security.protocol=SASL_PLAINTEXT\n";
                producerConfiguration += saslConfigs(kafkaUser);
            } else {
                producerConfiguration += "security.protocol=PLAINTEXT\n";
            }
        }

        if (tlsUser) {
            producerConfiguration +=
                    "ssl.keystore.location=/tmp/keystore.p12\n" +
                            "ssl.keystore.type=pkcs12\n";
            cb.addNewEnv().withName("PRODUCER_TLS").withValue("TRUE").endEnv();

            String userSecretVolumeName = "tls-cert";
            String userSecretMountPoint = "/opt/kafka/user-secret";
            cb.addNewVolumeMount()
                    .withName(userSecretVolumeName)
                    .withMountPath(userSecretMountPoint)
                    .endVolumeMount()
                    .addNewEnv().withName("USER_LOCATION").withValue(userSecretMountPoint).endEnv();
            podSpecBuilder
                    .addNewVolume()
                    .withName(userSecretVolumeName)
                    .withNewSecret()
                    .withSecretName(kafkaUserName)
                    .endSecret()
                    .endVolume();
        }

        cb.addNewEnv().withName("PRODUCER_CONFIGURATION").withValue(producerConfiguration).endEnv();

        if (kafkaUserName != null) {
            cb.addNewEnv().withName("KAFKA_USER").withValue(kafkaUserName).endEnv();
        }

        if (tlsListener) {
            String clusterCaSecretName = clusterCaCertSecretName(bootstrapServer);
            String clusterCaSecretVolumeName = "ca-cert";
            String caSecretMountPoint = "/opt/kafka/cluster-ca";
            cb.addNewVolumeMount()
                .withName(clusterCaSecretVolumeName)
                .withMountPath(caSecretMountPoint)
                .endVolumeMount()
                .addNewEnv().withName("PRODUCER_TLS").withValue("TRUE").endEnv()
                .addNewEnv().withName("CA_LOCATION").withValue(caSecretMountPoint).endEnv()
                .addNewEnv().withName("TRUSTSTORE_LOCATION").withValue("/tmp/truststore.p12").endEnv();
            if (tlsUser) {
                cb.addNewEnv().withName("KEYSTORE_LOCATION").withValue("/tmp/keystore.p12").endEnv();
            }
            podSpecBuilder
                .addNewVolume()
                    .withName(clusterCaSecretVolumeName)
                        .withNewSecret()
                            .withSecretName(clusterCaSecretName)
                        .endSecret()
                .endVolume();
        }

        return podSpecBuilder.withContainers(cb.build());
    }

    Job sendRecordsToClusterJob(String bootstrapServer, String name, String topic, int messagesCount, KafkaUser kafkaUser, boolean tlsListener) {

        String connect = tlsListener ? bootstrapServer + "-kafka-bootstrap:9093" : bootstrapServer + "-kafka-bootstrap:9092";

        ContainerBuilder cb = new ContainerBuilder()
                .withName("send-records")
                .withImage(TestUtils.changeOrgAndTag("strimzi/test-client:latest"))
                .addNewEnv().withName("PRODUCER_OPTS").withValue(
                        "--broker-list " + connect + " " +
                                "--topic " + topic + " " +
                                "--max-messages " + messagesCount).endEnv()
                .withCommand("/opt/kafka/producer.sh");

        PodSpec producerPodSpec = createPodSpecForProducer(cb, kafkaUser, tlsListener, bootstrapServer).build();

        Job job = resources().deleteLater(namespacedClient().extensions().jobs().create(new JobBuilder()
                .withNewMetadata()
                .withName(name)
                .endMetadata()
                .withNewSpec()
                .withNewTemplate()
                .withNewMetadata()
                .withName(name)
                .addToLabels("job", name)
                .endMetadata()
                .withSpec(producerPodSpec)
                .endTemplate()
                .endSpec()
                .build()));
        LOGGER.info("Created Job {}", job);
        return job;
    }

    private PodSpecBuilder createPodSpecForConsumer(ContainerBuilder cb, KafkaUser kafkaUser, boolean tlsListener, String bootstrapServer) {

        PodSpecBuilder podSpecBuilder = new PodSpecBuilder()
                .withRestartPolicy("OnFailure");

        String kafkaUserName = kafkaUser != null ? kafkaUser.getMetadata().getName() : null;
        boolean scramShaUser = kafkaUser != null && kafkaUser.getSpec() != null && kafkaUser.getSpec().getAuthentication() instanceof KafkaUserScramSha512ClientAuthentication;
        boolean tlsUser = kafkaUser != null && kafkaUser.getSpec() != null && kafkaUser.getSpec().getAuthentication() instanceof KafkaUserTlsClientAuthentication;

        String consumerConfiguration = "auto.offset.reset=earliest\n";
        if (tlsListener) {
            if (scramShaUser) {
                consumerConfiguration += "security.protocol=SASL_SSL\n";
                consumerConfiguration += saslConfigs(kafkaUser);
            } else {
                consumerConfiguration += "security.protocol=SSL\n";
            }
            consumerConfiguration += "auto.offset.reset=earliest\n" +
                    "ssl.truststore.location=/tmp/truststore.p12\n" +
                    "ssl.truststore.type=pkcs12\n";
        } else {
            if (scramShaUser) {
                consumerConfiguration += "security.protocol=SASL_PLAINTEXT\n";
                consumerConfiguration += saslConfigs(kafkaUser);
            } else {
                consumerConfiguration += "security.protocol=PLAINTEXT\n";
            }
        }

        if (tlsUser) {
            consumerConfiguration += "auto.offset.reset=earliest\n" +
                    "ssl.keystore.location=/tmp/keystore.p12\n" +
                    "ssl.keystore.type=pkcs12\n";
            cb.addNewEnv().withName("CONSUMER_TLS").withValue("TRUE").endEnv();

            String userSecretVolumeName = "tls-cert";
            String userSecretMountPoint = "/opt/kafka/user-secret";
            cb.addNewVolumeMount()
                    .withName(userSecretVolumeName)
                    .withMountPath(userSecretMountPoint)
                    .endVolumeMount()
                    .addNewEnv().withName("USER_LOCATION").withValue(userSecretMountPoint).endEnv();
            podSpecBuilder
                    .addNewVolume()
                    .withName(userSecretVolumeName)
                    .withNewSecret()
                    .withSecretName(kafkaUserName)
                    .endSecret()
                    .endVolume();
        }

        cb.addNewEnv().withName("CONSUMER_CONFIGURATION").withValue(consumerConfiguration).endEnv();

        if (kafkaUserName != null) {
            cb.addNewEnv().withName("KAFKA_USER").withValue(kafkaUserName).endEnv();
        }

        if (tlsListener) {
            String clusterCaSecretName = clusterCaCertSecretName(bootstrapServer);
            String clusterCaSecretVolumeName = "ca-cert";
            String caSecretMountPoint = "/opt/kafka/cluster-ca";
            cb.addNewVolumeMount()
                    .withName(clusterCaSecretVolumeName)
                    .withMountPath(caSecretMountPoint)
                    .endVolumeMount()
                    .addNewEnv().withName("CONSUMER_TLS").withValue("TRUE").endEnv()
                    .addNewEnv().withName("CA_LOCATION").withValue(caSecretMountPoint).endEnv()
                    .addNewEnv().withName("TRUSTSTORE_LOCATION").withValue("/tmp/truststore.p12").endEnv();
            if (tlsUser) {
                cb.addNewEnv().withName("KEYSTORE_LOCATION").withValue("/tmp/keystore.p12").endEnv();
            }
            podSpecBuilder
                    .addNewVolume()
                    .withName(clusterCaSecretVolumeName)
                    .withNewSecret()
                    .withSecretName(clusterCaSecretName)
                    .endSecret()
                    .endVolume();
        }
        return podSpecBuilder.withContainers(cb.build());
    }

    Job readMessagesFromClusterJob(String bootstrapServer, String name, String topic, int messagesCount, KafkaUser kafkaUser, boolean tlsListener) {

        String connect = tlsListener ? bootstrapServer + "-kafka-bootstrap:9093" : bootstrapServer + "-kafka-bootstrap:9092";
        ContainerBuilder cb = new ContainerBuilder()
                .withName("read-messages")
                .withImage(TestUtils.changeOrgAndTag("strimzi/test-client:latest"))
                .addNewEnv().withName("CONSUMER_OPTS").withValue(
                        "--broker-list " + connect + " " +
                                "--group-id " + name + "-" + "my-group" + " " +
                                "--verbose " +
                                "--topic " + topic + " " +
                                "--max-messages " + messagesCount).endEnv()
                .withCommand("/opt/kafka/consumer.sh");


        PodSpec consumerPodSpec = createPodSpecForConsumer(cb, kafkaUser, tlsListener, bootstrapServer).build();

        Job job = resources().deleteLater(namespacedClient().extensions().jobs().create(new JobBuilder()
            .withNewMetadata()
                .withName(name)
            .endMetadata()
            .withNewSpec()
                .withNewTemplate()
                    .withNewMetadata()
                        .withName(name)
                            .addToLabels("job", name)
                    .endMetadata()
                    .withSpec(consumerPodSpec)
                .endTemplate()
            .endSpec()
            .build()));
        LOGGER.info("Created Job {}", job);
        return job;
    }

    /**
     * Greps logs from a pod which ran kafka-verifiable-consumer.sh
     */
    void checkRecordsForConsumer(int messagesCount, Job job) {
        String podName = jobPodName(job);
        String log = podLog(podName);
        Pattern p = Pattern.compile("^\\{.*\\}$", Pattern.MULTILINE);
        Matcher m = p.matcher(log);
        boolean consumerSuccess = false;
        while (m.find()) {
            String json = m.group();
            String name = getValueFromJson(json, "$.name");
            if ("records_consumed".equals(name)) {
                assertEquals(String.valueOf(messagesCount), getValueFromJson(json, "$.count"));
                consumerSuccess = true;
            }
        }
        if (!consumerSuccess) {
            LOGGER.info("log from pod {}:\n----\n{}\n----", podName, indent(log));
        }
        assertTrue(consumerSuccess, "The consumer didn't consume any messages (no records_consumed message)");
    }

    String clusterCaCertSecretName(String cluster) {
        return cluster + "-cluster-ca-cert";
    }

    /**
     * Create a Job which which produce and then consume messages to a given topic.
     * The job will be deleted from the kubernetes cluster at the end of the test.
     * @param name The name of the {@code Job} and also the consumer group id.
     *             The Job's pod will also use this in a {@code job=<name>} selector.
     * @param topic The topic to send messages over
     * @param messagesCount The number of messages to send and receive.
     * @param kafkaUser The user to send and receive the messages as.
     * @param tlsListener true if the clients should connect over the TLS listener,
     *                    otherwise the plaintext listener will be used.
     * @param messagesCount The number of messages to produce & consume
     * @return The job
     */
    Job pingJob(String name, String topic, int messagesCount, KafkaUser kafkaUser, boolean tlsListener) {

        String connect = tlsListener ? KafkaResources.tlsBootstrapAddress(CLUSTER_NAME) : KafkaResources.plainBootstrapAddress(CLUSTER_NAME);
        ContainerBuilder cb = new ContainerBuilder()
                .withName("ping")
                .withImage(TestUtils.changeOrgAndTag(TestUtils.changeOrgAndTag("strimzi/test-client:latest-kafka-2.0.0")))
                .addNewEnv().withName("PRODUCER_OPTS").withValue(
                        "--broker-list " + connect + " " +
                                "--topic " + topic + " " +
                                "--max-messages " + messagesCount).endEnv()
                .addNewEnv().withName("CONSUMER_OPTS").withValue(
                        "--broker-list " + connect + " " +
                                "--group-id " + name + "-" + rng.nextInt(Integer.MAX_VALUE) + " " +
                                "--verbose " +
                                "--topic " + topic + " " +
                                "--max-messages " + messagesCount).endEnv()
                .withCommand("/opt/kafka/ping.sh");

        PodSpecBuilder podSpecBuilder = new PodSpecBuilder()
                .withRestartPolicy("OnFailure");

        String kafkaUserName = kafkaUser != null ? kafkaUser.getMetadata().getName() : null;
        boolean scramShaUser = kafkaUser != null && kafkaUser.getSpec() != null && kafkaUser.getSpec().getAuthentication() instanceof KafkaUserScramSha512ClientAuthentication;
        boolean tlsUser = kafkaUser != null && kafkaUser.getSpec() != null && kafkaUser.getSpec().getAuthentication() instanceof KafkaUserTlsClientAuthentication;
        String producerConfiguration = "acks=all\n";
        String consumerConfiguration = "auto.offset.reset=earliest\n";
        if (tlsListener) {
            if (scramShaUser) {
                consumerConfiguration += "security.protocol=SASL_SSL\n";
                producerConfiguration += "security.protocol=SASL_SSL\n";
                consumerConfiguration += saslConfigs(kafkaUser);
                producerConfiguration += saslConfigs(kafkaUser);
            } else {
                consumerConfiguration += "security.protocol=SSL\n";
                producerConfiguration += "security.protocol=SSL\n";
            }
            producerConfiguration +=
                    "ssl.truststore.location=/tmp/truststore.p12\n" +
                            "ssl.truststore.type=pkcs12\n";
            consumerConfiguration += "auto.offset.reset=earliest\n" +
                    "ssl.truststore.location=/tmp/truststore.p12\n" +
                    "ssl.truststore.type=pkcs12\n";
        } else {
            if (scramShaUser) {
                consumerConfiguration += "security.protocol=SASL_PLAINTEXT\n";
                producerConfiguration += "security.protocol=SASL_PLAINTEXT\n";
                consumerConfiguration += saslConfigs(kafkaUser);
                producerConfiguration += saslConfigs(kafkaUser);
            } else {
                consumerConfiguration += "security.protocol=PLAINTEXT\n";
                producerConfiguration += "security.protocol=PLAINTEXT\n";
            }
        }

        if (tlsUser) {
            producerConfiguration +=
                    "ssl.keystore.location=/tmp/keystore.p12\n" +
                            "ssl.keystore.type=pkcs12\n";
            consumerConfiguration += "auto.offset.reset=earliest\n" +
                    "ssl.keystore.location=/tmp/keystore.p12\n" +
                    "ssl.keystore.type=pkcs12\n";
            cb.addNewEnv().withName("PRODUCER_TLS").withValue("TRUE").endEnv()
                    .addNewEnv().withName("CONSUMER_TLS").withValue("TRUE").endEnv();

            String userSecretVolumeName = "tls-cert";
            String userSecretMountPoint = "/opt/kafka/user-secret";
            cb.addNewVolumeMount()
                    .withName(userSecretVolumeName)
                    .withMountPath(userSecretMountPoint)
                    .endVolumeMount()
                    .addNewEnv().withName("USER_LOCATION").withValue(userSecretMountPoint).endEnv();
            podSpecBuilder
                    .addNewVolume()
                    .withName(userSecretVolumeName)
                    .withNewSecret()
                    .withSecretName(kafkaUserName)
                    .endSecret()
                    .endVolume();
        }

        cb.addNewEnv().withName("PRODUCER_CONFIGURATION").withValue(producerConfiguration).endEnv()
                .addNewEnv().withName("CONSUMER_CONFIGURATION").withValue(consumerConfiguration).endEnv();

        if (kafkaUserName != null) {
            cb.addNewEnv().withName("KAFKA_USER").withValue(kafkaUserName).endEnv();
        }

        if (tlsListener) {
            String clusterCaSecretName = clusterCaCertSecretName(CLUSTER_NAME);
            String clusterCaSecretVolumeName = "ca-cert";
            String caSecretMountPoint = "/opt/kafka/cluster-ca";
            cb.addNewVolumeMount()
                    .withName(clusterCaSecretVolumeName)
                    .withMountPath(caSecretMountPoint)
                    .endVolumeMount()
                    .addNewEnv().withName("PRODUCER_TLS").withValue("TRUE").endEnv()
                    .addNewEnv().withName("CONSUMER_TLS").withValue("TRUE").endEnv()
                    .addNewEnv().withName("CA_LOCATION").withValue(caSecretMountPoint).endEnv()
                    .addNewEnv().withName("TRUSTSTORE_LOCATION").withValue("/tmp/truststore.p12").endEnv();
            if (tlsUser) {
                cb.addNewEnv().withName("KEYSTORE_LOCATION").withValue("/tmp/keystore.p12").endEnv();
            }
            podSpecBuilder
                    .addNewVolume()
                    .withName(clusterCaSecretVolumeName)
                    .withNewSecret()
                    .withSecretName(clusterCaSecretName)
                    .endSecret()
                    .endVolume();
        }

        Job job = resources().deleteLater(namespacedClient().extensions().jobs().create(new JobBuilder()
                .withNewMetadata()
                .withName(name)
                .endMetadata()
                .withNewSpec()
                .withNewTemplate()
                .withNewMetadata()
                .withName(name)
                .addToLabels("job", name)
                .endMetadata()
                .withSpec(podSpecBuilder.withContainers(cb.build()).build())
                .endTemplate()
                .endSpec()
                .build()));
        LOGGER.info("Created Job {}", job);
        return job;
    }
}
