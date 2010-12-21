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
package com.orientechnologies.orient.core.sql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.orientechnologies.common.parser.OStringParser;
import com.orientechnologies.common.profiler.OProfiler;
import com.orientechnologies.common.util.OPair;
import com.orientechnologies.orient.core.command.OCommandRequestText;
import com.orientechnologies.orient.core.exception.OCommandExecutionException;
import com.orientechnologies.orient.core.exception.OQueryParsingException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.index.OPropertyIndexFullText;
import com.orientechnologies.orient.core.index.OPropertyIndexNotUnique;
import com.orientechnologies.orient.core.index.OPropertyIndexUnique;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OProperty;
import com.orientechnologies.orient.core.metadata.security.ODatabaseSecurityResources;
import com.orientechnologies.orient.core.metadata.security.ORole;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.ORecordInternal;
import com.orientechnologies.orient.core.record.ORecordSchemaAware;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.serialization.serializer.OStringSerializerHelper;
import com.orientechnologies.orient.core.sort.ODocumentSorter;
import com.orientechnologies.orient.core.sql.filter.OSQLFilter;
import com.orientechnologies.orient.core.sql.filter.OSQLFilterCondition;
import com.orientechnologies.orient.core.sql.filter.OSQLFilterItemField;
import com.orientechnologies.orient.core.sql.filter.OSQLParser;
import com.orientechnologies.orient.core.sql.operator.OQueryOperatorContainsText;
import com.orientechnologies.orient.core.sql.operator.OQueryOperatorEquals;
import com.orientechnologies.orient.core.sql.query.OSQLAsynchQuery;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;
import com.orientechnologies.orient.core.storage.ORecordBrowsingListener;
import com.orientechnologies.orient.core.storage.impl.local.OStorageLocal;

/**
 * Executes the SQL SELECT statement. the parse() method compiles the query and builds the meta information needed by the execute().
 * If the query contains the ORDER BY clause, the results are temporary collected internally, then ordered and finally returned all
 * together to the listener.
 * 
 * @author Luca Garulli
 */
public class OCommandExecutorSQLSelect extends OCommandExecutorSQLAbstract implements ORecordBrowsingListener {
	public static final String											KEYWORD_SELECT		= "SELECT";
	public static final String											KEYWORD_ASC				= "ASC";
	public static final String											KEYWORD_DESC			= "DESC";
	public static final String											KEYWORD_ORDER			= "ORDER";
	public static final String											KEYWORD_BY				= "BY";
	public static final String											KEYWORD_ORDER_BY	= "ORDER BY";
	public static final String											KEYWORD_LIMIT			= "LIMIT";
	public static final String											KEYWORD_RANGE			= "RANGE";

	private OSQLAsynchQuery<ORecordSchemaAware<?>>	request;
	private OSQLFilter															compiledFilter;
	private Map<String, Object>											projections				= null;
	private List<OPair<String, String>>							orderedFields;
	private List<ODocument>													tempResult;
	private int																			limit							= -1;
	private int																			resultCount;
	private ORecordId																rangeFrom;
	private ORecordId																rangeTo;
	private String																	flattenField;

	/**
	 * Compile the filter conditions only the first time.
	 */
	@SuppressWarnings("unchecked")
	public OCommandExecutorSQLSelect parse(final OCommandRequestText iRequest) {
		iRequest.getDatabase().checkSecurity(ODatabaseSecurityResources.COMMAND, ORole.PERMISSION_READ);

		init(iRequest.getDatabase(), iRequest.getText());

		if (iRequest instanceof OSQLSynchQuery) {
			request = (OSQLSynchQuery<ORecordSchemaAware<?>>) iRequest;
			rangeFrom = request.getBeginRange().isValid() ? request.getBeginRange() : null;
			rangeTo = request.getEndRange().isValid() ? request.getEndRange() : null;
		} else if (iRequest instanceof OSQLAsynchQuery)
			request = (OSQLAsynchQuery<ORecordSchemaAware<?>>) iRequest;
		else {
			// BUILD A QUERY OBJECT FROM THE COMMAND REQUEST
			request = new OSQLSynchQuery<ORecordSchemaAware<?>>(iRequest.getText());
			request.setDatabase(iRequest.getDatabase());
			if (iRequest.getResultListener() != null)
				request.setResultListener(iRequest.getResultListener());
		}

		int pos = extractProjections();
		if (pos == -1)
			return this;

		int endPosition = textUpperCase.indexOf(" " + OCommandExecutorSQLSelect.KEYWORD_ORDER_BY, currentPos);
		if (endPosition == -1) {
			endPosition = textUpperCase.indexOf(" " + OCommandExecutorSQLSelect.KEYWORD_RANGE, currentPos);
			if (endPosition == -1) {
				endPosition = textUpperCase.indexOf(" " + OCommandExecutorSQLSelect.KEYWORD_LIMIT, currentPos);
				if (endPosition == -1) {
					// NO OTHER STUFF: GET UNTIL THE END AND ASSURE TO RETURN FALSE IN ORDER TO AVOID PARSING OF CONDITIONS
					endPosition = text.length();
				}
			}
		}

		compiledFilter = OSQLParser.getInstance().parseWhereCondition(iRequest.getDatabase(), text.substring(pos, endPosition));

		currentPos = compiledFilter.currentPos + pos;

		if (currentPos > -1 && currentPos < text.length()) {
			currentPos = OStringParser.jump(text, currentPos, " \r\n");

			final StringBuilder word = new StringBuilder();
			String w;

			while (currentPos > -1) {
				currentPos = OSQLHelper.nextWord(text, textUpperCase, currentPos, word, true);

				if (currentPos > -1) {
					w = word.toString();
					if (w.equals(KEYWORD_ORDER))
						extractOrderBy(word);
					else if (w.equals(KEYWORD_RANGE))
						extractRange(word);
					else if (w.equals(KEYWORD_LIMIT))
						extractLimit(word);
				}
			}
		}
		return this;
	}

	public Object execute(final Object... iArgs) {
		// TODO: SUPPORT MULTIPLE CLASSES LIKE A SQL JOIN
		final int[] clusterIds;

		if (compiledFilter.getTargetClasses() != null) {
			OClass cls = compiledFilter.getTargetClasses().keySet().iterator().next();

			database.checkSecurity(ODatabaseSecurityResources.CLASS, ORole.PERMISSION_READ, cls.getName());

			clusterIds = cls.getPolymorphicClusterIds();

			// CHECK PERMISSION TO ACCESS TO ALL THE CONFIGURED CLUSTERS
			for (int clusterId : clusterIds)
				database.checkSecurity(ODatabaseSecurityResources.CLUSTER, ORole.PERMISSION_READ, database.getClusterNameById(clusterId),
						clusterId);

			final List<ORecord<?>> resultSet = searchForIndexes(cls);

			if (resultSet.size() > 0) {
				OProfiler.getInstance().updateCounter("Query.indexUsage", 1);

				// FOUND USING INDEXES
				for (ORecord<?> record : resultSet)
					addResult(record);
			} else
				// NO INDEXES: SCAN THE ENTIRE CLUSTER
				scanEntireClusters(clusterIds);

		} else if (compiledFilter.getTargetClusters() != null) {
			String firstCluster = compiledFilter.getTargetClusters().keySet().iterator().next();

			if (firstCluster == null || firstCluster.length() == 0)
				throw new OCommandExecutionException("No cluster or schema class selected in query");

			if (Character.isDigit(firstCluster.charAt(0)))
				// GET THE CLUSTER NUMBER
				clusterIds = OStringSerializerHelper.splitIntArray(firstCluster);
			else
				// GET THE CLUSTER NUMBER BY THE CLASS NAME
				clusterIds = new int[] { database.getClusterIdByName(firstCluster.toLowerCase()) };

			database.checkSecurity(ODatabaseSecurityResources.CLUSTER, ORole.PERMISSION_READ, firstCluster.toLowerCase(), clusterIds[0]);

			scanEntireClusters(clusterIds);
		} else if (compiledFilter.getTargetRecords() != null) {
			ORecordId rid = new ORecordId();
			ORecordInternal<?> record;
			for (String rec : compiledFilter.getTargetRecords()) {
				rid.fromString(rec);
				record = database.load(rid);
				foreach(record);
			}
		} else
			throw new OQueryParsingException("No source found in query: specify class, clusters or single records");

		applyOrderBy();
		applyFlatten();
		return handleResult();
	}

	private Object handleResult() {
		if (tempResult != null) {
			// TEMP RESULT: RETURN ALL THE RECORDS AT THE END
			for (ODocument doc : tempResult)
				// CALL THE LISTENER
				sendResultToListener(doc);
			tempResult.clear();
			tempResult = null;
			return null;
		}

		if (request instanceof OSQLSynchQuery)
			return ((OSQLSynchQuery<ORecordSchemaAware<?>>) request).getResult();

		return null;
	}

	public boolean foreach(final ORecordInternal<?> iRecord) {
		if (filter(iRecord)) {
			resultCount++;
			addResult(iRecord.copy());

			if (limit > -1 && resultCount >= limit)
				// BREAK THE EXECUTION
				return false;

			if (request.getLimit() > -1 && resultCount >= request.getLimit())
				// BREAK THE EXECUTION
				return false;
		}
		return true;
	}

	public Map<String, Object> getProjections() {
		return projections;
	}

	public List<OPair<String, String>> getOrderedFields() {
		return orderedFields;
	}

	protected void extractOrderBy(final StringBuilder word) {
		int newPos = OSQLHelper.nextWord(text, textUpperCase, currentPos, word, true);

		if (!KEYWORD_BY.equals(word.toString()))
			throw new OQueryParsingException("Expected keyword " + KEYWORD_BY);

		currentPos = newPos;

		String fieldName;
		String fieldOrdering;

		orderedFields = new ArrayList<OPair<String, String>>();
		while (currentPos != -1 && (orderedFields.size() == 0 || word.toString().equals(","))) {
			currentPos = OSQLHelper.nextWord(text, textUpperCase, currentPos, word, false);
			if (currentPos == -1)
				throw new OCommandSQLParsingException("Field name expected", text, currentPos);

			fieldName = word.toString();

			currentPos = OSQLHelper.nextWord(text, textUpperCase, currentPos, word, true);
			if (currentPos == -1)
				fieldOrdering = KEYWORD_ASC;
			else {

				if (word.toString().endsWith(",")) {
					currentPos--;
					word.deleteCharAt(word.length() - 1);
				}

				if (word.toString().equals(KEYWORD_ASC))
					fieldOrdering = KEYWORD_ASC;
				else if (word.toString().equals(KEYWORD_DESC))
					fieldOrdering = KEYWORD_DESC;
				else
					throw new OCommandSQLParsingException("Ordering mode '" + word
							+ "' not supported. Valid is 'ASC', 'DESC' or nothing ('ASC' by default)", text, currentPos);

				currentPos = OSQLHelper.nextWord(text, textUpperCase, currentPos, word, true);
			}

			orderedFields.add(new OPair<String, String>(fieldName, fieldOrdering));

			if (currentPos == -1)
				break;
		}

		if (orderedFields.size() == 0)
			throw new OCommandSQLParsingException("Order by field set was missed. Example: ORDER BY name ASC, salary DESC", text,
					currentPos);
	}

	protected void extractRange(final StringBuilder word) {
		int newPos = OSQLHelper.nextWord(text, textUpperCase, currentPos, word, true);
		if (!word.toString().contains(":"))
			throw new OCommandSQLParsingException(
					"Range must contains record id in the form of <cluster-id>:<cluster-pos>. Example: RANGE 10:50, 10:100", text, currentPos);

		try {
			rangeFrom = new ORecordId(word.toString());
		} catch (Exception e) {
			throw new OCommandSQLParsingException("Invalid record id setted as RANGE from. Value setted is '" + word
					+ "' but it should be a valid record id in the form of <cluster-id>:<cluster-pos>. Example: RANGE 10:50", text,
					currentPos);
		}

		if (newPos == -1)
			return;

		currentPos = newPos;

		newPos = OSQLHelper.nextWord(text, textUpperCase, currentPos, word, true);

		if (newPos == -1)
			return;

		if (!word.toString().equalsIgnoreCase("LIMIT")) {
			if (!word.toString().contains(":"))
				throw new OCommandSQLParsingException(
						"Range must contains record id in the form of <cluster-id>:<cluster-pos>. Example: RANGE 10:50, 10:100", text,
						currentPos);

			try {
				rangeTo = new ORecordId(word.toString());
			} catch (Exception e) {
				throw new OCommandSQLParsingException("Invalid record id setted as RANGE to. Value setted is '" + word
						+ "' but it should be a valid record id in the form of <cluster-id>:<cluster-pos>. Example: RANGE 10:50, 10:100", text,
						currentPos);
			}

			currentPos = newPos;
		}
	}

	protected void extractLimit(final StringBuilder word) {
		if (!word.toString().equals(KEYWORD_LIMIT))
			return;

		currentPos = OSQLHelper.nextWord(text, textUpperCase, currentPos, word, true);
		try {
			limit = Integer.parseInt(word.toString());
		} catch (Exception e) {
			throw new OCommandSQLParsingException("Invalid LIMIT value setted to '" + word
					+ "' but it should be a valid integer. Example: LIMIT 10", text, currentPos);
		}
	}

	private void addResult(final ORecord<?> iRecord) {
		ODocument doc = (ODocument) iRecord;

		if (orderedFields != null || flattenField != null) {
			// ORDER BY CLAUSE: COLLECT ALL THE RECORDS AND ORDER THEM AT THE END
			if (tempResult == null)
				tempResult = new ArrayList<ODocument>();

			tempResult.add(doc);
		} else
			// CALL THE LISTENER
			sendResultToListener(doc);
	}

	private List<ORecord<?>> searchForIndexes(final OClass iSchemaClass) {
		return analyzeQueryBranch(new ArrayList<ORecord<?>>(), iSchemaClass, compiledFilter.getRootCondition());
	}

	private List<ORecord<?>> analyzeQueryBranch(final List<ORecord<?>> iResultSet, final OClass iSchemaClass,
			final OSQLFilterCondition iCondition) {
		if (iCondition == null)
			return iResultSet;

		if (iCondition.getLeft() != null)
			if (iCondition.getLeft() instanceof OSQLFilterCondition)
				analyzeQueryBranch(iResultSet, iSchemaClass, (OSQLFilterCondition) iCondition.getLeft());

		if (iCondition.getRight() != null)
			if (iCondition.getRight() instanceof OSQLFilterCondition)
				analyzeQueryBranch(iResultSet, iSchemaClass, (OSQLFilterCondition) iCondition.getRight());

		searchIndexedProperty(iResultSet, iSchemaClass, iCondition, iCondition.getLeft());

		if (iResultSet.size() == 0)
			searchIndexedProperty(iResultSet, iSchemaClass, iCondition, iCondition.getRight());

		return iResultSet;
	}

	private List<ORecord<?>> searchIndexedProperty(final List<ORecord<?>> iResultSet, final OClass iSchemaClass,
			final OSQLFilterCondition iCondition, final Object iItem) {
		if (iItem == null || !(iItem instanceof OSQLFilterItemField))
			return null;

		OSQLFilterItemField item = (OSQLFilterItemField) iItem;

		final OProperty prop = iSchemaClass.getProperty(item.getName());
		if (prop != null && prop.isIndexed()) {
			// TODO: IMPROVE THIS MANAGEMENT
			// ONLY EQUALS IS SUPPORTED NOW!
			if (((prop.getIndex() instanceof OPropertyIndexUnique || prop.getIndex() instanceof OPropertyIndexNotUnique) && iCondition
					.getOperator() instanceof OQueryOperatorEquals)
					|| prop.getIndex() instanceof OPropertyIndexFullText
					&& iCondition.getOperator() instanceof OQueryOperatorContainsText) {
				final Object value = iCondition.getLeft() == iItem ? iCondition.getRight() : iCondition.getLeft();
				if (value != null) {
					final List<?> resultSet = prop.getIndex().get(value.toString());
					if (resultSet != null && resultSet.size() > 0)
						for (Object o : resultSet) {
							if (o instanceof ORID)
								iResultSet.add(database.load((ORID) o));
							else
								iResultSet.add((ORecord<?>) o);

						}

				}
			}
		}

		return iResultSet;
	}

	protected boolean filter(final ORecordInternal<?> iRecord) {
		return compiledFilter.evaluate(database, (ORecordSchemaAware<?>) iRecord);
	}

	protected int extractProjections() {
		int currentPos = 0;

		final StringBuilder word = new StringBuilder();

		currentPos = OSQLHelper.nextWord(text, textUpperCase, currentPos, word, true);
		if (!word.toString().equals(KEYWORD_SELECT))
			return -1;

		int fromPosition = textUpperCase.indexOf(KEYWORD_FROM, currentPos);
		if (fromPosition == -1)
			throw new OQueryParsingException("Missed " + KEYWORD_FROM, text, currentPos);

		final String projectionString = textUpperCase.substring(currentPos, fromPosition).trim();
		if (projectionString.length() > 0 && !projectionString.equals("*")) {
			// EXTRACT PROJECTIONS
			projections = new HashMap<String, Object>();
			final String[] items = text.substring(currentPos, fromPosition).split(",");

			String fieldName;
			int pos;
			for (String i : items) {
				i = i.trim();

				// EXTRACT THE FIELD NAME WITHOUT FUNCTIONS AND/OR LINKS
				pos = i.indexOf('.');
				fieldName = pos > -1 ? i.substring(0, pos) : i;

				fieldName = OSQLHelper.stringContent(fieldName);

				// FIND A UNIQUE NAME BY ADDING A COUNTER
				for (int fieldIndex = 2; projections.containsKey(fieldName); ++fieldIndex) {
					fieldName += fieldIndex;
				}

				if (i.toUpperCase().startsWith("FLATTEN(")) {
					List<String> pars = OStringSerializerHelper.getParameters(i);
					if (pars.size() != 1)
						throw new OCommandSQLParsingException(
								"FLATTEN operator expects the field name as parameter. Example FLATTEN( outEdges )");
					flattenField = pars.get(0).trim();

					// BY PASS THIS AS PROJECTION BUT TREAT IT AS SPECIAL
					projections = null;
					continue;
				}

				projections.put(fieldName, OSQLHelper.parseValue(database, this, i));
			}
		}

		currentPos = fromPosition + KEYWORD_FROM.length() + 1;

		return currentPos;
	}

	private void scanEntireClusters(final int[] clusterIds) {
		((OStorageLocal) database.getStorage()).browse(database.getId(), clusterIds, rangeFrom, rangeTo, this, database.newInstance(),
				false);
	}

	private void applyOrderBy() {
		if (orderedFields == null || tempResult == null)
			return;

		ODocumentSorter.sort(tempResult, orderedFields);
		orderedFields.clear();
	}

	/**
	 * Extract the content of collections and/or links and put it as result
	 */
	private void applyFlatten() {
		if (flattenField == null)
			return;

		final List<ODocument> finalResult = new ArrayList<ODocument>();
		Object fieldValue;
		for (ODocument record : tempResult) {
			fieldValue = record.field(flattenField);
			if (fieldValue instanceof Collection<?>) {
				for (Object o : ((Collection<?>) fieldValue)) {
					if (o instanceof ODocument)
						finalResult.add((ODocument) o);
				}
			} else if (fieldValue instanceof ODocument)
				finalResult.add((ODocument) fieldValue);
		}

		tempResult = finalResult;
	}

	private void sendResultToListener(final ODocument iRecord) {
		if (projections != null) {
			final ODocument result = new ODocument(database);

			for (Entry<String, Object> projection : projections.entrySet()) {
				if (projection.getValue() instanceof OSQLFilterItemField) {
					result.field(projection.getKey(), ((OSQLFilterItemField) projection.getValue()).getValue(iRecord));
				} else
					result.field(projection.getKey(), projection.getValue());
			}

			request.getResultListener().result(result);
		} else
			request.getResultListener().result(iRecord);
	}
}
