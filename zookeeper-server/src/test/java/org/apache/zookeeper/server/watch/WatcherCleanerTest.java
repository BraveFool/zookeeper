/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.server.watch;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.metrics.impl.DefaultMetricsProvider;
import org.apache.zookeeper.server.ServerMetrics;
import org.junit.Test;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.number.OrderingComparison.greaterThan;

public class WatcherCleanerTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(WatcherCleanerTest.class);

    public static class MyDeadWatcherListener implements IDeadWatcherListener {

        private CountDownLatch latch;
        private int delayMs;
        private Set<Integer> deadWatchers = new HashSet<Integer>();

        public void setCountDownLatch(CountDownLatch latch) {
            this.latch = latch;
        }

        public void setDelayMs(int delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public void processDeadWatchers(Set<Integer> deadWatchers) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {}
            }
            this.deadWatchers.clear();
            this.deadWatchers.addAll(deadWatchers);
            latch.countDown();
        }

        public Set<Integer> getDeadWatchers() {
            return deadWatchers;
        }

        public boolean wait(int maxWaitMs) {
            try {
                return latch.await(maxWaitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {}
            return false;
        }
    }

    @Test
    public void testProcessDeadWatchersBasedOnThreshold() {
        MyDeadWatcherListener listener = new MyDeadWatcherListener();
        int threshold = 3;
        WatcherCleaner cleaner = new WatcherCleaner(listener, threshold, 60, 1, 10);
        cleaner.start();

        int i = 0;
        while (i++ < threshold - 1) {
            cleaner.addDeadWatcher(i);
        }
        // not trigger processDeadWatchers yet
        Assert.assertEquals(0, listener.getDeadWatchers().size());

        listener.setCountDownLatch(new CountDownLatch(1));
        // add another dead watcher to trigger the process
        cleaner.addDeadWatcher(i);
        Assert.assertTrue(listener.wait(1000));
        Assert.assertEquals(threshold, listener.getDeadWatchers().size());
    }

    @Test
    public void testProcessDeadWatchersBasedOnTime() {
        MyDeadWatcherListener listener = new MyDeadWatcherListener();
        WatcherCleaner cleaner = new WatcherCleaner(listener, 10, 1, 1, 10);
        cleaner.start();

        cleaner.addDeadWatcher(1);
        // not trigger processDeadWatchers yet
        Assert.assertEquals(0, listener.getDeadWatchers().size());

        listener.setCountDownLatch(new CountDownLatch(1));
        Assert.assertTrue(listener.wait(2000));
        Assert.assertEquals(1, listener.getDeadWatchers().size());

        // won't trigger event if there is no dead watchers
        listener.setCountDownLatch(new CountDownLatch(1));
        Assert.assertFalse(listener.wait(2000));
    }

    @Test
    public void testMaxInProcessingDeadWatchers() {
        MyDeadWatcherListener listener = new MyDeadWatcherListener();
        int delayMs = 1000;
        listener.setDelayMs(delayMs);
        WatcherCleaner cleaner = new WatcherCleaner(listener, 1, 60, 1, 1);
        cleaner.start();

        listener.setCountDownLatch(new CountDownLatch(2));

        long startTime = Time.currentElapsedTime();
        cleaner.addDeadWatcher(1);
        cleaner.addDeadWatcher(2);
        long time = Time.currentElapsedTime() - startTime;
        System.out.println("time used " + time);
        Assert.assertTrue(Time.currentElapsedTime() - startTime >= delayMs);
        Assert.assertTrue(listener.wait(5000));
    }

    @Test
    public void testDeadWatcherMetrics() {
        ServerMetrics.getMetrics().resetAll();
        MyDeadWatcherListener listener = new MyDeadWatcherListener();
        WatcherCleaner cleaner = new WatcherCleaner(listener, 1, 1, 1, 1);
        listener.setDelayMs(20);
        cleaner.start();
        listener.setCountDownLatch(new CountDownLatch(3));
        //the dead watchers will be added one by one and cleared one by one because we set both watchCleanThreshold and
        //maxInProcessingDeadWatchers to 1
        cleaner.addDeadWatcher(1);
        cleaner.addDeadWatcher(2);
        cleaner.addDeadWatcher(3);

        Assert.assertTrue(listener.wait(5000));
        
        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        Assert.assertThat("Adding dead watcher should be stalled twice",
                          (Long)values.get("add_dead_watcher_stall_time"),
                           greaterThan(0L));
        Assert.assertEquals("Total dead watchers added to the queue should be 3", 3L, values.get("dead_watchers_queued"));
        Assert.assertEquals("Total dead watchers cleared should be 3", 3L, values.get("dead_watchers_cleared"));

        Assert.assertEquals(3L, values.get("cnt_dead_watchers_cleaner_latency"));

        //Each latency should be a little over 20 ms, allow 5 ms deviation
        Assert.assertEquals(20D, (Double)values.get("avg_dead_watchers_cleaner_latency"), 5);
        Assert.assertEquals(20D, ((Long)values.get("min_dead_watchers_cleaner_latency")).doubleValue(), 5);
        Assert.assertEquals(20D, ((Long)values.get("max_dead_watchers_cleaner_latency")).doubleValue(), 5);
        Assert.assertEquals(20D, ((Long)values.get("p50_dead_watchers_cleaner_latency")).doubleValue(), 5);
        Assert.assertEquals(20D, ((Long)values.get("p95_dead_watchers_cleaner_latency")).doubleValue(), 5);
        Assert.assertEquals(20D, ((Long)values.get("p99_dead_watchers_cleaner_latency")).doubleValue(), 5);
    }
}
