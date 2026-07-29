/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import com.carrotsearch.hppc.LongObjectHashMap;

/**
 * Package-private {@link LongObjectHashMap} tuned for retained ATN scratch reuse.
 *
 * <p>
 * HPPC's default {@link LongObjectHashMap#clear()} always executes
 * {@link java.util.Arrays#fill} over the entire {@code keys} and {@code values}
 * tables (O(capacity)), even when the map is already empty. Retained config
 * sets call clear on every {@link RetainedConfigSet#obtain(int)} /
 * {@link RetainedConfigSet#release()} pair, so the empty fill became a hot
 * cost on lexer/parser {@code computeTargetState} (profiled as
 * {@code Arrays.fill(Object[])} on the values table).</p>
 *
 * <p>
 * This subclass makes empty clear a pure no-op (O(1)) by consulting the
 * protected occupancy fields. Non-empty clear still uses HPPC's bulk fill,
 * which is the right algorithm when occupancy is high.</p>
 *
 * <p>
 * Not part of any {@code public} or {@code protected} API. Callers interact
 * only with {@link ATNConfigSet}'s {@link java.util.Set} surface.</p>
 *
 * @param <VType> value type stored in the map
 */
final class ClearableLongObjectHashMap<VType> extends LongObjectHashMap<VType> {

	ClearableLongObjectHashMap() {
		super();
	}

	/**
	 * @param expectedElements expected entry count used as a capacity hint
	 * (same contract as {@link LongObjectHashMap#LongObjectHashMap(int)})
	 */
	ClearableLongObjectHashMap(int expectedElements) {
		super(expectedElements);
	}

	/**
	 * Clears all entries. When the map is already empty this method returns
	 * immediately without touching the backing arrays.
	 *
	 * <p>Uses {@link #isEmpty()} (not raw occupancy fields) so the fast path
	 * stays valid across HPPC 0.9.x layout tweaks as long as empty semantics
	 * are preserved. Non-empty clear still delegates to HPPC's bulk
	 * {@code Arrays.fill} (the right algorithm at high occupancy).</p>
	 */
	@Override
	public void clear() {
		if (isEmpty()) {
			return;
		}
		super.clear();
	}

	/**
	 * Open-addressed table length (including the dedicated empty-key slot).
	 * Used by {@link ATNConfigSet#clear()} to choose sparse vs bulk clear.
	 */
	int tableLength() {
		return keys.length;
	}
}
