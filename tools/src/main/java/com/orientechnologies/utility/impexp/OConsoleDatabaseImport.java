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
package com.orientechnologies.utility.impexp;

import java.io.FileReader;
import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

import com.orientechnologies.common.parser.OStringForwardReader;
import com.orientechnologies.common.parser.OStringParser;
import com.orientechnologies.orient.core.db.document.ODatabaseDocument;
import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.exception.OSchemaException;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.intent.OIntentMassiveInsert;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OProperty;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.ORecordInternal;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.record.impl.ORecordBytes;
import com.orientechnologies.orient.core.record.impl.ORecordFlat;
import com.orientechnologies.orient.core.serialization.serializer.OJSONReader;
import com.orientechnologies.orient.core.serialization.serializer.OStringSerializerHelper;
import com.orientechnologies.orient.core.serialization.serializer.record.string.ORecordSerializerJSON;
import com.orientechnologies.utility.console.OCommandListener;

/**
 * Import data into a database.
 * 
 * @author Luca Garulli
 * 
 */
public class OConsoleDatabaseImport {
	private static final String			MODE_ARRAY		= "array";
	private ODatabaseDocument				database;
	private String									fileName;
	private OCommandListener				listener;
	private Map<OProperty, String>	linkedClasses	= new HashMap<OProperty, String>();
	private Map<OClass, String>			superClasses	= new HashMap<OClass, String>();

	private OJSONReader							jsonReader;
	private OStringForwardReader		reader;
	private ORecordInternal<?>			record;

	public OConsoleDatabaseImport(final ODatabaseDocument database, final String iFileName, final OCommandListener iListener)
			throws IOException {
		this.database = database;
		this.fileName = iFileName;
		listener = iListener;

		jsonReader = new OJSONReader(new FileReader(fileName));

		database.declareIntent(new OIntentMassiveInsert());
	}

	public OConsoleDatabaseImport importDatabase() {
		try {
			jsonReader.readNext(OJSONReader.BEGIN_OBJECT);

			importInfo();
			importClusters();
			importSchema();
			importRecords();
			importDictionary();

			jsonReader.readNext(OJSONReader.END_OBJECT);

			listener.onMessage("\nImport of database completed.");

		} catch (Exception e) {
			throw new ODatabaseExportException("Error on importing database '" + database.getName() + "' from file: " + fileName, e);
		} finally {
			close();
		}

		return this;
	}

	private void importInfo() throws IOException, ParseException {
		listener.onMessage("\nImporting database info...");

		jsonReader.readNext(OJSONReader.BEGIN_OBJECT);
		jsonReader.checkContent("\"info\":");
		jsonReader.readNext(OJSONReader.COMMA_SEPARATOR);
		jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT);
		@SuppressWarnings("unused")
		int defClusterId = jsonReader.readNumber(OJSONReader.ANY_NUMBER, true);
		jsonReader.readNext(OJSONReader.END_OBJECT);
		jsonReader.readNext(OJSONReader.COMMA_SEPARATOR);

		listener.onMessage("OK");
	}

	private long importDictionary() throws IOException, ParseException {
		listener.onMessage("\nImporting database dictionary...");

		jsonReader.readNext(OJSONReader.BEGIN_OBJECT);
		jsonReader.checkContent("\"dictionary\":");

		String dictionaryKey;
		String dictionaryValue;

		final ODocument doc = new ODocument(database);
		final ORecordId rid = new ORecordId();

		long tot = 0;

		try {
			do {
				dictionaryKey = jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"key\"")
						.readString(OJSONReader.COMMA_SEPARATOR);
				dictionaryValue = jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"value\"")
						.readString(OJSONReader.NEXT_IN_OBJECT);

				rid.fromString(dictionaryValue);

				database.getDictionary().put(dictionaryKey, doc);
				tot++;
			} while (jsonReader.lastChar() == ',');

			listener.onMessage("OK (" + tot + " entries)");

			jsonReader.readNext(OJSONReader.COMMA_SEPARATOR);
		} catch (Exception e) {
			listener.onMessage("ERROR (" + tot + " entries): " + e);
		}

		return tot;
	}

	private void importSchema() throws IOException, ParseException {
		listener.onMessage("\nImporting database schema...");

		jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"schema\"").readNext(OJSONReader.BEGIN_OBJECT);
		@SuppressWarnings("unused")
		int schemaVersion = jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"version\"")
				.readNumber(OJSONReader.ANY_NUMBER, true);
		jsonReader.readNext(OJSONReader.COMMA_SEPARATOR).readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"classes\"")
				.readNext(OJSONReader.BEGIN_COLLECTION);

		long classImported = 0;
		String className;
		int classId;
		int classDefClusterId;
		String classClusterIds;
		String classSuper = null;

		OClass cls;

		try {
			do {
				jsonReader.readNext(OJSONReader.BEGIN_OBJECT);

				className = jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"name\"")
						.readString(OJSONReader.COMMA_SEPARATOR);

				classId = jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"id\"").readInteger(OJSONReader.COMMA_SEPARATOR);

				classDefClusterId = jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"default-cluster-id\"")
						.readInteger(OJSONReader.COMMA_SEPARATOR);

				classClusterIds = jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"cluster-ids\"")
						.readString(OJSONReader.NEXT_IN_OBJECT).trim();

				cls = database.getMetadata().getSchema().getClass(className);

				if (cls != null) {
					if (cls.getDefaultClusterId() != classDefClusterId)
						cls.setDefaultClusterId(classDefClusterId);
				} else
					cls = database.getMetadata().getSchema().createClass(className, classDefClusterId);

				if (classId != cls.getId())
					throw new OSchemaException("Imported class '" + className + "' has id=" + cls.getId() + " different from the original: "
							+ classId);

				if (classClusterIds != null) {
					// REMOVE BRACES
					classClusterIds = classClusterIds.substring(1, classClusterIds.length() - 1);

					// ASSIGN OTHER CLUSTER IDS
					for (int i : OStringSerializerHelper.splitIntArray(classClusterIds)) {
						cls.addClusterIds(i);
					}
				}

				String value;
				while (jsonReader.lastChar() == ',') {
					jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT);
					value = jsonReader.getValue();

					if (value.equals("\"super-class\"")) {
						classSuper = jsonReader.readString(OJSONReader.NEXT_IN_OBJECT);
						superClasses.put(cls, classSuper);
					} else if (value.equals("\"properties\"")) {
						// GET PROPERTIES
						jsonReader.readString(OJSONReader.BEGIN_COLLECTION);

						while (jsonReader.lastChar() != ']') {
							importProperty(cls);

							if (jsonReader.lastChar() == '}')
								jsonReader.readNext(OJSONReader.NEXT_IN_ARRAY);
						}
						jsonReader.readNext(OJSONReader.END_OBJECT);
					}
				}

				classImported++;

				jsonReader.readNext(OJSONReader.NEXT_IN_ARRAY);
			} while (jsonReader.lastChar() == ',');

			// REBUILD ALL THE INHERITANCE
			for (Map.Entry<OClass, String> entry : superClasses.entrySet()) {
				cls.setSuperClass(database.getMetadata().getSchema().getClass(entry.getValue()));
			}

			// SET ALL THE LINKED CLASSES
			for (Map.Entry<OProperty, String> entry : linkedClasses.entrySet()) {
				entry.getKey().setLinkedClass(database.getMetadata().getSchema().getClass(entry.getValue()));
			}

			listener.onMessage("OK (" + classImported + " classes)");

			jsonReader.readNext(OJSONReader.END_OBJECT);
			jsonReader.readNext(OJSONReader.COMMA_SEPARATOR);
		} catch (Exception e) {
			e.printStackTrace();
			listener.onMessage("ERROR (" + classImported + " entries): " + e);
		}
	}

	private void importProperty(final OClass iClass) throws IOException, ParseException {
		String propName = jsonReader.readNext(OJSONReader.BEGIN_OBJECT).readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"name\"")
				.readString(OJSONReader.COMMA_SEPARATOR);

		final int id = jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"id\"")
				.readInteger(OJSONReader.COMMA_SEPARATOR);

		final OType type = OType.valueOf(jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"type\"")
				.readString(OJSONReader.NEXT_IN_OBJECT));

		String attrib;
		String value;

		String min = null;
		String max = null;
		String linkedClass = null;
		OType linkedType = null;
		ORecordId indexRid = null;
		Boolean indexUnique = null;

		while (jsonReader.lastChar() == ',') {
			jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT);

			attrib = jsonReader.getValue();
			value = jsonReader.readString(OJSONReader.NEXT_IN_OBJECT);

			if (attrib.equals("\"min\""))
				min = value;
			else if (attrib.equals("\"max\""))
				max = value;
			else if (attrib.equals("\"linked-class\""))
				linkedClass = value;
			else if (attrib.equals("\"linked-type\""))
				linkedType = OType.valueOf(value);
			else if (attrib.equals("\"index-rid\""))
				indexRid = new ORecordId(value);
			else if (attrib.equals("\"index-unique\""))
				indexUnique = new Boolean(value);
		}

		OProperty prop = iClass.getProperty(propName);
		if (prop == null) {
			// CREATE IT
			prop = iClass.createProperty(propName, type);
		} else {
			if (prop.getId() != id)
				throw new OSchemaException("Imported property '" + iClass.getName() + "." + propName
						+ "' has an id different from the original: " + id);
		}

		if (min != null)
			prop.setMin(min);
		if (max != null)
			prop.setMin(max);
		if (linkedClass != null)
			linkedClasses.put(prop, linkedClass);
		if (linkedType != null)
			prop.setLinkedType(linkedType);
		if (indexRid != null)
			prop.setIndex(new ODocument(database, indexRid), indexUnique);
	}

	private long importClusters() throws ParseException, IOException {
		listener.onMessage("\nImporting clusters...");

		long total = 0;

		jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT);
		jsonReader.checkContent("\"clusters\"");
		jsonReader.readNext(OJSONReader.BEGIN_OBJECT);

		while (jsonReader.lastChar() != ']') {
			jsonReader.readNext(OJSONReader.BEGIN_OBJECT);

			String name = jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"name\"")
					.readString(OJSONReader.COMMA_SEPARATOR);
			int id = jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"id\"").readInteger(OJSONReader.COMMA_SEPARATOR);
			String type = jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT).checkContent("\"type\"")
					.readString(OJSONReader.NEXT_IN_OBJECT);

			int clusterId = database.getClusterIdByName(name);
			if (clusterId == -1) {
				// CREATE IT
				if (type.equals("PHYSICAL"))
					clusterId = database.addPhysicalCluster(name, name, -1);
				else
					clusterId = database.addLogicalCluster(name, database.getDefaultClusterId());
			}

			if (clusterId != id)
				throw new OSchemaException("Imported cluster '" + name + "' has id=" + clusterId + " different from the original: " + id);

			total++;

			jsonReader.readNext(OJSONReader.NEXT_IN_ARRAY);
		}
		jsonReader.readNext(OJSONReader.COMMA_SEPARATOR);

		listener.onMessage("OK (" + total + " clusters)");

		return total;
	}

	private long importRecords() throws ParseException, IOException {
		long total = 0;

		jsonReader.readNext(OJSONReader.FIELD_ASSIGNMENT);
		jsonReader.checkContent("\"records\"");
		jsonReader.readNext(OJSONReader.BEGIN_COLLECTION);

		long totalRecords = 0;

		while (jsonReader.lastChar() != ']') {
			importRecord();

			++totalRecords;
		}

		listener.onMessage("\n\nDone. Imported " + totalRecords + " records");

		return total;
	}

	@SuppressWarnings("unchecked")
	private void importRecord() throws IOException, ParseException {
		String value = jsonReader.readString(OJSONReader.END_OBJECT, true);

		record = ORecordSerializerJSON.INSTANCE.fromString(database, value, record);

		String rid = record.getIdentity().toString();

		System.out.print("\nImporting record of type '" + (char) record.getRecordType() + "' with id=" + rid);

		// SAVE THE RECORD
		if (record.getIdentity().getClusterPosition() < database.countClusterElements(record.getIdentity().getClusterId())) {
			if (record instanceof ORecordBytes)
				((ODatabaseRecord<ORecordBytes>) database.getUnderlying()).save((ORecordBytes) record);
			else if (record instanceof ORecordFlat || record instanceof ODocument)
				((ODocument) record).save();
		} else {
			String clusterName = database.getClusterNameById(record.getIdentity().getClusterId());
			record.setIdentity(-1, -1);
			if (record instanceof ORecordBytes)
				((ODatabaseRecord<ORecordBytes>) database.getUnderlying()).save((ORecordBytes) record, clusterName);
			else if (record instanceof ORecordFlat)
				((ODatabaseRecord<ORecordInternal<?>>) database.getUnderlying()).save(record, clusterName);
			else if (record instanceof ODocument)
				((ODatabaseRecord<ORecordInternal<?>>) database.getUnderlying()).save(record, clusterName);
		}

		if (!record.getIdentity().toString().equals(rid))
			throw new OSchemaException("Imported record '" + record.getIdentity() + "' has rid different from the original: " + rid);

		jsonReader.readNext(OJSONReader.NEXT_IN_ARRAY);
	}

	public OConsoleDatabaseImport importRecords(final String iMode, final String iClusterType) throws IOException {
		if (iMode == null)
			throw new IllegalArgumentException("Importing mode not specified received");

		int offset = OStringParser.jump(reader, (int) reader.getPosition(), OStringParser.COMMON_JUMP);
		if (offset == -1 || reader.charAt(offset) != '{')
			throw new IllegalStateException("Missed begin of json (expected char '{')");

		long importedRecordsTotal = 0;
		long notImportedRecordsTotal = 0;

		long beginChronoTotal = System.currentTimeMillis();

		final ODocument doc = new ODocument(database);

		try {
			while (reader.ready()) {
				String className = parse(":");
				if (className.length() > 2)
					className = className.substring(1, className.length() - 1);

				OClass cls = database.getMetadata().getSchema().getClass(className);

				if (cls == null) {
					// CREATE THE CLASS IF NOT EXISTS YET
					if (iClusterType.equalsIgnoreCase("logical"))
						cls = database.getMetadata().getSchema().createClass(className);
					else
						cls = database.getMetadata().getSchema().createClass(className, database.addPhysicalCluster(className, className, -1));

					database.getMetadata().getSchema().save();
				}

				doc.setClassName(className);

				listener.onMessage("\n- Importing document(s) of class '" + cls.getName() + "'...");

				offset = OStringParser.jump(reader, (int) reader.getPosition() + 1, OStringParser.COMMON_JUMP);

				if (iMode.equalsIgnoreCase(MODE_ARRAY)) {
					if (offset == -1 || reader.charAt(offset) != '[')
						throw new IllegalStateException("Missed begin of array (expected char '[')");
				} else
					throw new IllegalArgumentException("mode '" + iMode + "' not supported");

				long beginChronoClass = System.currentTimeMillis();

				long importedRecordsClass = 0;
				long notImportedRecordsClass = 0;

				try {
					char c;

					while (reader.ready()) {

						String chunk = OStringParser.getWord(reader, (int) reader.getPosition() + 1, "}");
						if (chunk == null)
							throw new IllegalStateException("Missed end of record (expected char '}')");

						chunk += "}";

						try {
							doc.reset();
							doc.fromJSON(chunk);
							doc.save();
						} catch (Exception e) {
							listener.onMessage("\nError on importing document: " + chunk);
							listener.onMessage("\n  The cause is " + e + "\n");
							notImportedRecordsClass++;
						}

						importedRecordsClass++;

						offset = OStringParser.jump(reader, (int) reader.getPosition() + 1, OStringParser.COMMON_JUMP);
						c = reader.charAt(offset);
						if (offset == -1 || (c != ']' && c != ','))
							throw new IllegalStateException("Missed separator or end of array (expected chars ',' or ']')");

						if (c == ']')
							// END OF CLUSTER
							break;
					}
				} finally {
					listener.onMessage("Done. Imported " + importedRecordsClass + " record(s) in "
							+ (System.currentTimeMillis() - beginChronoClass) + "ms. " + notImportedRecordsClass + " error(s)");
					importedRecordsTotal += importedRecordsClass;
					notImportedRecordsTotal += notImportedRecordsClass;
				}

				offset = OStringParser.jump(reader, (int) reader.getPosition() + 1, OStringParser.COMMON_JUMP);

				if (offset == -1 || reader.charAt(offset) == '}')
					break;
			}
		} finally {
			listener.onMessage("\n\nImported " + importedRecordsTotal + " document(s) in "
					+ (System.currentTimeMillis() - beginChronoTotal) + "ms. " + notImportedRecordsTotal + " error(s)\n");
		}

		return this;
	}

	private String parse(final String iSeparatorChars) throws IOException {
		int offset = OStringParser.jump(reader, (int) reader.getPosition(), OStringParser.COMMON_JUMP);

		if (offset == -1)
			throw new IllegalStateException("End of input caught");

		return OStringParser.getWord(reader, (int) reader.getPosition() + 1, iSeparatorChars);
	}

	public void close() {
		database.declareIntent(null);

		if (reader == null)
			return;

		try {
			reader.close();
			reader = null;
		} catch (IOException e) {
		}
	}
}
