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

import com.orientechnologies.orient.core.db.record.ODatabaseRecord;

/**
 * SQL INSERT command.
 * 
 * @author luca
 * 
 */
public class OCommandSQLInsert extends OCommandSQLAbstract {

	private static final String	KEYWORD_INTO	= "INTO";

	protected OCommandSQLInsert(final String iText, final String iTextUpperCase, final ODatabaseRecord<?> iDatabase) {
		super(iText, iTextUpperCase, iDatabase);
	}

	public Object execute() {
		int records = 0;

		int pos = textUpperCase.indexOf(KEYWORD_INTO);
		if (pos == -1)
			throw new OCommandSQLParsingException("Keyword " + KEYWORD_INTO + " not found", text, 0);

		pos += KEYWORD_INTO.length() + 1;

		StringBuilder word = new StringBuilder();
		pos = OSQLHelper.nextWord(text, textUpperCase, pos, word, true);
		if (pos == -1)
			throw new OCommandSQLParsingException("Invalid cluster/class name", text, pos);

		return records;
	}
}