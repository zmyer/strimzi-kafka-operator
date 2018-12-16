/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic.zk;

import io.strimzi.test.EmbeddedZooKeeper;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.zookeeper.CreateMode;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

@RunWith(VertxUnitRunner.class)
public class ZkImplTest {

    private EmbeddedZooKeeper zkServer;

    private Vertx vertx = Vertx.vertx();
    private ZkImpl zk;

    @Before
    public void setup()
            throws IOException, InterruptedException,
            TimeoutException, ExecutionException {
        this.zkServer = new EmbeddedZooKeeper();

        zk = new ZkImpl(vertx, zkServer.getZkConnectString(), 60_000, 10_000);
    }

    @After
    public void teardown(TestContext context) {
        Async async = context.async();
        zk.disconnect(result -> async.complete());
        async.await();
        if (this.zkServer != null) {
            this.zkServer.close();
        }
        vertx.close();
    }

    @Ignore
    @Test
    public void testReconnectOnBounce(TestContext context) throws IOException, InterruptedException {
        ZkImpl zkImpl = new ZkImpl(vertx, zkServer.getZkConnectString(), 60_000, 10_000);
        zkServer.restart();
        Async async = context.async();
        zkImpl.create("/foo", null, AclBuilder.PUBLIC, CreateMode.PERSISTENT, ar -> {
            context.assertTrue(ar.succeeded());
            async.complete();
        });
        async.await();
        zkServer.restart();
        // TODO Without the sleep this test fails, because there's a race between the creation of /bar
        // and the reconnection within ZkImpl. We probably need to fix ZkImpl to retry if things fail due to
        // connection loss, possibly with some limit on the number of retries.
        // TODO We also need to reset the watches on reconnection.
        Thread.sleep(2000);
        Async async2 = context.async();
        zkImpl.create("/bar", null, AclBuilder.PUBLIC, CreateMode.PERSISTENT, ar -> {
            //ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded(), ar.toString());
            async2.complete();
        });
    }

    @Test
    public void testWatchUnwatchChildren(TestContext context) {
        // Create a node
        Async fooFuture = context.async();
        zk.create("/foo", null, AclBuilder.PUBLIC, CreateMode.PERSISTENT, ar -> {
            fooFuture.complete();
        });
        fooFuture.await();

        // Now watch its children
        Async barFuture = context.async();
        zk.watchChildren("/foo", watchResult -> {
            context.assertEquals(singletonList("bar"), watchResult.result());
            zk.unwatchChildren("/foo");
            zk.delete("/foo/bar", -1, deleteResult -> {
                barFuture.countDown();
            });

        });
        zk.children("/foo", lsResult -> {
            context.assertEquals(emptyList(), lsResult.result());
            zk.create("/foo/bar", null, AclBuilder.PUBLIC, CreateMode.PERSISTENT, ig -> { });
        });
        barFuture.await();
    }

    @Test
    public void testWatchUnwatchData(TestContext context) {
        // Create a node
        Async fooFuture = context.async();
        byte[] data1 = new byte[]{1};
        zk.create("/foo", data1, AclBuilder.PUBLIC, CreateMode.PERSISTENT, ar -> {
            fooFuture.complete();
        });
        fooFuture.await();

        Async done = context.async();
        byte[] data2 = {2};
        zk.watchData("/foo", dataWatch -> {
            context.assertTrue(Arrays.equals(data2, dataWatch.result()));
        }).getData("/foo", dataResult -> {
            context.assertTrue(Arrays.equals(data1, dataResult.result()));

            zk.setData("/foo", data2, -1, setResult -> {
                done.complete();
            });
        });
    }

}
