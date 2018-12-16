/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaTopicList;
import io.strimzi.api.kafka.model.DoneableKafkaTopic;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.operator.topic.zk.Zk;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class Session extends AbstractVerticle {

    private final static Logger LOGGER = LogManager.getLogger(Session.class);

    private static final int HEALTH_SERVER_PORT = 8080;

    private final Config config;
    private final KubernetesClient kubeClient;

    OperatorAssignedKafkaImpl kafka;
    AdminClient adminClient;
    K8sImpl k8s;
    TopicOperator topicOperator;
    Watch topicWatch;
    ZkTopicsWatcher topicsWatcher;
    TopicConfigsWatcher topicConfigsWatcher;
    ZkTopicWatcher topicWatcher;
    /** The id of the periodic reconciliation timer. This is null during a periodic reconciliation. */
    private volatile Long timerId;
    private volatile boolean stopped = false;
    private Zk zk;
    private volatile HttpServer healthServer;

    public Session(KubernetesClient kubeClient, Config config) {
        this.kubeClient = kubeClient;
        this.config = config;
        StringBuilder sb = new StringBuilder(System.lineSeparator());
        for (Config.Value<?> v: Config.keys()) {
            sb.append("\t").append(v.key).append(": ").append(config.get(v)).append(System.lineSeparator());
        }
        LOGGER.info("Using config:{}", sb.toString());
    }

    /**
     * Stop the operator.
     */
    @Override
    public void stop(Future<Void> stopFuture) throws Exception {
        this.stopped = true;
        Long timerId = this.timerId;
        if (timerId != null) {
            vertx.cancelTimer(timerId);
        }
        vertx.executeBlocking(blockingResult -> {
            long t0 = System.currentTimeMillis();
            long timeout = 120_000L;
            LOGGER.info("Stopping");
            LOGGER.debug("Stopping kube watch");
            topicWatch.close();
            LOGGER.debug("Stopping zk watches");
            topicsWatcher.stop();

            while (topicOperator.isWorkInflight()) {
                if (System.currentTimeMillis() - t0 > timeout) {
                    LOGGER.error("Timeout waiting for inflight work to finish");
                    break;
                }
                LOGGER.debug("Waiting for inflight work to finish");
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            LOGGER.debug("Stopping kafka {}", kafka);
            kafka.stop();

            LOGGER.debug("Disconnecting from zookeeper {}", zk);
            zk.disconnect(zkResult -> {
                if (zkResult.failed()) {
                    LOGGER.warn("Error disconnecting from zookeeper: {}", String.valueOf(zkResult.cause()));
                }
                LOGGER.debug("Closing AdminClient {}", adminClient);
                adminClient.close(timeout - (System.currentTimeMillis() - t0), TimeUnit.MILLISECONDS);

                HttpServer healthServer = this.healthServer;
                if (healthServer != null) {
                    healthServer.close();
                }

                LOGGER.info("Stopped");
                blockingResult.complete();
            });
        }, stopFuture);
    }

    @Override
    public void start() {
        LOGGER.info("Starting");
        Properties adminClientProps = new Properties();
        adminClientProps.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.get(Config.KAFKA_BOOTSTRAP_SERVERS));

        if (Boolean.valueOf(config.get(Config.TLS_ENABLED))) {
            adminClientProps.setProperty(AdminClientConfig.SECURITY_PROTOCOL_CONFIG, "SSL");
            adminClientProps.setProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, config.get(Config.TLS_TRUSTSTORE_LOCATION));
            adminClientProps.setProperty(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, config.get(Config.TLS_TRUSTSTORE_PASSWORD));
            adminClientProps.setProperty(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, config.get(Config.TLS_KEYSTORE_LOCATION));
            adminClientProps.setProperty(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, config.get(Config.TLS_KEYSTORE_PASSWORD));
            adminClientProps.setProperty(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "HTTPS");
        }

        this.adminClient = AdminClient.create(adminClientProps);
        LOGGER.debug("Using AdminClient {}", adminClient);
        this.kafka = new OperatorAssignedKafkaImpl(adminClient, vertx, config);
        LOGGER.debug("Using Kafka {}", kafka);
        LabelPredicate resourcePredicate = config.get(Config.LABELS);

        String namespace = config.get(Config.NAMESPACE);
        LOGGER.debug("Using namespace {}", namespace);
        this.k8s = new K8sImpl(vertx, kubeClient, resourcePredicate, namespace);
        LOGGER.debug("Using k8s {}", k8s);

        this.zk = Zk.create(vertx, config.get(Config.ZOOKEEPER_CONNECT),
                this.config.get(Config.ZOOKEEPER_SESSION_TIMEOUT_MS).intValue(),
                this.config.get(Config.ZOOKEEPER_CONNECTION_TIMEOUT_MS).intValue());
        LOGGER.debug("Using ZooKeeper {}", zk);

        ZkTopicStore topicStore = new ZkTopicStore(zk);
        LOGGER.debug("Using TopicStore {}", topicStore);

        this.topicOperator = new TopicOperator(vertx, kafka, k8s, topicStore, resourcePredicate, namespace, config);
        LOGGER.debug("Using Operator {}", topicOperator);

        this.topicConfigsWatcher = new TopicConfigsWatcher(topicOperator);
        LOGGER.debug("Using TopicConfigsWatcher {}", topicConfigsWatcher);
        this.topicWatcher = new ZkTopicWatcher(topicOperator);
        LOGGER.debug("Using TopicWatcher {}", topicWatcher);
        this.topicsWatcher = new ZkTopicsWatcher(topicOperator, topicConfigsWatcher, topicWatcher);
        LOGGER.debug("Using TopicsWatcher {}", topicsWatcher);
        topicsWatcher.start(zk);

        Thread resourceThread = new Thread(() -> {
            LOGGER.debug("Watching KafkaTopics matching {}", resourcePredicate);
            Session.this.topicWatch = kubeClient.customResources(Crds.topic(), KafkaTopic.class, KafkaTopicList.class, DoneableKafkaTopic.class).inNamespace(namespace).watch(new K8sTopicWatcher(topicOperator, resourcePredicate));
            LOGGER.debug("Watching setup");

            // start the HTTP server for healthchecks
            healthServer = this.startHealthServer();

        }, "resource-watcher");
        LOGGER.debug("Starting {}", resourceThread);
        resourceThread.start();

        final Long interval = config.get(Config.FULL_RECONCILIATION_INTERVAL_MS);
        Handler<Long> periodic = new Handler<Long>() {
            @Override
            public void handle(Long oldTimerId) {
                if (!stopped) {
                    timerId = null;
                    topicOperator.reconcileAllTopics("periodic").setHandler(result -> {
                        if (!stopped) {
                            timerId = vertx.setTimer(interval, this);
                        }
                    });
                }
            }
        };
        periodic.handle(null);
        LOGGER.info("Started");
    }

    /**
     * Start an HTTP health server
     */
    private HttpServer startHealthServer() {

        return this.vertx.createHttpServer()
                .requestHandler(request -> {

                    if (request.path().equals("/healthy")) {
                        request.response().setStatusCode(200).end();
                    } else if (request.path().equals("/ready")) {
                        request.response().setStatusCode(200).end();
                    }
                })
                .listen(HEALTH_SERVER_PORT);
    }
}
