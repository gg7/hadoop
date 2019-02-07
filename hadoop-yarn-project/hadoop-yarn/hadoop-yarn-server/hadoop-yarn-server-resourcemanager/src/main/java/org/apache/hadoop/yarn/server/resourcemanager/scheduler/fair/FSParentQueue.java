/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.common.collect.ImmutableList;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.QueueACL;
import org.apache.hadoop.yarn.api.records.QueueUserACLInfo;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ActiveUsersManager;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplicationAttempt;

@Private
@Unstable
public class FSParentQueue extends FSQueue {
  private static final Log LOG = LogFactory.getLog(
      FSParentQueue.class.getName());

  private final List<FSQueue> childQueues = new ArrayList<>();
  private Resource demand = Resources.createResource(0);
  private int runnableApps;

  private ReadWriteLock rwLock = new ReentrantReadWriteLock();
  private Lock readLock = rwLock.readLock();
  private Lock writeLock = rwLock.writeLock();

  public FSParentQueue(String name, FairScheduler scheduler,
      FSParentQueue parent) {
    super(name, scheduler, parent);
  }
  
  void addChildQueue(FSQueue child) {
    writeLock.lock();
    try {
      childQueues.add(child);
    } finally {
      writeLock.unlock();
    }
  }

  void removeChildQueue(FSQueue child) {
    writeLock.lock();
    try {
      childQueues.remove(child);
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  void updateInternal() {
    readLock.lock();
    try {
      policy.computeShares(childQueues, getFairShare());
      for (FSQueue childQueue : childQueues) {
        childQueue.getMetrics().setFairShare(childQueue.getFairShare());
        childQueue.updateInternal();
      }
    } finally {
      readLock.unlock();
    }
  }

  void recomputeSteadyShares() {
    readLock.lock();
    try {
      policy.computeSteadyShares(childQueues, getSteadyFairShare());
      for (FSQueue childQueue : childQueues) {
        childQueue.getMetrics()
            .setSteadyFairShare(childQueue.getSteadyFairShare());
        if (childQueue instanceof FSParentQueue) {
          ((FSParentQueue) childQueue).recomputeSteadyShares();
        }
      }
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public Resource getDemand() {
    readLock.lock();
    try {
      return Resource.newInstance(demand.getMemorySize(), demand.getVirtualCores());
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public void updateDemand() {
    // Compute demand by iterating through apps in the queue
    // Limit demand to maxResources
    writeLock.lock();
    try {
      demand = Resources.createResource(0);
      for (FSQueue childQueue : childQueues) {
        childQueue.updateDemand();
        Resource toAdd = childQueue.getDemand();
        demand = Resources.add(demand, toAdd);
        if (LOG.isDebugEnabled()) {
          LOG.debug("Counting resource from " + childQueue.getName() + " " +
              toAdd + "; Total resource demand for " + getName() +
              " now " + demand);
        }
      }
      // Cap demand to maxShare to limit allocation to maxShare
      demand = Resources.componentwiseMin(demand, getMaxShare());
    } finally {
      writeLock.unlock();
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("The updated demand for " + getName() + " is " + demand +
          "; the max is " + getMaxShare());
    }    
  }
  
  private QueueUserACLInfo getUserAclInfo(UserGroupInformation user) {
    List<QueueACL> operations = new ArrayList<>();
    for (QueueACL operation : QueueACL.values()) {
      if (hasAccess(operation, user)) {
        operations.add(operation);
      } 
    }
    return QueueUserACLInfo.newInstance(getQueueName(), operations);
  }
  
  @Override
  public List<QueueUserACLInfo> getQueueUserAclInfo(UserGroupInformation user) {
    List<QueueUserACLInfo> userAcls = new ArrayList<>();
    
    // Add queue acls
    userAcls.add(getUserAclInfo(user));
    
    // Add children queue acls
    readLock.lock();
    try {
      for (FSQueue child : childQueues) {
        userAcls.addAll(child.getQueueUserAclInfo(user));
      }
    } finally {
      readLock.unlock();
    }
 
    return userAcls;
  }

  @Override
  public Resource assignContainer(FSSchedulerNode node) {
    Resource assigned = Resources.none();

    // If this queue is over its limit, reject
    if (!assignContainerPreCheck(node)) {
      return assigned;
    }

    // Hold the write lock when sorting childQueues
    writeLock.lock();
    try {
      // Crude workaround for the following crash (when running 3.1.2):
      //
      // > 2018-11-21 15:19:59,431 INFO org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerImpl: container_1537261967824_3360_01_000001 Container Transitioned from ALLOCATED to ACQUIRED
      // > 2018-11-21 15:19:59,431 INFO org.apache.hadoop.yarn.server.resourcemanager.security.NMTokenSecretManagerInRM: Clear node set for appattempt_1537261967824_3360_000001
      // > 2018-11-21 15:19:59,431 INFO org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptImpl: Storing attempt: AppId: application_1537261967824_3360 AttemptId: appattempt_1537261967824_3360_000001 MasterContainer: Container: [ContainerId: container_1537261967824_3360_01_000001, AllocationRequestId: -1, Version: 0, NodeId: censored-worker-5:38230, NodeHttpAddress: censored-worker-5:8042, Resource: <memory:1024, vCores:1>, Priority: 0, Token: Token { kind: ContainerToken, service: 10.222.9.200:38230 }, ExecutionType: GUARANTEED, ]
      // > 2018-11-21 15:19:59,431 INFO org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptImpl: appattempt_1537261967824_3360_000001 State change from SCHEDULED to ALLOCATED_SAVING on event = CONTAINER_ALLOCATED
      // > 2018-11-21 15:19:59,432 INFO org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptImpl: appattempt_1537261967824_3360_000001 State change from ALLOCATED_SAVING to ALLOCATED on event = ATTEMPT_NEW_SAVED
      // > 2018-11-21 15:19:59,432 FATAL org.apache.hadoop.yarn.event.EventDispatcher: Error in handling event type NODE_UPDATE to the Event Dispatcher
      // > java.lang.IllegalArgumentException: Comparison method violates its general contract!
      // >         at java.util.TimSort.mergeHi(TimSort.java:899)
      // >         at java.util.TimSort.mergeAt(TimSort.java:516)
      // >         at java.util.TimSort.mergeForceCollapse(TimSort.java:457)
      // >         at java.util.TimSort.sort(TimSort.java:254)
      // >         at java.util.Arrays.sort(Arrays.java:1512)
      // >         at java.util.ArrayList.sort(ArrayList.java:1454)
      // >         at java.util.Collections.sort(Collections.java:175)
      // >         at org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FSParentQueue.assignContainer(FSParentQueue.java:191)
      // >         at org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FairScheduler.attemptScheduling(FairScheduler.java:1069)
      // >         at org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FairScheduler.nodeUpdate(FairScheduler.java:936)
      // >         at org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FairScheduler.handle(FairScheduler.java:1154)
      // >         at org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FairScheduler.handle(FairScheduler.java:129)
      // >         at org.apache.hadoop.yarn.event.EventDispatcher$EventProcessor.run(EventDispatcher.java:66)
      // >         at java.lang.Thread.run(Thread.java:745)
      // > 2018-11-21 15:19:59,432 INFO org.apache.hadoop.yarn.server.resourcemanager.amlauncher.AMLauncher: Launching masterappattempt_1537261967824_3360_000001
      // > 2018-11-21 15:19:59,467 INFO org.apache.hadoop.yarn.event.EventDispatcher: Exiting, bbye..
      //
      // Confirmed to be working:
      //
      // > ggaidarov@yarn:~$ grep -r 'Caught an exception when calling Collections.sort' /var/log/hadoop
      // > /var/log/hadoop/hadoop-svc-yarn-resourcemanager-yarn.local.log.1:2019-04-03 16:25:02,340 ERROR org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FSParentQueue: Caught an exception when calling Collections.sort(), retrying
      for (int attempt = 0; true; ++attempt) {
        try {
          Collections.sort(childQueues, policy.getComparator());
          break;
        } catch (IllegalArgumentException e) {
          if (attempt == 4) {
            throw e;
          }
          LOG.error("Caught an exception when calling Collections.sort(), retrying");
        }
      }
    } finally {
      writeLock.unlock();
    }

    /*
     * We are releasing the lock between the sort and iteration of the
     * "sorted" list. There could be changes to the list here:
     * 1. Add a child queue to the end of the list, this doesn't affect
     * container assignment.
     * 2. Remove a child queue, this is probably good to take care of so we
     * don't assign to a queue that is going to be removed shortly.
     */
    readLock.lock();
    try {
      for (FSQueue child : childQueues) {
        assigned = child.assignContainer(node);
        if (!Resources.equals(assigned, Resources.none())) {
          break;
        }
      }
    } finally {
      readLock.unlock();
    }
    return assigned;
  }

  @Override
  public List<FSQueue> getChildQueues() {
    readLock.lock();
    try {
      return ImmutableList.copyOf(childQueues);
    } finally {
      readLock.unlock();
    }
  }

  void incrementRunnableApps() {
    writeLock.lock();
    try {
      runnableApps++;
    } finally {
      writeLock.unlock();
    }
  }
  
  void decrementRunnableApps() {
    writeLock.lock();
    try {
      runnableApps--;
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public int getNumRunnableApps() {
    readLock.lock();
    try {
      return runnableApps;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public void collectSchedulerApplications(
      Collection<ApplicationAttemptId> apps) {
    readLock.lock();
    try {
      for (FSQueue childQueue : childQueues) {
        childQueue.collectSchedulerApplications(apps);
      }
    } finally {
      readLock.unlock();
    }
  }
  
  @Override
  public ActiveUsersManager getAbstractUsersManager() {
    // Should never be called since all applications are submitted to LeafQueues
    return null;
  }

  @Override
  public void recoverContainer(Resource clusterResource,
      SchedulerApplicationAttempt schedulerAttempt, RMContainer rmContainer) {
    // TODO Auto-generated method stub
    
  }

  @Override
  protected void dumpStateInternal(StringBuilder sb) {
    sb.append("{Name: " + getName() +
        ", Weight: " + weights +
        ", Policy: " + policy.getName() +
        ", FairShare: " + getFairShare() +
        ", SteadyFairShare: " + getSteadyFairShare() +
        ", MaxShare: " + getMaxShare() +
        ", MinShare: " + minShare +
        ", ResourceUsage: " + getResourceUsage() +
        ", Demand: " + getDemand() +
        ", MaxAMShare: " + maxAMShare +
        ", Runnable: " + getNumRunnableApps() +
        "}");

    for(FSQueue child : getChildQueues()) {
      sb.append(", ");
      child.dumpStateInternal(sb);
    }
  }
}
