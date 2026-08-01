/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import com.carrotsearch.hppc.LongObjectHashMap;

/**
 * Package-private {@code long → V} open-addressed map with O(1) empty
 * {@link #clear()}, used as the ATN config merge index.
 *
 * <p>
 * <strong>API boundary:</strong> This type is package-private and never appears
 * in any {@code public} or {@code protected} signature. External callers only
 * touch {@link ATNConfigSet}'s JDK {@link java.util.Set} surface. Extending
 * HPPC here is intentional: the merge index is on the prediction hot path
 * ({@code indexOf}/{@code indexGet}/{@code indexInsert}), so monomorphic
 * inheritance avoids an extra wrapper frame while HPPC remains a non-exported
 * implementation detail.</p>
 *
 * <p>
 * HPPC's default {@link LongObjectHashMap#clear()} always
 * {@link java.util.Arrays#fill fills} the entire tables (O(capacity)), even
 * when empty. Retained config sets clear on every obtain/release; empty clear
 * is a pure no-op here. Non-empty clear keeps bulk fill (right at high
 * occupancy).</p>
 *
 * @param <VType> value type stored in the map
 */
final class ClearableLongObjectHashMap<VType> extends LongObjectHashMap<VType> {

	ClearableLongObjectHashMap() {
		super();
	}

	/**
	 * @param expectedElements expected entry count used as a capacity hint
	 */
	ClearableLongObjectHashMap(int expectedElements) {
		super(expectedElements);
	}

	/**
	 * Clears all entries. Already-empty maps return without touching backing
	 * arrays.
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
