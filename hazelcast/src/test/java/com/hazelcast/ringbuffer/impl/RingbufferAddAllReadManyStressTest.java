/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.ringbuffer.impl;

import com.hazelcast.config.Config;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.ringbuffer.ReadResultSet;
import com.hazelcast.ringbuffer.Ringbuffer;
import com.hazelcast.ringbuffer.StaleSequenceException;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.TestThread;
import com.hazelcast.test.annotation.NightlyTest;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hazelcast.ringbuffer.OverflowPolicy.FAIL;
import static java.lang.Math.max;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;

@RunWith(HazelcastSerialClassRunner.class)
@Category(NightlyTest.class)
public class RingbufferAddAllReadManyStressTest extends HazelcastTestSupport {

    public static final int MAX_BATCH = 100;
    private final AtomicBoolean stop = new AtomicBoolean();
    private Ringbuffer<Long> ringbuffer;

    @After
    public void tearDown() {
        if (ringbuffer != null) {
            ringbuffer.destroy();
        }
    }

    @Test
    public void whenNoTTL() throws Exception {
        RingbufferConfig ringbufferConfig = new RingbufferConfig("rb")
                .setCapacity(20 * 1000 * 1000)
                .setInMemoryFormat(InMemoryFormat.OBJECT)
                .setTimeToLiveSeconds(0);
        test(ringbufferConfig);
    }

    @Test
    public void whenTTLEnabled() throws Exception {
        RingbufferConfig ringbufferConfig = new RingbufferConfig("rb")
                .setCapacity(200 * 1000)
                .setTimeToLiveSeconds(2);
        test(ringbufferConfig);
    }

    @Test
    public void whenLongTTLAndSmallBuffer() throws Exception {
        RingbufferConfig ringbufferConfig = new RingbufferConfig("rb")
                .setCapacity(1000)
                .setTimeToLiveSeconds(30);
        test(ringbufferConfig);
    }

    @Test
    public void whenShortTTLAndBigBuffer() throws Exception {
        RingbufferConfig ringbufferConfig = new RingbufferConfig("rb")
                .setInMemoryFormat(InMemoryFormat.OBJECT)
                .setCapacity(20 * 1000 * 1000)
                .setTimeToLiveSeconds(2);
        test(ringbufferConfig);
    }

    public void test(RingbufferConfig ringbufferConfig) throws Exception {
        Config config = new Config();
        config.addRingBufferConfig(ringbufferConfig);
        HazelcastInstance[] instances = createHazelcastInstanceFactory(2).newInstances(config);

        ringbuffer = instances[0].getRingbuffer(ringbufferConfig.getName());

        ConsumeThread consumer1 = new ConsumeThread(1);
        consumer1.start();

        ConsumeThread consumer2 = new ConsumeThread(2);
        consumer2.start();

        sleepSeconds(2);

        ProduceThread producer = new ProduceThread();
        producer.start();

        sleepAndStop(stop, 60);
        System.out.println("Waiting fo completion");

        producer.assertSucceedsEventually();
        consumer1.assertSucceedsEventually();
        consumer2.assertSucceedsEventually();

        System.out.println("producer.produced:" + producer.produced);

        assertEquals(producer.produced, consumer1.seq);
        assertEquals(producer.produced, consumer2.seq);
    }

    class ProduceThread extends TestThread {
        private final ILogger logger = Logger.getLogger(ProduceThread.class);
        private volatile long produced;
        Random random = new Random();

        long lastLogMs = 0;


        public ProduceThread() {
            super("ProduceThread");
        }

        @Override
        public void onError(Throwable t) {
            stop.set(true);
        }

        @Override
        public void doRun() throws Throwable {
            while (!stop.get()) {
                LinkedList<Long> items = makeBatch();
                addAll(items);
            }

            ringbuffer.add(Long.MIN_VALUE);
        }

        private LinkedList<Long> makeBatch() {
            int count = max(1, random.nextInt(MAX_BATCH));
            LinkedList<Long> items = new LinkedList<Long>();

            for (int k = 0; k < count; k++) {
                items.add(produced);
                produced++;

                long currentTimeMs = currentTimeMillis();
                if (lastLogMs + SECONDS.toMillis(5) < currentTimeMs) {
                    lastLogMs = currentTimeMs;
                    logger.info(getName() + " at " + produced);
                }
            }
            return items;
        }

        private void addAll(LinkedList<Long> items) throws InterruptedException, ExecutionException {
            long sleepMs = 100;
            for (; ; ) {
                long result = ringbuffer.addAllAsync(items, FAIL).get();
                if (result != -1) {
                    break;
                }
                logger.info("Backoff");
                MILLISECONDS.sleep(sleepMs);
                sleepMs = sleepMs * 2;
                if (sleepMs > 1000) {
                    sleepMs = 1000;
                }
            }
        }
    }

    class ConsumeThread extends TestThread {
        private final ILogger logger = Logger.getLogger(ConsumeThread.class);
        volatile long seq;
        long lastLogMs = 0;

        public ConsumeThread(int id) {
            super("ConsumeThread-" + id);
        }

        @Override
        public void onError(Throwable t) {
            stop.set(true);
        }

        @Override
        public void doRun() throws Throwable {
            seq = ringbuffer.headSequence();

            Random random = new Random();

            for (; ; ) {
                int max = max(1, random.nextInt(MAX_BATCH));
                ReadResultSet<Long> result = null;
                while (result == null) {
                    try {
                        result = ringbuffer.readManyAsync(seq, 1, max, null).get();
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof StaleSequenceException) {
                            // this consumer is used in a stress test and can fall behind the producer if it gets delayed
                            // by any reason. This is ok, just jump to the the middle of the ringbuffer.
                            System.out.println(getName() + " has fallen behind, catching up...");
                            final long tail = ringbuffer.tailSequence();
                            final long head = ringbuffer.headSequence();
                            seq = tail >= head ? ((tail + head) / 2) : head;
                        } else {
                            throw e;
                        }
                    }
                }

                for (Long item : result) {
                    if (item.equals(Long.MIN_VALUE)) {
                        return;
                    }

                    assertEquals(new Long(seq), item);

                    long currentTimeMs = currentTimeMillis();
                    if (lastLogMs + SECONDS.toMillis(5) < currentTimeMs) {
                        lastLogMs = currentTimeMs;
                        logger.info(getName() + " at " + seq);
                    }
                    seq++;
                }
            }
        }
    }
}
