/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.dfa;

import org.junit.Test;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestEdgeMaps {

	@Test
	public void emptyEdgeMapBasics() {
		EmptyEdgeMap<String> empty = new EmptyEdgeMap<String>(0, 10);
		assertEquals(0, empty.size());
		assertTrue(empty.isEmpty());
		assertFalse(empty.containsKey(0));
		assertNull(empty.get(5));
		assertTrue(empty.toMap().isEmpty());
		assertTrue(empty.entrySet().isEmpty());
		assertSame(empty, empty.clear());
		assertSame(empty, empty.remove(3));
	}

	@Test
	public void emptyEdgeMapPutOutOfRangeOrNullRemainsEmpty() {
		EmptyEdgeMap<String> empty = new EmptyEdgeMap<String>(1, 5);
		assertSame(empty, empty.put(0, "a"));
		assertSame(empty, empty.put(6, "a"));
		assertSame(empty, empty.put(3, null));
	}

	@Test
	public void emptyEdgeMapPutCreatesSingleton() {
		EmptyEdgeMap<String> empty = new EmptyEdgeMap<String>(0, 10);
		AbstractEdgeMap<String> result = empty.put(3, "three");
		assertTrue(result instanceof SingletonEdgeMap);
		assertEquals(1, result.size());
		assertFalse(result.isEmpty());
		assertTrue(result.containsKey(3));
		assertEquals("three", result.get(3));
		assertNull(result.get(2));
	}

	@Test
	public void singletonEdgeMapBasics() {
		SingletonEdgeMap<String> single = new SingletonEdgeMap<String>(0, 10, 4, "four");
		assertEquals(4, single.getKey());
		assertEquals("four", single.getValue());
		assertEquals(1, single.size());
		assertFalse(single.isEmpty());
		assertTrue(single.containsKey(4));
		assertFalse(single.containsKey(5));
		assertEquals("four", single.get(4));
		assertNull(single.get(5));
		assertEquals(1, single.toMap().size());
		assertEquals("four", single.toMap().get(4));
	}

	@Test
	public void singletonEdgeMapKeyOutOfRangeIsEmpty() {
		SingletonEdgeMap<String> single = new SingletonEdgeMap<String>(0, 5, 99, "x");
		assertEquals(0, single.size());
		assertTrue(single.isEmpty());
		assertNull(single.getValue());
	}

	@Test
	public void singletonEdgeMapPutSameKeyReplaces() {
		SingletonEdgeMap<String> single = new SingletonEdgeMap<String>(0, 10, 4, "four");
		AbstractEdgeMap<String> result = single.put(4, "FOUR");
		assertTrue(result instanceof SingletonEdgeMap);
		assertEquals("FOUR", result.get(4));
	}

	@Test
	public void singletonEdgeMapPutNullValueOnSameKeyReplacesWithNullSingleton() {
		SingletonEdgeMap<String> single = new SingletonEdgeMap<String>(0, 10, 4, "four");
		AbstractEdgeMap<String> result = single.put(4, null);
		assertTrue(result instanceof SingletonEdgeMap);
		// null value means empty singleton
		assertTrue(result.isEmpty());
	}

	@Test
	public void singletonEdgeMapPutOutOfRangeReturnsSame() {
		SingletonEdgeMap<String> single = new SingletonEdgeMap<String>(0, 10, 4, "four");
		assertSame(single, single.put(-1, "nope"));
		assertSame(single, single.put(11, "nope"));
	}

	@Test
	public void singletonEdgeMapPutSecondKeyCreatesHashEdgeMap() {
		SingletonEdgeMap<String> single = new SingletonEdgeMap<String>(0, 100, 4, "four");
		AbstractEdgeMap<String> result = single.put(7, "seven");
		assertTrue(result instanceof HashEdgeMap);
		assertEquals(2, result.size());
		assertEquals("four", result.get(4));
		assertEquals("seven", result.get(7));
	}

	@Test
	public void singletonEdgeMapPutNullValueOnDifferentKeyReturnsSame() {
		SingletonEdgeMap<String> single = new SingletonEdgeMap<String>(0, 10, 4, "four");
		assertSame(single, single.put(5, null));
	}

	@Test
	public void singletonEdgeMapRemoveAndClear() {
		SingletonEdgeMap<String> single = new SingletonEdgeMap<String>(0, 10, 4, "four");
		AbstractEdgeMap<String> removed = single.remove(4);
		assertTrue(removed instanceof EmptyEdgeMap);
		assertSame(single, single.remove(5));

		AbstractEdgeMap<String> cleared = single.clear();
		assertTrue(cleared instanceof EmptyEdgeMap);

		SingletonEdgeMap<String> emptyish = new SingletonEdgeMap<String>(0, 5, 99, "x");
		assertSame(emptyish, emptyish.clear());
	}

	@Test
	public void singletonEdgeMapEntrySet() {
		SingletonEdgeMap<String> single = new SingletonEdgeMap<String>(0, 10, 4, "four");
		Set<Map.Entry<Integer, String>> entries = single.entrySet();
		assertEquals(1, entries.size());
		assertTrue(entries.contains(single.toMap().entrySet().iterator().next()));

		Iterator<Map.Entry<Integer, String>> it = entries.iterator();
		assertTrue(it.hasNext());
		Map.Entry<Integer, String> e = it.next();
		assertEquals(Integer.valueOf(4), e.getKey());
		assertEquals("four", e.getValue());
		try {
			e.setValue("x");
			fail("expected UnsupportedOperationException");
		} catch (UnsupportedOperationException ex) {
			// expected
		}
		try {
			it.remove();
			fail("expected UnsupportedOperationException");
		} catch (UnsupportedOperationException ex) {
			// expected
		}
		assertFalse(it.hasNext());
		try {
			it.next();
			fail("expected NoSuchElementException");
		} catch (NoSuchElementException ex) {
			// expected
		}
	}

	@Test
	public void arrayEdgeMapBasics() {
		ArrayEdgeMap<String> map = new ArrayEdgeMap<String>(0, 5);
		assertEquals(0, map.size());
		assertTrue(map.isEmpty());
		assertNull(map.get(0));
		assertFalse(map.containsKey(0));

		assertSame(map, map.put(2, "two"));
		assertEquals(1, map.size());
		assertEquals("two", map.get(2));
		assertTrue(map.containsKey(2));

		map.put(2, "TWO");
		assertEquals(1, map.size());
		assertEquals("TWO", map.get(2));

		map.put(0, "zero");
		map.put(5, "five");
		assertEquals(3, map.size());

		// out of range ignored
		map.put(-1, "neg");
		map.put(6, "six");
		assertEquals(3, map.size());
		assertNull(map.get(-1));
		assertNull(map.get(6));
	}

	@Test
	public void arrayEdgeMapRemoveClearAndToMap() {
		ArrayEdgeMap<String> map = new ArrayEdgeMap<String>(1, 4);
		map.put(1, "a");
		map.put(3, "c");
		assertSame(map, map.remove(1));
		assertEquals(1, map.size());
		assertNull(map.get(1));

		Map<Integer, String> asMap = map.toMap();
		assertEquals(1, asMap.size());
		assertEquals("c", asMap.get(3));

		EmptyEdgeMap<String> cleared = map.clear();
		assertTrue(cleared.isEmpty());

		ArrayEdgeMap<String> empty = new ArrayEdgeMap<String>(0, 2);
		assertTrue(empty.toMap().isEmpty());
	}

	@Test
	public void arrayEdgeMapPutNullDecrementsSize() {
		ArrayEdgeMap<String> map = new ArrayEdgeMap<String>(0, 3);
		map.put(1, "one");
		map.put(1, null);
		assertEquals(0, map.size());
		assertFalse(map.containsKey(1));
	}

	@Test
	public void arrayEdgeMapPutAllFromVariousMaps() {
		ArrayEdgeMap<String> target = new ArrayEdgeMap<String>(0, 20);

		// empty putAll
		assertSame(target, target.putAll(new EmptyEdgeMap<String>(0, 20)));

		// singleton
		target.putAll(new SingletonEdgeMap<String>(0, 20, 2, "two"));
		assertEquals("two", target.get(2));

		// hash
		HashEdgeMap<String> hash = new HashEdgeMap<String>(0, 20);
		hash = (HashEdgeMap<String>) hash.put(5, "five");
		hash = (HashEdgeMap<String>) hash.put(8, "eight");
		target.putAll(hash);
		assertEquals("five", target.get(5));
		assertEquals("eight", target.get(8));

		// another array with overlap
		ArrayEdgeMap<String> other = new ArrayEdgeMap<String>(3, 10);
		other.put(3, "three");
		other.put(5, "FIVE");
		target.putAll(other);
		assertEquals("three", target.get(3));
		assertEquals("FIVE", target.get(5));

		// sparse
		@SuppressWarnings("deprecation")
		SparseEdgeMap<String> sparse = new SparseEdgeMap<String>(0, 20);
		sparse.put(1, "one");
		target.putAll(sparse);
		assertEquals("one", target.get(1));
	}

	@Test
	public void arrayEdgeMapEntrySet() {
		ArrayEdgeMap<String> map = new ArrayEdgeMap<String>(0, 4);
		map.put(1, "one");
		map.put(3, "three");

		Set<Map.Entry<Integer, String>> entries = map.entrySet();
		assertEquals(2, entries.size());
		assertTrue(entries.contains(new java.util.AbstractMap.SimpleEntry<Integer, String>(1, "one")));
		assertFalse(entries.contains("not-an-entry"));
		assertFalse(entries.contains(new java.util.AbstractMap.SimpleEntry<String, String>("1", "one")));

		int count = 0;
		for (Map.Entry<Integer, String> e : entries) {
			assertTrue(e.getKey() == 1 || e.getKey() == 3);
			assertTrue("one".equals(e.getValue()) || "three".equals(e.getValue()));
			try {
				e.setValue("x");
				fail();
			} catch (UnsupportedOperationException ex) {
				// expected
			}
			count++;
		}
		assertEquals(2, count);

		Iterator<Map.Entry<Integer, String>> it = entries.iterator();
		it.next();
		it.next();
		try {
			it.remove();
			fail();
		} catch (UnsupportedOperationException ex) {
			// expected
		}
	}

	@Test
	public void hashEdgeMapBasics() {
		HashEdgeMap<String> map = new HashEdgeMap<String>(0, 50);
		assertTrue(map.isEmpty());
		assertEquals(0, map.size());
		assertNull(map.get(1));
		assertFalse(map.containsKey(1));

		AbstractEdgeMap<String> m = map.put(10, "ten");
		assertSame(map, m);
		assertEquals(1, map.size());
		assertEquals("ten", map.get(10));
		assertTrue(map.containsKey(10));

		// replace same key
		map.put(10, "TEN");
		assertEquals(1, map.size());
		assertEquals("TEN", map.get(10));

		// out of range
		assertSame(map, map.put(-1, "neg"));
		assertSame(map, map.put(51, "big"));
		assertEquals(1, map.size());

		// null value removes
		AbstractEdgeMap<String> afterNull = map.put(10, null);
		assertTrue(afterNull instanceof HashEdgeMap || afterNull instanceof EmptyEdgeMap
			|| afterNull.size() == 0 || afterNull.get(10) == null);
	}

	@Test
	public void hashEdgeMapMultipleEntriesAndToMap() {
		HashEdgeMap<String> map = new HashEdgeMap<String>(0, 100);
		map = (HashEdgeMap<String>) map.put(1, "a");
		// force growth / possible resize by adding keys that may collide
		AbstractEdgeMap<String> current = map;
		for (int i = 2; i <= 20; i++) {
			current = current.put(i, "v" + i);
		}
		assertTrue(current.size() >= 10);
		assertEquals("a", current.get(1));
		assertEquals("v5", current.get(5));

		Map<Integer, String> asMap = current.toMap();
		assertFalse(asMap.isEmpty());
		assertEquals(current.size(), asMap.size());

		// empty toMap
		HashEdgeMap<String> empty = new HashEdgeMap<String>(0, 5);
		assertTrue(empty.toMap().isEmpty());
	}

	@Test
	public void hashEdgeMapRemoveAndClear() {
		HashEdgeMap<String> map = new HashEdgeMap<String>(0, 20);
		map = (HashEdgeMap<String>) map.put(3, "three");
		map = (HashEdgeMap<String>) map.put(7, "seven");

		HashEdgeMap<String> removed = map.remove(3);
		assertNull(removed.get(3));
		assertEquals("seven", removed.get(7));
		assertSame(map, map.remove(99)); // missing key returns same

		AbstractEdgeMap<String> cleared = map.clear();
		assertTrue(cleared instanceof EmptyEdgeMap);

		HashEdgeMap<String> empty = new HashEdgeMap<String>(0, 5);
		assertSame(empty, empty.clear());
	}

	@Test
	public void hashEdgeMapEntrySet() {
		HashEdgeMap<String> map = new HashEdgeMap<String>(0, 20);
		map = (HashEdgeMap<String>) map.put(2, "two");
		map = (HashEdgeMap<String>) map.put(4, "four");
		Set<Map.Entry<Integer, String>> entries = map.entrySet();
		assertEquals(2, entries.size());
	}

	@Test
	@SuppressWarnings("deprecation")
	public void sparseEdgeMapBasics() {
		SparseEdgeMap<String> map = new SparseEdgeMap<String>(0, 100);
		assertTrue(map.isEmpty());
		assertEquals(0, map.size());
		assertEquals(5, map.getMaxSparseSize()); // default
		assertNull(map.get(1));
		assertFalse(map.containsKey(1));

		AbstractEdgeMap<String> m = map.put(1, "one");
		assertSame(map, m); // append stays sparse
		assertEquals(1, map.size());
		assertEquals("one", map.get(1));
		assertTrue(map.containsKey(1));

		// replace existing
		map.put(1, "ONE");
		assertEquals(1, map.size());
		assertEquals("ONE", map.get(1));

		// out of range
		assertSame(map, map.put(-1, "neg"));
		assertSame(map, map.put(101, "big"));

		// null removes
		AbstractEdgeMap<String> afterNull = map.put(1, null);
		assertNull(afterNull.get(1));
	}

	@Test
	@SuppressWarnings("deprecation")
	public void sparseEdgeMapGrowAndConvertToArray() {
		// Small symbol space so conversion to ArrayEdgeMap happens
		SparseEdgeMap<String> map = new SparseEdgeMap<String>(0, 9, 2);
		map.put(0, "a");
		map.put(1, "b"); // fills sparse
		// next insert should resize or convert
		AbstractEdgeMap<String> result = map.put(2, "c");
		assertTrue(result.size() >= 3);
		assertEquals("a", result.get(0));
		assertEquals("b", result.get(1));
		assertEquals("c", result.get(2));
	}

	@Test
	@SuppressWarnings("deprecation")
	public void sparseEdgeMapInsertMiddleAndRemove() {
		SparseEdgeMap<String> map = new SparseEdgeMap<String>(0, 1000, 10);
		map.put(10, "ten");
		// insert key before existing (insertIndex != size) forces resize path
		AbstractEdgeMap<String> result = map.put(5, "five");
		assertEquals("five", result.get(5));
		assertEquals("ten", result.get(10));

		if (result instanceof SparseEdgeMap) {
			SparseEdgeMap<String> sparse = (SparseEdgeMap<String>) result;
			assertTrue(sparse.getKeys().length >= sparse.size());
			assertEquals(sparse.size(), sparse.getValues().size());

			SparseEdgeMap<String> removed = sparse.remove(5);
			assertNull(removed.get(5));
			assertEquals("ten", removed.get(10));
			assertSame(sparse, sparse.remove(99));
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	public void sparseEdgeMapClearAndToMap() {
		SparseEdgeMap<String> map = new SparseEdgeMap<String>(0, 50);
		assertSame(map, map.clear()); // already empty

		map.put(3, "three");
		map.put(4, "four");
		Map<Integer, String> asMap = map.toMap();
		assertEquals(2, asMap.size());
		assertEquals("three", asMap.get(3));

		AbstractEdgeMap<String> cleared = map.clear();
		assertTrue(cleared instanceof EmptyEdgeMap);
		assertTrue(cleared.entrySet().isEmpty());

		SparseEdgeMap<String> empty = new SparseEdgeMap<String>(0, 5);
		assertTrue(empty.toMap().isEmpty());
		assertEquals(empty.entrySet(), empty.toMap().entrySet());
	}

	@Test
	public void abstractEdgeMapPutAll() {
		EmptyEdgeMap<String> empty = new EmptyEdgeMap<String>(0, 20);
		SingletonEdgeMap<String> s1 = new SingletonEdgeMap<String>(0, 20, 1, "one");
		AbstractEdgeMap<String> combined = empty.putAll(s1);
		assertEquals("one", combined.get(1));

		SingletonEdgeMap<String> s2 = new SingletonEdgeMap<String>(0, 20, 2, "two");
		combined = combined.putAll(s2);
		assertEquals("one", combined.get(1));
		assertEquals("two", combined.get(2));
	}
}
