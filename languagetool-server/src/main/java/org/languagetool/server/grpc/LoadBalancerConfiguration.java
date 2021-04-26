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
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.jetbrains.annotations.Nullable;
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

  private final String name;
  private final int port;
  private final int batchSize;
  private final int queueTimeoutMilliseconds;
  private final int healthCheckIntervalMilliseconds;
  private final String host;
  private final Path backendServersFile;
  private final int threads;
  @Nullable
  private final Integer monitoringPort;
  @Nullable
  private final String clientKey;
  @Nullable
  private final String clientCertificate;
  @Nullable
  private final String CACertificate;
  private final boolean ssl;
  private final File certificate;
  private final File privateKey;
  private final List<Runnable> backendChangeListeners = new LinkedList<>();
  private List<LBEntry> backendServersList;
  LoadBalancerConfiguration(File path) throws IOException {
    Properties props = new Properties();
    try (FileInputStream fis = new FileInputStream(path)) {
      props.load(fis);
    }
    name = Objects.requireNonNull(props.getProperty("name"),
      "Missing configuration setting: name");

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

    if (props.containsKey("monitoringPort")) {
      monitoringPort = Integer.parseInt(props.getProperty("monitoringPort"));
    } else {
      monitoringPort = null;
    }

    clientKey = props.getProperty("clientKey");
    clientCertificate = props.getProperty("clientCertificate");
    CACertificate = props.getProperty("CACertificate");

    ssl = Boolean.parseBoolean(props.getProperty("ssl", "false"));
    if (ssl) {
      certificate = new File(Objects.requireNonNull(props.getProperty("certificate"),
        "Missing configuration setting: certificate"));
      privateKey = new File(Objects.requireNonNull(props.getProperty("privateKey"),
        "Missing configuration setting: privateKey"));
      if (!(certificate.isFile() && privateKey.isFile())) {
        throw new IllegalArgumentException("Couldn't read certificate " + certificate + " and/or privateKey " + privateKey);
      }
    } else {
      certificate = null;
      privateKey = null;
    }

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
    configurationMonitor.setName("lt-server-configuration-monitor-" + name);
    configurationMonitor.start();
  }

  public boolean isSsl() {
    return ssl;
  }

  public File getCertificate() {
    return certificate;
  }

  public File getPrivateKey() {
    return privateKey;
  }

  private void loadServerList() throws IOException {
    try (Stream<String> content = Files.lines(backendServersFile)) {
      backendServersList = content.filter(LBEntry::filter).map(LBEntry::parse).collect(Collectors.toList());
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

  public String getName() {
    return name;
  }

  public List<LBEntry> getBackendServersList() {
    return Collections.unmodifiableList(backendServersList);
  }

  public int getThreads() {
    return threads;
  }

  @Nullable
  public Integer getMonitoringPort() {
    return monitoringPort;
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
    private final boolean ssl;

    public LBEntry(String address) {
      this(address, false);
    }

    public LBEntry(String address, boolean ssl) {
      this.address = address;
      this.ssl = ssl;
    }

    public static LBEntry parse(String line) {
      String[] parts = line.split(" ", 2);
      if (parts.length == 1) {
        return new LBEntry(line);
      } else if (parts.length == 0) {
        throw new IllegalArgumentException("Can't parse backend server configuration: '" + line + "'");
      } else {
        boolean ssl = parts[1].contains("ssl");
        return new LBEntry(parts[0], ssl);
      }
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
        .append(ssl, lbEntry.ssl)
        .append(address, lbEntry.address)
        .isEquals();
    }

    @Override
    public int hashCode() {
      return new HashCodeBuilder(17, 37)
        .append(address)
        .append(ssl)
        .toHashCode();
    }

    public String getAddress() {
      return address;
    }

    public boolean isSsl() {
      return ssl;
    }

    @Override
    public String toString() {
      return new ToStringBuilder(this, ToStringStyle.NO_CLASS_NAME_STYLE)
        .append("address", address)
        .append("ssl", ssl)
        .toString();
    }

    public static boolean filter(String line) {
      return !line.startsWith("#") && !line.trim().isEmpty();
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
