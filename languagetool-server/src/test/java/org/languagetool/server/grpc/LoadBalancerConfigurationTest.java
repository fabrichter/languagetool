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

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.hamcrest.CoreMatchers.*;

public class LoadBalancerConfigurationTest {

  protected Path testConfig;
  protected Path testServerList;


  @Before
  public void setUp() throws Exception {
    testConfig = Files.createTempFile("lb-config", ".properties");
    testServerList = Files.createTempFile("lb-servers", ".txt");

    Files.write(testServerList, Arrays.asList(
      "server1",
      "server2"
    ));

    Files.write(testConfig, Arrays.asList(
      "port=1234",
      "host=localhost",
      "batchSize=16",
      "threads=4",
      "queueTimeoutMilliseconds=100",
      "healthCheckIntervalMilliseconds=5000",
      "backendServers=" + testServerList.toAbsolutePath(),
      "clientKey=foo",
      "clientCertificate=bar",
      "CACertificate=baz"
    ));
  }

  @Test
  public void testLoading() throws IOException {
    LoadBalancerConfiguration config = new LoadBalancerConfiguration(testConfig.toFile());

    assertEquals(1234, config.getPort());
    assertEquals("localhost", config.getHost());
    assertEquals("foo", config.getClientKey());
    assertEquals("bar", config.getClientCertificate());
    assertEquals("baz", config.getCACertificate());
    assertEquals(16, config.getBatchSize());
    assertEquals(100, config.getQueueTimeoutMilliseconds());
    assertEquals(5000, config.getHealthCheckIntervalMilliseconds());
    assertEquals(4, config.getThreads());
    assertThat(config.getBackendServersList(), hasItems(
      equalTo(new LoadBalancerConfiguration.LBEntry("server1")),
      equalTo(new LoadBalancerConfiguration.LBEntry("server2"))
    ));
  }

  @Test
  public void testChangeMonitoring() throws IOException, InterruptedException {

    LoadBalancerConfiguration config = new LoadBalancerConfiguration(testConfig.toFile());
    assertEquals(config.getBackendServersList().size(), 2);
    AtomicBoolean changed = new AtomicBoolean(false);
    config.onBackendServerChange(() -> {
      changed.set(true);
    });
    Thread.sleep(LoadBalancerConfiguration.ServerConfigurationMonitor.CHANGE_POLLING_INTERVAL * 5);
    Files.write(testServerList, Arrays.asList(
      "server1",
      "server2",
      "newServer1"
    ));
    Thread.sleep(LoadBalancerConfiguration.ServerConfigurationMonitor.CHANGE_POLLING_INTERVAL * 5);

    assertThat(changed.get(), is(true));
    assertThat(config.getBackendServersList(), hasItems(
      equalTo(new LoadBalancerConfiguration.LBEntry("server1")),
      equalTo(new LoadBalancerConfiguration.LBEntry("server2")),
      equalTo(new LoadBalancerConfiguration.LBEntry("newServer1"))
    ));
  }
}
