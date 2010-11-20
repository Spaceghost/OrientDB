/*
 * Copyright 1999-2010 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.server.handler.distributed;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import javax.crypto.SecretKey;

import com.orientechnologies.common.concur.resource.OSharedResourceExternal;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.db.ODatabaseComplex;
import com.orientechnologies.orient.core.db.ODatabaseLifecycleListener;
import com.orientechnologies.orient.core.exception.OConfigurationException;
import com.orientechnologies.orient.core.record.ORecordInternal;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.security.OSecurityManager;
import com.orientechnologies.orient.core.serialization.OBase64Utils;
import com.orientechnologies.orient.core.tx.OTransactionEntry;
import com.orientechnologies.orient.server.OClientConnection;
import com.orientechnologies.orient.server.OServer;
import com.orientechnologies.orient.server.config.OServerHandlerConfiguration;
import com.orientechnologies.orient.server.config.OServerParameterConfiguration;
import com.orientechnologies.orient.server.handler.OServerHandlerAbstract;
import com.orientechnologies.orient.server.network.OServerNetworkListener;
import com.orientechnologies.orient.server.network.protocol.distributed.ONetworkProtocolDistributed;

/**
 * Distributed server node handler. It starts the discovery signaler and listener and manages the configuration of the distributed
 * server nodes. When the node startups and after a configurable timeout no nodes are joined, then the node became the LEADER.
 * <p>
 * Distributed server messages are sent using IP Multicast.
 * </p>
 * <p>
 * Distributed server message format:
 * </p>
 * <code>
 * Orient v. &lt;orientdb-version&gt;-&lt;protocol-version&gt;-&lt;cluster-name&gt;-&lt;cluster-password&gt-&lt;callback-address&gt-&lt;callback-port&gt;
 * </code>
 * 
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 * @see ODistributedServerDiscoveryListener, ODistributedServerDiscoverySignaler
 * 
 */
public class ODistributedServerManager extends OServerHandlerAbstract  {
	protected OServer																			server;

	protected String																			name;
	protected String																			id;
	protected SecretKey																		securityKey;
	protected String																			securityAlgorithm;
	protected InetAddress																	networkMulticastAddress;
	protected int																					networkMulticastPort;
	protected int																					networkMulticastHeartbeat;																								// IN
	protected int																					networkTimeoutLeader;																										// IN
	protected int																					networkTimeoutNode;																											// IN
	private int																						networkHeartbeatDelay;																										// IN
	protected int																					serverUpdateDelay;																												// IN
	protected int																					serverOutSynchMaxBuffers;

	private ODistributedServerDiscoverySignaler						discoverySignaler;
	private ODistributedServerDiscoveryListener						discoveryListener;
	private ODistributedServerLeaderChecker								leaderCheckerTask;
	private ODistributedServerRecordHook									trigger;
	private final OSharedResourceExternal									lock							= new OSharedResourceExternal();

	private final HashMap<String, ODistributedServerNode>	nodes							= new LinkedHashMap<String, ODistributedServerNode>();	;

	static final String																		CHECKSUM					= "ChEcKsUm1976";

	static final String																		PACKET_HEADER			= "OrientDB v.";
	static final int																			PROTOCOL_VERSION	= 0;

	private OServerNetworkListener												distributedNetworkListener;
	private ONetworkProtocolDistributed										leaderConnection;
	public long																						lastHeartBeat;
	private ODocument																			clusterConfiguration;

	public void startup() {
		trigger = new ODistributedServerRecordHook(this);

		// LAUNCH THE SIGNAL AND WAIT FOR A CONNECTION
		discoverySignaler = new ODistributedServerDiscoverySignaler(this, distributedNetworkListener);
	}

	public void shutdown() {
		if (discoverySignaler != null)
			discoverySignaler.sendShutdown();
		if (discoveryListener != null)
			discoveryListener.sendShutdown();
	}

	public void receivedLeaderConnection(final ONetworkProtocolDistributed iNetworkProtocolDistributed) {
		OLogManager.instance().info(this, "Connected from the distributed node Leader");

		// STOP TO SEND PACKETS TO BEING DISCOVERED
		if (discoverySignaler != null) {
			discoverySignaler.sendShutdown();
			discoverySignaler = null;
		}

		leaderConnection = iNetworkProtocolDistributed;

		// FIRST TIME: SCHEDULE THE HEARTBEAT CHECKER
		leaderCheckerTask = new ODistributedServerLeaderChecker(this);
		Orient.getTimer().schedule(leaderCheckerTask, networkHeartbeatDelay, networkHeartbeatDelay / 2);
	}

	/**
	 * Callback invoked by OClusterDiscoveryListener when a good packed has been received.
	 * 
	 * @param iServerAddress
	 *          Server address where to connect
	 * @param iServerPort
	 *          Server port
	 */
	public void receivedNodePresence(final String iServerAddress, final int iServerPort) {
		final String key = getNodeName(iServerAddress, iServerPort);
		final ODistributedServerNode node;

		lock.acquireExclusiveLock();

		try {
			if (nodes.containsKey(key)) {
				// ALREADY REGISTERED, MAYBE IT WAS DISCONNECTED. INVOKE THE RECONNECTION
				node = nodes.get(key);
			} else {
				node = new ODistributedServerNode(this, iServerAddress, iServerPort);
				nodes.put(key, node);
			}

		} finally {
			lock.releaseExclusiveLock();
		}

		OLogManager.instance().warn(this, "Discovered new distributed server node %s. Trying to connect...", key);

		try {
			node.connect(networkTimeoutNode);
			node.startSynchronization();
		} catch (IOException e) {
			OLogManager.instance().error(this, "Can't connect to  distributed server node: %s:%d", node.networkAddress, node.networkPort);
		}
	}

	/**
	 * Handle the failure of a node
	 */
	public void handleNodeFailure(final ODistributedServerNode node) {
		// ERROR
		OLogManager.instance().warn(this, "Remote server node %s:%d seems down, retrying to connect...", node.networkAddress,
				node.networkPort);

		// RETRY TO CONNECT
		try {
			node.connect(networkTimeoutNode);
		} catch (IOException e) {
			// IO ERROR: THE NODE SEEMD ALWAYS MORE DOWN: START TO COLLECT DATA FOR IT WAITING FOR A FUTURE RE-CONNECTION
			OLogManager.instance().warn(this, "Remote server node %s:%d is down, set it as DISCONNECTED and start to buffer changes",
					node.networkAddress, node.networkPort);

			node.setAsTemporaryDisconnected(serverOutSynchMaxBuffers);
		}
	}

	/**
	 * Became the cluster leader
	 * 
	 * @param iForce
	 */
	public void becameLeader(final boolean iForce) {
		synchronized (lock) {
			if (discoveryListener != null)
				// I'M ALREADY THE LEADER, DO NOTHING
				return;

			if (iForce)
				leaderConnection = null;
			else if (leaderConnection != null)
				// I'M NOT THE LEADER CAUSE I WAS BEEN CONNECTED BY THE LEADER
				return;

			if (leaderCheckerTask != null)
				// STOP THE CHECK OF HEART-BEAT
				leaderCheckerTask.cancel();

			if (clusterConfiguration == null)
				clusterConfiguration = createDatabaseConfiguration();

			// NO NODE HAS JOINED: BECAME THE LEADER AND LISTEN FOR OTHER NODES
			discoveryListener = new ODistributedServerDiscoveryListener(this, distributedNetworkListener);

			// START HEARTBEAT FOR CONNECTIONS
			Orient.getTimer().schedule(new ODistributedServerNodeChecker(this), networkHeartbeatDelay, networkHeartbeatDelay);
		}
	}

	@Override
	public void onClientError(final OClientConnection iConnection, final Throwable iThrowable) {
		// handleNodeFailure(node);
	}

	/**
	 * Parse parameters and configure services.
	 */
	public void config(final OServer iServer, final OServerParameterConfiguration[] iParams) {
		server = iServer;

		try {
			name = "unknown";
			securityKey = null;
			networkMulticastAddress = InetAddress.getByName("235.1.1.1");
			networkMulticastPort = 2424;
			networkMulticastHeartbeat = 5000;
			networkTimeoutLeader = 3000;
			networkTimeoutNode = 5000;
			networkHeartbeatDelay = 5000;
			securityAlgorithm = "Blowfish";
			serverUpdateDelay = 0;
			serverOutSynchMaxBuffers = 300;
			byte[] tempSecurityKey = null;

			if (iParams != null)
				for (OServerParameterConfiguration param : iParams) {
					if ("name".equalsIgnoreCase(param.name))
						name = param.value;
					else if ("security.algorithm".equalsIgnoreCase(param.name))
						securityAlgorithm = param.value;
					else if ("security.key".equalsIgnoreCase(param.name))
						tempSecurityKey = OBase64Utils.decode(param.value);
					else if ("network.multicast.address".equalsIgnoreCase(param.name))
						networkMulticastAddress = InetAddress.getByName(param.value);
					else if ("network.multicast.port".equalsIgnoreCase(param.name))
						networkMulticastPort = Integer.parseInt(param.value);
					else if ("network.multicast.heartbeat".equalsIgnoreCase(param.name))
						networkMulticastHeartbeat = Integer.parseInt(param.value);
					else if ("network.timeout.leader".equalsIgnoreCase(param.name))
						networkTimeoutLeader = Integer.parseInt(param.value);
					else if ("network.timeout.connection".equalsIgnoreCase(param.name))
						networkTimeoutNode = Integer.parseInt(param.value);
					else if ("network.heartbeat.delay".equalsIgnoreCase(param.name))
						networkHeartbeatDelay = Integer.parseInt(param.value);
					else if ("server.update.delay".equalsIgnoreCase(param.name))
						serverUpdateDelay = Integer.parseInt(param.value);
					else if ("server.outsynch.maxbuffers".equalsIgnoreCase(param.name))
						serverOutSynchMaxBuffers = Integer.parseInt(param.value);
				}

			if (tempSecurityKey == null) {
				OLogManager.instance().info(this, "Generating Server security key and save it in configuration...");
				// GENERATE NEW SECURITY KEY
				securityKey = OSecurityManager.instance().generateKey(securityAlgorithm, 96);

				// CHANGE AND SAVE THE NEW CONFIGURATION
				for (OServerHandlerConfiguration handler : iServer.getConfiguration().handlers) {
					if (handler.clazz.equals(getClass().getName())) {
						handler.parameters = new OServerParameterConfiguration[iParams.length + 1];
						for (int i = 0; i < iParams.length; ++i) {
							handler.parameters[i] = iParams[i];
						}
						handler.parameters[iParams.length] = new OServerParameterConfiguration("security.key",
								OBase64Utils.encodeBytes(securityKey.getEncoded()));
					}
				}
				iServer.saveConfiguration();

			} else
				// CREATE IT FROM STRING REPRESENTATION
				securityKey = OSecurityManager.instance().createKey(securityAlgorithm, tempSecurityKey);

			distributedNetworkListener = server.getListenerByProtocol(ONetworkProtocolDistributed.class);
			if (distributedNetworkListener == null)
				OLogManager.instance().error(this,
						"Can't find a configured network listener with 'distributed' protocol. Can't start distributed node", null,
						OConfigurationException.class);

			id = distributedNetworkListener.getInboundAddr().getHostName() + ":" + distributedNetworkListener.getInboundAddr().getPort();

		} catch (Exception e) {
			throw new OConfigurationException("Can't configure OrientDB Server as Cluster Node", e);
		}
	}

	public ODistributedServerNode getNode(final String iNodeName) {
		try {
			lock.acquireSharedLock();

			final ODistributedServerNode node = nodes.get(iNodeName);
			if (node == null)
				throw new IllegalArgumentException("Node '" + iNodeName + "' is not configured");

			return node;

		} finally {
			lock.releaseSharedLock();
		}
	}

	public List<ODistributedServerNode> getNodeList() {
		try {
			lock.acquireSharedLock();

			return new ArrayList<ODistributedServerNode>(nodes.values());
		} finally {
			lock.releaseSharedLock();
		}
	}

	public void removeNode(final ODistributedServerNode iNode) {
		try {
			lock.acquireExclusiveLock();

			OLogManager.instance().warn(this, "Removed server node %s:%d from distributed cluster", iNode.networkAddress,
					iNode.networkPort);

			nodes.remove(iNode.toString());

		} finally {
			lock.releaseExclusiveLock();
		}
	}

	public int getServerUpdateDelay() {
		return serverUpdateDelay;
	}

	/**
	 * Tells if there is a distributed configuration active right now.
	 */
	public boolean isDistributedConfiguration() {
		return !nodes.isEmpty();
	}

	public String getName() {
		return name;
	}

	/**
	 * Distributed the request to all the configured nodes. Each node has the responsibility to bring the message early (synch-mode)
	 * or using an asynchronous queue.
	 */
	public void distributeRequest(final OTransactionEntry<ORecordInternal<?>> iTransactionEntry) {
		lock.acquireSharedLock();

		try {
			if (nodes != null) {
				for (ODistributedServerNode node : nodes.values()) {
					node.sendRequest(iTransactionEntry);
				}
			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			lock.releaseSharedLock();
		}

	}

	public int getNetworkHeartbeatDelay() {
		return networkHeartbeatDelay;
	}

	public long getLastHeartBeat() {
		return lastHeartBeat;
	}

	public void updateHeartBeatTime() {
		this.lastHeartBeat = System.currentTimeMillis();
	}

	public ODocument getClusterConfiguration() {
		return clusterConfiguration;
	}

	public String getId() {
		return id;
	}

	private static String getNodeName(final String iServerAddress, final int iServerPort) {
		return iServerAddress + ":" + iServerPort;
	}

	private ODocument createDatabaseConfiguration() {
		clusterConfiguration = new ODocument();

		clusterConfiguration.field("servers", new ODocument(getId(), new ODocument("update-delay", getServerUpdateDelay())));
		clusterConfiguration.field("clusters", new ODocument("*", new ODocument("owner", getId())));

		return clusterConfiguration;
	}
}