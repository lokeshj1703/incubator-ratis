/*
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
package org.apache.ratis.grpc;

import static org.apache.ratis.server.impl.RaftServerMetrics.RAFT_CLIENT_READ_REQUEST;
import static org.apache.ratis.server.impl.RaftServerMetrics.RAFT_CLIENT_STALE_READ_REQUEST;
import static org.apache.ratis.server.impl.RaftServerMetrics.RAFT_CLIENT_WATCH_REQUEST;
import static org.apache.ratis.server.impl.RaftServerMetrics.RAFT_CLIENT_WRITE_REQUEST;
import static org.apache.ratis.server.impl.RaftServerMetrics.REQUEST_QUEUE_LIMIT_HIT_COUNTER;
import static org.apache.ratis.server.impl.RaftServerMetrics.REQUEST_BYTE_SIZE;
import static org.apache.ratis.server.impl.RaftServerMetrics.REQUEST_BYTE_SIZE_LIMIT_HIT_COUNTER;
import static org.apache.ratis.server.impl.RaftServerMetrics.RESOURCE_LIMIT_HIT_COUNTER;

import com.codahale.metrics.Gauge;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.log4j.Level;
import org.apache.ratis.BaseTest;
import org.apache.ratis.MiniRaftCluster;
import org.apache.ratis.RaftTestUtil;
import org.apache.ratis.RaftTestUtil.SimpleMessage;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientConfigKeys;
import org.apache.ratis.client.RaftClientRpc;
import org.apache.ratis.client.impl.RaftClientTestUtil;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.client.GrpcClientProtocolClient;
import org.apache.ratis.grpc.client.GrpcClientProtocolService;
import org.apache.ratis.proto.RaftProtos;
import org.apache.ratis.proto.RaftProtos.RaftPeerRole;
import org.apache.ratis.protocol.AlreadyClosedException;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.TimeoutIOException;
import org.apache.ratis.retry.RetryPolicies;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.RaftServerRpc;
import org.apache.ratis.server.impl.RaftServerImpl;
import org.apache.ratis.server.impl.RaftServerMetrics;
import org.apache.ratis.server.impl.RaftServerTestUtil;
import org.apache.ratis.server.impl.ServerImplUtils;
import org.apache.ratis.statemachine.SimpleStateMachine4Testing;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.util.Log4jUtils;
import org.apache.ratis.util.ProtoUtils;
import org.apache.ratis.util.SizeInBytes;
import org.apache.ratis.util.TimeDuration;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.util.SortedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TestRaftServerWithGrpc extends BaseTest implements MiniRaftClusterWithGrpc.FactoryGet {
  {
    Log4jUtils.setLogLevel(GrpcClientProtocolService.LOG, Level.ALL);
    Log4jUtils.setLogLevel(GrpcClientProtocolClient.LOG, Level.ALL);
  }

  @Before
  public void setup() {
    final RaftProperties p = getProperties();
    p.setClass(MiniRaftCluster.STATEMACHINE_CLASS_KEY, SimpleStateMachine4Testing.class, StateMachine.class);
    RaftClientConfigKeys.Rpc.setRequestTimeout(p, TimeDuration.valueOf(1, TimeUnit.SECONDS));
  }

  @Test
  public void testServerRestartOnException() throws Exception {
    runWithNewCluster(1, this::runTestServerRestartOnException);
  }

  void runTestServerRestartOnException(MiniRaftClusterWithGrpc cluster) throws Exception {
    final RaftServerImpl leader = RaftTestUtil.waitForLeader(cluster);
    final RaftPeerId leaderId = leader.getId();

    final RaftProperties p = getProperties();
    GrpcConfigKeys.Server.setPort(p, leader.getServerRpc().getInetSocketAddress().getPort());

    // Create a raft server proxy with server rpc bound to a different address
    // compared to leader. This helps in locking the raft storage directory to
    // be used by next raft server proxy instance.
    final StateMachine stateMachine = cluster.getLeader().getStateMachine();
    ServerImplUtils.newRaftServer(leaderId, cluster.getGroup(), gid -> stateMachine, p, null);
    // Close the server rpc for leader so that new raft server can be bound to it.
    cluster.getLeader().getServerRpc().close();

    // Create a raft server proxy with server rpc bound to same address as
    // the leader. This step would fail as the raft storage has been locked by
    // the raft server proxy created earlier. Raft server proxy should close
    // the rpc server on failure.
    testFailureCase("start a new server with the same address",
        () -> ServerImplUtils.newRaftServer(leaderId, cluster.getGroup(), gid -> stateMachine, p, null).start(),
        IOException.class, OverlappingFileLockException.class);
    // Try to start a raft server rpc at the leader address.
    cluster.getServer(leaderId).getFactory().newRaftServerRpc(cluster.getServer(leaderId));
  }

  @Test
  public void testUnsupportedMethods() throws Exception {
    runWithNewCluster(1, this::runTestUnsupportedMethods);
  }

  void runTestUnsupportedMethods(MiniRaftClusterWithGrpc cluster) throws Exception {
    final RaftPeerId leaderId = RaftTestUtil.waitForLeader(cluster).getId();
    final RaftServerRpc rpc = cluster.getServer(leaderId).getFactory().newRaftServerRpc(cluster.getServer(leaderId));

    testFailureCase("appendEntries",
        () -> rpc.appendEntries(null),
        UnsupportedOperationException.class);

    testFailureCase("installSnapshot",
        () -> rpc.installSnapshot(null),
        UnsupportedOperationException.class);
  }

  @Test
  public void testLeaderRestart() throws Exception {
    runWithNewCluster(1, this::runTestLeaderRestart);
  }

  void runTestLeaderRestart(MiniRaftClusterWithGrpc cluster) throws Exception {
    final RaftServerImpl leader = RaftTestUtil.waitForLeader(cluster);

    try (final RaftClient client = cluster.createClient()) {
      // send a request to make sure leader is ready
      final CompletableFuture<RaftClientReply> f = client.sendAsync(new SimpleMessage("testing"));
      Assert.assertTrue(f.get().isSuccess());
    }

    try (final RaftClient client = cluster.createClient()) {
      final RaftClientRpc rpc = client.getClientRpc();

      final AtomicLong seqNum = new AtomicLong();
      {
        // send a request using rpc directly
        final RaftClientRequest request = newRaftClientRequest(client, leader.getId(), seqNum.incrementAndGet());
        final CompletableFuture<RaftClientReply> f = rpc.sendRequestAsync(request);
        Assert.assertTrue(f.get().isSuccess());
      }

      // send another request which will be blocked
      final SimpleStateMachine4Testing stateMachine = SimpleStateMachine4Testing.get(leader);
      stateMachine.blockStartTransaction();
      final RaftClientRequest requestBlocked = newRaftClientRequest(client, leader.getId(), seqNum.incrementAndGet());
      final CompletableFuture<RaftClientReply> futureBlocked = rpc.sendRequestAsync(requestBlocked);

      // change leader
      RaftTestUtil.changeLeader(cluster, leader.getId());
      Assert.assertNotEquals(RaftPeerRole.LEADER, RaftServerTestUtil.getRole(leader));

      // the blocked request should fail
      testFailureCase("request should fail", futureBlocked::get,
          ExecutionException.class, AlreadyClosedException.class);
      stateMachine.unblockStartTransaction();

      // send one more request which should timeout.
      final RaftClientRequest requestTimeout = newRaftClientRequest(client, leader.getId(), seqNum.incrementAndGet());
      rpc.handleException(leader.getId(), new Exception(), true);
      final CompletableFuture<RaftClientReply> f = rpc.sendRequestAsync(requestTimeout);
      testFailureCase("request should timeout", f::get,
          ExecutionException.class, TimeoutIOException.class);
    }

  }

  @Test
  public void testRaftClientMetrics() throws Exception {
    runWithNewCluster(3, this::testRaftClientRequestMetrics);
  }

  @Test
  public void testRaftServerMetrics() throws Exception {
    final RaftProperties p = getProperties();
    RaftServerConfigKeys.Write.setElementLimit(p, 10);
    RaftServerConfigKeys.Write.setByteLimit(p, SizeInBytes.valueOf(110));
    try {
      runWithNewCluster(3, this::testRequestMetrics);
    } finally {
      RaftServerConfigKeys.Write.setElementLimit(p, RaftServerConfigKeys.Write.ELEMENT_LIMIT_DEFAULT);
      RaftServerConfigKeys.Write.setByteLimit(p, RaftServerConfigKeys.Write.BYTE_LIMIT_DEFAULT);
    }
  }

  void testRequestMetrics(MiniRaftClusterWithGrpc cluster) throws Exception {

    try (RaftClient client = cluster.createClient()) {
      // send a request to make sure leader is ready
      final CompletableFuture< RaftClientReply > f = client.sendAsync(new SimpleMessage("testing"));
      Assert.assertTrue(f.get().isSuccess());
    }

    SimpleStateMachine4Testing stateMachine = SimpleStateMachine4Testing.get(cluster.getLeader());
    stateMachine.blockFlushStateMachineData();

    String message = "2nd Message";
    // Block stateMachine flush data, so that 2nd request will not be
    // completed, and so it will not be removed from pending request map.
    cluster.createClient(cluster.getLeader().getId(), RetryPolicies.noRetry()).sendAsync(new SimpleMessage(message));

   final SortedMap< String, Gauge > gaugeMap =
        cluster.getLeader().getRaftServerMetrics().getRegistry()
            .getGauges((s, metric) -> s.contains(REQUEST_BYTE_SIZE));

    RaftTestUtil.waitFor(() -> (int) gaugeMap.get(gaugeMap.firstKey()).getValue() == message.length(),
        300, 5000);


    for (int i = 0; i < 10; i++) {
      cluster.createClient(cluster.getLeader().getId(), RetryPolicies.noRetry()).sendAsync(new SimpleMessage(message));
    }

    // Because we have passed 11 requests, and the element queue size is 10.
    RaftTestUtil.waitFor(() -> cluster.getLeader().getRaftServerMetrics().getCounter(REQUEST_QUEUE_LIMIT_HIT_COUNTER)
        .getCount() == 1, 300, 5000);

    stateMachine.unblockFlushStateMachineData();

    // Send a message with 120, our byte size limit is 110, so it should fail
    // and byte size counter limit will be hit.

    cluster.createClient(cluster.getLeader().getId(), RetryPolicies.noRetry())
        .sendAsync(new SimpleMessage(RandomStringUtils.random(120, true, false)));

    RaftTestUtil.waitFor(() -> cluster.getLeader().getRaftServerMetrics()
        .getCounter(REQUEST_BYTE_SIZE_LIMIT_HIT_COUNTER).getCount() == 1, 300, 5000);

    Assert.assertEquals(2, cluster.getLeader().getRaftServerMetrics()
        .getCounter(RESOURCE_LIMIT_HIT_COUNTER).getCount());
  }


  void testRaftClientRequestMetrics(MiniRaftClusterWithGrpc cluster) throws IOException,
      ExecutionException, InterruptedException {
    final RaftServerImpl leader = RaftTestUtil.waitForLeader(cluster);
    RaftServerMetrics raftServerMetrics = leader.getRaftServerMetrics();

    try (final RaftClient client = cluster.createClient()) {
      final CompletableFuture<RaftClientReply> f1 = client.sendAsync(new SimpleMessage("testing"));
      Assert.assertTrue(f1.get().isSuccess());
      Assert.assertTrue(raftServerMetrics.getTimer(RAFT_CLIENT_WRITE_REQUEST).getCount() > 0);

      final CompletableFuture<RaftClientReply> f2 = client.sendReadOnlyAsync(new SimpleMessage("testing"));
      Assert.assertTrue(f2.get().isSuccess());
      Assert.assertTrue(raftServerMetrics.getTimer(RAFT_CLIENT_READ_REQUEST).getCount() > 0);

      final CompletableFuture<RaftClientReply> f3 = client.sendStaleReadAsync(new SimpleMessage("testing"),
          0, leader.getId());
      Assert.assertTrue(f3.get().isSuccess());
      Assert.assertTrue(raftServerMetrics.getTimer(RAFT_CLIENT_STALE_READ_REQUEST).getCount() > 0);

      final CompletableFuture<RaftClientReply> f4 = client.sendWatchAsync(0, RaftProtos.ReplicationLevel.ALL);
      Assert.assertTrue(f4.get().isSuccess());
      Assert.assertTrue(raftServerMetrics.getTimer(String.format(RAFT_CLIENT_WATCH_REQUEST, "-ALL")).getCount() > 0);

      final CompletableFuture<RaftClientReply> f5 = client.sendWatchAsync(0, RaftProtos.ReplicationLevel.MAJORITY);
      Assert.assertTrue(f5.get().isSuccess());
      Assert.assertTrue(raftServerMetrics.getTimer(String.format(RAFT_CLIENT_WATCH_REQUEST, "")).getCount() > 0);
    }
  }

  static RaftClientRequest newRaftClientRequest(RaftClient client, RaftPeerId serverId, long seqNum) {
    final SimpleMessage m = new SimpleMessage("m" + seqNum);
    return RaftClientTestUtil.newRaftClientRequest(client, serverId, seqNum, m,
        RaftClientRequest.writeRequestType(), ProtoUtils.toSlidingWindowEntry(seqNum, seqNum == 1L));
  }
}
