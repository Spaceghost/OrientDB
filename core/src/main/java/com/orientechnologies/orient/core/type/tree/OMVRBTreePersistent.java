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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orientechnologies.common.collection.OMVRBTree;
import com.orientechnologies.common.collection.OMVRBTreeEntry;
import com.orientechnologies.common.collection.OMVRBTreeEventListener;
import com.orientechnologies.common.concur.resource.OSharedResourceExternal;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.profiler.OProfiler;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.exception.OSerializationException;
import com.orientechnologies.orient.core.exception.OStorageException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ORecordBytes;
import com.orientechnologies.orient.core.record.impl.ORecordBytesLazy;
import com.orientechnologies.orient.core.serialization.OMemoryInputStream;
import com.orientechnologies.orient.core.serialization.OMemoryOutputStream;
import com.orientechnologies.orient.core.serialization.OSerializableStream;
import com.orientechnologies.orient.core.serialization.serializer.record.OSerializationThreadLocal;
import com.orientechnologies.orient.core.serialization.serializer.stream.OStreamSerializer;
import com.orientechnologies.orient.core.serialization.serializer.stream.OStreamSerializerFactory;

/**
 * Persistent based MVRB-Tree implementation. The difference with the class OMVRBTreePersistent is the level. In facts this class
 * works directly at the storage level, while the other at database level. This class is used for Logical Clusters. It can'be
 * transactional. It uses the entryPoints linked list to get the best entry point for searching a node.
 * 
 */
@SuppressWarnings("serial")
public abstract class OMVRBTreePersistent<K, V> extends OMVRBTree<K, V> implements OMVRBTreeEventListener<K, V>,
		OSerializableStream {
	protected int																						optimizeThreshold;

	protected OSharedResourceExternal												lock						= new OSharedResourceExternal();

	protected OStreamSerializer															keySerializer;
	protected OStreamSerializer															valueSerializer;

	protected final Set<OMVRBTreeEntryPersistent<K, V>>			recordsToCommit	= new HashSet<OMVRBTreeEntryPersistent<K, V>>();
	protected final OMemoryOutputStream											entryRecordBuffer;

	protected final String																	clusterName;
	protected ORecordBytesLazy															record;
	protected String																				fetchPlan;
	protected volatile int																	usageCounter		= 0;

	// STORES IN MEMORY DIRECT REFERENCES TO PORTION OF THE TREE
	protected int																						entryPointsSize;
	protected float																					optimizeEntryPointsFactor;
	protected volatile List<OMVRBTreeEntryPersistent<K, V>>	entryPoints			= new ArrayList<OMVRBTreeEntryPersistent<K, V>>(
																																							entryPointsSize);
	protected List<OMVRBTreeEntryPersistent<K, V>>					newEntryPoints	= new ArrayList<OMVRBTreeEntryPersistent<K, V>>(
																																							entryPointsSize);

	protected Map<ORID, OMVRBTreeEntryPersistent<K, V>>			cache						= new HashMap<ORID, OMVRBTreeEntryPersistent<K, V>>();

	public OMVRBTreePersistent(final String iClusterName, final ORID iRID) {
		this(iClusterName, null, null);
		record.setIdentity(iRID.getClusterId(), iRID.getClusterPosition());
		config();
	}

	public OMVRBTreePersistent(String iClusterName, final OStreamSerializer iKeySerializer, final OStreamSerializer iValueSerializer) {
		// MINIMIZE I/O USING A LARGER PAGE THAN THE DEFAULT USED IN MEMORY
		super(1024, 0.7f);
		config();

		clusterName = iClusterName;
		record = new ORecordBytesLazy(this);

		keySerializer = iKeySerializer;
		valueSerializer = iValueSerializer;

		entryRecordBuffer = new OMemoryOutputStream(getPageSize() * 15);

		setListener(this);
	}

	public abstract OMVRBTreePersistent<K, V> load() throws IOException;

	public abstract OMVRBTreePersistent<K, V> save() throws IOException;

	/**
	 * Lazy loads a node.
	 */
	protected abstract OMVRBTreeEntryPersistent<K, V> loadEntry(OMVRBTreeEntryPersistent<K, V> iParent, ORID iRecordId)
			throws IOException;

	@Override
	public void clear() {
		final long timer = OProfiler.getInstance().startChrono();
		lock.acquireExclusiveLock();

		try {
			if (root != null) {
				((OMVRBTreeEntryPersistent<K, V>) root).delete();
				super.clear();
				getListener().signalTreeChanged(this);
			}

			recordsToCommit.clear();
			usageCounter = 0;
			entryPoints.clear();
			cache.clear();

		} catch (IOException e) {
			OLogManager.instance().error(this, "Error on deleting the tree: " + record.getIdentity(), e, OStorageException.class);
		} finally {

			lock.releaseExclusiveLock();
			OProfiler.getInstance().stopChrono("OMVRBTreePersistent.clear", timer);
		}
	}

	/**
	 * Unload all the in-memory nodes. This is called on transaction rollback.
	 */
	public void unload() {
		final long timer = OProfiler.getInstance().startChrono();
		lock.acquireExclusiveLock();

		try {
			// DISCONNECT ALL THE NODES
			for (OMVRBTreeEntryPersistent<K, V> entryPoint : entryPoints)
				entryPoint.disconnect(true);
			entryPoints.clear();

			recordsToCommit.clear();
			cache.clear();
			root = null;

			usageCounter = 0;

			load();

		} catch (IOException e) {
			OLogManager.instance().error(this, "Error on unload the tree: " + record.getIdentity(), e, OStorageException.class);
		} finally {

			lock.releaseExclusiveLock();
			OProfiler.getInstance().stopChrono("OMVRBTreePersistent.unload", timer);
		}
	}

	/**
	 * Optimize the tree memory consumption by keeping part of nodes as entry points and clearing all the rest.
	 */
	public void optimize() {
		usageCounter = 0;

		final long timer = System.currentTimeMillis();// OProfiler.getInstance().startChrono();

		lock.acquireExclusiveLock();

		try {
			if (root == null)
				return;

			OLogManager.instance().debug(this, "Starting optimization of RB+Tree...");

			// printInMemoryStructure();

			OMVRBTreeEntryPersistent<K, V> pRoot = (OMVRBTreeEntryPersistent<K, V>) root;

			final int depth = pRoot.getMaxDepthInMemory();

			// RECONFIG IT TO CATCH CHANGED VALUES
			config();

			if (depth < entryPointsSize * optimizeEntryPointsFactor)
				// UNDER THRESHOLD AVOID TO OPTIMIZE
				return;

			// System.out.printf("\nOptimizing: total items %d, root is %s", size(), pRoot.toString());

			pRoot.checkToDisconnect((int) (entryPointsSize * optimizeEntryPointsFactor));

			if (isRuntimeCheckEnabled()) {
				for (OMVRBTreeEntryPersistent<K, V> entryPoint : entryPoints)
					for (OMVRBTreeEntryPersistent<K, V> e = (OMVRBTreeEntryPersistent<K, V>) entryPoint.getFirstInMemory(); e != null; e = e
							.getNextInMemory())
						e.checkEntryStructure();
			}

		} finally {
			// System.out.println("End of optimization.");
			// printInMemoryStructure();

			if (isRuntimeCheckEnabled()) {
				if (entryPoints.size() > 0)
					for (OMVRBTreeEntryPersistent<K, V> entryPoint : entryPoints)
						checkTreeStructure(entryPoint.getFirstInMemory());
				else
					checkTreeStructure(root);
			}

			lock.releaseExclusiveLock();
			OProfiler.getInstance().stopChrono("OMVRBTreePersistent.optimize", timer);

			if (OLogManager.instance().isDebugEnabled())
				OLogManager.instance().debug(this, "Optimization completed in %d ms\n", System.currentTimeMillis() - timer);

			usageCounter = 0;
		}
	}

	@Override
	public V put(final K key, final V value) {
		final long timer = OProfiler.getInstance().startChrono();

		updateUsageCounter();

		lock.acquireExclusiveLock();

		try {
			final V v = internalPut(key, value);
			commitChanges(null);
			return v;
		} finally {

			lock.releaseExclusiveLock();
			OProfiler.getInstance().stopChrono("OMVRBTreePersistent.put", timer);
		}
	}

	@Override
	public void putAll(final Map<? extends K, ? extends V> map) {
		final long timer = OProfiler.getInstance().startChrono();

		updateUsageCounter();

		lock.acquireExclusiveLock();

		try {
			for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
				internalPut(entry.getKey(), entry.getValue());
			}
			commitChanges(null);

		} finally {

			lock.releaseExclusiveLock();
			OProfiler.getInstance().stopChrono("OMVRBTreePersistent.putAll", timer);
		}
	}

	@Override
	public V remove(final Object key) {
		final long timer = OProfiler.getInstance().startChrono();
		lock.acquireExclusiveLock();

		try {
			V v = super.remove(key);
			commitChanges(null);
			return v;
		} finally {

			lock.releaseExclusiveLock();
			OProfiler.getInstance().stopChrono("remove", timer);
		}
	}

	public void commitChanges(final ODatabaseRecord<?> iDatabase) {
		final long timer = OProfiler.getInstance().startChrono();
		lock.acquireExclusiveLock();

		try {
			if (recordsToCommit.size() > 0) {
				final List<OMVRBTreeEntryPersistent<K, V>> tmp = new ArrayList<OMVRBTreeEntryPersistent<K, V>>();

				while (recordsToCommit.iterator().hasNext()) {
					// COMMIT BEFORE THE NEW RECORDS (TO ASSURE RID IN RELATIONSHIPS)
					tmp.addAll(recordsToCommit);

					recordsToCommit.clear();

					for (OMVRBTreeEntryPersistent<K, V> node : tmp)
						if (node.record.isDirty()) {
							if (iDatabase != null)
								// REPLACE THE DATABASE WITH THE NEW ACQUIRED
								node.record.setDatabase(iDatabase);

							boolean wasNew = node.record.getIdentity().isNew();

							// CREATE THE RECORD
							node.save();

							if (wasNew) {
								// INSERT THE NEW NODE IN CACHE
								if (node.record.getIdentity().getClusterPosition() < -1)
									// INSERT A COPY TO PREVENT CHANGES
									cache.put(node.record.getIdentity().copy(), node);
								else
									cache.put(node.record.getIdentity(), node);
							}
						}

					tmp.clear();
				}

				recordsToCommit.clear();
			}

			if (record.isDirty()) {
				// TREE IS CHANGED AS WELL
				if (iDatabase != null)
					// REPLACE THE DATABASE WITH THE NEW ACQUIRED
					record.setDatabase(iDatabase);

				save();
			}

		} catch (IOException e) {
			OLogManager.instance().exception("Error on saving the tree", e, OStorageException.class);

		} finally {

			lock.releaseExclusiveLock();
			OProfiler.getInstance().stopChrono("OMVRBTreePersistent.commitChanges", timer);
		}
	}

	public OSerializableStream fromStream(final byte[] iStream) throws OSerializationException {
		final long timer = OProfiler.getInstance().startChrono();

		final ORID rootRid = new ORecordId();

		try {
			final OMemoryInputStream stream = new OMemoryInputStream(iStream);

			rootRid.fromStream(stream.getAsByteArrayFixed(ORecordId.PERSISTENT_SIZE));

			size = stream.getAsInteger();
			lastPageSize = stream.getAsShort();

			serializerFromStream(stream);

			// LOAD THE ROOT OBJECT AFTER ALL
			if (rootRid.isValid())
				root = loadEntry(null, rootRid);

			return this;

		} catch (Exception e) {

			OLogManager.instance().error(this, "Error on unmarshalling OMVRBTreePersistent object from record: %s", e,
					OSerializationException.class, rootRid);

		} finally {
			OProfiler.getInstance().stopChrono("OMVRBTreePersistent.fromStream", timer);
		}
		return this;
	}

	public byte[] toStream() throws OSerializationException {
		final long timer = OProfiler.getInstance().startChrono();

		// CHECK IF THE RECORD IS PENDING TO BE MARSHALLED
		final Integer identityRecord = System.identityHashCode(record);
		final Set<Integer> marshalledRecords = OSerializationThreadLocal.INSTANCE.get();
		if (marshalledRecords.contains(identityRecord)) {
			// ALREADY IN STACK, RETURN EMPTY
			return new byte[] {};
		} else
			marshalledRecords.add(identityRecord);

		OMemoryOutputStream stream = new OMemoryOutputStream();

		try {
			if (root != null) {
				OMVRBTreeEntryPersistent<K, V> pRoot = (OMVRBTreeEntryPersistent<K, V>) root;
				if (pRoot.record.getIdentity().isNew()) {
					// FIRST TIME: SAVE IT
					pRoot.save();
				}

				stream.addAsFixed(pRoot.record.getIdentity().toStream());
			} else
				stream.addAsFixed(ORecordId.EMPTY_RECORD_ID_STREAM);

			stream.add(size);
			stream.add((short) lastPageSize);

			stream.add(keySerializer.getName());
			stream.add(valueSerializer.getName());

			record.fromStream(stream.getByteArray());
			return record.toStream();

		} catch (IOException e) {
			throw new OSerializationException("Error on marshalling RB+Tree", e);
		} finally {
			marshalledRecords.remove(identityRecord);

			OProfiler.getInstance().stopChrono("OMVRBTreePersistent.toStream", timer);
		}
	}

	public void signalTreeChanged(final OMVRBTree<K, V> iTree) {
		record.setDirty();
	}

	public void signalNodeChanged(final OMVRBTreeEntry<K, V> iNode) {
		recordsToCommit.add((OMVRBTreeEntryPersistent<K, V>) iNode);
	}

	@Override
	public int hashCode() {
		final ORID rid = record.getIdentity();
		return rid == null ? 0 : rid.hashCode();
	}

	public ORecordBytes getRecord() {
		return record;
	}

	protected void adjustPageSize() {
		// int newPageSize = (int) (size * 0.2 / 100);
		// if (newPageSize > lastPageSize)
		// lastPageSize = newPageSize;
	}

	@Override
	public V get(final Object iKey) {
		updateUsageCounter();

		lock.acquireSharedLock();

		try {
			return super.get(iKey);

		} finally {
			lock.releaseSharedLock();
		}
	}

	public V get(final Object iKey, final String iFetchPlan) {
		fetchPlan = iFetchPlan;
		return get(iKey);
	}

	@Override
	public boolean containsKey(final Object key) {
		updateUsageCounter();

		lock.acquireSharedLock();

		try {
			return super.containsKey(key);

		} finally {
			lock.releaseSharedLock();
		}
	}

	@Override
	public boolean containsValue(final Object value) {
		updateUsageCounter();

		lock.acquireSharedLock();

		try {
			return super.containsValue(value);

		} finally {
			lock.releaseSharedLock();
		}
	}

	@Override
	public Set<java.util.Map.Entry<K, V>> entrySet() {
		updateUsageCounter();

		lock.acquireSharedLock();

		try {
			return super.entrySet();

		} finally {
			lock.releaseSharedLock();
		}
	}

	@Override
	public Set<K> keySet() {
		updateUsageCounter();

		lock.acquireSharedLock();

		try {
			return super.keySet();

		} finally {
			lock.releaseSharedLock();
		}
	}

	@Override
	public Collection<V> values() {
		updateUsageCounter();

		lock.acquireSharedLock();

		try {
			return super.values();

		} finally {
			lock.releaseSharedLock();
		}
	}

	public String getClusterName() {
		return clusterName;
	}

	public String getFetchPlan() {
		return fetchPlan;
	}

	public void setFetchPlan(String fetchPlan) {
		this.fetchPlan = fetchPlan;
	}

	public int getOptimizeThreshold() {
		return optimizeThreshold;
	}

	public void setOptimizeThreshold(int optimizeThreshold) {
		this.optimizeThreshold = optimizeThreshold;
	}

	public int getEntryPointSize() {
		return entryPointsSize;
	}

	public void setEntryPointSize(int entryPointSize) {
		this.entryPointsSize = entryPointSize;
	}

	@Override
	public String toString() {

		final StringBuilder buffer = new StringBuilder();
		buffer.append("size=");
		buffer.append(size);

		if (size > 0) {
			final int currPageIndex = pageIndex;
			buffer.append(" ");
			buffer.append(getFirstEntry().getFirstKey());
			buffer.append("-");
			buffer.append(getLastEntry().getLastKey());
			pageIndex = currPageIndex;
		}

		return buffer.toString();
	}

	private V internalPut(final K key, final V value) {
		ORecord<?> rec;

		if (key instanceof ORecord<?>) {
			// RECORD KEY: ASSURE IT'S PERSISTENT TO AVOID STORING INVALID RIDs
			rec = (ORecord<?>) key;
			if (!rec.getIdentity().isValid())
				rec.save();
		}

		if (value instanceof ORecord<?>) {
			// RECORD VALUE: ASSURE IT'S PERSISTENT TO AVOID STORING INVALID RIDs
			rec = (ORecord<?>) value;
			if (!rec.getIdentity().isValid())
				rec.save();
		}

		return super.put(key, value);
	}

	public Object getKeySerializer() {
		return keySerializer;
	}

	public Object getValueSerializer() {
		return valueSerializer;
	}

	/**
	 * Updates the usage counter and check if it's higher than the configured threshold. In this case executes the optimization and
	 * reset the usage counter.
	 */
	protected void updateUsageCounter() {
		usageCounter++;
		if (optimizeThreshold > 0 && usageCounter > optimizeThreshold) {
			optimize();
		}
	}

	/**
	 * Returns the best entry point to start the search.
	 */
	@SuppressWarnings("unchecked")
	@Override
	protected OMVRBTreeEntry<K, V> getBestEntryPoint(final Object iKey) {
		final Comparable<? super K> key = (Comparable<? super K>) iKey;

		if (entryPoints.size() == 0)
			// TREE EMPTY: RETURN ROOT
			return root;

		// SEARCH THE BEST KEY
		OMVRBTreeEntryPersistent<K, V> e;
		int entryPointSize = entryPoints.size();
		int cmp;
		OMVRBTreeEntryPersistent<K, V> bestNode = null;
		if (entryPointSize < OMVRBTreeEntry.BINARY_SEARCH_THRESHOLD) {
			// LINEAR SEARCH
			for (int i = 0; i < entryPointSize; ++i) {
				e = entryPoints.get(i);

				if (e.serializedKeys == null) {
					// CLEAN WRONG ENTRY (WHY THEY ARE WRONG?)
					OLogManager.instance().error(this, "Found wrong entrypoint in position %d", i);
					entryPoints.remove(i);
					--i;
					continue;
				}

				cmp = key.compareTo(e.getFirstKey());
				if (cmp < 0) {
					// RETURN THE PREVIOUS ONE OF CURRENT IF IT'S NULL
					return bestNode != null ? bestNode : e;
				} else if (cmp >= 0 && key.compareTo(e.getLastKey()) <= 0)
					// PERFECT MATCH, VERY LUCKY: RETURN THE CURRENT = 0 READS
					return e;

				// SET THE CURRENT AS BEST NODE
				bestNode = e;
			}
		} else {
			// BINARY SEARCH
			int low = 0;
			int high = entryPointSize - 1;
			int mid = 0;

			while (low <= high) {
				mid = (low + high) >>> 1;
				e = entryPoints.get(mid);

				if (e.serializedKeys == null) {
					// CLEAN WRONG ENTRY (WHY THEY ARE WRONG?)
					OLogManager.instance().error(this, "Found wrong entrypoint in position %d", mid);
					entryPoints.remove(mid);
					low = 0;
					entryPointSize = entryPoints.size();
					high = entryPointSize - 1;
					continue;
				}

				cmp = key.compareTo(e.getFirstKey());

				if (cmp >= 0 && key.compareTo(e.getLastKey()) <= 0)
					// PERFECT MATCH, VERY LUCKY: RETURN THE CURRENT = 0 READS
					return e;

				if (low == high)
					break;

				if (cmp > 0)
					low = mid + 1;
				else
					high = mid;

				// SET THE CURRENT AS BEST NODE
				bestNode = e;
			}

			if (mid > 0 && key.compareTo(bestNode.getFirstKey()) < 0)
				// GET THE PREVIOUS ONE
				bestNode = entryPoints.get(mid - 1);
		}

		// RETURN THE LATEST ONE
		return bestNode;
	}

	/**
	 * Remove an entry point from the list
	 */
	void removeEntryPoint(final OMVRBTreeEntryPersistent<K, V> iEntry) {
		for (int i = 0; i < entryPoints.size(); ++i)
			if (entryPoints.get(i) == iEntry) {
				entryPoints.remove(i);
				break;
			}
	}

	/**
	 * Returns the first Entry in the OMVRBTree (according to the OMVRBTree's key-sort function). Returns null if the OMVRBTree is
	 * empty.
	 */
	@Override
	protected OMVRBTreeEntry<K, V> getFirstEntry() {
		if (entryPoints.size() > 0) {
			// FIND THE FIRST ELEMENT STARTING FROM THE FIRST NODE
			OMVRBTreeEntryPersistent<K, V> e = entryPoints.get(0);

			while (e.getLeft() != null) {
				e = (OMVRBTreeEntryPersistent<K, V>) e.getLeft();
			}
			return e;
		}

		return super.getFirstEntry();
	}

	// private void printInMemoryStructure() {
	// System.out.println("* Entrypoints (" + entryPoints.size() + "), in cache=" + cache.size() + ": *");
	// for (OMVRBTreeEntryPersistent<K, V> entryPoint : entryPoints)
	// printInMemoryStructure(entryPoint);
	// }

	@Override
	protected void setRoot(final OMVRBTreeEntry<K, V> iRoot) {
		if (iRoot == root)
			return;

		super.setRoot(iRoot);
		if (listener != null)
			listener.signalTreeChanged(this);
	}

	protected void config() {
		lastPageSize = OGlobalConfiguration.MVRBTREE_NODE_PAGE_SIZE.getValueAsInteger();
		pageLoadFactor = OGlobalConfiguration.MVRBTREE_LOAD_FACTOR.getValueAsFloat();
		optimizeThreshold = OGlobalConfiguration.MVRBTREE_OPTIMIZE_THRESHOLD.getValueAsInteger();
		entryPointsSize = OGlobalConfiguration.MVRBTREE_ENTRYPOINTS.getValueAsInteger();
		optimizeEntryPointsFactor = OGlobalConfiguration.MVRBTREE_OPTIMIZE_ENTRYPOINTS_FACTOR.getValueAsFloat();
	}

	protected void serializerFromStream(final OMemoryInputStream stream) throws IOException {
		keySerializer = OStreamSerializerFactory.get(stream.getAsString());
		valueSerializer = OStreamSerializerFactory.get(stream.getAsString());
	}

}
