/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for the package-private LL(1) primitive concurrent map and its
 * JDK {@link ConcurrentMap} view used as {@link ATN#LL1Table}.
 */
public class TestConcurrentIntIntMap {

	@Test
	public void getMissingReturnsZero() {
		ConcurrentIntIntMap map = new ConcurrentIntIntMap();
		assertEquals(ConcurrentIntIntMap.MISSING, map.get(42));
		assertEquals(0, map.size());
	}

	@Test
	public void putGetAndIdempotentPut() {
		ConcurrentIntIntMap map = new ConcurrentIntIntMap();
		assertEquals(ConcurrentIntIntMap.MISSING, map.put((0 << 16) + 1, 3));
		assertEquals(3, map.get((0 << 16) + 1));
		assertEquals(1, map.size());
		// Same mapping: no growth, value unchanged; previous returned.
		assertEquals(3, map.put((0 << 16) + 1, 3));
		assertEquals(3, map.get((0 << 16) + 1));
		assertEquals(1, map.size());
		// Overwrite returns previous.
		assertEquals(3, map.put((0 << 16) + 1, 9));
		assertEquals(9, map.get((0 << 16) + 1));
	}

	@Test
	public void putIfAbsentAtomicSemantics() {
		ConcurrentIntIntMap map = new ConcurrentIntIntMap();
		assertEquals(ConcurrentIntIntMap.MISSING, map.putIfAbsent(1, 5));
		assertEquals(5, map.putIfAbsent(1, 7));
		assertEquals(5, map.get(1));
	}

	@Test
	public void removeAndConditionalRemove() {
		ConcurrentIntIntMap map = new ConcurrentIntIntMap();
		assertEquals(ConcurrentIntIntMap.MISSING, map.remove(1));
		map.put(1, 2);
		assertFalse(map.remove(1, 99));
		assertEquals(2, map.get(1));
		assertTrue(map.remove(1, 2));
		assertEquals(ConcurrentIntIntMap.MISSING, map.get(1));
		assertFalse(map.remove(1, 0)); // MISSING expected value never matches
	}

	@Test
	public void replaceVariants() {
		ConcurrentIntIntMap map = new ConcurrentIntIntMap();
		assertFalse(map.replace(1, 2, 3));
		assertEquals(ConcurrentIntIntMap.MISSING, map.replace(1, 3));
		map.put(1, 2);
		assertTrue(map.replace(1, 2, 2)); // same value still true
		assertTrue(map.replace(1, 2, 4));
		assertEquals(4, map.get(1));
		assertFalse(map.replace(1, 2, 5));
		assertEquals(4, map.replace(1, 6));
		assertEquals(6, map.get(1));
	}

	@Test
	public void clearEmpties() {
		ConcurrentIntIntMap map = new ConcurrentIntIntMap();
		map.put(1, 1);
		map.put(2, 2);
		map.clear();
		assertEquals(ConcurrentIntIntMap.MISSING, map.get(1));
		assertEquals(0, map.size());
		map.clear(); // empty clear
		assertEquals(0, map.size());
	}

	@Test
	public void snapshotEntriesAndContainsValue() {
		ConcurrentIntIntMap map = new ConcurrentIntIntMap();
		assertEquals(0, map.snapshotEntries().length);
		assertFalse(map.containsValue(1));
		map.put(10, 1);
		map.put(20, 2);
		int[] entries = map.snapshotEntries();
		assertEquals(4, entries.length);
		Set<Integer> keys = new HashSet<Integer>();
		Set<Integer> values = new HashSet<Integer>();
		for (int i = 0; i < entries.length; i += 2) {
			keys.add(entries[i]);
			values.add(entries[i + 1]);
		}
		assertEquals(new HashSet<Integer>(java.util.Arrays.asList(10, 20)), keys);
		assertEquals(new HashSet<Integer>(java.util.Arrays.asList(1, 2)), values);
		assertTrue(map.containsValue(1));
		assertTrue(map.containsValue(2));
		assertFalse(map.containsValue(3));
		assertFalse(map.containsValue(0));
	}

	@Test
	public void concurrentReadersAndWriters() throws Exception {
		final ConcurrentIntIntMap map = new ConcurrentIntIntMap();
		final int writers = 4;
		final int keysPerWriter = 200;
		ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
		try {
			List<Future<?>> futures = new ArrayList<Future<?>>();
			final AtomicInteger reads = new AtomicInteger();
			// Background readers
			for (int r = 0; r < 2; r++) {
				futures.add(pool.submit(new Callable<Void>() {
					@Override
					public Void call() {
						for (int i = 0; i < 5000; i++) {
							map.get(i % (writers * keysPerWriter));
							reads.incrementAndGet();
						}
						return null;
					}
				}));
			}
			// Writers insert disjoint key ranges
			for (int w = 0; w < writers; w++) {
				final int base = w * keysPerWriter;
				futures.add(pool.submit(new Callable<Void>() {
					@Override
					public Void call() {
						for (int i = 0; i < keysPerWriter; i++) {
							map.put(base + i, base + i + 1);
						}
						return null;
					}
				}));
			}
			for (Future<?> f : futures) {
				f.get();
			}
			assertTrue(reads.get() > 0);
			// Every written key must be visible with the expected value.
			for (int w = 0; w < writers; w++) {
				int base = w * keysPerWriter;
				for (int i = 0; i < keysPerWriter; i++) {
					assertEquals(base + i + 1, map.get(base + i));
				}
			}
			assertEquals(writers * keysPerWriter, map.size());
		}
		finally {
			pool.shutdownNow();
		}
	}

	// -------------------------------------------------------------------------
	// ConcurrentIntIntMapView — full Map / ConcurrentMap contracts for LL1Table
	// -------------------------------------------------------------------------

	@Test
	public void viewEntrySetConsistentWithSize() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		ConcurrentMap<Integer, Integer> table = atn.LL1Table;
		assertTrue(table.isEmpty());
		assertEquals(0, table.size());
		assertTrue(table.entrySet().isEmpty());
		assertTrue(table.keySet().isEmpty());
		assertTrue(table.values().isEmpty());

		table.put(1, 2);
		table.put(3, 4);
		assertEquals(2, table.size());
		assertFalse(table.isEmpty());
		assertEquals(2, table.entrySet().size());
		assertEquals(2, table.keySet().size());
		assertEquals(2, table.values().size());

		Set<Integer> keys = new HashSet<Integer>();
		Set<Integer> values = new HashSet<Integer>();
		for (Map.Entry<Integer, Integer> e : table.entrySet()) {
			keys.add(e.getKey());
			values.add(e.getValue());
		}
		assertEquals(new HashSet<Integer>(java.util.Arrays.asList(1, 3)), keys);
		assertEquals(new HashSet<Integer>(java.util.Arrays.asList(2, 4)), values);
	}

	@Test
	public void viewPutGetRemoveAndContains() {
		ConcurrentMap<Integer, Integer> table =
			new ConcurrentIntIntMapView(new ConcurrentIntIntMap());
		assertNull(table.put(5, 6));
		assertEquals(Integer.valueOf(6), table.get(5));
		assertTrue(table.containsKey(5));
		assertTrue(table.containsValue(6));
		assertFalse(table.containsKey(9));
		assertFalse(table.containsValue(9));
		assertFalse(table.containsKey("x"));
		assertFalse(table.containsValue("x"));
		assertNull(table.get("x"));
		assertEquals(Integer.valueOf(6), table.put(5, 7));
		assertEquals(Integer.valueOf(7), table.remove(5));
		assertNull(table.remove(5));
		assertNull(table.remove("x"));
	}

	@Test
	public void viewPutIfAbsentAndReplace() {
		ConcurrentMap<Integer, Integer> table =
			new ConcurrentIntIntMapView(new ConcurrentIntIntMap());
		assertNull(table.putIfAbsent(1, 2));
		assertEquals(Integer.valueOf(2), table.putIfAbsent(1, 3));
		assertEquals(Integer.valueOf(2), table.get(1));
		assertTrue(table.replace(1, 2, 4));
		assertFalse(table.replace(1, 2, 5));
		assertEquals(Integer.valueOf(4), table.replace(1, 6));
		assertNull(table.replace(99, 1));
	}

	@Test
	public void viewRemoveKeyValue() {
		ConcurrentMap<Integer, Integer> table =
			new ConcurrentIntIntMapView(new ConcurrentIntIntMap());
		table.put(1, 2);
		assertFalse(table.remove(1, 3));
		assertTrue(table.remove(1, 2));
		assertFalse(table.remove(1, 2));
		assertFalse(table.remove("x", 1));
		assertFalse(table.remove(1, "x"));
	}

	@Test
	public void viewNullKeysAndValuesRejected() {
		ConcurrentMap<Integer, Integer> table =
			new ConcurrentIntIntMapView(new ConcurrentIntIntMap());
		try {
			table.put(null, 1);
			fail("expected NPE");
		}
		catch (NullPointerException expected) {
			// ok
		}
		try {
			table.put(1, null);
			fail("expected NPE");
		}
		catch (NullPointerException expected) {
			// ok
		}
		try {
			table.putIfAbsent(null, 1);
			fail("expected NPE");
		}
		catch (NullPointerException expected) {
			// ok
		}
		try {
			table.replace(1, null);
			fail("expected NPE");
		}
		catch (NullPointerException expected) {
			// ok
		}
		try {
			table.replace(1, 2, null);
			fail("expected NPE");
		}
		catch (NullPointerException expected) {
			// ok
		}
	}

	@Test
	public void viewEntrySetIteratorRemoveAndSetValue() {
		ConcurrentMap<Integer, Integer> table =
			new ConcurrentIntIntMapView(new ConcurrentIntIntMap());
		table.put(1, 10);
		table.put(2, 20);
		Iterator<Map.Entry<Integer, Integer>> it = table.entrySet().iterator();
		assertTrue(it.hasNext());
		Map.Entry<Integer, Integer> first = it.next();
		assertNotNull(first.getKey());
		Integer prev = first.setValue(99);
		assertTrue(Integer.valueOf(10).equals(prev) || Integer.valueOf(20).equals(prev));
		assertEquals(Integer.valueOf(99), table.get(first.getKey()));
		// setValue wrote through
		assertTrue(table.containsValue(99));
		it.remove();
		assertEquals(1, table.size());

		// entrySet.contains / remove by entry
		Map.Entry<Integer, Integer> remaining = table.entrySet().iterator().next();
		assertTrue(table.entrySet().contains(remaining));
		assertTrue(table.entrySet().remove(remaining));
		assertTrue(table.isEmpty());
	}

	@Test
	public void viewEqualsHashCodeAndToStringWithEntries() {
		ConcurrentMap<Integer, Integer> table =
			new ConcurrentIntIntMapView(new ConcurrentIntIntMap());
		table.put(1, 2);
		Map<Integer, Integer> jdk = new HashMap<Integer, Integer>();
		jdk.put(1, 2);
		assertEquals(jdk, table);
		assertEquals(table, jdk);
		assertEquals(jdk.hashCode(), table.hashCode());
		assertTrue(table.toString().contains("1"));
		assertTrue(table.toString().contains("2"));
	}

	@Test
	public void viewClearViaEntrySet() {
		ConcurrentMap<Integer, Integer> table =
			new ConcurrentIntIntMapView(new ConcurrentIntIntMap());
		table.put(1, 1);
		table.put(2, 2);
		table.entrySet().clear();
		assertTrue(table.isEmpty());
		assertEquals(0, table.entrySet().size());
	}

	@Test
	public void atnLl1TableAndPrimitiveCacheShareStore() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		atn.LL1Table.put((0 << 16) + 1, 3);
		assertEquals(3, atn.ll1Cache.get((0 << 16) + 1));
		atn.ll1Cache.put((0 << 16) + 2, 4);
		assertEquals(Integer.valueOf(4), atn.LL1Table.get((0 << 16) + 2));
		atn.clearDFA();
		assertTrue(atn.LL1Table.isEmpty());
		assertEquals(0, atn.ll1Cache.size());
	}

	@Test
	public void viewMutationsSyncAndClearDenseTable() {
		ATN atn = new ATN(ATNType.PARSER, 4);
		assertNull(atn.ll1Dense);
		atn.LL1Table.put((0 << 16) + 1, 2); // no dense yet
		atn.ensureLl1Dense(2);
		assertNotNull(atn.ll1Dense);
		assertEquals(5, atn.ll1Stride);

		atn.LL1Table.put((0 << 16) + 1, 3);
		assertEquals(3, atn.ll1Dense[1]);
		atn.LL1Table.put((0 << 16) + 1, 0);
		assertEquals(0, atn.ll1Dense[1]);
		atn.LL1Table.put((0 << 16) + 2, Short.MAX_VALUE + 1);
		assertEquals(0, atn.ll1Dense[2]);
		assertNull(atn.LL1Table.putIfAbsent((0 << 16) + 3, 4));
		assertEquals(4, atn.ll1Dense[3]);
		assertEquals(Integer.valueOf(4), atn.LL1Table.putIfAbsent((0 << 16) + 3, 9));
		assertTrue(atn.LL1Table.replace((0 << 16) + 3, 4, 5));
		assertEquals(5, atn.ll1Dense[3]);
		assertFalse(atn.LL1Table.replace((0 << 16) + 3, 4, 6));
		assertEquals(Integer.valueOf(5), atn.LL1Table.replace((0 << 16) + 3, 7));
		assertEquals(7, atn.ll1Dense[3]);
		assertNull(atn.LL1Table.replace(99 << 16, 1));
		assertEquals(Integer.valueOf(7), atn.LL1Table.remove((0 << 16) + 3));
		assertEquals(0, atn.ll1Dense[3]);
		assertNull(atn.LL1Table.remove((0 << 16) + 3));

		atn.LL1Table.put((0 << 16) + 1, 9);
		atn.LL1Table.clear();
		assertEquals(0, atn.ll1Dense[1]);
		assertTrue(atn.LL1Table.isEmpty());
	}

	@Test
	public void ensureLl1DenseCoversStrideOverflowAndFill() {
		ATN negativeMax = new ATN(ATNType.PARSER, -1);
		negativeMax.ensureLl1Dense(3);
		assertEquals(1, negativeMax.ll1Stride);
		assertEquals(3, negativeMax.ll1Dense.length);

		ATN overflow = new ATN(ATNType.PARSER, Integer.MAX_VALUE - 1);
		overflow.ensureLl1Dense(4);
		assertEquals(0, overflow.ll1Dense.length);

		ATN atn = new ATN(ATNType.PARSER, 2);
		atn.ensureLl1Dense(1);
		int stride = atn.ll1Stride;
		atn.ll1Dense[0] = 11;
		atn.ensureLl1Dense(1);
		assertEquals(stride, atn.ll1Stride);
		assertEquals(0, atn.ll1Dense[0]);
		atn.ensureLl1Dense(4);
		assertEquals(4 * stride, atn.ll1Dense.length);
	}

	@Test
	public void viewConcurrentPutIfAbsent() throws Exception {
		final ConcurrentMap<Integer, Integer> table =
			new ConcurrentIntIntMapView(new ConcurrentIntIntMap());
		final int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Integer>> futures = new ArrayList<Future<Integer>>();
			for (int t = 0; t < threads; t++) {
				futures.add(pool.submit(new Callable<Integer>() {
					@Override
					public Integer call() {
						return table.putIfAbsent(42, 7);
					}
				}));
			}
			int nulls = 0;
			int sevens = 0;
			for (Future<Integer> f : futures) {
				Integer r = f.get();
				if (r == null) {
					nulls++;
				}
				else {
					assertEquals(Integer.valueOf(7), r);
					sevens++;
				}
			}
			assertEquals(1, nulls);
			assertEquals(threads - 1, sevens);
			assertEquals(Integer.valueOf(7), table.get(42));
			assertEquals(1, table.size());
		}
		finally {
			pool.shutdownNow();
		}
	}
}
