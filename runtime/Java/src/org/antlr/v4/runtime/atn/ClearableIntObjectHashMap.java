/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import com.carrotsearch.hppc.IntObjectHashMap;

/**
 * Package-private {@link IntObjectHashMap} with O(1) empty {@link #clear()}.
 *
 * <p>HPPC's stock {@link IntObjectHashMap#clear()} always
 * {@link java.util.Arrays#fill fills} the entire open-addressed table. Retained
 * scratch maps used by {@link ParserATNSimulator#applyPrecedenceFilter} call
 * clear on every invocation, so empty bulk fills would dominate when the prior
 * edge left the map empty or after an explicit release.</p>
 *
 * <p><strong>Not part of any {@code public} or {@code protected} API.</strong>
 * Call sites keep the map as a private field; no HPPC type appears in
 * signatures of public or protected methods.</p>
 *
 * @param <VType> value type
 */
final class ClearableIntObjectHashMap<VType> extends IntObjectHashMap<VType> {

	ClearableIntObjectHashMap() {
		super();
	}

	/**
	 * @param expectedElements capacity hint (same contract as
	 * {@link IntObjectHashMap#IntObjectHashMap(int)})
	 */
	ClearableIntObjectHashMap(int expectedElements) {
		super(expectedElements);
	}

	/**
	 * Clears all entries. Already-empty maps return immediately without
	 * touching the backing arrays.
	 */
	@Override
	public void clear() {
		if (isEmpty()) {
			return;
		}
		super.clear();
	}
}
