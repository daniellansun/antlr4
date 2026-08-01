/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import com.carrotsearch.hppc.LongObjectHashMap;

/**
 * Package-private {@code long → V} map for ATN config merge indexes, with O(1)
 * empty {@link #clear()}.
 *
 * <p>
 * <strong>Encapsulation:</strong> HPPC {@link LongObjectHashMap} is a private
 * field only — this type does <em>not</em> extend or implement any HPPC type,
 * so HPPC never appears in the type hierarchy of production code. Hot-path
 * methods ({@link #indexOf}, {@link #indexGet}, {@link #indexInsert}, …) are
 * thin final forwards so the JIT can inline them. Not part of any
 * {@code public} or {@code protected} API; callers use
 * {@link ATNConfigSet}'s {@link java.util.Set} surface.</p>
 *
 * <p>
 * HPPC's default {@link LongObjectHashMap#clear()} always executes
 * {@link java.util.Arrays#fill} over the entire tables (O(capacity)), even
 * when empty. Retained config sets clear on every obtain/release, so empty
 * bulk fills would dominate; empty clear is a pure no-op here.</p>
 *
 * @param <VType> value type stored in the map
 */
final class ClearableLongObjectHashMap<VType> {

	/** Private HPPC storage — never exposed in signatures. */
	private final LongObjectHashMap<VType> map;

	ClearableLongObjectHashMap() {
		this.map = new LongObjectHashMap<VType>();
	}

	/**
	 * @param expectedElements expected entry count used as a capacity hint
	 */
	ClearableLongObjectHashMap(int expectedElements) {
		this.map = new LongObjectHashMap<VType>(expectedElements);
	}

	private ClearableLongObjectHashMap(LongObjectHashMap<VType> map) {
		this.map = map;
	}

	/**
	 * Clears all entries. Already-empty maps return without touching backing
	 * arrays. Non-empty clear uses HPPC's bulk fill (right algorithm at high
	 * occupancy).
	 */
	void clear() {
		if (map.isEmpty()) {
			return;
		}
		map.clear();
	}

	/**
	 * Deep copy preserving capacity and entries. Used when cloning a writable
	 * {@link ATNConfigSet}.
	 */
	@SuppressWarnings("unchecked")
	ClearableLongObjectHashMap<VType> deepCopy() {
		return new ClearableLongObjectHashMap<VType>(
			(LongObjectHashMap<VType>) map.clone());
	}

	/**
	 * Open-addressed table length (including the dedicated empty-key slot).
	 * Used by {@link ATNConfigSet#clear()} to choose sparse vs bulk clear.
	 */
	int tableLength() {
		return map.keys.length;
	}

	int size() {
		return map.size();
	}

	boolean isEmpty() {
		return map.isEmpty();
	}

	VType get(long key) {
		return map.get(key);
	}

	VType put(long key, VType value) {
		return map.put(key, value);
	}

	VType remove(long key) {
		return map.remove(key);
	}

	boolean containsKey(long key) {
		return map.containsKey(key);
	}

	int indexOf(long key) {
		return map.indexOf(key);
	}

	VType indexGet(int index) {
		return map.indexGet(index);
	}

	void indexInsert(int index, long key, VType value) {
		map.indexInsert(index, key, value);
	}

	VType indexRemove(int index) {
		return map.indexRemove(index);
	}
}
