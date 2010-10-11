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
package com.orientechnologies.orient.core.iterator;

import java.util.NoSuchElementException;

import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.db.record.ODatabaseRecordAbstract;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.record.ORecordInternal;
import com.orientechnologies.orient.core.tx.OTransactionEntry;

/**
 * Iterator class to browse forward and backward the records of a cluster. Once browsed in a direction, the iterator can't change
 * it.
 * 
 * @author Luca Garulli
 * 
 * @param <T>
 *          Record Type
 */
public class ORecordIteratorCluster<REC extends ORecordInternal<?>> extends ORecordIterator<REC> {
	protected int		currentClusterId;
	protected long	rangeFrom;
	protected long	rangeTo;
	protected long	firstClusterPosition;
	protected long	lastClusterPosition;
	protected long	totalAvailableRecords;

	public ORecordIteratorCluster(final ODatabaseRecord<REC> iDatabase, final ODatabaseRecordAbstract<REC> iLowLevelDatabase,
			final int iClusterId) {
		super(iDatabase, iLowLevelDatabase);
		if (iClusterId == ORID.CLUSTER_ID_INVALID)
			throw new IllegalArgumentException("The clusterId is invalid");

		currentClusterId = iClusterId;
		rangeFrom = -1;
		rangeTo = -1;

		long[] range = database.getStorage().getClusterDataRange(currentClusterId);
		firstClusterPosition = range[0];
		lastClusterPosition = range[1];

		totalAvailableRecords = database.countClusterElements(currentClusterId);

		txEntries = iDatabase.getTransaction().getEntriesByClusterIds(new int[] { iClusterId });

		if (txEntries != null)
			// ADJUST TOTAL ELEMENT BASED ON CURRENT TRANSACTION'S ENTRIES
			for (OTransactionEntry<?> entry : txEntries) {
				switch (entry.status) {
				case OTransactionEntry.CREATED:
					totalAvailableRecords++;
					break;

				case OTransactionEntry.DELETED:
					totalAvailableRecords--;
					break;
				}
			}
	}

	@Override
	public boolean hasPrevious() {
		checkDirection(false);

		if (limit > -1 && browsedRecords >= limit)
			// LIMIT REACHED
			return false;

		return currentClusterPosition > getRangeFrom() + 1;
	}

	public boolean hasNext() {
		checkDirection(true);

		if (limit > -1 && browsedRecords >= limit)
			// LIMIT REACHED
			return false;

		if (browsedRecords >= totalAvailableRecords)
			return false;

		// COMPUTE THE NUMBER OF RECORDS TO BROWSE
		if (liveUpdated)
			lastClusterPosition = getRangeTo();

		final long recordsToBrowse = currentClusterPosition > -2 && lastClusterPosition > -1 ? lastClusterPosition
				- currentClusterPosition : 0;

		if (recordsToBrowse <= 0)
			return hasTxEntry();

		return true;
	}

	/**
	 * Return the element at the current position and move backward the cursor to the previous position available.
	 * 
	 * @return the previous record found, otherwise the NoSuchElementException exception is thrown when no more records are found.
	 */
	@Override
	public REC previous() {
		checkDirection(false);

		final REC record = getRecord();

		// ITERATE UNTIL THE PREVIOUS GOOD RECORD
		while (hasPrevious()) {
			if (readCurrentRecord(record, -1) != null)
				// FOUND
				return record;
		}

		throw new NoSuchElementException();
	}

	/**
	 * Return the element at the current position and move forward the cursor to the next position available.
	 * 
	 * @return the next record found, otherwise the NoSuchElementException exception is thrown when no more records are found.
	 */
	@SuppressWarnings("unchecked")
	public REC next() {
		checkDirection(true);

		if (currentTxEntryPosition > -1)
			// IN TX
			return (REC) txEntries.get(currentTxEntryPosition).record;

		// ITERATE UNTIL THE NEXT GOOD RECORD
		while (hasNext()) {
			REC record = getRecord();

			record = readCurrentRecord(record, +1);
			if (record != null)
				// FOUND
				return record;
		}

		throw new NoSuchElementException();
	}

	public REC current() {
		final REC record = getRecord();
		return readCurrentRecord(record, 0);
	}

	/**
	 * Move the iterator to the begin of the range. If no range was specified move to the first record of the cluster.
	 * 
	 * @return The object itself
	 */
	@Override
	public ORecordIterator<REC> begin() {
		currentClusterPosition = getRangeFrom();
		return this;
	}

	/**
	 * Move the iterator to the end of the range. If no range was specified move to the last record of the cluster.
	 * 
	 * @return The object itself
	 */
	@Override
	public ORecordIterator<REC> last() {
		currentClusterPosition = getRangeTo();
		return this;
	}

	/**
	 * Define the range where move the iterator forward and backward.
	 * 
	 * @param iFrom
	 *          Lower bound limit of the range
	 * @param iEnd
	 *          Upper bound limit of the range
	 * @return
	 */
	public ORecordIteratorCluster<REC> setRange(final long iFrom, final long iEnd) {
		firstClusterPosition = iFrom;
		rangeTo = iEnd;
		currentClusterPosition = firstClusterPosition;
		return this;
	}

	/**
	 * Return the lower bound limit of the range if any, otherwise 0.
	 * 
	 * @return
	 */
	public long getRangeFrom() {
		if (!liveUpdated)
			return firstClusterPosition - 1;

		final long limit = database.getStorage().getClusterDataRange(currentClusterId)[0] - 1;
		if (rangeFrom > -1)
			return Math.max(rangeFrom, limit);
		return limit;
	}

	/**
	 * Return the upper bound limit of the range if any, otherwise the last record.
	 * 
	 * @return
	 */
	public long getRangeTo() {
		if (!liveUpdated)
			return lastClusterPosition + 1;

		final long limit = database.getStorage().getClusterDataRange(currentClusterId)[1] + 1;
		if (rangeTo > -1)
			return Math.min(rangeTo, limit);
		return limit;
	}

	/**
	 * Tell to the iterator that the upper limit must be checked at every cycle. Useful when concurrent deletes or additions change
	 * the size of the cluster while you're browsing it. Default is false.
	 * 
	 * @param iLiveUpdated
	 *          True to activate it, otherwise false (default)
	 * @see #isLiveUpdated()
	 */
	@Override
	public ORecordIterator<REC> setLiveUpdated(boolean iLiveUpdated) {
		super.setLiveUpdated(iLiveUpdated);

		// SET THE RANGE LIMITS
		if (iLiveUpdated) {
			firstClusterPosition = -1;
			lastClusterPosition = -1;
		} else {
			long[] range = database.getStorage().getClusterDataRange(currentClusterId);
			firstClusterPosition = range[0];
			lastClusterPosition = range[1];
		}

		totalAvailableRecords = database.countClusterElements(currentClusterId);

		return this;
	}

	/**
	 * Read the current record and increment the counter if the record was found.
	 * 
	 * @param iRecord
	 * @return
	 */
	private REC readCurrentRecord(REC iRecord, final int iMovement) {
		if (limit > -1 && browsedRecords >= limit)
			// LIMIT REACHED
			return null;

		currentClusterPosition += iMovement;

		iRecord = lowLevelDatabase.executeReadRecord(currentClusterId, currentClusterPosition, iRecord, fetchPlan);
		if (iRecord != null)
			browsedRecords++;

		return iRecord;
	}
}
