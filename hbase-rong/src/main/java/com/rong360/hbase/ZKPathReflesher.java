/**
 *
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
package com.rong360.hbase;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperListener;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.zookeeper.KeeperException;

public class ZKPathReflesher extends ZooKeeperListener {
	private static final Log LOG = LogFactory.getLog(ZKPathReflesher.class);

	private final String znode;
	private final String appConf;

	public ZKPathReflesher(ZooKeeperWatcher watcher, String znode,
			String appConf) {
		super(watcher);
		this.znode = znode;
		this.appConf = appConf;
	}

	@Override
	public synchronized void nodeCreated(String path) {
		if (!path.equals(znode))
			return;
		try {
			byte[] data = ZKUtil.getDataAndWatch(watcher, znode);
			if (data != null) {
				LOG.info(znode + " created");
			}
		} catch (KeeperException e) {
		}
	}

	@Override
	public synchronized void nodeDeleted(String path) {
		if (path.equals(znode)) {
			try {
				if (ZKUtil.watchAndCheckExists(watcher, znode)) {
					nodeCreated(path);
				} else {
					LOG.info(znode + " deleted");
				}
			} catch (KeeperException e) {
			}
		}
	}

	@Override
	public synchronized void nodeDataChanged(String path) {
		if (path.equals(znode)) {
			nodeCreated(path);
		}
	}

	@Override
	public void nodeChildrenChanged(String path) {
		if (path.equals(this.znode)) {
			try {
				List<String> children = ZKUtil.listChildrenAndWatchForNewChildren(
						this.watcher, path);
				this.write_conf(children);
				LOG.info("get thrift server list changed");
			} catch (KeeperException e) {
				e.printStackTrace();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				LOG.fatal("can't find file: " + this.appConf);
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
		} else {
			LOG.warn("Wrong path: " + path);
		}
	}

	public synchronized void start() throws FileNotFoundException, UnsupportedEncodingException {
		this.watcher.registerListener(this);
		LOG.info("watch znode: " + znode + " , sink to path: " + appConf);
		try {
			List<String> children = ZKUtil.listChildrenAndWatchForNewChildren(watcher, znode);
			this.write_conf(children);
		} catch (KeeperException e) {
		}
	}
	
	private void write_conf(List<String> children) throws FileNotFoundException, UnsupportedEncodingException {
		if (children.size() <= 0) {
			return;
		}
		PrintWriter writer = new PrintWriter(appConf, "UTF-8");
		for (String child : children) {
			writer.println(child);
		}
		writer.close();
	}

	public static void main(String[] args) throws ZooKeeperConnectionException,
			IOException {
		if (args.length < 2) {
			System.out.println("Usage: ZKPathReflesher znode appConfpath");
			return;
		}
		String znode = args[0];
		String appConf = args[1];
		String uuid = UUID.randomUUID().toString();
		Configuration conf = HBaseConfiguration.create();
		ZKUtil.loginClient(conf, "hbase.thrift.keytab.file",
				"hbase.thrift.kerberos.principal", InetAddress.getLocalHost()
						.getHostName());
		LOG.info("login success for zookeeper client "
				+ InetAddress.getLocalHost().getHostName());
		ZooKeeperWatcher watch = new ZooKeeperWatcher(conf, uuid, null);
		new ZKPathReflesher(watch, znode, appConf).start();
		while (true) {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

}
