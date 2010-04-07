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
package com.orientechnologies.orient.core.db.document;

import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.db.ODatabaseRecordWrapperAbstract;
import com.orientechnologies.orient.core.db.record.ODatabaseRecordTx;
import com.orientechnologies.orient.core.exception.ODatabaseException;
import com.orientechnologies.orient.core.iterator.ORecordIteratorCluster;
import com.orientechnologies.orient.core.iterator.ORecordIteratorMultiCluster;
import com.orientechnologies.orient.core.metadata.security.OUser;
import com.orientechnologies.orient.core.record.ORecordSchemaAware;
import com.orientechnologies.orient.core.record.impl.ORecordDocument;

public class ODatabaseDocumentTx extends ODatabaseRecordWrapperAbstract<ODatabaseRecordTx<ORecordDocument>, ORecordDocument> implements
		ODatabaseDocument {

	public ODatabaseDocumentTx(final String iURL) {
		super(new ODatabaseRecordTx<ORecordDocument>(iURL, ORecordDocument.class));
	}

	@Override
	public ORecordDocument newInstance() {
		return new ORecordDocument(this);
	}

	public ORecordDocument newInstance(final String iClassName) {
		checkSecurity(OUser.CLASS + "." + iClassName, OUser.CREATE);

		return new ORecordDocument(this, iClassName);
	}

	public ORecordIteratorMultiCluster<ORecordDocument> browseClass(final String iClassName) {
		checkSecurity(OUser.CLASS + "." + iClassName, OUser.READ);

		return new ORecordIteratorMultiCluster<ORecordDocument>(this, underlying, getMetadata().getSchema().getClass(iClassName)
				.getClusterIds());
	}

	public ORecordIteratorCluster<ORecordDocument> browseCluster(final String iClusterName) {
		checkSecurity(OUser.CLUSTER + "." + iClusterName, OUser.READ);

		return new ORecordIteratorCluster<ORecordDocument>(this, underlying, getClusterIdByName(iClusterName));
	}

	/**
	 * If the record is new and a class was specified, the configured cluster id will be used to store the class.
	 */
	public ODatabaseDocumentTx save(final ORecordDocument iContent) {
		try {
			if (!iContent.getIdentity().isValid()) {
				// NEW RECORD
				if (iContent.getClassName() != null)
					checkSecurity(OUser.CLASS + "." + iContent.getClassName(), OUser.CREATE);

				if (iContent.getSchemaClass() != null) {
					// CLASS FOUND: FORCE THE STORING IN THE CLUSTER CONFIGURED
					String clusterName = getClusterNameById(iContent.getSchemaClass().getDefaultClusterId());

					super.save(iContent, clusterName);
					return this;
				}
			} else {
				// UPDATE: CHECK ACCESS ON SCHEMA CLASS NAME (IF ANY)
				if (iContent.getClassName() != null)
					checkSecurity(OUser.CLASS + "." + iContent.getClassName(), OUser.UPDATE);
			}

			super.save(iContent);

		} catch (Throwable t) {
			OLogManager.instance().error(
					this,
					"Error on saving record #" + iContent.getIdentity() + " of class '"
							+ (iContent.getClassName() != null ? iContent.getClassName() : "?") + "'", t, ODatabaseException.class);
		}
		return this;
	}

	/**
	 * Store the record on the specified cluster only after having checked the cluster is allowed and figures in the configured and
	 * the record is valid following the constraints declared in the schema.
	 * 
	 * @see ORecordSchemaAware#validate()
	 */
	public ODatabaseDocumentTx save(final ORecordDocument iContent, String iClusterName) {
		if (!iContent.getIdentity().isValid()) {
			if (iClusterName == null && iContent.getSchemaClass() != null)
				// FIND THE RIGHT CLUSTER AS CONFIGURED IN CLASS
				iClusterName = getClusterNameById(iContent.getSchemaClass().getDefaultClusterId());

			int id = getClusterIdByName(iClusterName);
			if (id == -1)
				throw new IllegalArgumentException("Cluster name " + iClusterName + " is not configured");

			// CHECK IF THE CLUSTER IS PART OF THE CONFIGURED CLUSTERS
			int[] clusterIds = iContent.getSchemaClass().getClusterIds();
			int i = 0;
			for (; i < clusterIds.length; ++i)
				if (clusterIds[i] == id)
					break;

			if (id == clusterIds.length)
				throw new IllegalArgumentException("Cluster name " + iClusterName + " is not configured to store the class "
						+ iContent.getClassName());
		}

		iContent.validate();

		super.save(iContent, iClusterName);
		return this;
	}

	public ODatabaseDocumentTx delete(final ORecordDocument iContent) {
		// CHECK ACCESS ON SCHEMA CLASS NAME (IF ANY)
		if (iContent.getClassName() != null)
			checkSecurity(OUser.CLASS + "." + iContent.getClassName(), OUser.DELETE);

		try {
			underlying.delete(iContent);

		} catch (Throwable t) {
			OLogManager.instance().error(this,
					"Error on deleting record #" + iContent.getIdentity() + " of class '" + iContent.getClassName() + "'", t,
					ODatabaseException.class);
		}
		return this;
	}

	public long countClass(String iClassName) {
		return countClusterElements(getMetadata().getSchema().getClass(iClassName).getClusterIds());
	}
}