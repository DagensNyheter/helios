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

package com.spotify.helios.common.descriptors;

import com.google.common.base.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.jetbrains.annotations.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Basically, a pair of {@link Job} and {@link Goal}.  This is different than {@link Deployment}
 * which has a {@link JobId} and not a {@link Job}
 */
public class Task extends Descriptor {
  public static final String EMPTY_DEPLOYER_USER = null;
  public static final String EMPTY_DEPLOYER_MASTER = null;
  public static final String EMPTY_DEPOYMENT_GROUP_NAME = null;

  private final Job job;
  private final Goal goal;
  private final String deployerUser;
  private final String deployerMaster;
  private final String deploymentGroupName;

  public Task(@JsonProperty("job") final Job job,
              @JsonProperty("goal") final Goal goal,
              @JsonProperty("deployerUser") @Nullable final String deployerUser,
              @JsonProperty("deployerMaster") @Nullable final String deployerMaster,
              @JsonProperty("deploymentGroupName") @Nullable final String deploymentGroupName) {
    this.job = checkNotNull(job, "job");
    this.goal = checkNotNull(goal, "goal");
    this.deployerUser = deployerUser;
    this.deployerMaster = deployerMaster;
    this.deploymentGroupName = deploymentGroupName;
  }

  public Goal getGoal() {
    return goal;
  }

  public Job getJob() {
    return job;
  }

  public String getDeployerUser() {
    return deployerUser;
  }

  public String getDeploymentGroupName() {
    return deploymentGroupName;
  }

  public String getDeployerMaster() {
    return deployerMaster;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    Task task = (Task) o;

    if (job != null ? !job.equals(task.job) : task.job != null) {
      return false;
    }
    if (goal != task.goal) {
      return false;
    }
    if (deployerUser != null ? !deployerUser.equals(task.deployerUser)
                             : task.deployerUser != null) {
      return false;
    }
    if (deploymentGroupName != null ? !deploymentGroupName.equals(task.deploymentGroupName)
                                    : task.deploymentGroupName != null) {
      return false;
    }
    return !(deployerMaster != null ? !deployerMaster
        .equals(task.deployerMaster)
                                           : task.deployerMaster != null);

  }

  @Override
  public int hashCode() {
    int result = job != null ? job.hashCode() : 0;
    result = 31 * result + (goal != null ? goal.hashCode() : 0);
    result = 31 * result + (deployerUser != null ? deployerUser.hashCode() : 0);
    result = 31 * result + (deploymentGroupName != null ? deploymentGroupName.hashCode() : 0);
    result = 31 * result + (deployerMaster != null ? deployerMaster.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
        .add("job", job)
        .add("goal", goal)
        .add("deployerUser", deployerUser)
        .add("deployerMaster", deployerMaster)
        .add("deploymentGroupName", deploymentGroupName)
        .toString();
  }
}
