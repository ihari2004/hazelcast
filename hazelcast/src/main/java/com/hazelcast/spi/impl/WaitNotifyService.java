/*
 * Copyright (c) 2008-2012, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.spi.impl;

import com.hazelcast.executor.ExecutorThreadFactory;
import com.hazelcast.instance.Node;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.partition.MigrationInfo;
import com.hazelcast.spi.*;
import com.hazelcast.spi.annotation.PrivateApi;
import com.hazelcast.spi.exception.CallTimeoutException;
import com.hazelcast.spi.exception.PartitionMigratingException;
import com.hazelcast.util.Clock;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.logging.Level;

@PrivateApi
class WaitNotifyService {
    private final ConcurrentMap<Object, Queue<WaitingOp>> mapWaitingOps = new ConcurrentHashMap<Object, Queue<WaitingOp>>(100);
    private final DelayQueue delayQueue = new DelayQueue();
    private final ExecutorService expirationExecutor;
    private final Future expirationTask;
    private final WaitingOpProcessor waitingOpProcessor;
    private final NodeEngine nodeEngine;
    private final ILogger logger;

    public WaitNotifyService(final NodeEngineImpl nodeEngine, final WaitingOpProcessor waitingOpProcessor) {
        this.nodeEngine = nodeEngine;
        this.waitingOpProcessor = waitingOpProcessor;
        final Node node = nodeEngine.getNode();
        logger = node.getLogger(WaitNotifyService.class.getName());

        expirationExecutor = Executors.newSingleThreadExecutor(
                new ExecutorThreadFactory(node.threadGroup, node.hazelcastInstance,
                        node.getThreadNamePrefix("wait-notify"), node.getConfig().getClassLoader()));

        expirationTask = expirationExecutor.submit(new Runnable() {
            public void run() {
                while (true) {
                    if (Thread.interrupted()) {
                        return;
                    }
                    try {
                        long waitTime = 1000;
                        while (waitTime > 0) {
                            long begin = System.currentTimeMillis();
                            WaitingOp waitingOp = (WaitingOp) delayQueue.poll(waitTime, TimeUnit.MILLISECONDS);
                            if (waitingOp != null) {
                                if (waitingOp.isValid()) {
                                    waitingOpProcessor.process(waitingOp);
                                }
                            }
                            long end = System.currentTimeMillis();
                            waitTime -= (end - begin);
                            if (waitTime > 1000) {
                                waitTime = 0;
                            }
                        }
                        for (Queue<WaitingOp> q : mapWaitingOps.values()) {
                            Iterator<WaitingOp> it = q.iterator();
                            while (it.hasNext()) {
                                if (Thread.interrupted()) {
                                    return;
                                }
                                WaitingOp waitingOp = it.next();
                                if (waitingOp.isValid()) {
                                    if (waitingOp.expired() || waitingOp.timedOut()) {
                                        waitingOpProcessor.process(waitingOp);
                                    }
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        logger.log(Level.WARNING, t.getMessage(), t);
                    }
                }
            }
        });
    }

    // runs after queue lock
    public void wait(WaitSupport so) {
        final Object key = so.getWaitKey();
        Queue<WaitingOp> q = mapWaitingOps.get(key);
        if (q == null) {
            q = new ConcurrentLinkedQueue<WaitingOp>();
            Queue<WaitingOp> qExisting = mapWaitingOps.putIfAbsent(key, q);
            if (qExisting != null) {
                q = qExisting;
            }
        }
        long timeout = so.getWaitTimeoutMillis();
        WaitingOp waitingOp = (so instanceof KeyBasedOperation) ? new KeyBasedWaitingOp(q, so) : new WaitingOp(q, so);
        waitingOp.setNodeEngine(nodeEngine);
        if (timeout > -1 && timeout < 1500) {
            delayQueue.offer(waitingOp);
        }
        q.offer(waitingOp);
    }

    // runs after queue lock
    public void notify(Notifier notifier) {
        Object key = notifier.getNotifiedKey();
        Queue<WaitingOp> q = mapWaitingOps.get(key);
        if (q == null) return;
        WaitingOp so = q.peek();
        while (so != null) {
            if (so.isValid()) {
                if (so.expired()) {
                    // expired
                    so.expire();
                } else {
                    if (so.shouldWait()) {
                        return;
                    }
                    waitingOpProcessor.processUnderExistingLock(so.getOperation());
                }
                so.setValid(false);
            }
            q.poll(); // consume
            so = q.peek();
        }
    }

    private Queue<WaitingOp> getScheduleQueue(Object scheduleQueueKey) {
        return mapWaitingOps.get(scheduleQueueKey);
    }

    // invalidated waiting ops will removed from queue eventually by notifiers.
    public void onMemberDisconnect(Address leftMember) {
        for (Queue<WaitingOp> q : mapWaitingOps.values()) {
            Iterator<WaitingOp> it = q.iterator();
            while (it.hasNext()) {
                if (Thread.interrupted()) {
                    return;
                }
                WaitingOp waitingOp = it.next();
                if (waitingOp.isValid()) {
                    Operation op = waitingOp.getOperation();
                    if (leftMember.equals(op.getCaller())) {
                        waitingOp.setValid(false);
                    }
                }
            }
        }
    }

    // This is executed under partition migration lock!
    public void onPartitionMigrate(Address thisAddress, MigrationInfo migrationInfo) {
        if (migrationInfo.getReplicaIndex() == 0) {
            if (thisAddress.equals(migrationInfo.getFromAddress())) {
                int partitionId = migrationInfo.getPartitionId();
                for (Queue<WaitingOp> q : mapWaitingOps.values()) {
                    Iterator<WaitingOp> it = q.iterator();
                    while (it.hasNext()) {
                        if (Thread.interrupted()) {
                            return;
                        }
                        WaitingOp waitingOp = it.next();
                        if (waitingOp.isValid()) {
                            Operation op = waitingOp.getOperation();
                            if (partitionId == op.getPartitionId()) {
                                waitingOp.setValid(false);
                                PartitionMigratingException pme = new PartitionMigratingException(thisAddress,
                                        partitionId, op.getClass().getName(), op.getServiceName());
                                op.getResponseHandler().sendResponse(pme);
                                it.remove();
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SchedulingService{");
        sb.append("delayQueue=" + delayQueue.size());
        sb.append(" \n[");
        for (Queue<WaitingOp> ScheduledOps : mapWaitingOps.values()) {
            sb.append("\t");
            sb.append(ScheduledOps.size() + ", ");
        }
        sb.append("]\n}");
        return sb.toString();
    }

    interface WaitingOpProcessor {
        void process(WaitingOp so) throws Exception;

        void processUnderExistingLock(Operation operation);
    }

    void shutdown() {
        logger.log(Level.FINEST, "Stopping tasks...");
        expirationTask.cancel(true);
        expirationExecutor.shutdown();
    }

    static class KeyBasedWaitingOp extends WaitingOp implements KeyBasedOperation {
        KeyBasedWaitingOp(Queue<WaitingOp> queue, WaitSupport so) {
            super(queue, so);
        }

        public int getKeyHash() {
            return ((KeyBasedOperation) getOperation()).getKeyHash();
        }
    }

    static class WaitingOp extends AbstractOperation implements Delayed, PartitionAwareOperation {
        final Queue<WaitingOp> queue;
        final Operation op;
        final WaitSupport waitSupport;
        final long expirationTime;
        volatile boolean valid = true;

        WaitingOp(Queue<WaitingOp> queue, WaitSupport waitSupport) {
            this.op = (Operation) waitSupport;
            this.waitSupport = waitSupport;
            this.queue = queue;
            this.expirationTime = waitSupport.getWaitTimeoutMillis() < 0 ? -1
                    : Clock.currentTimeMillis() + waitSupport.getWaitTimeoutMillis();
            this.setPartitionId(op.getPartitionId());
        }

        public Operation getOperation() {
            return op;
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }

        public boolean isValid() {
            return valid;
        }

        public boolean expired() {
            return expirationTime != -1 && Clock.currentTimeMillis() >= expirationTime;
        }

        public boolean timedOut() {
            final NodeEngineImpl nodeEngine = (NodeEngineImpl) getNodeEngine();
            return nodeEngine.operationService.isCallTimedOut(op);
        }

        public boolean shouldWait() {
            return waitSupport.shouldWait();
        }

        public long getDelay(TimeUnit unit) {
            return unit.convert(expirationTime - Clock.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        public int compareTo(Delayed other) {
            if (other == this) // compare zero ONLY if same object
                return 0;
            long d = (getDelay(TimeUnit.NANOSECONDS) -
                    other.getDelay(TimeUnit.NANOSECONDS));
            return (d == 0) ? 0 : ((d < 0) ? -1 : 1);
        }

        @Override
        public void run() throws Exception {
            if (valid) {
                if (expired()) {
                    queue.remove(this);
                    waitSupport.onWaitExpire();
                } else if (timedOut()) {
                    queue.remove(this);
                    Object response = new CallTimeoutException("Call timed out for "
                            + op.getClass().getName());
                    op.getResponseHandler().sendResponse(response);
                }
            }
        }

        @Override
        public boolean returnsResponse() {
            return false;
        }

        @Override
        public String getServiceName() {
            return op.getServiceName();
        }

        public void expire() {
            waitSupport.onWaitExpire();
        }

        public String toString() {
            return "W_" + Integer.toHexString(op.hashCode());
        }
    }
}
