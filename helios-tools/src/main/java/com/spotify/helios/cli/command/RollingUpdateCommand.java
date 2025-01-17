/*
 * Copyright (c) 2015 Spotify AB.
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

package com.spotify.helios.cli.command;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import com.spotify.helios.client.HeliosClient;
import com.spotify.helios.common.Json;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.RolloutOptions;
import com.spotify.helios.common.descriptors.TaskStatus;
import com.spotify.helios.common.protocol.DeploymentGroupStatusResponse;
import com.spotify.helios.common.protocol.RollingUpdateResponse;

import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static net.sourceforge.argparse4j.impl.Arguments.storeTrue;

public class RollingUpdateCommand extends WildcardJobCommand {

  private static final long POLL_INTERVAL_MILLIS = 1000;

  private final SleepFunction sleepFunction;
  private final Supplier<Long> timeSupplier;

  private final Argument nameArg;
  private final Argument timeoutArg;
  private final Argument parallelismArg;
  private final Argument asyncArg;
  private final Argument rolloutTimeoutArg;
  private final Argument migrateArg;

  public RollingUpdateCommand(final Subparser parser) {
    this(parser, new SleepFunction() {
      @Override
      public void sleep(final long millis) throws InterruptedException {
        Thread.sleep(millis);
      }
    }, new Supplier<Long>() {
      @Override
      public Long get() {
        return System.currentTimeMillis();
      }
    });
  }

  @VisibleForTesting
  RollingUpdateCommand(final Subparser parser, final SleepFunction sleepFunction,
                       final Supplier<Long> timeSupplier) {
    super(parser);

    this.sleepFunction = sleepFunction;
    this.timeSupplier = timeSupplier;

    parser.help("Initiate a rolling update");

    nameArg = parser.addArgument("deployment-group-name")
        .required(true)
        .help("Deployment group name");

    timeoutArg = parser.addArgument("-t", "--timeout")
        .setDefault(RolloutOptions.DEFAULT_TIMEOUT)
        .type(Long.class)
        .help("Fail rollout if a job takes longer than this to reach RUNNING (seconds)");

    parallelismArg = parser.addArgument("-p", "--par")
        .dest("parallelism")
        .setDefault(RolloutOptions.DEFAULT_PARALLELISM)
        .type(Integer.class)
        .help("Number of hosts to deploy to concurrently");

    asyncArg = parser.addArgument("--async")
        .action(storeTrue())
        .help("Don't block until rolling-update is complete");

    rolloutTimeoutArg = parser.addArgument("-T", "--rollout-timeout")
        .setDefault(60L)
        .type(Long.class)
        .help("Exit if rolling-update takes longer than the given value (minutes). Note that " +
              "this will NOT abort the rolling update, it will just cause this command to exit.");

    migrateArg = parser.addArgument("--migrate")
        .setDefault(false)
        .action(storeTrue())
        .help("When specified a rolling-update will undeploy not only jobs previously deployed " +
              "by the deployment-group but also jobs with the same job id. Use it ONCE when " +
              "migrating a service to using deployment-groups");
  }

  @Override
  protected int runWithJobId(final Namespace options, final HeliosClient client,
                             final PrintStream out, final boolean json, final JobId jobId,
                             final BufferedReader stdin)
      throws ExecutionException, InterruptedException, IOException {
    final String name = options.getString(nameArg.getDest());
    final long timeout = options.getLong(timeoutArg.getDest());
    final int parallelism = options.getInt(parallelismArg.getDest());
    final boolean async = options.getBoolean(asyncArg.getDest());
    final long rolloutTimeout = options.getLong(rolloutTimeoutArg.getDest());
    final boolean migrate = options.getBoolean(migrateArg.getDest());

    checkArgument(timeout > 0, "Timeout must be greater than 0");
    checkArgument(parallelism > 0, "Parallelism must be greater than 0");
    checkArgument(rolloutTimeout > 0, "Rollout timeout must be greater than 0");

    final long startTime = timeSupplier.get();

    final RolloutOptions rolloutOptions = RolloutOptions.newBuilder()
        .setTimeout(timeout)
        .setParallelism(parallelism)
        .setMigrate(migrate)
        .build();
    final RollingUpdateResponse response = client.rollingUpdate(name, jobId, rolloutOptions).get();

    if (response.getStatus() != RollingUpdateResponse.Status.OK) {
      if (!json) {
        out.println("Failed: " + response);
      } else {
        out.println(response.toJsonString());
      }
      return 1;
    }

    if (!json) {
      out.println(format("Rolling update%s started: %s -> %s (parallelism=%d, timeout=%d)%s",
                         async ? " (async)" : "",
                         name, jobId.toShortString(), parallelism, timeout,
                         async ? "" : "\n"));
    }

    final Map<String, Object> jsonOutput = Maps.newHashMap();
    jsonOutput.put("parallelism", parallelism);
    jsonOutput.put("timeout", timeout);

    if (async) {
      if (json) {
        jsonOutput.put("status", response.getStatus());
        out.println(Json.asStringUnchecked(jsonOutput));
      }
      return 0;
    }

    String error = "";
    boolean failed = false;
    boolean timedOut = false;
    final Set<String> reported = Sets.newHashSet();
    while (true) {
      final DeploymentGroupStatusResponse status = client.deploymentGroupStatus(name).get();

      if (status == null) {
        failed = true;
        error = "Failed to fetch deployment-group status";
        break;
      }

      if (!jobId.equals(status.getJobId())) {
        // Another rolling-update was started, overriding this one -- exit
        failed = true;
        error = "Deployment-group job id changed during rolling-update";
        break;
      }

      if (!json) {
        for (DeploymentGroupStatusResponse.HostStatus hostStatus : status.getHostStatuses()) {
          final JobId hostJobId = hostStatus.getJobId();
          final String host = hostStatus.getHost();
          final TaskStatus.State state = hostStatus.getState();
          final boolean done = hostJobId != null &&
                               hostJobId.equals(jobId) &&
                               state == TaskStatus.State.RUNNING;

          if (done && reported.add(host)) {
            out.println(format("%s -> %s (%d/%d)", host, state,
                               reported.size(), status.getHostStatuses().size()));
          }
        }
      }

      if (status.getStatus() != DeploymentGroupStatusResponse.Status.ROLLING_OUT) {
        if (status.getStatus() == DeploymentGroupStatusResponse.Status.FAILED) {
          failed = true;
          error = status.getError();
        }
        break;
      }

      if (timeSupplier.get() - startTime > TimeUnit.MINUTES.toMillis(rolloutTimeout)) {
        // Rollout timed out
        timedOut = true;
        break;
      }

      sleepFunction.sleep(POLL_INTERVAL_MILLIS);
    }

    final double duration = (timeSupplier.get() - startTime) / 1000.0;

    if (json) {
      if (failed) {
        jsonOutput.put("status", "FAILED");
        jsonOutput.put("error", error);
      } else if (timedOut) {
        jsonOutput.put("status", "TIMEOUT");
      } else {
        jsonOutput.put("status", "DONE");
      }
      jsonOutput.put("duration", duration);
      out.println(Json.asStringUnchecked(jsonOutput));
    } else {
      out.println();
      if (failed) {
        out.println(format("Failed: %s", error));
      } else if (timedOut) {
        out.println("Timed out! (rolling-update still in progress)");
      } else {
        out.println("Done.");
      }
      out.println(format("Duration: %.2f s", duration));
    }

    return (failed || timedOut) ? 1 : 0;
  }

  interface SleepFunction {
    void sleep(long millis) throws InterruptedException;
  }
}

