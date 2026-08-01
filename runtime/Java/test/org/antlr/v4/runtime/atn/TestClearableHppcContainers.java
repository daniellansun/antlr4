/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for package-private clear-optimized HPPC wrappers used on ATN
 * hot paths: {@link ClearableLongObjectHashMap} (merge index) and empty-fast
 * clear on the busy set via {@link OpenAddressedHashSet}. Empty clear must be
 * a pure no-op; non-empty clear must fully drop entries while retaining
 * capacity for reuse.
 */
public class TestClearableHppcContainers {

	@Test
	public void longMapEmptyClearIsIdempotent() {
		ClearableLongObjectHashMap<String> map = new ClearableLongObjectHashMap<String>(8);
		assertTrue(map.isEmpty());
		int tableLen = map.tableLength();
		assertTrue(tableLen > 0);

		// Empty clear must not shrink or reallocate; repeated calls are free.
		map.clear();
		map.clear();
		assertTrue(map.isEmpty());
		assertEquals(0, map.size());
		assertEquals(tableLen, map.tableLength());
	}

	@Test
	public void longMapClearDropsEntriesAndAllowsReuse() {
		ClearableLongObjectHashMap<String> map = new ClearableLongObjectHashMap<String>(4);
		map.put(1L, "a");
		map.put(2L, "b");
		map.put(0L, "zero-key"); // exercises HPPC empty-key slot
		assertEquals(3, map.size());
		assertEquals("a", map.get(1L));
		assertEquals("zero-key", map.get(0L));

		map.clear();
		assertTrue(map.isEmpty());
		assertNull(map.get(1L));
		assertNull(map.get(0L));
		assertFalse(map.containsKey(2L));

		// Capacity preserved: table length unchanged; reuse without rehash cost.
		int tableLen = map.tableLength();
		map.put(9L, "c");
		assertEquals(1, map.size());
		assertEquals("c", map.get(9L));
		assertEquals(tableLen, map.tableLength());

		// Second clear after use, then empty clear again.
		map.clear();
		assertTrue(map.isEmpty());
		map.clear();
		assertTrue(map.isEmpty());
	}

	@Test
	public void longMapDefaultConstructorAndClonePreserveClearableType() {
		ClearableLongObjectHashMap<ATNConfig> map = new ClearableLongObjectHashMap<ATNConfig>();
		BasicState state = new BasicState();
		state.stateNumber = 3;
		ATNConfig config = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		map.put(42L, config);

		@SuppressWarnings("unchecked")
		ClearableLongObjectHashMap<ATNConfig> copy =
			(ClearableLongObjectHashMap<ATNConfig>) map.clone();
		assertEquals(1, copy.size());
		assertSame(config, copy.get(42L));
		copy.clear();
		assertTrue(copy.isEmpty());
		// Original unaffected.
		assertEquals(1, map.size());
		copy.clear(); // empty clear on clone
		assertTrue(copy.isEmpty());
	}

	@Test
	public void openAddressedBusySetEmptyClearIsIdempotent() {
		// Busy-set path: empty clear after every close must not pay bulk fill.
		OpenAddressedHashSet<String> set = new OpenAddressedHashSet<String>(8);
		assertTrue(set.isEmpty());
		set.clear();
		set.clear();
		assertTrue(set.isEmpty());
		assertEquals(0, set.size());
	}

	@Test
	public void openAddressedBusySetClearDropsElementsAndAllowsReuse() {
		OpenAddressedHashSet<String> set = new OpenAddressedHashSet<String>(4);
		assertTrue(set.add("x"));
		assertTrue(set.add("y"));
		assertTrue(set.add(null)); // empty-key path
		assertEquals(3, set.size());

		set.clear();
		assertTrue(set.isEmpty());
		assertFalse(set.contains("x"));
		assertFalse(set.contains(null));

		assertTrue(set.add("z"));
		assertEquals(1, set.size());
		set.clear();
		set.clear();
		assertTrue(set.isEmpty());
	}

	@Test
	public void openAddressedBusySetGrowsThenEmptyClearKeepsCapacitySemantics() {
		OpenAddressedHashSet<Integer> set = new OpenAddressedHashSet<Integer>(2);
		for (int i = 0; i < 64; i++) {
			assertTrue(set.add(i));
		}
		assertEquals(64, set.size());
		set.clear();
		assertTrue(set.isEmpty());
		// Reuse after growth: empty clear must leave the set usable.
		set.clear();
		assertTrue(set.add(100));
		assertTrue(set.contains(100));
		assertEquals(1, set.size());
	}
}
