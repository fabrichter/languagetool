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
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.*;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.languagetool.rules.GRPCRule;
import org.languagetool.rules.ml.MLServerGrpc;
import org.languagetool.rules.ml.MLServerProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@SuppressWarnings("ALL")
public class LoadBalancer extends MLServerGrpc.MLServerImplBase {
  private static final Logger logger = LoggerFactory.getLogger(LoadBalancer.class);
  private static final long CONNECTION_CLOSE_DELAY_MS = 30_000;

  private final LoadBalancerConfiguration config;

  private final List<BackendServer> backends = new LinkedList<>();
  private final Random rand = new Random();// WIP -> implement round robin?
  // TODO: capacity for queues?
  private final BlockingQueue<QueuedRequest> requests = new LinkedBlockingQueue<>();
  private final BlockingQueue<List<QueuedRequest>> batches = new LinkedBlockingQueue<>();
  private final DelayQueue<ScheduledCheck> healthchecks = new DelayQueue<ScheduledCheck>();
  private final Executor proxyPool;
  private final ScheduledExecutorService scheduledTasks;

  class QueuedRequest {
      private final MLServerProto.MatchRequest request;
      private final StreamObserver<MLServerProto.MatchResponse> response;
      private final long timestamp;
      // TODO could add retries

    QueuedRequest(MLServerProto.MatchRequest request, StreamObserver<MLServerProto.MatchResponse> response) {
      this.request = request;
      this.response = response;
      timestamp = System.nanoTime();
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
      if (source.isSsl()) {
        this.channel = GRPCRule.Connection.getManagedChannel(addr, port, true, config.getClientKey(), config.getClientCertificate(), config.getCACertificate());
      } else {
        this.channel = GRPCRule.Connection.getManagedChannel(addr, port, false, config.getClientKey(), config.getClientCertificate(), config.getCACertificate());
      }
      this.stub = MLServerGrpc.newFutureStub(channel);
      this.healthStub = HealthGrpc.newFutureStub(channel);
    }
  }

  class RequestBatcher implements Runnable {

    @Override
    public void run() {
      long timeout = TimeUnit.NANOSECONDS.convert(config.getQueueTimeoutMilliseconds(), TimeUnit.MILLISECONDS);
      while (true) {
        List<QueuedRequest> batch = new ArrayList<>(config.getBatchSize());
        long start = System.nanoTime();
        int batchSize = 0;
        try {
          do {
            long time = System.nanoTime();
            long elapsedTime = time - start;
            long remainingTime = timeout - elapsedTime;

            QueuedRequest req = requests.poll(remainingTime, TimeUnit.NANOSECONDS);
            long elapsed = System.nanoTime() - time;
            //logger.info("Waited {}ms on poll({})",
            //  TimeUnit.MILLISECONDS.convert(elapsed, TimeUnit.NANOSECONDS),
            //  TimeUnit.MILLISECONDS.convert(remainingTime, TimeUnit.NANOSECONDS));
            if (req != null) {
              start = Math.min(req.timestamp, start); // TODO: is this necessary
              batchSize += req.request.getSentencesCount();
              batch.add(req);
            }
          } while (System.nanoTime() - start < timeout && batchSize < config.getBatchSize());
        } catch (InterruptedException e) {
          logger.error("Interrupted while retrieving elements from request queue.", e);
        }

        if (!batch.isEmpty()) {
          boolean added = batches.offer(batch);
          if (!added) {
            logger.error("Request batch queue full, discarding requests.");
          }
        }
      }
    }
  }

  class ProxyWorker implements Runnable {

    @Override
    public void run() {
      while (true) {
        try {
          final List<QueuedRequest> batch = batches.take();
          boolean logging = true;
          List<Integer> requestSizes = new ArrayList<>(batch.size());
          MLServerProto.MatchRequest.Builder batchedRequest = MLServerProto.MatchRequest.newBuilder();
          for (QueuedRequest req : batch) {
            batchedRequest.addAllSentences(req.request.getSentencesList());
            batchedRequest.addAllTextSessionID(req.request.getTextSessionIDList());
            if (!req.request.getInputLogging()) { // only allow logging if all batched requests allow it
              logging = false;
            }
            requestSizes.add(req.request.getSentencesCount());
          }
          batchedRequest.setInputLogging(logging);
          final ListenableFuture<MLServerProto.MatchResponse> future;
          BackendServer selected;
          synchronized (backends) {
            selected = selectBackendServer();
            if (selected == null) {
              StatusException e = new StatusException(Status.UNAVAILABLE.withDescription("No backend servers available"));
              logger.error("Requests aborted - no backend servers up out of {} - {}", backends.size(), backends);
              batch.forEach(queuedRequest -> queuedRequest.response.onError(e));
              continue;
            }
          }
          future = selected.stub.match(batchedRequest.build());
          // TODO: timeout, cancel/retry frontend requests
          // TODO: deadline -> timeout based on request size, as in clients
          future.addListener(() -> {
            try {
              MLServerProto.MatchResponse batchResponse = future.get();
              logger.info("[{}] Request[{}/{}] -> Backend[{}] -> Response[{}]", config.getName(),
                batch.size(), batchedRequest.getSentencesCount(), selected.source, batchResponse.getSentenceMatchesCount());
              int sentenceOffset = 0;

              for (int batchIndex = 0; batchIndex < batch.size(); batchIndex++) {
                QueuedRequest req = batch.get(batchIndex);
                int sentenceSize = requestSizes.get(batchIndex);
                MLServerProto.MatchResponse.Builder response = MLServerProto.MatchResponse.newBuilder();
                for (int sentenceIndex = sentenceOffset; sentenceIndex < sentenceOffset + sentenceSize; sentenceIndex++) {
                  response.addSentenceMatches(batchResponse.getSentenceMatches(sentenceIndex));
                }
                sentenceOffset += sentenceSize;
                req.response.onNext(response.build());
                req.response.onCompleted();
              }
            } catch (InterruptedException | ExecutionException e) {
              logger.error("Reading backend response failed.", e);
              batch.forEach(queued -> queued.response.onError(e));
            }
          }, proxyPool);
        } catch (InterruptedException e) {
          logger.error("Interrupted while retrieving request batch", e);
        }
      }
    }
  }

  class ScheduledCheck implements Delayed {

    private final BackendServer server;

    public ScheduledCheck(BackendServer server) {
      this.server = server;
    }

    @Override
    public long getDelay(@NotNull TimeUnit unit) {
      if (server.lastHealthcheck == 0) {
        return 0;
      }
      long elapsed = System.currentTimeMillis() - server.lastHealthcheck;
      return unit.convert(config.getHealthCheckIntervalMilliseconds() - elapsed, TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(@NotNull Delayed o) {
      if (this == o) {
        return 0;
      } else {
        ScheduledCheck that = (ScheduledCheck) o;
        long d = this.getDelay(TimeUnit.MILLISECONDS) - that.getDelay(TimeUnit.MILLISECONDS);
        if (d < 0L) {
          return -1;
        } else if (d > 0L) {
          return 1;
        } else {
          return 0;
        }
      }
    }
  }

  class HealthcheckMonitor implements Runnable {

    @Override
    public void run() {

      // TODO: is this okay?
      Executor healthCheckExecutor = MoreExecutors.directExecutor();
      while (true) {
        try {
          ScheduledCheck check = healthchecks.take();
          BackendServer backend = check.server;
          final boolean initialHealthcheck = backend.lastHealthcheck == 0;
          backend.lastHealthcheck = System.currentTimeMillis();
          // only schedule next checks if still in backend list
          synchronized (backends) {
            if (backends.stream().map(b -> b.source).anyMatch(addr -> addr.equals(backend.source))) {
              healthchecks.add(check);
            }
          }
          HealthCheckRequest req = HealthCheckRequest.newBuilder().build();
          final ListenableFuture<HealthCheckResponse> result = backend.healthStub.check(req);
          result.addListener(() -> {
            try {
              HealthCheckResponse response = result.get();
              if (response.getStatus() != HealthCheckResponse.ServingStatus.SERVING) {
                if (backend.up || initialHealthcheck) {
                  logger.warn("[{}] Health check for {} failed with status {}", config.getName(), backend.source, response.getStatus());
                  backend.up = false;
                }
              } else {
                if (!backend.up) {
                  logger.info("[{}] Health check for {} succeeded, now marked as up", config.getName(), backend.source);
                }
                backend.up = true;
              }
            } catch (InterruptedException | ExecutionException e) {
              if (backend.up || initialHealthcheck) {
                logger.warn("[{}] Health check for {} failed with exception", config.getName(), backend.source, e);
                backend.up = false;
              }
            }
          }, healthCheckExecutor);

        } catch (InterruptedException e) {
          logger.error("Interrupted while running health checks", e);
        }
      }
    }
  }

  private final Server server;

  LoadBalancer(File configPath) throws IOException {
    config = new LoadBalancerConfiguration(configPath);
    config.onBackendServerChange(this::setupServers);

    proxyPool = Executors.newCachedThreadPool(
      new ThreadFactoryBuilder().setNameFormat("lt-proxy-listener-" + config.getName() + "-%d").build());
     scheduledTasks = Executors.newScheduledThreadPool(1,
      new ThreadFactoryBuilder().setNameFormat("lt-lb-scheduled-tasks-" + config.getName() + "-%d").build());

    Executor executor = Executors.newFixedThreadPool(config.getThreads(),
      new ThreadFactoryBuilder().setNameFormat("lt-lb-grpc-server-" + config.getName() + "-%d").build());

    ServerBuilder builder = ServerBuilder.forPort(config.getPort())
      .addService(this)
      .executor(executor);
    if (config.isSsl()) {
      //SslContext ctx = SslContextBuilder.forServer(config.getCertificate(), config.getPrivateKey())
      //  .sslProvider(SslProvider.OPENSSL).build();
      builder.useTransportSecurity(config.getCertificate(), config.getPrivateKey());
    }
    server = builder.build();
    setupServers();
  }

  private void setupServers() {
    // deal with existing connections - shutdown removed backends after delay, re-use existing connections
    synchronized (backends) {
      List<BackendServer> removedBackends = new ArrayList<>();
      Set<LoadBalancerConfiguration.LBEntry> existingConnections = new HashSet<>();
      Iterator<BackendServer> iter = backends.iterator();
      while (iter.hasNext()) {
        BackendServer backend = iter.next();
        if (!config.getBackendServersList().contains(backend.source)) {
          logger.info("[{}] Removing {} from backend pool", config.getName(), backend.source);
          removedBackends.add(backend); // shutdown removed connections with delay
          iter.remove();
        } else { // preserve existing connections
          existingConnections.add(backend.source);
        }
      }

      for (LoadBalancerConfiguration.LBEntry backendConfig : config.getBackendServersList()) {
        try {
          if (!existingConnections.contains(backendConfig)) {
            logger.info("[{}] Adding {} to backend pool", config.getName(), backendConfig);
            BackendServer backend = new BackendServer(backendConfig);
            backends.add(backend);
            healthchecks.add(new ScheduledCheck(backend));
          }
        } catch (SSLException e) {
          logger.error("Couldn't connect to server at {}", backendConfig, e);
        }
      }
      scheduledTasks.schedule(() -> {
        removedBackends.forEach(backend -> backend.channel.shutdownNow());
      }, CONNECTION_CLOSE_DELAY_MS, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Main launches the server from the command line.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    // TODO: alternative: run one LB, collect results from multiple backends
    List<LoadBalancer> loadBalancers = new ArrayList<>(args.length);
    for (String configPath : args) {
      logger.info("Loading configuration {}...", configPath);
      LoadBalancer lb = new LoadBalancer(new File(configPath));
      logger.info("[{}] Starting load balancer...", lb.config.getName());
      lb.start();
      logger.info("[{}] Started.", lb.config.getName());
      loadBalancers.add(lb);
    }
    for (LoadBalancer lb : loadBalancers) {
      lb.blockUntilShutdown();
      logger.info("Stopped {}.", lb.config.getName());
    }
  }

  private void start() throws IOException {
    Thread healthcheckDaemon = new Thread(new HealthcheckMonitor());
    healthcheckDaemon.setName("lt-healthcheck-monitor-" + config.getName());
    healthcheckDaemon.setDaemon(true);
    healthcheckDaemon.start();

    Thread batchDaemon = new Thread(new RequestBatcher());
    batchDaemon.setName("lt-batch-worker-" + config.getName());
    batchDaemon.setDaemon(true);
    batchDaemon.start();

    // TODO: Multiple proxy workers?
    Thread proxyDaemon = new Thread(new ProxyWorker());
    proxyDaemon.setName("lt-proxy-worker-" + config.getName());
    proxyDaemon.setDaemon(true);
    proxyDaemon.start();

    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      // Use stderr here since the logger may have been reset by its JVM shutdown hook.
      System.err.println("*** shutting down gRPC server for " + config.getName() + " since JVM is shutting down");
      try {
        LoadBalancer.this.stop();
      } catch (InterruptedException e) {
        e.printStackTrace(System.err);
      }
      System.err.println("*** server " + config.getName() + " shut down");
    }, "lt-lb-shutdown-hook-" + config.getName()));
  }

  private void stop() throws InterruptedException {
    // TODO: try to wait for queue to be processed?
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
  }

  @Nullable
  protected BackendServer selectBackendServer() {
    BackendServer selected = null;
    // TODO round robin instead of random; or maybe configurable

    if (backends.stream().noneMatch(backend -> backend.up)) {
      return null;
    }
    while (selected == null) {
      selected = backends.get(rand.nextInt(backends.size()));
      if (!selected.up) {
        selected = null;
      }
    }
    return selected;
  }
}
