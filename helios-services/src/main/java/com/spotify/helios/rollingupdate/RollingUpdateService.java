/*
 * Copyright (c) 2014 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.helios.rollingupdate;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.AbstractIdleService;

import com.spotify.helios.common.descriptors.DeploymentGroup;
import com.spotify.helios.common.descriptors.HostSelector;
import com.spotify.helios.common.descriptors.HostStatus;
import com.spotify.helios.master.MasterModel;
import com.spotify.helios.servicescommon.Reactor;
import com.spotify.helios.servicescommon.ReactorFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.spotify.helios.servicescommon.Reactor.Callback;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Deploys and undeploys jobs to implement the desired deployment group state.
 */
public class RollingUpdateService extends AbstractIdleService {

  private static final Logger log = LoggerFactory.getLogger(RollingUpdateService.class);

  private static final long UPDATE_INTERVAL = SECONDS.toMillis(1);

  private final MasterModel masterModel;
  private final Reactor hostUpdateReactor;
  private final Reactor rollingUpdateReactor;

  /**
   * Create a new RollingUpdateService.
   *
   * @param reactorFactory    The factory to use for creating reactors.
   */
  public RollingUpdateService(final MasterModel masterModel,
                              final ReactorFactory reactorFactory) {
    this.masterModel = checkNotNull(masterModel, "masterModel");
    checkNotNull(reactorFactory, "reactorFactory");

    this.hostUpdateReactor = reactorFactory.create("hostUpdate",
                                                   new UpdateDeploymentGroupHosts(),
                                                   UPDATE_INTERVAL);
    this.rollingUpdateReactor = reactorFactory.create("rollingUpdate", new RollingUpdate(),
                                                      UPDATE_INTERVAL);
  }

  @Override
  protected void startUp() throws Exception {
    hostUpdateReactor.startAsync().awaitRunning();
    hostUpdateReactor.signal();

    rollingUpdateReactor.startAsync().awaitRunning();
    rollingUpdateReactor.signal();
  }

  @Override
  protected void shutDown() throws Exception {
    hostUpdateReactor.stopAsync().awaitTerminated();
    rollingUpdateReactor.stopAsync().awaitTerminated();
  }

  /**
   * Updates the list of hosts associated with a deployment group. Called by the hostUpdateReactor.
   */
  private class UpdateDeploymentGroupHosts implements Callback {

    @Override
    public void run(final boolean timeout) throws InterruptedException {
      final List<String> allHosts = masterModel.listHosts();
      final Map<String, Map<String, String>> hostsToLabels = Maps.newHashMap();

      // determine all hosts and their labels
      for (final String host : allHosts) {
        final HostStatus hostStatus = masterModel.getHostStatus(host);
        if (hostStatus != null) {
          hostsToLabels.put(host, hostStatus.getLabels());
        }
      }

      for (final DeploymentGroup dg : masterModel.getDeploymentGroups().values()) {
        final List<String> matchingHosts = Lists.newArrayList();

        // determine the hosts that match the current deployment group
        hostLoop:
        for (final Map.Entry<String, Map<String, String>> entry : hostsToLabels.entrySet()) {
          final String host = entry.getKey();
          final Map<String, String> hostLabels = entry.getValue();

          for (final HostSelector hostSelector : dg.getHostSelectors()) {
            final String key = hostSelector.getLabel();
            if (!hostLabels.containsKey(key)) {
              continue hostLoop;
            }

            final String hostValue = hostLabels.get(key);
            if (!hostSelector.matches(hostValue)) {
              continue hostLoop;
            }
          }

          matchingHosts.add(host);
        }

        try {
          masterModel.updateDeploymentGroupHosts(dg.getName(), matchingHosts);
        } catch (Exception e) {
          log.warn("error processing hosts update for deployment group: {} - {}",
                   dg.getName(), e);
        }
      }
    }
  }

  /**
   * Processes rolling update tasks. Called by the rollingUpdateReactor.
   */
  private class RollingUpdate implements Callback {

    @Override
    public void run(final boolean timeout) throws InterruptedException {
      for (final DeploymentGroup dg : masterModel.getDeploymentGroups().values()) {
        try {
          masterModel.rollingUpdateStep(dg, DefaultRolloutPlanner.of(dg));
        } catch (Exception e) {
          log.warn("error processing rolling update step for deployment group: {} - {}",
                   dg.getName(), e);
        }
      }
    }
  }
}
