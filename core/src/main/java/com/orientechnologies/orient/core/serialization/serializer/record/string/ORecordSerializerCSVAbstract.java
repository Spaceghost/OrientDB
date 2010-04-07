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
package com.orientechnologies.orient.core.serialization.serializer.record.string;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import com.orientechnologies.orient.core.db.OUserObject2RecordHandler;
import com.orientechnologies.orient.core.db.document.ODatabaseDocument;
import com.orientechnologies.orient.core.db.object.ODatabaseObjectTx;
import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.entity.OEntityManagerInternal;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.ORecordInternal;
import com.orientechnologies.orient.core.record.ORecordSchemaAware;
import com.orientechnologies.orient.core.record.impl.ORecordDocument;
import com.orientechnologies.orient.core.serialization.serializer.object.OObjectSerializerHelper;

@SuppressWarnings("unchecked")
public abstract class ORecordSerializerCSVAbstract extends ORecordSerializerStringAbstract {
	public static final String	FIELD_VALUE_SEPARATOR	= ":";

	protected abstract ORecordSchemaAware<?> newObject(ODatabaseRecord<?> iDatabase, String iClassName);

	public Object fieldFromStream(final ODatabaseRecord<?> iDatabase, final OType iType, OClass iLinkedClass,
			final OType iLinkedType, final String iName, final String iValue) {
		switch (iType) {
		case EMBEDDEDLIST:
		case EMBEDDEDSET: {
			if (iValue.length() == 0)
				return null;

			// REMOVE BEGIN & END COLLECTIONS CHARACTERS
			String value = iValue.substring(1, iValue.length() - 1);

			if (value.length() == 0)
				return null;

			ArrayList<Object> coll = new ArrayList<Object>();

			String[] items = split(value, RECORD_SEPARATOR_AS_CHAR);

			if (iLinkedClass != null)
				// EMBEDDED OBJECTS
				for (String item : items) {
					coll.add(fromString(iDatabase, item, new ORecordDocument((ODatabaseDocument) iDatabase, iLinkedClass.getName())));
				}
			else {
				if (iLinkedType == null)
					throw new IllegalArgumentException(
							"Linked type can't be null. Probably the serialized type has not stored the type along with data");

				// EMBEDDED LITERALS
				for (String item : items) {
					coll.add(fieldTypeFromStream(iLinkedType, item));
				}
			}

			return coll;
		}

		case LINKLIST:
		case LINKSET: {
			if (iValue.length() == 0)
				return null;

			// REMOVE BEGIN & END COLLECTIONS CHARACTERS
			String value = iValue.substring(1, iValue.length() - 1);

			ArrayList<ORecord<?>> coll = new ArrayList<ORecord<?>>();

			String[] items = split(value, RECORD_SEPARATOR_AS_CHAR);

			for (String item : items) {
				// GET THE CLASS NAME IF ANY
				int classSeparatorPos = value.indexOf(CLASS_SEPARATOR);
				if (classSeparatorPos > -1) {
					String className = value.substring(1, classSeparatorPos);
					if (className != null) {
						iLinkedClass = iDatabase.getMetadata().getSchema().getClass(className);
						item = item.substring(classSeparatorPos + 1);
					}
				}

				coll.add(new ORecordDocument(iDatabase, iLinkedClass != null ? iLinkedClass.getName() : null, new ORecordId(item)));
			}

			return coll;
		}

		case LINK:
			int pos = iValue.indexOf(CLASS_SEPARATOR);
			if (pos > -1)
				iLinkedClass = iDatabase.getMetadata().getSchema().getClass(iValue.substring(LINK.length(), pos));
			else
				pos = 0;

			if (iLinkedClass == null)
				throw new IllegalArgumentException("Linked class not specified in ORID field: " + iValue);

			ORecordId recId = new ORecordId(iValue.substring(pos + 1));
			return new ORecordDocument(iDatabase, iLinkedClass.getName(), recId);

		default:
			return fieldTypeFromStream(iType, iValue);
		}
	}

	public String fieldToStream(final ORecordDocument iRecord, final ODatabaseRecord iDatabase,
			final OUserObject2RecordHandler iObjHandler, final OType iType, final OClass iLinkedClass, final OType iLinkedType,
			final String iName, final Object iValue, final Map<ORecordInternal<?>, ORecordId> iMarshalledRecords) {
		StringBuilder buffer = new StringBuilder();

		switch (iType) {
		case EMBEDDED:
			buffer.append(toString((ORecordDocument) iValue, iObjHandler, iMarshalledRecords));
			break;

		case LINK: {
			linkToStream(buffer, (ORecordSchemaAware<?>) iValue);
			break;
		}

		case EMBEDDEDLIST:
		case EMBEDDEDSET: {
			buffer.append(COLLECTION_BEGIN);

			int items = 0;
			if (iLinkedClass != null) {
				// EMBEDDED OBJECTS
				ORecordDocument record;
				for (Object o : (Collection<?>) iValue) {
					if (items > 0)
						buffer.append(RECORD_SEPARATOR);

					if (o != null) {
						if (o instanceof ORecordDocument)
							record = (ORecordDocument) o;
						else
							record = OObjectSerializerHelper.toStream(o, new ORecordDocument(o.getClass().getSimpleName()),
									iDatabase instanceof ODatabaseObjectTx ? ((ODatabaseObjectTx) iDatabase).getEntityManager()
											: OEntityManagerInternal.INSTANCE, iLinkedClass, iObjHandler != null ? iObjHandler
											: new OUserObject2RecordHandler() {

												public Object getUserObjectByRecord(ORecord<?> iRecord) {
													return iRecord;
												}

												public ORecord<?> getRecordByUserObject(Object iPojo, boolean iIsMandatory) {
													return new ORecordDocument(iLinkedClass);
												}
											});

						buffer.append(toString(record, iObjHandler, iMarshalledRecords));
					}

					items++;
				}
			} else
				// EMBEDDED LITERALS
				for (Object o : (Collection<?>) iValue) {
					if (items > 0)
						buffer.append(RECORD_SEPARATOR);

					buffer.append(fieldTypeToString(iLinkedType, o));
					items++;
				}

			buffer.append(COLLECTION_END);
			break;
		}

		case LINKLIST:
		case LINKSET: {
			buffer.append(COLLECTION_BEGIN);

			int items = 0;
			if (iLinkedClass != null) {
				// LINKED OBJECTS
				for (ORecordSchemaAware<?> record : (Collection<ORecordSchemaAware<?>>) iValue) {
					if (items++ > 0)
						buffer.append(RECORD_SEPARATOR);

					// ASSURE THE OBJECT IS SAVED
					if (record.getDatabase() == null)
						record.setDatabase(iDatabase);

					linkToStream(buffer, record);
				}
			}

			buffer.append(COLLECTION_END);
			break;
		}

		default:
			return fieldTypeToString(iType, iValue);
		}

		return buffer.toString();
	}

	private void linkToStream(StringBuilder buffer, ORecordSchemaAware<?> record) {
		ORID link = record.getIdentity();
		if (!link.isValid())
			// STORE THE TRAVERSED OBJECT TO KNOW THE RECORD ID
			record.getDatabase().save((ORecordInternal) record);

		buffer.append(LINK);

		if (record.getClassName() != null) {
			buffer.append(record.getClassName());
			buffer.append(CLASS_SEPARATOR);
		}

		buffer.append(link.toString());
	}
}