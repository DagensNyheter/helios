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

import com.spotify.helios.client.HeliosClient;
import com.spotify.helios.common.protocol.RemoveDeploymentGroupResponse;

import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.ExecutionException;

public class DeploymentGroupRemoveCommand extends ControlCommand {

  private final Argument nameArg;

  public DeploymentGroupRemoveCommand(final Subparser parser) {
    super(parser);

    parser.help("remove a deployment group");

    nameArg = parser.addArgument("name")
        .required(true)
        .help("Deployment group name");
  }

  @Override
  int run(final Namespace options, final HeliosClient client, final PrintStream out,
          final boolean json, final BufferedReader stdin)
      throws ExecutionException, InterruptedException, IOException {

    final String name = options.getString(nameArg.getDest());

    if (name == null) {
      throw new IllegalArgumentException("Please specify a name and at least one label.");
    }

    final RemoveDeploymentGroupResponse status =
        client.removeDeploymentGroup(name).get();

    if (status == null) {
      throw new RuntimeException("The Helios master could not remove the given deployment group.");
    }

    if (status.getStatus() != RemoveDeploymentGroupResponse.Status.REMOVED) {
      out.println(status.toJsonString());
      return 0;
    } else {
      if (!json) {
        out.println("Failed: " + status);
      } else {
        out.println(status.toJsonString());
      }
      return 1;
    }
  }
}

