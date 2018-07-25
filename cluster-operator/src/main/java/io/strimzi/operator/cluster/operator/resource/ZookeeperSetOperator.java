/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Specialization of {@link StatefulSetOperator} for StatefulSets of Zookeeper nodes
 */
public class ZookeeperSetOperator extends StatefulSetOperator {

    private static final Logger log = LogManager.getLogger(ZookeeperSetOperator.class);
    public final PodOperator podOperations;

    /**
     * Constructor
     *
     * @param vertx  The Vertx instance
     * @param client The Kubernetes client
     */
    public ZookeeperSetOperator(Vertx vertx, KubernetesClient client, long operationTimeoutMs, PodOperator podOperator) {
        super(vertx, client, operationTimeoutMs, podOperator);
        this.podOperations = podOperator;
    }

    @Override
    protected boolean shouldIncrementGeneration(StatefulSet current, StatefulSet desired) {
        StatefulSetDiff diff = new StatefulSetDiff(current, desired);
        if (diff.changesVolumeClaimTemplates()) {
            log.warn("Changing Zookeeper storage type or size is not possible. The changes will be ignored.");
            diff = revertStorageChanges(current, desired);
        }
        return !diff.isEmpty() && needsRollingUpdate(diff);
    }

    public static boolean needsRollingUpdate(StatefulSetDiff diff) {
        // Because for ZK the brokers know about each other via the config, and rescaling requires a rolling update
        if (diff.changesSpecReplicas()) {
            log.debug("Changed #replicas => needs rolling update");
            return true;
        }
        if (diff.changesLabels()) {
            log.debug("Changed labels => needs rolling update");
            return true;
        }
        if (diff.changesSpecTemplateSpec()) {
            log.debug("Changed template spec => needs rolling update");
            return true;
        }
        return false;
    }

    /**
     * Returns a future that completes when all the pods [0..replicas-1] in the given statefulSet are ready.
     */
    public Future<?> podReadiness(String namespace, StatefulSet desired, long pollInterval, long operationTimeoutMs, int replicas) {
        //final int replicas = desired.getSpec().getReplicas();
        List<Future> waitPodResult = new ArrayList<>(replicas);

        for (int i = 0; i < replicas; i++) {
            String podName = getPodName(desired, i);
            waitPodResult.add(podOperations.readiness(namespace, podName, pollInterval, operationTimeoutMs));
        }
        return CompositeFuture.join(waitPodResult);
    }
}
