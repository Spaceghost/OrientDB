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
import java.util.Set;

import com.orientechnologies.common.collection.OMVRBTreeEntry;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.profiler.OProfiler;
import com.orientechnologies.orient.core.exception.OConfigurationException;
import com.orientechnologies.orient.core.exception.ODatabaseException;
import com.orientechnologies.orient.core.exception.OSerializationException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.record.impl.ORecordBytesLazy;
import com.orientechnologies.orient.core.serialization.OMemoryInputStream;
import com.orientechnologies.orient.core.serialization.OMemoryOutputStream;
import com.orientechnologies.orient.core.serialization.OSerializableStream;
import com.orientechnologies.orient.core.serialization.serializer.record.OSerializationThreadLocal;

/**
 * 
 * Serialized as:
 * <table>
 * <tr>
 * <td>FROM</td>
 * <td>TO</td>
 * <td>FIELD</td>
 * </tr>
 * <tr>
 * <td>00</td>
 * <td>02</td>
 * <td>PAGE SIZE</td>
 * </tr>
 * <tr>
 * <td>02</td>
 * <td>12</td>
 * <td>PARENT RID</td>
 * </tr>
 * <tr>
 * <td>12</td>
 * <td>22</td>
 * <td>LEFT RID</td>
 * </tr>
 * <tr>
 * <td>22</td>
 * <td>32</td>
 * <td>RIGHT RID</td>
 * </tr>
 * <tr>
 * <td>32</td>
 * <td>33</td>
 * <td>COLOR</td>
 * </tr>
 * <tr>
 * <td>33</td>
 * <td>35</td>
 * <td>SIZE</td>
 * </tr>
 * </table>
 * VARIABLE
 * 
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 * 
 * @param <K>
 * @param <V>
 */
@SuppressWarnings("unchecked")
public abstract class OMVRBTreeEntryPersistent<K, V> extends OMVRBTreeEntry<K, V> implements OSerializableStream {
	protected OMVRBTreePersistent<K, V>				pTree;

	byte[][]																	serializedKeys;
	byte[][]																	serializedValues;

	protected ORID														parentRid;
	protected ORID														leftRid;
	protected ORID														rightRid;

	public ORecordBytesLazy										record;

	protected OMVRBTreeEntryPersistent<K, V>	parent;
	protected OMVRBTreeEntryPersistent<K, V>	left;
	protected OMVRBTreeEntryPersistent<K, V>	right;

	/**
	 * Called on event of splitting an entry.
	 * 
	 * @param iParent
	 *          Parent node
	 * @param iPosition
	 *          Current position
	 * @param iLeft
	 */
	public OMVRBTreeEntryPersistent(final OMVRBTreeEntry<K, V> iParent, final int iPosition) {
		super(iParent, iPosition);
		pTree = (OMVRBTreePersistent<K, V>) tree;
		record = new ORecordBytesLazy(this);

		setParent(iParent);

		parentRid = new ORecordId();
		leftRid = new ORecordId();
		rightRid = new ORecordId();

		pageSize = pTree.getPageSize();

		// COPY ALSO THE SERIALIZED KEYS/VALUES
		serializedKeys = new byte[pageSize][];
		serializedValues = new byte[pageSize][];

		final OMVRBTreeEntryPersistent<K, V> p = (OMVRBTreeEntryPersistent<K, V>) iParent;

		System.arraycopy(p.serializedKeys, iPosition, serializedKeys, 0, size);
		System.arraycopy(p.serializedValues, iPosition, serializedValues, 0, size);

		markDirty();
	}

	/**
	 * Called upon unmarshalling.
	 * 
	 * @param iTree
	 *          Tree which belong
	 * @param iParent
	 *          Parent node if any
	 * @param iRecordId
	 *          Record to unmarshall
	 */
	public OMVRBTreeEntryPersistent(final OMVRBTreePersistent<K, V> iTree, final OMVRBTreeEntryPersistent<K, V> iParent,
			final ORID iRecordId) throws IOException {
		super(iTree);
		pTree = iTree;
		record = new ORecordBytesLazy(this);
		record.setIdentity((ORecordId) iRecordId);

		parent = (OMVRBTreeEntryPersistent<K, V>) iParent;
		parentRid = iParent == null ? ORecordId.EMPTY_RECORD_ID : parent.record.getIdentity();
	}

	public OMVRBTreeEntryPersistent(final OMVRBTreePersistent<K, V> iTree, final K key, final V value,
			final OMVRBTreeEntryPersistent<K, V> iParent) {
		super(iTree, key, value, iParent);
		pTree = iTree;

		parentRid = new ORecordId();
		leftRid = new ORecordId();
		rightRid = new ORecordId();

		record = new ORecordBytesLazy(this);

		pageSize = pTree.getPageSize();

		serializedKeys = new byte[pageSize][];
		serializedValues = new byte[pageSize][];

		markDirty();
	}

	protected abstract Object keyFromStream(final int iIndex) throws IOException;

	protected abstract Object valueFromStream(final int iIndex) throws IOException;

	public OMVRBTreeEntryPersistent<K, V> load() throws IOException {
		return this;
	}

	public OMVRBTreeEntryPersistent<K, V> save() throws IOException {
		return this;
	}

	public OMVRBTreeEntryPersistent<K, V> delete() throws IOException {
		pTree.removeEntryPoint(this);

		if (record.getIdentity().isValid())
			pTree.cache.remove(record.getIdentity());

		// DELETE THE NODE FROM THE PENDING RECORDS TO COMMIT
		for (OMVRBTreeEntryPersistent<K, V> node : pTree.recordsToCommit) {
			if (node.record.getIdentity().equals(record.getIdentity())) {
				pTree.recordsToCommit.remove(node);
				break;
			}
		}
		return this;
	}

	/**
	 * Disconnect the current node from others.
	 * 
	 * @param iForceDirty
	 *          Force disconnection also if the record it's dirty
	 */
	protected int disconnect(final boolean iForceDirty) {
		if (record.isDirty() && !iForceDirty)
			// DIRTY NODE
			return 0;

		if (pTree.cache.remove(record.getIdentity()) == null) {
			System.out.println("CACHE INVALID?");
		}

		int totalDisconnected = 1;

		if (this != tree.getRoot()) {
			// SPEED UP MEMORY CLAIM BY RESETTING INTERNAL FIELDS
			keys = null;
			values = null;
			serializedKeys = null;
			serializedValues = null;
			pTree = null;
			record = null;
		}

		// DISCONNECT FROM THE PARENT
		if (parent != null) {
			if (parent.left == this) {
				parent.left = null;
			} else {
				parent.right = null;
			}
			parent = null;
		}

		// DISCONNECT RECURSIVELY THE LEFT NODE
		if (left != null) {
			// DISCONNECT MYSELF FROM THE LEFT NODE
			left.parent = null;
			int disconnected = left.disconnect(iForceDirty);

			if (disconnected > 0) {
				totalDisconnected += disconnected;
				left = null;
			}
		}

		// DISCONNECT RECURSIVELY THE RIGHT NODE
		if (right != null) {
			// DISCONNECT MYSELF FROM THE RIGHT NODE
			right.parent = null;
			int disconnected = right.disconnect(iForceDirty);

			if (disconnected > 0) {
				totalDisconnected += disconnected;
				right = null;
			}
		}

		return totalDisconnected;
	}

	/**
	 * Clear links and current node only if it's not an entry point.
	 * 
	 * @param iForceDirty
	 * 
	 * @param iSource
	 */
	protected int checkToDisconnect(final int iDepthLevel) {
		if (record == null || record.isDirty())
			// DIRTY NODE OR IS ROOT
			return 0;

		int freed = 0;

		if (getDepthInMemory() >= iDepthLevel)
			freed = disconnect(false);
		else {
			if (left != null)
				freed += left.checkToDisconnect(iDepthLevel);
			if (right != null)
				freed += right.checkToDisconnect(iDepthLevel);
		}

		return freed;
	}

	public int getDepthInMemory() {
		int level = 0;
		OMVRBTreeEntryPersistent<K, V> entry = this;
		while (entry.parent != null) {
			level++;
			entry = (OMVRBTreeEntryPersistent<K, V>) entry.parent;
		}
		return level;
	}

	@Override
	public int getDepth() {
		int level = 0;
		OMVRBTreeEntryPersistent<K, V> entry = this;
		while (entry.getParent() != null) {
			level++;
			entry = (OMVRBTreeEntryPersistent<K, V>) entry.getParent();
		}
		return level;
	}

	@Override
	public OMVRBTreeEntry<K, V> getParent() {
		if (parentRid == null)
			return null;

		if (parent == null && parentRid.isValid()) {
			try {
				// System.out.println("Node " + record.getIdentity() + " is loading PARENT node " + parentRid + "...");

				// LAZY LOADING OF THE PARENT NODE
				parent = pTree.loadEntry(null, parentRid);

				checkEntryStructure();

				if (parent != null) {
					// if (!parent.record.isDirty())
					// parent.load();

					// TRY TO ASSIGN IT FOLLOWING THE RID
					if (parent.leftRid.isValid() && parent.leftRid.equals(record.getIdentity()))
						parent.left = this;
					else if (parent.rightRid.isValid() && parent.rightRid.equals(record.getIdentity()))
						parent.right = this;
					else {
						OLogManager.instance().error(this, "getParent: Can't assign node %s to parent. Nodes parent-left=%s, parent-right=%s",
								parentRid, parent.leftRid, parent.rightRid);
					}
				}

			} catch (IOException e) {
				OLogManager.instance().error(this, "getParent: Can't load the tree. The tree could be invalid.", e,
						ODatabaseException.class);
			}
		}
		return parent;
	}

	@Override
	public OMVRBTreeEntry<K, V> setParent(final OMVRBTreeEntry<K, V> iParent) {
		if (iParent != getParent()) {
			markDirty();

			this.parent = (OMVRBTreeEntryPersistent<K, V>) iParent;
			this.parentRid = iParent == null ? ORecordId.EMPTY_RECORD_ID : parent.record.getIdentity();

			if (parent != null) {
				if (parent.left == this && !parent.leftRid.equals(record.getIdentity()))
					parent.leftRid = record.getIdentity();
				if (parent.left != this && parent.leftRid.isValid() && parent.leftRid.equals(record.getIdentity()))
					parent.left = this;
				if (parent.right == this && !parent.rightRid.equals(record.getIdentity()))
					parent.rightRid = record.getIdentity();
				if (parent.right != this && parent.rightRid.isValid() && parent.rightRid.equals(record.getIdentity()))
					parent.right = this;
			}
		}
		return iParent;
	}

	@Override
	public OMVRBTreeEntry<K, V> getLeft() {
		if (left == null && leftRid.isValid()) {
			try {
				// System.out.println("Node " + record.getIdentity() + " is loading LEFT node " + leftRid + "...");

				// LAZY LOADING OF THE LEFT LEAF
				left = pTree.loadEntry(this, leftRid);

				checkEntryStructure();

			} catch (IOException e) {
				OLogManager.instance().error(this, "getLeft: Can't load the tree. The tree could be invalid.", e, ODatabaseException.class);
			}
		}
		return left;
	}

	@Override
	public void setLeft(final OMVRBTreeEntry<K, V> iLeft) {
		if (iLeft == left)
			return;

		left = (OMVRBTreeEntryPersistent<K, V>) iLeft;
		// if (left == null || !left.record.getIdentity().isValid() || !left.record.getIdentity().equals(leftRid)) {
		markDirty();
		this.leftRid = iLeft == null ? ORecordId.EMPTY_RECORD_ID : left.record.getIdentity();
		// }

		if (iLeft != null && iLeft.getParent() != this)
			iLeft.setParent(this);

		checkEntryStructure();
	}

	@Override
	public OMVRBTreeEntry<K, V> getRight() {
		if (rightRid.isValid() && right == null) {
			// LAZY LOADING OF THE RIGHT LEAF
			try {
				// System.out.println("Node " + record.getIdentity() + " is loading RIGHT node " + rightRid + "...");

				right = pTree.loadEntry(this, rightRid);

				checkEntryStructure();

			} catch (IOException e) {
				OLogManager.instance().error(this, "getRight: Can't load tree. The tree could be invalid.", e, ODatabaseException.class);
			}
		}
		return right;
	}

	@Override
	public OMVRBTreeEntry<K, V> setRight(final OMVRBTreeEntry<K, V> iRight) {
		if (iRight == right)
			return this;

		right = (OMVRBTreeEntryPersistent<K, V>) iRight;
		// if (right == null || !right.record.getIdentity().isValid() || !right.record.getIdentity().equals(rightRid)) {
		markDirty();
		rightRid = iRight == null ? ORecordId.EMPTY_RECORD_ID : right.record.getIdentity();
		// }

		if (iRight != null && iRight.getParent() != this)
			iRight.setParent(this);

		checkEntryStructure();

		return right;
	}

	public void checkEntryStructure() {
		if (!tree.isRuntimeCheckEnabled())
			return;

		if (parentRid == null)
			OLogManager.instance().error(this, "checkEntryStructure: Node %s has parentRid null!\n", this);
		if (leftRid == null)
			OLogManager.instance().error(this, "checkEntryStructure: Node %s has leftRid null!\n", this);
		if (rightRid == null)
			OLogManager.instance().error(this, "checkEntryStructure: Node %s has rightRid null!\n", this);

		if (this == left || record.getIdentity().isValid() && record.getIdentity().equals(leftRid))
			OLogManager.instance().error(this, "checkEntryStructure: Node %s has left that points to itself!\n", this);
		if (this == right || record.getIdentity().isValid() && record.getIdentity().equals(rightRid))
			OLogManager.instance().error(this, "checkEntryStructure: Node %s has right that points to itself!\n", this);
		if (left != null && left == right)
			OLogManager.instance().error(this, "checkEntryStructure: Node %s has left and right equals!\n", this);

		if (left != null) {
			if (!left.record.getIdentity().equals(leftRid))
				OLogManager.instance().error(this, "checkEntryStructure: Wrong left node loaded: " + leftRid);
			// if (left.parent != this)
			// OLogManager.instance().error(this, "checkEntryStructure: Left node is not correctly connected to the parent" + leftRid);
		}

		if (right != null) {
			if (!right.record.getIdentity().equals(rightRid))
				OLogManager.instance().error(this, "checkEntryStructure: Wrong right node loaded: " + rightRid);
			// if (right.parent != this)
			// OLogManager.instance().error(this, "checkEntryStructure: Right node is not correctly connected to the parent" + leftRid);
		}
	}

	@Override
	protected void copyFrom(final OMVRBTreeEntry<K, V> iSource) {
		markDirty();

		final OMVRBTreeEntryPersistent<K, V> source = (OMVRBTreeEntryPersistent<K, V>) iSource;

		parent = source.parent;
		left = source.left;
		right = source.right;

		parentRid = source.parentRid;
		leftRid = source.leftRid;
		rightRid = source.rightRid;

		serializedKeys = new byte[source.serializedKeys.length][];
		for (int i = 0; i < source.serializedKeys.length; ++i)
			serializedKeys[i] = source.serializedKeys[i];

		serializedValues = new byte[source.serializedValues.length][];
		for (int i = 0; i < source.serializedValues.length; ++i)
			serializedValues[i] = source.serializedValues[i];

		super.copyFrom(source);
	}

	@Override
	protected void insert(final int iPosition, final K key, final V value) {
		markDirty();

		if (iPosition < size) {
			System.arraycopy(serializedKeys, iPosition, serializedKeys, iPosition + 1, size - iPosition);
			System.arraycopy(serializedValues, iPosition, serializedValues, iPosition + 1, size - iPosition);
		}

		serializedKeys[iPosition] = null;
		serializedValues[iPosition] = null;

		super.insert(iPosition, key, value);
	}

	@Override
	protected void remove() {
		markDirty();

		final int index = tree.getPageIndex();

		if (index == size - 1) {
			// LAST ONE: JUST REMOVE IT
		} else if (index > -1) {
			// SHIFT LEFT THE VALUES
			System.arraycopy(serializedKeys, index + 1, serializedKeys, index, size - index - 1);
			System.arraycopy(serializedValues, index + 1, serializedValues, index, size - index - 1);
		}

		// FREE RESOURCES
		serializedKeys[size - 1] = null;
		serializedValues[size - 1] = null;

		super.remove();
	}

	/**
	 * Return the key. Keys are lazy loaded.
	 * 
	 * @param iIndex
	 * @return
	 */
	@Override
	public K getKeyAt(final int iIndex) {
		if (keys[iIndex] == null)
			try {
				OProfiler.getInstance().updateCounter("OMVRBTreeEntryP.unserializeKey", 1);

				keys[iIndex] = (K) pTree.keySerializer.fromStream(null, serializedKeys[iIndex]);
			} catch (IOException e) {

				OLogManager.instance().error(this, "Can't lazy load the key #" + iIndex + " in tree node " + this, e,
						OSerializationException.class);
			}

		return keys[iIndex];
	}

	@Override
	protected V getValueAt(final int iIndex) {
		if (values[iIndex] == null)
			try {
				OProfiler.getInstance().updateCounter("OMVRBTreeEntryP.unserializeValue", 1);

				values[iIndex] = (V) valueFromStream(iIndex);
			} catch (IOException e) {

				OLogManager.instance().error(this, "Can't lazy load the value #" + iIndex + " in tree node " + this, e,
						OSerializationException.class);
			}

		return values[iIndex];
	}

	/**
	 * Invalidate serialized Value associated in order to be re-marshalled on the next node storing.
	 */
	@Override
	public V setValue(final V value) {
		markDirty();

		V oldValue = super.setValue(value);
		serializedValues[tree.getPageIndex()] = null;
		return oldValue;
	}

	public int getMaxDepthInMemory() {
		return getMaxDepthInMemory(0);
	}

	private int getMaxDepthInMemory(final int iCurrDepthLevel) {
		int depth;

		if (left != null)
			// GET THE LEFT'S DEPTH LEVEL AS GOOD
			depth = left.getMaxDepthInMemory(iCurrDepthLevel + 1);
		else
			// GET THE CURRENT DEPTH LEVEL AS GOOD
			depth = iCurrDepthLevel;

		if (right != null) {
			int rightDepth = right.getMaxDepthInMemory(iCurrDepthLevel + 1);
			if (rightDepth > depth)
				depth = rightDepth;
		}

		return depth;
	}

	/**
	 * Returns the successor of the current Entry only by traversing the memory, or null if no such.
	 */
	public OMVRBTreeEntryPersistent<K, V> getNextInMemory() {
		OMVRBTreeEntryPersistent<K, V> t = this;
		OMVRBTreeEntryPersistent<K, V> p = null;

		if (t.right != null) {
			p = t.right;
			while (p.left != null)
				p = p.left;
		} else {
			p = t.parent;
			while (p != null && t == p.right) {
				t = p;
				p = p.parent;
			}
		}

		return p;
	}

	public final OSerializableStream fromStream(final byte[] iStream) throws OSerializationException {
		final long timer = OProfiler.getInstance().startChrono();

		final OMemoryInputStream buffer = new OMemoryInputStream(iStream);

		try {
			pageSize = buffer.getAsShort();

			parentRid = new ORecordId().fromStream(buffer.getAsByteArrayFixed(ORecordId.PERSISTENT_SIZE));
			leftRid = new ORecordId().fromStream(buffer.getAsByteArrayFixed(ORecordId.PERSISTENT_SIZE));
			rightRid = new ORecordId().fromStream(buffer.getAsByteArrayFixed(ORecordId.PERSISTENT_SIZE));

			color = buffer.getAsBoolean();
			init();
			size = buffer.getAsShort();

			if (size > pageSize)
				throw new OConfigurationException("Loaded index with page size setted to " + pageSize
						+ " while the loaded was built with: " + size);

			// UNCOMPACT KEYS SEPARATELY
			serializedKeys = new byte[pageSize][];
			for (int i = 0; i < size; ++i) {
				serializedKeys[i] = buffer.getAsByteArray();
			}

			// KEYS WILL BE LOADED LAZY
			keys = (K[]) new Object[pageSize];

			// UNCOMPACT VALUES SEPARATELY
			serializedValues = new byte[pageSize][];
			for (int i = 0; i < size; ++i) {
				serializedValues[i] = buffer.getAsByteArray();
			}

			// VALUES WILL BE LOADED LAZY
			values = (V[]) new Object[pageSize];

			return this;
		} catch (IOException e) {
			throw new OSerializationException("Can't unmarshall RB+Tree node", e);
		} finally {
			buffer.close();

			OProfiler.getInstance().stopChrono("OMVRBTreeEntryP.fromStream", timer);
		}
	}

	public final byte[] toStream() throws OSerializationException {
		// CHECK IF THE RECORD IS PENDING TO BE MARSHALLED
		final Integer identityRecord = System.identityHashCode(record);
		final Set<Integer> marshalledRecords = OSerializationThreadLocal.INSTANCE.get();
		if (marshalledRecords.contains(identityRecord)) {
			// ALREADY IN STACK, RETURN EMPTY
			return new byte[] {};
		} else
			marshalledRecords.add(identityRecord);

		if (parent != null && parentRid.isNew()) {
			// FORCE DIRTY
			parent.record.setDirty();

			((OMVRBTreeEntryDatabase<K, V>) parent).save();
			parentRid = parent.record.getIdentity();
			record.setDirty();
		}

		if (left != null && leftRid.isNew()) {
			// FORCE DIRTY
			left.record.setDirty();

			((OMVRBTreeEntryDatabase<K, V>) left).save();
			leftRid = left.record.getIdentity();
			record.setDirty();
		}

		if (right != null && rightRid.isNew()) {
			// FORCE DIRTY
			right.record.setDirty();

			((OMVRBTreeEntryDatabase<K, V>) right).save();
			rightRid = right.record.getIdentity();
			record.setDirty();
		}

		final long timer = OProfiler.getInstance().startChrono();

		OMemoryOutputStream stream = pTree.entryRecordBuffer;

		try {
			stream.add((short) pageSize);

			stream.addAsFixed(parentRid.toStream());
			stream.addAsFixed(leftRid.toStream());
			stream.addAsFixed(rightRid.toStream());

			stream.add(color);
			stream.add((short) size);

			serializeNewKeys();
			serializeNewValues();

			for (int i = 0; i < size; ++i)
				stream.add(serializedKeys[i]);

			for (int i = 0; i < size; ++i)
				stream.add(serializedValues[i]);

			stream.flush();

			final byte[] buffer = stream.getByteArray();
			record.fromStream(buffer);
			return buffer;

		} catch (IOException e) {
			throw new OSerializationException("Can't marshall RB+Tree node", e);
		} finally {
			stream.close();

			marshalledRecords.remove(identityRecord);

			checkEntryStructure();

			OProfiler.getInstance().stopChrono("OMVRBTreeEntryP.toStream", timer);
		}
	}

	/**
	 * Serialize only the new keys or the changed.
	 * 
	 * @throws IOException
	 */
	private void serializeNewKeys() throws IOException {
		for (int i = 0; i < size; ++i) {
			if (serializedKeys[i] == null) {
				OProfiler.getInstance().updateCounter("OMVRBTreeEntryP.serializeValue", 1);

				serializedKeys[i] = pTree.keySerializer.toStream(null, keys[i]);
			}
		}
	}

	/**
	 * Serialize only the new values or the changed.
	 * 
	 * @throws IOException
	 */
	private void serializeNewValues() throws IOException {
		for (int i = 0; i < size; ++i) {
			if (serializedValues[i] == null) {
				OProfiler.getInstance().updateCounter("OMVRBTreeEntryP.serializeKey", 1);

				serializedValues[i] = pTree.valueSerializer.toStream(null, values[i]);
			}
		}
	}

	@Override
	protected void setColor(final boolean iColor) {
		if (iColor == color)
			return;

		markDirty();
		super.setColor(iColor);
	}

	private void markDirty() {
		if (record == null)
			return;

		record.setDirty();
		tree.getListener().signalNodeChanged(this);
	}

	// @Override
	// public boolean equals(final Object o) {
	// if (this == o)
	// return true;
	// if (!(o instanceof OMVRBTreeEntryPersistent<?, ?>))
	// return false;
	//
	// final OMVRBTreeEntryPersistent<?, ?> e = (OMVRBTreeEntryPersistent<?, ?>) o;
	//
	// if (record != null && e.record != null)
	// return record.getIdentity().equals(e.record.getIdentity());
	//
	// return false;
	// }
	//
	// @Override
	// public int hashCode() {
	// final ORID rid = record.getIdentity();
	// return rid == null ? 0 : rid.hashCode();
	// }

	@Override
	protected OMVRBTreeEntry<K, V> getLeftInMemory() {
		return left;
	}

	@Override
	protected OMVRBTreeEntry<K, V> getParentInMemory() {
		return parent;
	}

	@Override
	protected OMVRBTreeEntry<K, V> getRightInMemory() {
		return right;
	}

	/**
	 * Assure that all the links versus parent, left and right are consistent.
	 * 
	 * @param iMarshalledRecords
	 */
	protected void flush2Record() throws OSerializationException {
		if (!record.isDirty())
			return;

		final boolean isNew = record.getIdentity().isNew();

		// toStream();

		if (record.isDirty())
			// SAVE IF IT'S DIRTY YET
			record.save(pTree.getClusterName());

		// RE-ASSIGN RID
		if (isNew) {
			final ORecordId rid = (ORecordId) record.getIdentity();

			if (left != null)
				left.parentRid = rid;

			if (right != null)
				right.parentRid = rid;

			if (parent != null) {
				parentRid = parent.record.getIdentity();
				if (parent.left == this)
					parent.leftRid = rid;
				else if (parent.right == this)
					parent.rightRid = rid;
			}
		}
	}

	@Override
	public String toString() {
		final StringBuilder builder = new StringBuilder();
		if (record != null && record.getIdentity().isValid())
			builder.append('@').append(record.getIdentity()).append(" ");
		builder.append(super.toString());
		return builder.toString();
	}
}
