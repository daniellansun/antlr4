/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Test;

import java.util.Collection;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TestDoubleKeyMap {
	@Test
	public void putGet() {
		DoubleKeyMap<String, String, Integer> map = new DoubleKeyMap<String, String, Integer>();
		assertNull(map.put("r", "c", 1));
		assertEquals(Integer.valueOf(1), map.get("r", "c"));
		assertEquals(Integer.valueOf(1), map.put("r", "c", 2));
		assertEquals(Integer.valueOf(2), map.get("r", "c"));
		assertNull(map.get("missing", "c"));
		assertNull(map.get("r", "missing"));
	}

	@Test
	public void primaryKeyViews() {
		DoubleKeyMap<String, String, Integer> map = new DoubleKeyMap<String, String, Integer>();
		map.put("r1", "c1", 1);
		map.put("r1", "c2", 2);
		map.put("r2", "c1", 3);

		assertEquals(Integer.valueOf(1), map.get("r1").get("c1"));
		assertNull(map.get("missing"));

		Collection<Integer> values = map.values("r1");
		assertEquals(2, values.size());
		assertTrue(values.contains(1));
		assertTrue(values.contains(2));
		assertNull(map.values("missing"));

		Set<String> keys = map.keySet();
		assertTrue(keys.contains("r1"));
		assertTrue(keys.contains("r2"));

		Set<String> secondary = map.keySet("r1");
		assertTrue(secondary.contains("c1"));
		assertTrue(secondary.contains("c2"));
		assertNull(map.keySet("missing"));
	}
}
