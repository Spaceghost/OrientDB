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
package com.orientechnologies.orient.core.type.tree;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.db.ODatabaseListener;
import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.index.OIndexException;
import com.orientechnologies.orient.core.serialization.serializer.stream.OStreamSerializer;

/**
 * Collects changes all together and save them following the selected strategy. By default the map is saved automatically every
 * "maxUpdatesBeforeSave" updates (=500). "maxUpdatesBeforeSave" is configurable: 0 means no automatic save, 1 means non-lazy map
 * (save each operation) and > 1 is lazy.
 * 
 * @author Luca Garulli
 */
@SuppressWarnings("serial")
public class OMVRBTreeDatabaseLazySave<K, V> extends OMVRBTreeDatabase<K, V> implements ODatabaseListener {
	protected int	maxUpdatesBeforeSave;
	protected int	updates	= 0;

	public OMVRBTreeDatabaseLazySave(ODatabaseRecord<?> iDatabase, ORID iRID) {
		super(iDatabase, iRID);
		init(iDatabase);
	}

	public OMVRBTreeDatabaseLazySave(ODatabaseRecord<?> iDatabase, String iClusterName, OStreamSerializer iKeySerializer,
			OStreamSerializer iValueSerializer) {
		super(iDatabase, iClusterName, iKeySerializer, iValueSerializer);
		init(iDatabase);
	}

	/**
	 * Do nothing since all the changes will be committed expressly at lazySave() time or on closing.
	 */
	@Override
	public synchronized void commitChanges(final ODatabaseRecord<?> iDatabase) {
		if (maxUpdatesBeforeSave > 0 && ++updates >= maxUpdatesBeforeSave) {
			lazySave();
			updates = 0;
		}
	}

	@Override
	public void clear() {
		super.clear();
		lazySave();
	}

	public void lazySave() {
		super.commitChanges(database);
		optimize();
	}

	public void onOpen(final ODatabase iDatabase) {
	}

	public void onBeforeTxBegin(ODatabase iDatabase) {
	}

	public void onTxRollback(ODatabase iDatabase) {
		cache.clear();
		entryPoints.clear();
		try {
			if (root != null)
				((OMVRBTreeEntryDatabase<K, V>) root).load();
		} catch (IOException e) {
			throw new OIndexException("Error on loading root node");
		}
	}

	public void onBeforeTxCommit(final ODatabase iDatabase) {
		super.commitChanges(database);
	}

	public void onAfterTxCommit(final ODatabase iDatabase) {
		if (cache.keySet().size() == 0)
			return;

		// FIX THE CACHE CONTENT WITH FINAL RECORD-IDS
		final Set<ORID> keys = new HashSet<ORID>(cache.keySet());
		OMVRBTreeEntryDatabase<K, V> entry;
		for (ORID rid : keys) {
			if (rid.getClusterPosition() < -1) {
				// FIX IT IN CACHE
				entry = (OMVRBTreeEntryDatabase<K, V>) cache.get(rid);

				// OVERWRITE IT WITH THE NEW RID
				cache.put(entry.record.getIdentity(), entry);
				cache.remove(rid);
			}
		}
	}

	/**
	 * Assure to save all the data without the optimization.
	 */
	public void onClose(final ODatabase iDatabase) {
		super.commitChanges(database);
		cache.clear();
		entryPoints.clear();
		root = null;
	}

	/**
	 * Returns the maximum updates to save the map persistently.
	 * 
	 * @return 0 means no automatic save, 1 means non-lazy map (save each operation) and > 1 is lazy.
	 */
	public int getMaxUpdatesBeforeSave() {
		return maxUpdatesBeforeSave;
	}

	/**
	 * Sets the maximum updates to save the map persistently.
	 * 
	 * @param iValue
	 *          0 means no automatic save, 1 means non-lazy map (save each operation) and > 1 is lazy.
	 */
	public void setMaxUpdatesBeforeSave(final int iValue) {
		this.maxUpdatesBeforeSave = iValue;
	}

	private void init(ODatabaseRecord<?> iDatabase) {
		iDatabase.registerListener(this);
	}

	@Override
	protected void config() {
		super.config();
		maxUpdatesBeforeSave = OGlobalConfiguration.MVRBTREE_LAZY_UPDATES.getValueAsInteger();
	}

	public void onCreate(ODatabase iDatabase) {
	}

	public void onDelete(ODatabase iDatabase) {
	}
}
