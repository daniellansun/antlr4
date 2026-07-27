/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import java.util.Collection;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TestFlexibleHashMap {
	@Test
	public void putGetContains() {
		FlexibleHashMap<String, Integer> map = new FlexibleHashMap<String, Integer>();
		assertTrue(map.isEmpty());
		assertNull(map.put("a", 1));
		assertEquals(Integer.valueOf(1), map.get("a"));
		assertTrue(map.containsKey("a"));
		assertFalse(map.containsKey("b"));
		assertNull(map.get(null));
		assertNull(map.put(null, 99));
		assertFalse(map.containsKey(null));
		assertEquals(1, map.size());
	}

	@Test
	public void putReplacesValue() {
		FlexibleHashMap<String, Integer> map = new FlexibleHashMap<String, Integer>();
		map.put("a", 1);
		// implementation increments n on replace as well
		Integer prev = map.put("a", 2);
		assertEquals(Integer.valueOf(1), prev);
		assertEquals(Integer.valueOf(2), map.get("a"));
	}

	@Test
	public void constructorsAndExpand() {
		FlexibleHashMap<String, Integer> map = new FlexibleHashMap<String, Integer>(ObjectEqualityComparator.INSTANCE);
		FlexibleHashMap<Integer, String> small = new FlexibleHashMap<Integer, String>(null, 2, 2);
		for (int i = 0; i < 30; i++) {
			small.put(Integer.valueOf(i), "v" + i);
		}
		assertTrue(small.size() >= 30);
		assertEquals("v0", small.get(Integer.valueOf(0)));
		assertEquals("v29", small.get(Integer.valueOf(29)));
	}

	@Test
	public void valuesClearToString() {
		FlexibleHashMap<String, Integer> map = new FlexibleHashMap<String, Integer>();
		assertEquals("{}", map.toString());
		map.put("a", 1);
		map.put("b", 2);
		Collection<Integer> values = map.values();
		assertEquals(2, values.size());
		assertTrue(values.contains(1));
		assertTrue(values.contains(2));
		assertTrue(map.toString().contains("a:1") || map.toString().contains("b:2"));
		assertTrue(map.toTableString().length() > 0);

		map.clear();
		assertTrue(map.isEmpty());
		assertEquals("{}", map.toString());
	}

	@Test
	public void hashCodeStable() {
		FlexibleHashMap<String, Integer> map = new FlexibleHashMap<String, Integer>();
		map.put("x", 1);
		map.put("y", 2);
		assertEquals(map.hashCode(), map.hashCode());
	}

	@Test
	public void unsupportedOperations() {
		final FlexibleHashMap<String, Integer> map = new FlexibleHashMap<String, Integer>();
		map.put("a", 1);
		assertThrows(UnsupportedOperationException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				map.remove("a");
			}
		});
		assertThrows(UnsupportedOperationException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				map.putAll(new HashMap<String, Integer>());
			}
		});
		assertThrows(UnsupportedOperationException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				map.keySet();
			}
		});
		assertThrows(UnsupportedOperationException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				map.entrySet();
			}
		});
		assertThrows(UnsupportedOperationException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				map.containsValue(1);
			}
		});
		assertThrows(UnsupportedOperationException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				map.equals(map);
			}
		});
	}

	@Test
	public void entryToString() {
		FlexibleHashMap.Entry<String, Integer> e = new FlexibleHashMap.Entry<String, Integer>("k", 5);
		assertEquals("k:5", e.toString());
	}
}
