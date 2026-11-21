/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Focused unit tests for {@link PredictionContextCache}'s monomorphic
 * open-addressed tables ({@code OpenTableBase}, {@code ContextInternSet},
 * {@code ChildContextMap}, {@code JoinContextMap}), empty-context join fast
 * paths, and {@link PredictionContext}'s iterative {@code getCachedContext}.
 */
public class TestPredictionContextCacheTables {

	@Test
	public void testEmptyContextJoinFastPathsAndCaching() {
		PredictionContextCache cache = new PredictionContextCache();
		assertTrue(cache.isEnableCache());
		assertFalse(PredictionContextCache.UNCACHED.isEnableCache());
		assertNull(cache.getAsCached(null));
		PredictionContextCache.UNCACHED.clear();
		cache.clear(); // clear when already empty is a no-op

		PredictionContext s10 = cache.getChild(PredictionContext.EMPTY_FULL, 10);
		assertFalse(s10.hasEmpty());

		// EMPTY_LOCAL absorption on either side
		assertSame(PredictionContext.EMPTY_LOCAL, cache.join(PredictionContext.EMPTY_LOCAL, s10));
		assertSame(PredictionContext.EMPTY_LOCAL, cache.join(s10, PredictionContext.EMPTY_LOCAL));
		assertSame(PredictionContext.EMPTY_LOCAL, cache.join(PredictionContext.EMPTY_LOCAL, PredictionContext.EMPTY_FULL));

		// EMPTY_FULL with !other.hasEmpty() MUST go through JoinContextMap and allocate ArrayPredictionContext
		PredictionContext withEmpty = cache.join(PredictionContext.EMPTY_FULL, s10);
		assertTrue(withEmpty.hasEmpty());
		assertEquals(2, withEmpty.size());
		// Commutative cache hit (y == EMPTY_FULL, x == s10)
		assertSame(withEmpty, cache.join(s10, PredictionContext.EMPTY_FULL));

		// EMPTY_FULL with other.hasEmpty() hits O(1) fast path on either side
		assertSame(withEmpty, cache.join(PredictionContext.EMPTY_FULL, withEmpty));
		assertSame(withEmpty, cache.join(withEmpty, PredictionContext.EMPTY_FULL));
		// Self-join fast path
		assertSame(s10, cache.join(s10, s10));
		// UNCACHED delegation
		PredictionContext uncachedChild = PredictionContextCache.UNCACHED.getChild(PredictionContext.EMPTY_LOCAL, 7);
		assertEquals(7, uncachedChild.getReturnState(0));
		PredictionContext uncachedJoin = PredictionContextCache.UNCACHED.join(
			new SingletonPredictionContext(PredictionContext.EMPTY_FULL, 1),
			new SingletonPredictionContext(PredictionContext.EMPTY_FULL, 2));
		assertEquals(2, uncachedJoin.size());

		// Structural equality hit in ContextInternSet & ChildContextMap with distinct instances
		PredictionContext dupS10 = new SingletonPredictionContext(PredictionContext.EMPTY_FULL, 10);
		assertSame(s10, cache.getAsCached(dupS10));
		PredictionContext childFromDup = cache.getChild(dupS10, 20);
		assertSame(childFromDup, cache.getChild(s10, 20));
	}

	@Test
	public void testTableGrowthSparseTrackingAndAllThreeClearModes() {
		PredictionContextCache cache = new PredictionContextCache();

		// 1. Small sparse clear (usedCount <= SPARSE_TRACK_LIMIT && cap <= MAX_RETAINED_CAPACITY)
		PredictionContext p1 = cache.getChild(PredictionContext.EMPTY_LOCAL, 1);
		PredictionContext p2 = cache.getChild(PredictionContext.EMPTY_LOCAL, 2);
		assertSame(p1, cache.getChild(PredictionContext.EMPTY_LOCAL, 1));
		PredictionContext j1 = cache.join(p1, p2);
		assertSame(j1, cache.join(p2, p1));
		cache.clear();

		// 2. Grow tables to 16384 (<= MAX_RETAINED_CAPACITY), dense clear, then fill > 4096
		//    entries to exercise usedSlots doubling (128->256->...->4096) and overflow (usedCount = -1)
		int nGrow = 4200;
		PredictionContext[] arr = new PredictionContext[nGrow];
		for (int i = 0; i < nGrow; i++) {
			arr[i] = cache.getChild(PredictionContext.EMPTY_LOCAL, i + 1);
			cache.getAsCached(arr[i]);
		}
		for (int i = 0; i < nGrow - 1; i++) {
			cache.join(arr[i], arr[i + 1]);
		}
		cache.clear(); // dense clear retains 16384-slot tables
		for (int i = 0; i < nGrow; i++) {
			arr[i] = cache.getChild(PredictionContext.EMPTY_LOCAL, i + 1);
			cache.getAsCached(arr[i]);
		}
		for (int i = 0; i < nGrow - 1; i++) {
			cache.join(arr[i], arr[i + 1]);
		}
		cache.clear();

		// 3. Grow all 3 tables past MAX_RETAINED_CAPACITY (16384 -> 32768 slots)
		int bigN = 9000;
		PredictionContext[] big = new PredictionContext[bigN];
		for (int i = 0; i < bigN; i++) {
			big[i] = cache.getChild(PredictionContext.EMPTY_LOCAL, i + 1);
			cache.getAsCached(big[i]);
		}
		for (int i = 0; i < bigN - 1; i++) {
			cache.join(big[i], big[i + 1]);
		}
		assertSame(big[0], cache.getChild(PredictionContext.EMPTY_LOCAL, 1));
		assertSame(big[bigN - 1], cache.getChild(PredictionContext.EMPTY_LOCAL, bigN));
		assertSame(cache.join(big[0], big[1]), cache.join(big[1], big[0]));
		// Dense clear at size > cap/4 retains 32768-slot tables
		cache.clear();
		// Insert 2 entries into 32768-slot tables and clear() -> takes shouldShrink() branch!
		PredictionContext tiny1 = cache.getChild(PredictionContext.EMPTY_LOCAL, 1);
		PredictionContext tiny2 = cache.getChild(PredictionContext.EMPTY_LOCAL, 2);
		cache.getAsCached(tiny1);
		cache.join(tiny1, tiny2);
		cache.clear();
		// Still functional after shrinking back to INITIAL_CAPACITY
		assertEquals(1, cache.getChild(PredictionContext.EMPTY_LOCAL, 1).getReturnState(0));
	}

	@Test
	public void testIterativeGetCachedContextDeepChainsDagsAndAppendContext() {
		ConcurrentHashMap<PredictionContext, PredictionContext> map =
			new ConcurrentHashMap<PredictionContext, PredictionContext>();

		// 1. Deep singleton chain (500 frames) -> zero-map iterative path + cache hit reuse
		PredictionContext deep = PredictionContext.EMPTY_LOCAL;
		for (int i = 1; i <= 500; i++) {
			deep = new SingletonPredictionContext(deep, i);
		}
		PredictionContext cachedDeep = PredictionContext.getCachedContextInternal(deep, map);
		assertEquals(deep, cachedDeep);
		// Second identical chain reuses the cached root directly
		PredictionContext deep2 = PredictionContext.EMPTY_LOCAL;
		for (int i = 1; i <= 500; i++) {
			deep2 = new SingletonPredictionContext(deep2, i);
		}
		assertSame(cachedDeep, PredictionContext.getCachedContextInternal(deep2, map));

		// 2. Shared DAG with ArrayPredictionContext + visited map overload
		PredictionContext sharedLeaf = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, 55);
		PredictionContext arr1 = PredictionContext.join(
			new SingletonPredictionContext(sharedLeaf, 10),
			new SingletonPredictionContext(sharedLeaf, 20),
			PredictionContextCache.UNCACHED);
		PredictionContext arrTop = PredictionContext.join(
			new SingletonPredictionContext(arr1, 100),
			new SingletonPredictionContext(arr1, 200),
			PredictionContextCache.UNCACHED);
		// First pass with internal iterative cache (map has fresh entries -> updated == true)
		PredictionContext cachedTop = PredictionContext.getCachedContextInternal(arrTop, map);
		assertEquals(arrTop, cachedTop);
		assertSame(cachedTop, PredictionContext.getCachedContextInternal(arrTop, map));

		// Explicit IdentityHashMap visited overload
		PredictionContext.IdentityHashMap visited = new PredictionContext.IdentityHashMap();
		ConcurrentHashMap<PredictionContext, PredictionContext> map2 =
			new ConcurrentHashMap<PredictionContext, PredictionContext>();
		PredictionContext cachedWithVisited = PredictionContext.getCachedContext(arrTop, map2, visited);
		assertSame(cachedWithVisited, PredictionContext.getCachedContext(arrTop, map2, visited));

		// Empty contexts and singleton-to-EMPTY_FULL / singleton-to-ArrayPredictionContext chains
		assertSame(PredictionContext.EMPTY_LOCAL, PredictionContext.getCachedContextInternal(PredictionContext.EMPTY_LOCAL, map));
		assertSame(PredictionContext.EMPTY_FULL, PredictionContext.getCachedContextInternal(PredictionContext.EMPTY_FULL, map));
		PredictionContext singleFull = new SingletonPredictionContext(PredictionContext.EMPTY_FULL, 42);
		assertEquals(singleFull, PredictionContext.getCachedContextInternal(singleFull, new ConcurrentHashMap<PredictionContext, PredictionContext>()));
		PredictionContext singleOverArray = new SingletonPredictionContext(arr1, 77);
		assertEquals(singleOverArray, PredictionContext.getCachedContextInternal(singleOverArray, new ConcurrentHashMap<PredictionContext, PredictionContext>()));

		// JoinContextMap overwrite of existing (x, y) pair when re-inserted via recursive join
		PredictionContextCache cache2 = new PredictionContextCache();
		PredictionContext a1 = new SingletonPredictionContext(PredictionContext.EMPTY_FULL, 1);
		PredictionContext a2 = new SingletonPredictionContext(PredictionContext.EMPTY_FULL, 2);
		PredictionContext b1 = new SingletonPredictionContext(a1, 10);
		PredictionContext b2 = new SingletonPredictionContext(a2, 20);
		assertNotNull(cache2.join(b1, b2));

		// 3. ArrayPredictionContext.appendContext(suffix, null) when hasEmpty() is true
		PredictionContext withEmpty = PredictionContext.join(
			new SingletonPredictionContext(PredictionContext.EMPTY_FULL, 10),
			PredictionContext.EMPTY_FULL,
			PredictionContextCache.UNCACHED);
		assertTrue(withEmpty.hasEmpty());
		PredictionContext suffix = new SingletonPredictionContext(PredictionContext.EMPTY_FULL, 20);
		PredictionContext appended = withEmpty.appendContext(suffix, null);
		assertNotNull(appended);
		assertEquals(2, appended.size());

		// 4. Protected key classes backward compatibility
		List<PredictionContext> items = new ArrayList<PredictionContext>();
		items.add(sharedLeaf);
		PredictionContextCache.PredictionContextAndInt k1 =
			new PredictionContextCache.PredictionContextAndInt(sharedLeaf, 7);
		PredictionContextCache.PredictionContextAndInt k2 =
			new PredictionContextCache.PredictionContextAndInt(new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, 55), 7);
		assertEquals(k1, k2);
		assertEquals(k1.hashCode(), k2.hashCode());
		assertFalse(k1.equals("x"));

		PredictionContextCache.IdentityCommutativePredictionContextOperands j1 =
			new PredictionContextCache.IdentityCommutativePredictionContextOperands(sharedLeaf, suffix);
		PredictionContextCache.IdentityCommutativePredictionContextOperands j2 =
			new PredictionContextCache.IdentityCommutativePredictionContextOperands(suffix, sharedLeaf);
		assertEquals(j1, j2);
		assertEquals(j1.hashCode(), j2.hashCode());
		assertSame(sharedLeaf, j1.getX());
		assertSame(suffix, j1.getY());
		assertFalse(j1.equals("x"));
	}
}
