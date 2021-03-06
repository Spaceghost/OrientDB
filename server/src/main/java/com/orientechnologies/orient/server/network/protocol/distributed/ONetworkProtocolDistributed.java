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
package com.orientechnologies.orient.server.network.protocol.distributed;

import java.io.IOException;

import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.command.OCommandOutputListener;
import com.orientechnologies.orient.core.db.tool.ODatabaseImport;
import com.orientechnologies.orient.core.exception.OConfigurationException;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.storage.impl.local.OStorageLocal;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinaryInputStream;
import com.orientechnologies.orient.enterprise.channel.distributed.OChannelDistributedProtocol;
import com.orientechnologies.orient.server.OServerMain;
import com.orientechnologies.orient.server.handler.distributed.ODistributedServerManager;
import com.orientechnologies.orient.server.handler.distributed.ODistributedServerNodeRemote;
import com.orientechnologies.orient.server.network.protocol.binary.ONetworkProtocolBinary;

/**
 * Extends binary protocol to include cluster commands.
 * 
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 * 
 */
public class ONetworkProtocolDistributed extends ONetworkProtocolBinary implements OCommandOutputListener {
	private ODistributedServerManager	manager;

	public ONetworkProtocolDistributed() {
		super("Distributed-DB");

		manager = OServerMain.server().getHandler(ODistributedServerManager.class);
		if (manager == null)
			throw new OConfigurationException(
					"Can't find a ODistributedServerDiscoveryManager instance registered as handler. Check the server configuration in the handlers section.");
	}

	@Override
	protected void parseCommand() throws IOException {
		if (lastRequestType < 80) {
			// BINARY REQUESTS
			super.parseCommand();
			return;
		}

		// DISTRIBUTED SERVER REQUESTS
		switch (lastRequestType) {

		case OChannelDistributedProtocol.REQUEST_DISTRIBUTED_HEARTBEAT:
			data.commandInfo = "Keep-alive";
			manager.updateHeartBeatTime();

			sendOk(lastClientTxId);

			// SEND DB VERSION BACK
			channel.writeLong(connection.database == null ? 0 : connection.database.getStorage().getVersion());
			break;

		case OChannelDistributedProtocol.REQUEST_DISTRIBUTED_CONNECT: {
			data.commandInfo = "Cluster connection";
			manager.receivedLeaderConnection(this);

			final String dbName = channel.readString();
			if (dbName != null) {
				// REOPEN PREVIOUSLY MANAGED DATABASE
				connection.database = openDatabase(dbName, channel.readString(), channel.readString());
			}

			sendOk(lastClientTxId);

			channel.writeLong(connection.database != null ? connection.database.getStorage().getVersion() : 0);
			break;
		}

		case OChannelDistributedProtocol.REQUEST_DISTRIBUTED_DB_SHARE_SENDER: {
			data.commandInfo = "Share the database to a remote server";

			final String dbName = channel.readString();
			final String dbUser = channel.readString();
			final String dbPassword = channel.readString();
			final String remoteServerName = channel.readString();
			final boolean synchronousMode = channel.readByte() == 1;

			checkServerAccess("database.share");

			connection.database = openDatabase(dbName, dbUser, dbPassword);

			final String engineName = connection.database.getStorage() instanceof OStorageLocal ? "local" : "memory";

			final ODistributedServerNodeRemote remoteServerNode = manager.getNode(remoteServerName);

			remoteServerNode.shareDatabase(connection.database, remoteServerName, dbUser, dbPassword, engineName, synchronousMode);

			sendOk(connection.id);

			manager.addServerInConfiguration(dbName, remoteServerName, engineName, synchronousMode);

			break;
		}

		case OChannelDistributedProtocol.REQUEST_DISTRIBUTED_DB_SHARE_RECEIVER: {
			data.commandInfo = "Received a shared database from a remote server to install";

			final String dbName = channel.readString();
			final String dbUser = channel.readString();
			final String dbPasswd = channel.readString();
			final String engineName = channel.readString();

			manager.setStatus(ODistributedServerManager.STATUS.SYNCHRONIZING);
			try {
				OLogManager.instance().info(this, "Received database '%s' to share on local server node", dbName);

				connection.database = getDatabaseInstance(dbName, engineName);

				if (connection.database.exists()) {
					OLogManager.instance().info(this, "Deleting existent database '%s'", connection.database.getName());
					connection.database.delete();
				}

				createDatabase(connection.database);

				if (connection.database.isClosed())
					connection.database.open(dbUser, dbPasswd);

				OLogManager.instance().info(this, "Importing database '%s' via streaming from remote server node...", dbName);

				new ODatabaseImport(connection.database, new OChannelBinaryInputStream(channel), this).importDatabase();

				OLogManager.instance().info(this, "Database imported correctly", dbName);

				sendOk(lastClientTxId);
				channel.writeLong(connection.database.getStorage().getVersion());
			} finally {
				manager.updateHeartBeatTime();
				manager.setStatus(ODistributedServerManager.STATUS.ONLINE);
			}
			break;
		}

		case OChannelDistributedProtocol.REQUEST_DISTRIBUTED_DB_CONFIG: {
			data.commandInfo = "Update db configuration from server node leader";
			
			final ODocument config = (ODocument) new ODocument().fromStream(channel.readBytes());
			manager.getClusterConfiguration(connection.database.getName(), config);

			OLogManager.instance().warn(this, "Changed distributed server configuration:\n%s", config.toJSON("indent:2"));

			sendOk(lastClientTxId);
			break;
		}

		default:
			super.parseCommand();
		}
	}

	public void onMessage(String iText) {
	}
}
