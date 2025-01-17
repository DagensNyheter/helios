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

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.spotify.helios.client.HeliosClient;
import com.spotify.helios.common.Json;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.RolloutOptions;
import com.spotify.helios.common.descriptors.TaskStatus;
import com.spotify.helios.common.protocol.DeploymentGroupStatusResponse;
import com.spotify.helios.common.protocol.RollingUpdateResponse;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.Namespace;

import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RollingUpdateCommandTest {

  private static final String GROUP_NAME = "my_group";
  private static final JobId JOB_ID = new JobId("foo", "2", "1212121212121212121");
  private static final JobId OLD_JOB_ID = new JobId("foo", "1", "3232323232323232323");
  private static final JobId NEW_JOB_ID = new JobId("foo", "3", "4242424242424242424");
  private static final int PARALLELISM = 1;
  private static final long TIMEOUT = 300;

  private final Namespace options = mock(Namespace.class);
  private final HeliosClient client = mock(HeliosClient.class);
  private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(baos);
  private final TimeUtil timeUtil = new TimeUtil();
  private final RollingUpdateCommand command = new RollingUpdateCommand(
      ArgumentParsers.newArgumentParser("test").addSubparsers().addParser("rolling-update"),
      timeUtil, timeUtil);

  @Before
  public void before() {
    // Default CLI argument stubs
    when(options.getString("deployment-group-name")).thenReturn(GROUP_NAME);
    when(options.getInt("parallelism")).thenReturn(PARALLELISM);
    when(options.getLong("timeout")).thenReturn(TIMEOUT);
    when(options.getLong("rollout_timeout")).thenReturn(10L);
    when(options.getBoolean("async")).thenReturn(false);
    when(options.getBoolean("migrate")).thenReturn(false);
  }

  private static DeploymentGroupStatusResponse.HostStatus makeHostStatus(
      final String host, final TaskStatus.State state) {
    return makeHostStatus(host, null, state);
  }

  private static DeploymentGroupStatusResponse.HostStatus makeHostStatus(
      final String host, final JobId jobId, final TaskStatus.State state) {
    return new DeploymentGroupStatusResponse.HostStatus(host, jobId, state);
  }

  private static DeploymentGroupStatusResponse statusResponse(
      final DeploymentGroupStatusResponse.Status status, final String error,
      DeploymentGroupStatusResponse.HostStatus... args) {
    return statusResponse(status, JOB_ID, error, args);
  }

  private static DeploymentGroupStatusResponse statusResponse(
      final DeploymentGroupStatusResponse.Status status, final JobId jobId, final String error,
      DeploymentGroupStatusResponse.HostStatus... args) {
    return new DeploymentGroupStatusResponse(
        GROUP_NAME, status, jobId, error, Arrays.asList(args), null);
  }

  @Test
  public void testRollingUpdate() throws Exception {
    when(client.rollingUpdate(anyString(), any(JobId.class), any(RolloutOptions.class)))
        .thenReturn(immediateFuture(new RollingUpdateResponse(RollingUpdateResponse.Status.OK)));

    when(client.deploymentGroupStatus(GROUP_NAME)).then(new ResponseAnswer(
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, null),
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, null,
                       makeHostStatus("host1", null, null),
                       makeHostStatus("host2", OLD_JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host3", OLD_JOB_ID, TaskStatus.State.RUNNING)),
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, null,
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host2", JOB_ID, TaskStatus.State.PULLING_IMAGE),
                       makeHostStatus("host3", OLD_JOB_ID, TaskStatus.State.RUNNING)),
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, null,
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host2", JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host3", JOB_ID, TaskStatus.State.CREATING)),
        statusResponse(DeploymentGroupStatusResponse.Status.ACTIVE, null,
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host2", JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host3", JOB_ID, TaskStatus.State.RUNNING))
    ));

    final int ret = command.runWithJobId(options, client, out, false, JOB_ID, null);
    final String output = baos.toString();
    System.out.println(output);

    verify(client).rollingUpdate(
        GROUP_NAME, JOB_ID, new RolloutOptions(TIMEOUT, PARALLELISM, false));
    assertEquals(0, ret);

    final String expected = (
        "Rolling update started: my_group -> foo:2:1212121 (parallelism=1, timeout=300)\n" +
        "\n" +
        "host1 -> RUNNING (1/3)\n" +
        "host2 -> RUNNING (2/3)\n" +
        "host3 -> RUNNING (3/3)\n" +
        "\n" +
        "Done.\n" +
        "Duration: 4.00 s\n");

    assertEquals(expected, output.replaceAll("\\p{Blank}+|(?:\\p{Blank})$", " "));
  }

  @Test
  public void testRollingUpdateAsync() throws Exception {
    when(client.rollingUpdate(anyString(), any(JobId.class), any(RolloutOptions.class)))
        .thenReturn(immediateFuture(new RollingUpdateResponse(RollingUpdateResponse.Status.OK)));

    when(options.getBoolean("async")).thenReturn(true);

    final int ret = command.runWithJobId(options, client, out, false, JOB_ID, null);
    final String output = baos.toString();
    System.out.println(output);

    verify(client).rollingUpdate(
        GROUP_NAME, JOB_ID, new RolloutOptions(TIMEOUT, PARALLELISM, false));
    assertEquals(0, ret);

    final String expected =
        "Rolling update (async) started: my_group -> foo:2:1212121 (parallelism=1, timeout=300)\n";

    assertEquals(expected, output);
  }

  @Test
  public void testRollingUpdateFailsIfJobIdChangedDuringRollout() throws Exception {
    when(client.rollingUpdate(anyString(), any(JobId.class), any(RolloutOptions.class)))
        .thenReturn(immediateFuture(new RollingUpdateResponse(RollingUpdateResponse.Status.OK)));

    when(client.deploymentGroupStatus(GROUP_NAME)).then(new ResponseAnswer(
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, null,
                       makeHostStatus("host1", null, null),
                       makeHostStatus("host2", OLD_JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host3", OLD_JOB_ID, TaskStatus.State.RUNNING)),
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, null,
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host2", JOB_ID, TaskStatus.State.PULLING_IMAGE),
                       makeHostStatus("host3", OLD_JOB_ID, TaskStatus.State.RUNNING)),
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, NEW_JOB_ID, null,
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host2", JOB_ID, TaskStatus.State.STARTING),
                       makeHostStatus("host3", OLD_JOB_ID, TaskStatus.State.RUNNING))
    ));

    final int ret = command.runWithJobId(options, client, out, false, JOB_ID, null);
    final String output = baos.toString();
    System.out.println(output);

    verify(client).rollingUpdate(
        GROUP_NAME, JOB_ID, new RolloutOptions(TIMEOUT, PARALLELISM, false));
    assertEquals(1, ret);

    final String expected =
        "Rolling update started: my_group -> foo:2:1212121 (parallelism=1, timeout=300)\n" +
        "\n" +
        "host1 -> RUNNING (1/3)\n" +
        "\n" +
        "Failed: Deployment-group job id changed during rolling-update\n" +
        "Duration: 2.00 s\n";

    assertEquals(expected, output.replaceAll("\\p{Blank}+|(?:\\p{Blank})$", " "));
  }

  @Test
  public void testRollingUpdateFailsOnRolloutTimeout() throws Exception {
    when(client.rollingUpdate(anyString(), any(JobId.class), any(RolloutOptions.class)))
        .thenReturn(immediateFuture(new RollingUpdateResponse(RollingUpdateResponse.Status.OK)));

    when(client.deploymentGroupStatus(GROUP_NAME)).then(new ResponseAnswer(
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, null,
                       makeHostStatus("host1", null, null),
                       makeHostStatus("host2", null, null)),
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, null,
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.PULLING_IMAGE),
                       makeHostStatus("host2", null, null))
    ));

    final int ret = command.runWithJobId(options, client, out, false, JOB_ID, null);
    final String output = baos.toString();
    System.out.println(output);

    verify(client).rollingUpdate(
        GROUP_NAME, JOB_ID, new RolloutOptions(TIMEOUT, PARALLELISM, false));
    assertEquals(1, ret);

    final String expected =
        "Rolling update started: my_group -> foo:2:1212121 (parallelism=1, timeout=300)\n" +
        "\n" +
        "\n" +
        "Timed out! (rolling-update still in progress)\n" +
        "Duration: 601.00 s\n";

    assertEquals(expected, output.replaceAll("\\p{Blank}+|(?:\\p{Blank})$", " "));
  }

  @Test
  public void testRollingUpdateFailed() throws Exception {
    when(client.rollingUpdate(anyString(), any(JobId.class), any(RolloutOptions.class)))
        .thenReturn(immediateFuture(new RollingUpdateResponse(RollingUpdateResponse.Status.OK)));

    when(client.deploymentGroupStatus(GROUP_NAME)).then(new ResponseAnswer(
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, null,
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.PULLING_IMAGE),
                       makeHostStatus("host2", null, null)),
        statusResponse(DeploymentGroupStatusResponse.Status.FAILED, "foobar",
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host2", null, null))
    ));

    final int ret = command.runWithJobId(options, client, out, false, JOB_ID, null);
    final String output = baos.toString();
    System.out.println(output);

    verify(client).rollingUpdate(
        GROUP_NAME, JOB_ID, new RolloutOptions(TIMEOUT, PARALLELISM, false));
    assertEquals(1, ret);

    final String expected =
        "Rolling update started: my_group -> foo:2:1212121 (parallelism=1, timeout=300)\n" +
        "\n" +
        "host1 -> RUNNING (1/2)\n" +
        "\n" +
        "Failed: foobar\n" +
        "Duration: 1.00 s\n";

    assertEquals(expected, output.replaceAll("\\p{Blank}+|(?:\\p{Blank})$", " "));
  }

  // ----------------------------

  @Test
  public void testRollingUpdateJson() throws Exception {
    when(client.rollingUpdate(anyString(), any(JobId.class), any(RolloutOptions.class)))
        .thenReturn(immediateFuture(new RollingUpdateResponse(RollingUpdateResponse.Status.OK)));

    when(client.deploymentGroupStatus(GROUP_NAME)).then(new ResponseAnswer(
        statusResponse(DeploymentGroupStatusResponse.Status.ACTIVE, null,
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host2", JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host3", JOB_ID, TaskStatus.State.RUNNING))
    ));

    final int ret = command.runWithJobId(options, client, out, true, JOB_ID, null);
    final String output = baos.toString();
    System.out.println(output);

    verify(client).rollingUpdate(
        GROUP_NAME, JOB_ID, new RolloutOptions(TIMEOUT, PARALLELISM, false));
    assertEquals(0, ret);

    assertJsonOutputEquals(output, ImmutableMap.<String, Object>of(
        "status", "DONE",
        "duration", 0.00,
        "parallelism", PARALLELISM,
        "timeout", TIMEOUT));
  }

  @Test
  public void testRollingUpdateAsyncJson() throws Exception {
    when(client.rollingUpdate(anyString(), any(JobId.class), any(RolloutOptions.class)))
        .thenReturn(immediateFuture(new RollingUpdateResponse(RollingUpdateResponse.Status.OK)));

    when(options.getBoolean("async")).thenReturn(true);

    final int ret = command.runWithJobId(options, client, out, true, JOB_ID, null);
    final String output = baos.toString();
    System.out.println(output);

    verify(client).rollingUpdate(
        GROUP_NAME, JOB_ID, new RolloutOptions(TIMEOUT, PARALLELISM, false));
    assertEquals(0, ret);

    assertJsonOutputEquals(output, ImmutableMap.<String, Object>of(
        "status", "OK",
        "parallelism", PARALLELISM,
        "timeout", TIMEOUT));
  }

  @Test
  public void testRollingUpdateFailsIfJobIdChangedDuringRolloutJson() throws Exception {
    when(client.rollingUpdate(anyString(), any(JobId.class), any(RolloutOptions.class)))
        .thenReturn(immediateFuture(new RollingUpdateResponse(RollingUpdateResponse.Status.OK)));

    when(client.deploymentGroupStatus(GROUP_NAME)).then(new ResponseAnswer(
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, null,
                       makeHostStatus("host1", null, null),
                       makeHostStatus("host2", OLD_JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host3", OLD_JOB_ID, TaskStatus.State.RUNNING)),
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, NEW_JOB_ID, null,
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host2", JOB_ID, TaskStatus.State.STARTING),
                       makeHostStatus("host3", OLD_JOB_ID, TaskStatus.State.RUNNING))
    ));

    final int ret = command.runWithJobId(options, client, out, true, JOB_ID, null);
    final String output = baos.toString();
    System.out.println(output);

    verify(client).rollingUpdate(
        GROUP_NAME, JOB_ID, new RolloutOptions(TIMEOUT, PARALLELISM, false));
    assertEquals(1, ret);

    assertJsonOutputEquals(output, ImmutableMap.<String, Object>of(
        "status", "FAILED",
        "error", "Deployment-group job id changed during rolling-update",
        "duration", 1.00,
        "parallelism", PARALLELISM,
        "timeout", TIMEOUT));
  }

  @Test
  public void testRollingUpdateFailsOnRolloutTimeoutJson() throws Exception {
    when(client.rollingUpdate(anyString(), any(JobId.class), any(RolloutOptions.class)))
        .thenReturn(immediateFuture(new RollingUpdateResponse(RollingUpdateResponse.Status.OK)));

    when(client.deploymentGroupStatus(GROUP_NAME)).then(new ResponseAnswer(
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, null,
                       makeHostStatus("host1", null, null),
                       makeHostStatus("host2", null, null)),
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, null,
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.PULLING_IMAGE),
                       makeHostStatus("host2", null, null))
    ));

    final int ret = command.runWithJobId(options, client, out, true, JOB_ID, null);
    final String output = baos.toString();
    System.out.println(output);

    verify(client).rollingUpdate(
        GROUP_NAME, JOB_ID, new RolloutOptions(TIMEOUT, PARALLELISM, false));
    assertEquals(1, ret);

    assertJsonOutputEquals(output, ImmutableMap.<String, Object>of(
        "status", "TIMEOUT",
        "duration", 601.00,
        "parallelism", PARALLELISM,
        "timeout", TIMEOUT));
  }

  @Test
  public void testRollingUpdateFailedJson() throws Exception {
    when(client.rollingUpdate(anyString(), any(JobId.class), any(RolloutOptions.class)))
        .thenReturn(immediateFuture(new RollingUpdateResponse(RollingUpdateResponse.Status.OK)));

    when(client.deploymentGroupStatus(GROUP_NAME)).then(new ResponseAnswer(
        statusResponse(DeploymentGroupStatusResponse.Status.ROLLING_OUT, null,
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.PULLING_IMAGE),
                       makeHostStatus("host2", null, null)),
        statusResponse(DeploymentGroupStatusResponse.Status.FAILED, "foobar",
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.RUNNING),
                       makeHostStatus("host2", null, null))
    ));

    final int ret = command.runWithJobId(options, client, out, true, JOB_ID, null);
    final String output = baos.toString();
    System.out.println(output);

    verify(client).rollingUpdate(
        GROUP_NAME, JOB_ID, new RolloutOptions(TIMEOUT, PARALLELISM, false));
    assertEquals(1, ret);

    assertJsonOutputEquals(output, ImmutableMap.<String, Object>of(
        "status", "FAILED",
        "error", "foobar",
        "duration", 1.00,
        "parallelism", PARALLELISM,
        "timeout", TIMEOUT));
  }

  @Test
  public void testRollingUpdateMigrate() throws Exception {
    when(client.rollingUpdate(anyString(), any(JobId.class), any(RolloutOptions.class)))
        .thenReturn(immediateFuture(new RollingUpdateResponse(RollingUpdateResponse.Status.OK)));

    when(client.deploymentGroupStatus(GROUP_NAME)).then(new ResponseAnswer(
        statusResponse(DeploymentGroupStatusResponse.Status.ACTIVE, null,
                       makeHostStatus("host1", JOB_ID, TaskStatus.State.RUNNING))
    ));
    when(options.getBoolean("migrate")).thenReturn(true);

    final int ret = command.runWithJobId(options, client, out, true, JOB_ID, null);
    final String output = baos.toString();
    System.out.println(output);

    // Verify that rollingUpdate() was called with migrate=true
    verify(client).rollingUpdate(
        GROUP_NAME, JOB_ID, new RolloutOptions(TIMEOUT, PARALLELISM, true));
    assertEquals(0, ret);

    assertJsonOutputEquals(output, ImmutableMap.<String, Object>of(
        "status", "DONE",
        "duration", 0.00,
        "parallelism", PARALLELISM,
        "timeout", TIMEOUT));
  }

  private static class TimeUtil implements RollingUpdateCommand.SleepFunction, Supplier<Long> {

    private long curentTimeMillis = 0;

    @Override
    public void sleep(final long millis) throws InterruptedException {
      advanceTime(millis);
    }

    public void advanceTime(final long millis) {
      curentTimeMillis += millis;
    }

    @Override
    public Long get() {
      return curentTimeMillis;
    }
  }

  private static class ResponseAnswer implements Answer<
      ListenableFuture<DeploymentGroupStatusResponse>> {
    private final List<DeploymentGroupStatusResponse> responses;
    private int index = 0;

    public ResponseAnswer(final DeploymentGroupStatusResponse... responses) {
      this(Arrays.asList(responses));
    }

    public ResponseAnswer(final List<DeploymentGroupStatusResponse> responses) {
      this.responses = responses;
    }

    @Override
    public ListenableFuture<DeploymentGroupStatusResponse> answer(
        final InvocationOnMock ignored) {
      return immediateFuture(responses.get(index++ % responses.size()));
    }
  }

  private static void assertJsonOutputEquals(
      final String actual,
      final Map<String, Object> expected) throws IOException {
    // * Long(2) != Integer(2)
    // * Json serializing a Long and then parsing it makes it into an Integer (in some cases?)
    // * => Can't easily compare a map with a json-deserialized map
    // * => Serialize and deserialize the expected value map, and compare against that
    final TypeReference<Map<String, Object>> typeRef = new TypeReference<Map<String, Object>>() {};
    final Map<String, Object> actualMap = Json.read(actual, typeRef);
    final Map<String, Object> expectedMap = Json.read(Json.asString(expected), typeRef);
    assertEquals(expectedMap, actualMap);
  }
}
