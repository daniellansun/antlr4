/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import com.carrotsearch.hppc.IntIntHashMap;

/**
 * Thread-safe {@code int → int} map for rare-write / frequent-read workloads.
 *
 * <p>Used by the LL(1) prediction cache on {@link ATN}: many parser threads may
 * read the shared ATN while occasional writers insert newly discovered
 * single-token predictions. Reads are lock-free and allocation-free; writes
 * copy-on-write under a monitor and publish via a {@code volatile} reference.
 * </p>
 *
 * <p>Missing keys return {@link #MISSING} ({@code 0}). Predicted alternatives
 * are always {@code ≥ 1} ({@link ATN#INVALID_ALT_NUMBER} is {@code 0}), so the
 * HPPC default empty value matches the domain without a second probe.</p>
 *
 * <p><strong>Not part of any {@code public} or {@code protected} API.</strong>
 * HPPC types stay package-private implementation detail.</p>
 */
final class ConcurrentIntIntMap {

	/**
	 * Sentinel returned by {@link #get(int)} when the key is absent. Equal to
	 * {@link ATN#INVALID_ALT_NUMBER} so callers can treat zero as a miss.
	 */
	static final int MISSING = 0;

	private final Object lock = new Object();

	/**
	 * Published snapshot. Readers load once and consult the snapshot without
	 * further synchronization.
	 */
	private volatile IntIntHashMap map = new IntIntHashMap();

	/**
	 * @param key packed map key
	 * @return stored value, or {@link #MISSING} if absent
	 */
	int get(int key) {
		IntIntHashMap snapshot = map;
		// indexOf avoids a second hash on the subsequent indexGet.
		int index = snapshot.indexOf(key);
		if (index < 0) {
			return MISSING;
		}
		return snapshot.indexGet(index);
	}

	/**
	 * Associates {@code key} with {@code value}. Idempotent when the snapshot
	 * already holds the same mapping.
	 */
	void put(int key, int value) {
		// Fast path: already present with the same value — no copy.
		IntIntHashMap snapshot = map;
		int index = snapshot.indexOf(key);
		if (index >= 0 && snapshot.indexGet(index) == value) {
			return;
		}
		synchronized (lock) {
			snapshot = map;
			index = snapshot.indexOf(key);
			if (index >= 0 && snapshot.indexGet(index) == value) {
				return;
			}
			IntIntHashMap next = (IntIntHashMap) snapshot.clone();
			next.put(key, value);
			map = next;
		}
	}

	/** Drops every entry (used by {@link ATN#clearDFA()}). */
	void clear() {
		synchronized (lock) {
			if (map.isEmpty()) {
				return;
			}
			map = new IntIntHashMap();
		}
	}

	/** Approximate size of the published snapshot (for diagnostics/tests). */
	int size() {
		return map.size();
	}
}
