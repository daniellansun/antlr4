/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import com.carrotsearch.hppc.IntObjectHashMap;

/**
 * Package-private {@code int → V} map with O(1) empty {@link #clear()}.
 *
 * <p>
 * <strong>API boundary:</strong> Package-private only — used as a private field
 * on {@link ParserATNSimulator} for the retained precedence-filter alt-1 index.
 * Never appears in any {@code public} or {@code protected} signature. Extends
 * HPPC so hot {@code put}/{@code get} stay monomorphic without a wrapper
 * frame; HPPC is not part of the published API.</p>
 *
 * <p>HPPC's stock {@link IntObjectHashMap#clear()} always bulk-fills the table.
 * Retained scratch clears every filter invocation; empty clear is a no-op.</p>
 *
 * @param <VType> value type
 */
final class ClearableIntObjectHashMap<VType> extends IntObjectHashMap<VType> {

	ClearableIntObjectHashMap() {
		super();
	}

	/**
	 * @param expectedElements capacity hint
	 */
	ClearableIntObjectHashMap(int expectedElements) {
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
}
