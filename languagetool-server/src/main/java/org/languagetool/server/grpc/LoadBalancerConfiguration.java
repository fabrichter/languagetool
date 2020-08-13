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


import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class LoadBalancerConfiguration {
  private static final Logger logger = LoggerFactory.getLogger(LoadBalancerConfiguration.class);

  private final int port;
  private final int batchSize;
  private final int queueTimeoutMilliseconds;
  private final int healthCheckIntervalMilliseconds;
  private final String host;
  private final Path backendServersFile;
  private List<LBEntry> backendServersList;
  private final int threads;
  private final String clientKey;
  private final String clientCertificate;
  private final String CACertificate;

  private final List<Runnable> backendChangeListeners = new LinkedList<>();

  LoadBalancerConfiguration(File path) throws IOException {
    Properties props = new Properties();
    try (FileInputStream fis = new FileInputStream(path)) {
      props.load(fis);
    }

    port = Integer.parseInt(Objects.requireNonNull(props.getProperty("port"),
      "Missing configuration setting: port"));

    batchSize = Integer.parseInt(Objects.requireNonNull(props.getProperty("batchSize"),
      "Missing configuration setting: batchSize"));

    queueTimeoutMilliseconds = Integer.parseInt(Objects.requireNonNull(props.getProperty("queueTimeoutMilliseconds"),
      "Missing configuration setting: queueTimeoutMilliseconds"));

    healthCheckIntervalMilliseconds = Integer.parseInt(Objects.requireNonNull(props.getProperty("healthCheckIntervalMilliseconds"),
      "Missing configuration setting: healthCheckIntervalMilliseconds"));


    threads = Integer.parseInt(Objects.requireNonNull(props.getProperty("threads"),
      "Missing configuration setting: threads"));

    host = Objects.requireNonNull(props.getProperty("host"),
      "Missing configuration setting: host");

    clientKey = Objects.requireNonNull(props.getProperty("clientKey"),
      "Missing configuration setting: clientKey");
    clientCertificate = Objects.requireNonNull(props.getProperty("clientCertificate"),
      "Missing configuration setting: clientCertificate");
    CACertificate = Objects.requireNonNull(props.getProperty("CACertificate"),
      "Missing configuration setting: CACertificate");

    backendServersFile = Paths.get(Objects.requireNonNull(props.getProperty("backendServers"),
      "Missing configuration setting: backendServers"));
    if (!Files.isRegularFile(backendServersFile)) {
      throw new IllegalArgumentException("backendServers must be a file with a list of servers");
    }
    try {
      loadServerList();
    } catch (IOException ex) {
      throw new RuntimeException("Could not load backend server configuration", ex);
    }
    Thread configurationMonitor = new Thread(new ServerConfigurationMonitor());
    configurationMonitor.setDaemon(true);
    configurationMonitor.setName("grpc-server-configuration-monitor");
    configurationMonitor.start();
  }

  private void loadServerList() throws IOException {
    try (Stream<String> content = Files.lines(backendServersFile)) {
      backendServersList = content.map(LBEntry::parse).collect(Collectors.toList());
    }
  }

  public void onBackendServerChange(Runnable listener) {
    backendChangeListeners.add(listener);
  }

  public int getPort() {
    return port;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public int getQueueTimeoutMilliseconds() {
    return queueTimeoutMilliseconds;
  }

  public String getHost() {
    return host;
  }

  public List<LBEntry> getBackendServersList() {
    return Collections.unmodifiableList(backendServersList);
  }

  public int getThreads() {
    return threads;
  }

  public String getClientKey() {
    return clientKey;
  }

  public String getClientCertificate() {
    return clientCertificate;
  }

  public String getCACertificate() {
    return CACertificate;
  }

  public int getHealthCheckIntervalMilliseconds() {
    return healthCheckIntervalMilliseconds;
  }

  public static class LBEntry {
    private final String address;

    public LBEntry(String address) {
      this.address = address;
    }

    public static LBEntry parse(String line) {
      return new LBEntry(line);
    }

    public String getAddress() {
      return address;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }

      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      LBEntry lbEntry = (LBEntry) o;

      return new EqualsBuilder()
        .append(address, lbEntry.address)
        .isEquals();
    }

    @Override
    public int hashCode() {
      return new HashCodeBuilder(17, 37)
        .append(address)
        .toHashCode();
    }

    @Override
    public String toString() {
      return "LBEntry{" +
        "address='" + address + '\'' +
        '}';
    }
  }

  class ServerConfigurationMonitor implements Runnable {

    public static final int CHANGE_POLLING_INTERVAL = 1000;
    private Path configPath;

    @Override
    public void run() {
      FileSystem fs = FileSystems.getDefault();
      try (final WatchService watchService = fs.newWatchService()){
        Path configDirectory = backendServersFile.getParent();
        configPath = configDirectory.relativize(backendServersFile);
        final WatchKey watchKey = configDirectory.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
        while (true) {
          List<WatchEvent<?>> events = watchKey.pollEvents();
          if (events.stream().anyMatch(this::affectsConfiguration)) {
            logger.info("Reloading server configuration at {}", backendServersFile);
            loadServerList();
            backendChangeListeners.forEach(Runnable::run);
          }
          Thread.sleep(CHANGE_POLLING_INTERVAL);
        }
      } catch (IOException e) {
        logger.error("Unable to monitor/reload server configuration for changes", e);
      } catch (InterruptedException e) {
        logger.warn("Interrupted while sleeping in configuration monitoring thread", e);
      }
    }

    private boolean affectsConfiguration(WatchEvent<?> event) {
      if (event.kind() != StandardWatchEventKinds.ENTRY_MODIFY) {
        return false;
      }
      Path path = (Path) event.context();
      return path.equals(configPath);
    }
  }
}
