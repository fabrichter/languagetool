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

  };

  private final Gauge backendStatus = Gauge
    .build("languagetool_grpc_lb_backend_server_up", "Server status (up/down)")
    .labelNames("backend", "server").register();

  private final Histogram incomingRequestSizes = Histogram
    .build("languagetool_grpc_lb_request_size_sentences", "Incoming request batch size (number of sentences)")
    .buckets()
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
      instance = new LoadBalancerMetrics();
    }
    return instance;
  }

  public void setBackendStatus(String backend, String server, boolean up) {
    backendStatus.labels(backend, server).set(up ? 1.0 : 0.0);
  }
}
