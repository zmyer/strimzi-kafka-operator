/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.controller.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.DoneableEvent;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.vertx.core.Vertx;

/**
 * Operations for {@code ConfigMap}s.
 */
public class EventOperator extends AbstractResourceOperator<KubernetesClient, Event, EventList, DoneableEvent, Resource<Event, DoneableEvent>, Void> {
    /**
     * Constructor
     * @param vertx The Vertx instance
     * @param client The Kubernetes client
     */
    public EventOperator(Vertx vertx, KubernetesClient client) {
        super(vertx, client, "Event");
    }

    @Override
    protected MixedOperation<Event, EventList, DoneableEvent, Resource<Event, DoneableEvent>> operation() {
        return client.events();
    }
}
