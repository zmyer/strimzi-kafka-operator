/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.api.model.extensions.StatefulSetStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Specialization of {@link StatefulSetOperator} for StatefulSets of Kafka brokers
 */
public class KafkaSetOperator extends StatefulSetOperator {

    private static final Logger log = LogManager.getLogger(KafkaSetOperator.class);

    /**
     * Constructor
     *
     * @param vertx  The Vertx instance
     * @param client The Kubernetes client
     */
    public KafkaSetOperator(Vertx vertx, KubernetesClient client, long operationTimeoutMs) {
        super(vertx, client, operationTimeoutMs);
    }

    @Override
    protected boolean shouldIncrementGeneration(StatefulSet current, StatefulSet desired) {
        StatefulSetDiff diff = new StatefulSetDiff(current, desired);
        if (diff.changesVolumeClaimTemplates()) {
            log.warn("Changing Kafka storage type or size is not possible. The changes will be ignored.");
            diff = revertStorageChanges(current, desired);
        }
        return !diff.isEmpty() && needsRollingUpdate(diff);
    }

    public static boolean needsRollingUpdate(StatefulSetDiff diff) {
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
     * If the SS exists, wait until it has finished any updates.
     * Then check that all the pods are up to date wrt the SS.
     *
     * @return A Future which completes with the current state of the SS, or with null if the SS never existed.
     */
    public Future<StatefulSet> waitForQuiescence(String namespace, String statefulSetName) {
        AtomicReference<StatefulSet> ssRef = new AtomicReference<>();
        return waitFor(namespace, statefulSetName, 1000, operationTimeoutMs, (ignore1, ignore2) -> {
            StatefulSet statefulSet = get(namespace, statefulSetName);
            ssRef.set(statefulSet);
            if (statefulSet == null) {
                return true;
            }
            // TODO this also needs to check for and complete any rolling updates
            StatefulSetStatus status = statefulSet.getStatus();
            return status != null && Objects.equals(status.getCurrentRevision(), status.getUpdateRevision());
        }).compose(ignored -> {
            StatefulSet ss = ssRef.get();
            return ss != null ? maybeRollingUpdate(ss, false) : Future.succeededFuture();
        })
                    .map(v -> ssRef.get());
    }
}
