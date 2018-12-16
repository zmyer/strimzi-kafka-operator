/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.strimzi.operator.topic.zk.AclBuilder;
import io.strimzi.operator.topic.zk.Zk;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.I0Itec.zkclient.exception.ZkNodeExistsException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.ACL;

import java.util.List;

/**
 * Implementation of {@link TopicStore} that stores the topic state in ZooKeeper.
 */
public class ZkTopicStore implements TopicStore {

    private final static Logger LOGGER = LogManager.getLogger(ZkTopicStore.class);
    public static final String TOPICS_PATH = "/strimzi/topics";

    private final Zk zk;

    private final List<ACL> acl;

    public ZkTopicStore(Zk zk) {
        this.zk = zk;
        acl = new AclBuilder().setWorld(AclBuilder.Permission.values()).build();
        createStrimziTopicsPath();
    }

    private void createStrimziTopicsPath() {
        zk.create("/strimzi", null, acl, CreateMode.PERSISTENT, result -> {
            if (result.failed()) {
                if (!(result.cause() instanceof ZkNodeExistsException)) {
                    LOGGER.error("Error creating {}", "/strimzi", result.cause());
                    throw new RuntimeException(result.cause());
                }
            }
            zk.create(TOPICS_PATH, null, acl, CreateMode.PERSISTENT, result2 -> {
                if (result2.failed()) {
                    if (!(result2.cause() instanceof ZkNodeExistsException)) {
                        LOGGER.error("Error creating {}", TOPICS_PATH, result2.cause());
                        throw new RuntimeException(result2.cause());
                    }
                }
            });
        });
    }


    private static String getTopicPath(TopicName name) {
        return TOPICS_PATH + "/" + name;
    }

    @Override
    public void read(TopicName topicName, Handler<AsyncResult<Topic>> handler) {
        String topicPath = getTopicPath(topicName);
        zk.getData(topicPath, result -> {
            final AsyncResult<Topic> fut;
            if (result.succeeded()) {
                fut = Future.succeededFuture(TopicSerialization.fromJson(result.result()));
            } else {
                if (result.cause() instanceof ZkNoNodeException) {
                    fut = Future.succeededFuture(null);
                } else {
                    fut = result.map((Topic) null);
                }
            }
            handler.handle(fut);
        });
    }

    @Override
    public void create(Topic topic, Handler<AsyncResult<Void>> handler) {
        byte[] data = TopicSerialization.toJson(topic);
        String topicPath = getTopicPath(topic.getTopicName());
        LOGGER.debug("create znode {}", topicPath);
        zk.create(topicPath, data, acl, CreateMode.PERSISTENT, result -> {
            if (result.failed() && result.cause() instanceof ZkNodeExistsException) {
                handler.handle(Future.failedFuture(new EntityExistsException()));
            } else {
                handler.handle(result);
            }
        });
    }

    @Override
    public void update(Topic topic, Handler<AsyncResult<Void>> handler) {
        byte[] data = TopicSerialization.toJson(topic);
        // TODO pass a non-zero version
        String topicPath = getTopicPath(topic.getTopicName());
        LOGGER.debug("update znode {}", topicPath);
        zk.setData(topicPath, data, -1, handler);
    }

    @Override
    public void delete(TopicName topicName, Handler<AsyncResult<Void>> handler) {
        // TODO pass a non-zero version
        String topicPath = getTopicPath(topicName);
        LOGGER.debug("delete znode {}", topicPath);
        zk.delete(topicPath, -1, result -> {
            if (result.failed() && result.cause() instanceof ZkNoNodeException) {
                handler.handle(Future.failedFuture(new NoSuchEntityExistsException()));
            } else {
                handler.handle(result);
            }
        });
    }
}
