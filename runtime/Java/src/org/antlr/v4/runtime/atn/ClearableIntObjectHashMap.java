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
 * <strong>Encapsulation:</strong> HPPC {@link IntObjectHashMap} is a private
 * field only — this type does not extend HPPC, so no HPPC type appears in the
 * production type hierarchy or in any {@code public}/{@code protected}
 * signature. Used as a private field on {@link ParserATNSimulator} for the
 * retained precedence-filter alt-1 index.</p>
 *
 * <p>HPPC's stock {@link IntObjectHashMap#clear()} always
 * {@link java.util.Arrays#fill fills} the entire open-addressed table. Retained
 * scratch maps clear on every filter invocation; empty bulk fills would
 * dominate when the prior edge left the map empty.</p>
 *
 * @param <VType> value type
 */
final class ClearableIntObjectHashMap<VType> {

	/** Private HPPC storage — never exposed in signatures. */
	private final IntObjectHashMap<VType> map;

	ClearableIntObjectHashMap() {
		this.map = new IntObjectHashMap<VType>();
	}

	/**
	 * @param expectedElements capacity hint
	 */
	ClearableIntObjectHashMap(int expectedElements) {
		this.map = new IntObjectHashMap<VType>(expectedElements);
	}

	/**
	 * Clears all entries. Already-empty maps return without touching backing
	 * arrays.
	 */
	void clear() {
		if (map.isEmpty()) {
			return;
		}
		map.clear();
	}

	int size() {
		return map.size();
	}

	boolean isEmpty() {
		return map.isEmpty();
	}

	VType get(int key) {
		return map.get(key);
	}

	VType put(int key, VType value) {
		return map.put(key, value);
	}

	boolean containsKey(int key) {
		return map.containsKey(key);
	}
}
