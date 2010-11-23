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
package com.orientechnologies.orient.test.database.auto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.impl.ODocument;

@SuppressWarnings("unchecked")
@Test
public class JSONTest {
	@Parameters(value = "url")
	public JSONTest(final String iURL) {
	}

	@Test
	public void testEmbeddedList() {
		ODocument newDoc = new ODocument();

		final ArrayList<ODocument> list = new ArrayList<ODocument>();
		newDoc.field("embeddedList", list, OType.EMBEDDEDLIST);
		list.add(new ODocument().field("name", "Luca"));
		list.add(new ODocument().field("name", "Marcus"));

		String json = newDoc.toJSON();
		ODocument loadedDoc = new ODocument().fromJSON(json);

		Assert.assertTrue(newDoc.hasSameContentOf(loadedDoc));

		Assert.assertTrue(loadedDoc.containsField("embeddedList"));
		Assert.assertTrue(loadedDoc.field("embeddedList") instanceof List<?>);
		Assert.assertTrue(((List<ODocument>) loadedDoc.field("embeddedList")).get(0) instanceof ODocument);

		ODocument d = ((List<ODocument>) loadedDoc.field("embeddedList")).get(0);
		Assert.assertEquals(d.field("name"), "Luca");
		d = ((List<ODocument>) loadedDoc.field("embeddedList")).get(1);
		Assert.assertEquals(d.field("name"), "Marcus");
	}

	@Test
	public void testEmptyEmbeddedMap() {
		ODocument newDoc = new ODocument();

		final Map<String, ODocument> map = new HashMap<String, ODocument>();
		newDoc.field("embeddedMap", map, OType.EMBEDDEDMAP);

		String json = newDoc.toJSON();
		ODocument loadedDoc = new ODocument().fromJSON(json);

		Assert.assertTrue(newDoc.hasSameContentOf(loadedDoc));

		Assert.assertTrue(loadedDoc.containsField("embeddedMap"));
		Assert.assertTrue(loadedDoc.field("embeddedMap") instanceof Map<?, ?>);

		final Map<String, ODocument> loadedMap = loadedDoc.field("embeddedMap");
		Assert.assertEquals(loadedMap.size(), 0);
	}

	@Test
	public void testEmbeddedMap() {
		ODocument newDoc = new ODocument();

		final Map<String, ODocument> map = new HashMap<String, ODocument>();
		newDoc.field("map", map);
		map.put("Luca", new ODocument().field("name", "Luca"));
		map.put("Marcus", new ODocument().field("name", "Marcus"));
		map.put("Cesare", new ODocument().field("name", "Cesare"));

		String json = newDoc.toJSON();
		ODocument loadedDoc = new ODocument().fromJSON(json);

		Assert.assertTrue(newDoc.hasSameContentOf(loadedDoc));

		Assert.assertTrue(loadedDoc.containsField("map"));
		Assert.assertTrue(loadedDoc.field("map") instanceof Map<?, ?>);
		Assert.assertTrue(((Map<String, ODocument>) loadedDoc.field("map")).values().iterator().next() instanceof ODocument);

		ODocument d = ((Map<String, ODocument>) loadedDoc.field("map")).get("Luca");
		Assert.assertEquals(d.field("name"), "Luca");

		d = ((Map<String, ODocument>) loadedDoc.field("map")).get("Marcus");
		Assert.assertEquals(d.field("name"), "Marcus");

		d = ((Map<String, ODocument>) loadedDoc.field("map")).get("Cesare");
		Assert.assertEquals(d.field("name"), "Cesare");
	}

	@Test
	public void testMerge() {
		ODocument doc1 = new ODocument();
		final ArrayList<String> list = new ArrayList<String>();
		doc1.field("embeddedList", list, OType.EMBEDDEDLIST);
		list.add("Luca");
		list.add("Marcus");
		list.add("Jay");
		doc1.field("salary", 10000);
		doc1.field("years", 16);

		ODocument doc2 = new ODocument();
		final ArrayList<String> list2 = new ArrayList<String>();
		doc2.field("embeddedList", list2, OType.EMBEDDEDLIST);
		list2.add("Luca");
		list2.add("Michael");
		doc2.field("years", 32);

		ODocument docMerge1 = doc1.copy();
		docMerge1.merge(doc2, true, true);

		Assert.assertTrue(docMerge1.containsField("embeddedList"));
		Assert.assertTrue(docMerge1.field("embeddedList") instanceof List<?>);
		Assert.assertEquals(((List<String>) docMerge1.field("embeddedList")).size(), 4);
		Assert.assertTrue(((List<String>) docMerge1.field("embeddedList")).get(0) instanceof String);
		Assert.assertEquals(((Integer) docMerge1.field("salary")).intValue(), 10000);
		Assert.assertEquals(((Integer) docMerge1.field("years")).intValue(), 32);

		ODocument docMerge2 = doc1.copy();
		docMerge2.merge(doc2, true, false);

		Assert.assertTrue(docMerge2.containsField("embeddedList"));
		Assert.assertTrue(docMerge2.field("embeddedList") instanceof List<?>);
		Assert.assertEquals(((List<String>) docMerge2.field("embeddedList")).size(), 2);
		Assert.assertTrue(((List<String>) docMerge2.field("embeddedList")).get(0) instanceof String);
		Assert.assertEquals(((Integer) docMerge2.field("salary")).intValue(), 10000);
		Assert.assertEquals(((Integer) docMerge2.field("years")).intValue(), 32);

		ODocument docMerge3 = doc1.copy();
		docMerge3.merge(doc2, false, false);

		Assert.assertTrue(docMerge3.containsField("embeddedList"));
		Assert.assertTrue(docMerge3.field("embeddedList") instanceof List<?>);
		Assert.assertEquals(((List<String>) docMerge3.field("embeddedList")).size(), 3);
		Assert.assertTrue(((List<String>) docMerge3.field("embeddedList")).get(0) instanceof String);
		Assert.assertEquals(((Integer) docMerge3.field("salary")).intValue(), 10000);
		Assert.assertEquals(((Integer) docMerge3.field("years")).intValue(), 16);
	}
}
