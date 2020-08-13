/*
 *  LanguageTool, a natural language style checker
 *  * Copyright (C) 2020 Fabian Richter
 *  *
 *  * This library is free software; you can redistribute it and/or
 *  * modify it under the terms of the GNU Lesser General Public
 *  * License as published by the Free Software Foundation; either
 *  * version 2.1 of the License, or (at your option) any later version.
 *  *
 *  * This library is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  * Lesser General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU Lesser General Public
 *  * License along with this library; if not, write to the Free Software
 *  * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 *  * USA
 *
 */

package org.languagetool.server.grpc;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;
import org.jetbrains.annotations.NotNull;
import org.languagetool.rules.GRPCRule;
import org.languagetool.rules.ml.MLServerGrpc;
import org.languagetool.rules.ml.MLServerProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class LoadBalancer extends MLServerGrpc.MLServerImplBase {
  private static final Logger logger = LoggerFactory.getLogger(LoadBalancer.class);

  private final LoadBalancerConfiguration config;

  private final List<BackendServer> backends = new LinkedList<>();
  private final Random rand = new Random();// WIP
  private final BlockingQueue<QueuedRequest> requests = new LinkedBlockingQueue<>();
  private final Executor proxyPool = Executors.newCachedThreadPool();

  class QueuedRequest {
      private final MLServerProto.MatchRequest request;
      private final StreamObserver<MLServerProto.MatchResponse> response;
      private final long deadline;

    QueuedRequest(MLServerProto.MatchRequest request, StreamObserver<MLServerProto.MatchResponse> response) {
      this.request = request;
      this.response = response;
      deadline = System.currentTimeMillis() + config.getQueueTimeoutMilliseconds();
    }
  }

  class BackendServer {
    private final LoadBalancerConfiguration.LBEntry source;
    private final ManagedChannel channel;
    private final MLServerGrpc.MLServerFutureStub stub;
    private final HealthGrpc.HealthFutureStub healthStub;
    private boolean up = false;
    private long lastHealthcheck = 0;

    BackendServer(LoadBalancerConfiguration.LBEntry backend) throws SSLException {
      this.source = backend;
      String addr = backend.getAddress();
      int port = config.getPort();
      if (addr.contains(":")) {
        String[] parts = addr.split(":", 2);
        addr = parts[0];
        port = Integer.parseInt(parts[1]);
      }
      this.channel = GRPCRule.Connection.getManagedChannel(addr, port, false, config.getClientKey(), config.getClientCertificate(), config.getCACertificate());
      //this.channel = GRPCRule.Connection.getManagedChannel(addr, port, true, config.getClientKey(), config.getClientCertificate(), config.getCACertificate());
      this.stub = MLServerGrpc.newFutureStub(channel);
      this.healthStub = HealthGrpc.newFutureStub(channel);
    }
  }

  class ProxyWorker implements Runnable {

    @Override
    public void run() {
      while (true) {
          List<QueuedRequest> batch = new ArrayList<>(config.getBatchSize());
          long deadline = System.currentTimeMillis() + config.getQueueTimeoutMilliseconds();
          int batchSize = 0;
          do {
            try {
              long remainingTime = deadline - System.currentTimeMillis();
              QueuedRequest req = requests.poll(remainingTime, TimeUnit.MILLISECONDS);
              if (req != null) {
                deadline = Math.min(req.deadline, deadline);
                batchSize += req.request.getSentencesCount();
                batch.add(req);
              }
            } catch (InterruptedException e) {
              logger.error("Interrupted while retrieving elements from request queue.", e);
            }
          } while(System.currentTimeMillis() < deadline && batchSize < config.getBatchSize());

          if (batch.isEmpty()) {
            continue;
          }
          boolean logging = batch.stream().allMatch(queued -> queued.request.getInputLogging());
          List<String> allSentences = batch.stream()
            .flatMap(queued -> queued.request.getSentencesList().stream())
            .collect(Collectors.toList());
          List<Integer> requestSizes = batch.stream()
            .map(queued -> queued.request.getSentencesCount())
            .collect(Collectors.toList());
          MLServerProto.MatchRequest batchedRequest = MLServerProto.MatchRequest.newBuilder().
            setInputLogging(logging).addAllSentences(allSentences).build();
          BackendServer selected = selectBackendServer();
          final ListenableFuture<MLServerProto.MatchResponse> future = selected.stub.match(batchedRequest);
          // TODO: timeout, cancel/retry frontend requests
          // TODO: deadline -> timeout based on request size, as in clients
          future.addListener(() -> {
            try {
              MLServerProto.MatchResponse batchResponse = future.get();
              logger.info("Request[{}] -> Backend[{}] -> Response[{}]", batchedRequest.getSentencesCount(), selected.source, batchResponse.getSentenceMatchesCount());
              int sentenceOffset = 0;

              for (int batchIndex = 0; batchIndex < batch.size(); batchIndex++) {
                QueuedRequest req = batch.get(batchIndex);
                int sentenceSize = requestSizes.get(batchIndex);
                MLServerProto.MatchResponse.Builder response = MLServerProto.MatchResponse.newBuilder();
                for (int sentenceIndex = sentenceOffset; sentenceIndex < sentenceOffset + sentenceSize; sentenceIndex++) {
                  response.addSentenceMatches(batchResponse.getSentenceMatches(sentenceIndex));
                }
                req.response.onNext(batchResponse);
                req.response.onCompleted();
              }
            } catch (InterruptedException | ExecutionException e) {
              logger.error("Reading backend response failed.", e);
            }
          }, proxyPool);
      }
    }
  }

  class HealthcheckMonitor implements Runnable {

    @Override
    public void run() {
      // TODO: is this okay?
      Executor healthCheckExecutor = MoreExecutors.directExecutor();
      boolean initialHealthcheck = true;
      // TODO: use Executors.newScheduledThreadPool(config.getThreads()).scheduleAtFixedRate()?
      // TODO: use DelayQueue?
      while (true) {
        for (BackendServer backend : backends) {
          if (System.currentTimeMillis() - backend.lastHealthcheck > config.getHealthCheckIntervalMilliseconds()) {
            backend.lastHealthcheck = System.currentTimeMillis();
            HealthCheckRequest req = HealthCheckRequest.newBuilder().build();
            final ListenableFuture<HealthCheckResponse> result = backend.healthStub.check(req);
            boolean finalInitialHealthcheck = initialHealthcheck;
            result.addListener(() -> {
              try {
                HealthCheckResponse response = result.get();
                if (response.getStatus() != HealthCheckResponse.ServingStatus.SERVING) {
                  if (backend.up || finalInitialHealthcheck) {
                    logger.warn("Health check for {} failed with status {}", backend.source, response.getStatus());
                    backend.up = false;
                  }
                } else {
                  if (!backend.up) {
                    logger.info("Health check for {} succeeded, now marked as up", backend.source);
                  }
                  backend.up = true;
                }
              } catch (InterruptedException | ExecutionException e) {
                if (backend.up || finalInitialHealthcheck) {
                  logger.warn("Health check for {} failed with exception", backend.source, e);
                  backend.up = false;
                }
              }
            }, healthCheckExecutor);
          }
        }
        initialHealthcheck = false;
      }
    }
  }

  private final Server server;

  LoadBalancer(File configPath) throws IOException {
    config = new LoadBalancerConfiguration(configPath);
    config.onBackendServerChange(this::setupServers);
    Executor executor = Executors.newFixedThreadPool(config.getThreads());
    server = ServerBuilder.forPort(config.getPort())
      .addService(this)
      .executor(executor)
      .build();
    setupServers();
  }

  private void setupServers() {
    // TODO: how to deal with existing connections
    for (BackendServer backend : backends) {
      backend.channel.shutdownNow();
    }
    backends.clear();

    for (LoadBalancerConfiguration.LBEntry backendConfig : config.getBackendServersList()) {
      try {
        backends.add(new BackendServer(backendConfig));
      } catch (SSLException e) {
        logger.error("Couldn't connect to server at {}", backendConfig, e);
      }
    }
  }

  /**
   * Main launches the server from the command line.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    LoadBalancer lb = new LoadBalancer(new File(args[0]));
    lb.start();
    lb.blockUntilShutdown();
  }

  private void start() throws IOException {
    Thread healthcheckDaemon = new Thread(new HealthcheckMonitor());
    healthcheckDaemon.setDaemon(true);
    healthcheckDaemon.start();

    Thread proxyDaemon = new Thread(new ProxyWorker());
    proxyDaemon.setDaemon(true);
    proxyDaemon.start();

    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      // Use stderr here since the logger may have been reset by its JVM shutdown hook.
      System.err.println("*** shutting down gRPC server since JVM is shutting down");
      try {
        LoadBalancer.this.stop();
      } catch (InterruptedException e) {
        e.printStackTrace(System.err);
      }
      System.err.println("*** server shut down");
    }));
  }

  private void stop() throws InterruptedException {
    if (server != null) {
      server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
      backends.forEach(backend -> backend.channel.shutdownNow());
    }
  }

  /**
   * Await termination on the main thread since the grpc library uses daemon threads.
   */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  @Override
  public void match(MLServerProto.MatchRequest request, StreamObserver<MLServerProto.MatchResponse> responseObserver) {

    requests.add(new QueuedRequest(request, responseObserver));
/*
    BackendServer selected = selectBackendServer();

    ListenableFuture<MLServerProto.MatchResponse> match = selected.stub.match(request);
    try {
      MLServerProto.MatchResponse response = match.get();
      logger.info("Request[{}] -> Backend[{}] -> Response[{}]", request.getSentencesCount(), selected.source, response.getSentenceMatchesCount());
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (InterruptedException | ExecutionException e) {
      logger.error("Interrupted while waiting for response", e);
    }
*/
  }

  @NotNull
  protected BackendServer selectBackendServer() {
    BackendServer selected = null;
    // TODO round robin instead of random; or maybe configurable

    while (selected == null) {
      selected = backends.get(rand.nextInt(backends.size()));
      if (!selected.up) {
        selected = null;
      }
    }
    return selected;
  }
}
