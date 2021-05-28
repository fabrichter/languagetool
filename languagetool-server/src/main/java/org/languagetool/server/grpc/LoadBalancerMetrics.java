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

import io.prometheus.client.*;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;

import java.io.IOException;

public class LoadBalancerMetrics {
  private static HTTPServer server;
  private static LoadBalancerMetrics instance;

  private static final double[] BATCH_BUCKETS = {
  1, 2, 4, 8, 16, 32, 64
  };

  private static final double[] TIME_BUCKETS = {
    0.01, 0.02, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5,
    0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.0,
    1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0
  };

  public final Gauge queueSize = Gauge
    .build("languagetool_grpc_lb_queue_size", "Number of requests currently in queue waiting to be batched")
    .labelNames("backend").register();

  public final Gauge backendQueueSize = Gauge
    .build("languagetool_grpc_lb_backend_queue_size", "Number of batched requests ready to be sent to backends")
    .labelNames("backend").register();

  public final Gauge sessions = Gauge
    .build("languagetool_grpc_lb_server_current_sessions", "Number of active requests per backend/server")
    .labelNames("backend", "server").register();

  public final Counter requests = Counter
    .build("languagetool_grpc_lb_server_requests_total", "Number of processed requests per backend/server")
    .labelNames("backend", "server").register();

  public final Counter errors = Counter
    .build("languagetool_grpc_lb_errors_total", "Number of encountered errors")
    .labelNames("backend", "exception").register();

  public final Gauge serverStatus = Gauge
    .build("languagetool_grpc_lb_backend_server_up", "Server status (up/down)")
    .labelNames("backend", "server").register();

  public final Histogram batchSize = Histogram
    .build("languagetool_grpc_lb_request_size_sentences", "Incoming request batch size (number of sentences)")
    .buckets(BATCH_BUCKETS)
    .labelNames("backend")
    .register();

  public final Histogram responseTime = Histogram
    .build("languagetool_grpc_lb_response_time", "Time spent waiting for response from servers")
    .buckets(TIME_BUCKETS)
    .labelNames("backend")
    .register();

  public final Histogram queueTime = Histogram
    .build("languagetool_grpc_lb_queue_time", "Time before sending request to backend server")
    .buckets(TIME_BUCKETS)
    .labelNames("backend")
    .register();

  public final Histogram totalTime = Histogram
    .build("languagetool_grpc_lb_total_time", "Time from receiving the request to response")
    .buckets(TIME_BUCKETS)
    .labelNames("backend")
    .register();

  public static void init(int port) throws IOException {
    DefaultExports.initialize();
    server = new HTTPServer(port, true);
  }

  public static void stop() {
    server.stop();
  }

  public static LoadBalancerMetrics getInstance() {
    if (instance == null) {
      synchronized(TIME_BUCKETS) {
        if (instance == null) {
          instance = new LoadBalancerMetrics();
        }
      }
    }
    return instance;
  }
}
