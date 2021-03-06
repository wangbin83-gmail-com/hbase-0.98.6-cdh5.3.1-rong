/**
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

package org.apache.hadoop.hbase.thrift;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.thrift.ThriftServerRunner.ImplType;
import org.apache.hadoop.hbase.util.InfoServer;
import org.apache.hadoop.hbase.util.VersionInfo;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.hadoop.util.Shell.ExitCodeException;
import org.apache.zookeeper.KeeperException;

/**
 * ThriftServer- this class starts up a Thrift server which implements the
 * Hbase API specified in the Hbase.thrift IDL file. The server runs in an
 * independent process.
 */
@InterfaceAudience.Private
public class ThriftServer {

  private static final Log LOG = LogFactory.getLog(ThriftServer.class);

  private static final String MIN_WORKERS_OPTION = "minWorkers";
  private static final String MAX_WORKERS_OPTION = "workers";
  private static final String MAX_QUEUE_SIZE_OPTION = "queue";
  private static final String KEEP_ALIVE_SEC_OPTION = "keepAliveSec";
  static final String BIND_OPTION = "bind";
  static final String COMPACT_OPTION = "compact";
  static final String FRAMED_OPTION = "framed";
  static final String PORT_OPTION = "port";

  private static final String DEFAULT_BIND_ADDR = "0.0.0.0";
  private static final int DEFAULT_LISTEN_PORT = 9090;
  
  private ZooKeeperWatcher zooKeeper;

  private Configuration conf;
  ThriftServerRunner serverRunner;

  private InfoServer infoServer;

  //
  // Main program and support routines
  //

  public ThriftServer(Configuration conf) throws ZooKeeperConnectionException, IOException {
    this.conf = HBaseConfiguration.create(conf);
    String hostname = InetAddress.getLocalHost().getHostName();
    ZKUtil.loginClient(this.conf, "hbase.thrift.keytab.file",
    	      "hbase.thrift.kerberos.principal",
    	      hostname);
    LOG.info("login success for zookeeper client " + InetAddress.getLocalHost().getHostName());
    this.zooKeeper = new ZooKeeperWatcher(conf, hostname, null, true);
  }

  private static void printUsageAndExit(Options options, int exitCode)
      throws ExitCodeException {
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp("Thrift", null, options,
        "To start the Thrift server run 'bin/hbase-daemon.sh start thrift'\n" +
        "To shutdown the thrift server run 'bin/hbase-daemon.sh stop " +
        "thrift' or send a kill signal to the thrift server pid",
        true);
    throw new ExitCodeException(exitCode, "");
  }
  
  public void register_zk() throws UnknownHostException, UnsupportedEncodingException, KeeperException {
     String hostname = InetAddress.getLocalHost().getHostName();
     int thrift_port = conf.getInt(ThriftServerRunner.PORT_CONF_KEY, 
    		                       DEFAULT_LISTEN_PORT);
     String zk_key = hostname + ":"+ thrift_port;

     String znode = ZKUtil.joinZNode(this.zooKeeper.thriftServerZNode, zk_key);
     ZKUtil.deleteNodeFailSilent(this.zooKeeper, znode);
     ZKUtil.createEphemeralNodeAndWatch(this.zooKeeper, znode, zk_key.getBytes("UTF-8"));
     
     LOG.info("create ephemeral node " + znode);
  }

  /**
   * Start up or shuts down the Thrift server, depending on the arguments.
   * @param args
   */
   void doMain(final String[] args) throws Exception {
     processOptions(args);
     
     serverRunner = new ThriftServerRunner(conf, this);
     // Put up info server.
     int port = conf.getInt("hbase.thrift.info.port", 9095);
     if (port >= 0) {
       conf.setLong("startcode", System.currentTimeMillis());
       String a = conf.get("hbase.thrift.info.bindAddress", "0.0.0.0");
       infoServer = new InfoServer("thrift", a, port, false, conf);
       infoServer.setAttribute("hbase.conf", conf);
       infoServer.start();
     }
     serverRunner.run();
  }

  /**
   * Parse the command line options to set parameters the conf.
   */
  private void processOptions(final String[] args) throws Exception {
    Options options = new Options();
    options.addOption("b", BIND_OPTION, true, "Address to bind " +
        "the Thrift server to. [default: " + DEFAULT_BIND_ADDR + "]");
    options.addOption("p", PORT_OPTION, true, "Port to bind to [default: " +
        DEFAULT_LISTEN_PORT + "]");
    options.addOption("f", FRAMED_OPTION, false, "Use framed transport");
    options.addOption("c", COMPACT_OPTION, false, "Use the compact protocol");
    options.addOption("h", "help", false, "Print help information");
    options.addOption(null, "infoport", true, "Port for web UI");

    options.addOption("m", MIN_WORKERS_OPTION, true,
        "The minimum number of worker threads for " +
        ImplType.THREAD_POOL.simpleClassName());

    options.addOption("w", MAX_WORKERS_OPTION, true,
        "The maximum number of worker threads for " +
        ImplType.THREAD_POOL.simpleClassName());

    options.addOption("q", MAX_QUEUE_SIZE_OPTION, true,
        "The maximum number of queued requests in " +
        ImplType.THREAD_POOL.simpleClassName());

    options.addOption("k", KEEP_ALIVE_SEC_OPTION, true,
        "The amount of time in secods to keep a thread alive when idle in " +
        ImplType.THREAD_POOL.simpleClassName());

    options.addOptionGroup(ImplType.createOptionGroup());

    CommandLineParser parser = new PosixParser();
    CommandLine cmd = parser.parse(options, args);

    // This is so complicated to please both bin/hbase and bin/hbase-daemon.
    // hbase-daemon provides "start" and "stop" arguments
    // hbase should print the help if no argument is provided
    List<String> commandLine = Arrays.asList(args);
    boolean stop = commandLine.contains("stop");
    boolean start = commandLine.contains("start");
    boolean invalidStartStop = (start && stop) || (!start && !stop);
    if (cmd.hasOption("help") || invalidStartStop) {
      if (invalidStartStop) {
        LOG.error("Exactly one of 'start' and 'stop' has to be specified");
      }
      printUsageAndExit(options, 1);
    }

    // Get port to bind to
    try {
      if (cmd.hasOption(PORT_OPTION)) {
        int listenPort = Integer.parseInt(cmd.getOptionValue(PORT_OPTION));
        conf.setInt(ThriftServerRunner.PORT_CONF_KEY, listenPort);
      }
    } catch (NumberFormatException e) {
      LOG.error("Could not parse the value provided for the port option", e);
      printUsageAndExit(options, -1);
    }

    // check for user-defined info server port setting, if so override the conf
    try {
      if (cmd.hasOption("infoport")) {
        String val = cmd.getOptionValue("infoport");
        conf.setInt("hbase.thrift.info.port", Integer.valueOf(val));
        LOG.debug("Web UI port set to " + val);
      }
    } catch (NumberFormatException e) {
      LOG.error("Could not parse the value provided for the infoport option", e);
      printUsageAndExit(options, -1);
    }

    // Make optional changes to the configuration based on command-line options
    optionToConf(cmd, MIN_WORKERS_OPTION,
        conf, TBoundedThreadPoolServer.MIN_WORKER_THREADS_CONF_KEY);
    optionToConf(cmd, MAX_WORKERS_OPTION,
        conf, TBoundedThreadPoolServer.MAX_WORKER_THREADS_CONF_KEY);
    optionToConf(cmd, MAX_QUEUE_SIZE_OPTION,
        conf, TBoundedThreadPoolServer.MAX_QUEUED_REQUESTS_CONF_KEY);
    optionToConf(cmd, KEEP_ALIVE_SEC_OPTION,
        conf, TBoundedThreadPoolServer.THREAD_KEEP_ALIVE_TIME_SEC_CONF_KEY);

    // Set general thrift server options
    boolean compact = cmd.hasOption(COMPACT_OPTION) ||
      conf.getBoolean(ThriftServerRunner.COMPACT_CONF_KEY, false);
    conf.setBoolean(ThriftServerRunner.COMPACT_CONF_KEY, compact);
    boolean framed = cmd.hasOption(FRAMED_OPTION) ||
      conf.getBoolean(ThriftServerRunner.FRAMED_CONF_KEY, false);
    conf.setBoolean(ThriftServerRunner.FRAMED_CONF_KEY, framed);
    if (cmd.hasOption(BIND_OPTION)) {
      conf.set(
          ThriftServerRunner.BIND_CONF_KEY, cmd.getOptionValue(BIND_OPTION));
    }

    ImplType.setServerImpl(cmd, conf);
  }

  public void stop() {
    if (this.infoServer != null) {
      LOG.info("Stopping infoServer");
      try {
        this.infoServer.stop();
      } catch (Exception ex) {
        ex.printStackTrace();
      }
    }
    serverRunner.shutdown();
  }

  private static void optionToConf(CommandLine cmd, String option,
      Configuration conf, String destConfKey) {
    if (cmd.hasOption(option)) {
      String value = cmd.getOptionValue(option);
      LOG.info("Set configuration key:" + destConfKey + " value:" + value);
      conf.set(destConfKey, value);
    }
  }

  /**
   * @param args
   * @throws Exception
   */
  public static void main(String [] args) throws Exception {
    VersionInfo.logVersion();
    try {
      new ThriftServer(HBaseConfiguration.create()).doMain(args);
    } catch (ExitCodeException ex) {
      System.exit(ex.getExitCode());
    }
  }
}
