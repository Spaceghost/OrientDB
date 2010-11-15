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
package com.orientechnologies.orient.core.index;

import com.orientechnologies.common.factory.ODynamicFactory;
import com.orientechnologies.orient.core.exception.OConfigurationException;
import com.orientechnologies.orient.core.metadata.schema.OProperty.INDEX_TYPE;

@SuppressWarnings("unchecked")
public class OIndexFactory extends ODynamicFactory<String, Class<? extends OPropertyIndex>> {
	private static final OIndexFactory	instance	= new OIndexFactory();

	/**
	 * Register default index implementation.
	 */
	protected OIndexFactory() {
		registry.put(INDEX_TYPE.UNIQUE.toString(), OPropertyIndexUnique.class);
		registry.put(INDEX_TYPE.NOTUNIQUE.toString(), OPropertyIndexNotUnique.class);
		registry.put(INDEX_TYPE.FULLTEXT.toString(), OFullTextIndex.class);
	}

	public <T extends OPropertyIndex> T newInstance(final String iIndexType) {
		if (iIndexType == null)
			throw new IllegalArgumentException("Index type is null");

		final Class<? extends OPropertyIndex> indexClass = registry.get(iIndexType);

		if (indexClass == null)
			throw new OConfigurationException("Index type '" + iIndexType + "' is not configured");

		try {
			final OPropertyIndex index = indexClass.newInstance();
			return (T) index;
		} catch (Exception e) {
			throw new OConfigurationException("Can't create index type '" + iIndexType + "'", e);
		}
	}

	public static OIndexFactory instance() {
		return instance;
	}
}
