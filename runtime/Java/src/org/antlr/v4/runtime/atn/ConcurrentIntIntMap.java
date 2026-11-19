/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.predicates.IntIntPredicate;
import com.carrotsearch.hppc.procedures.IntIntProcedure;

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
 * <p>Mutating operations that must report a previous value or enforce
 * {@code ConcurrentMap}-style atomicity ({@link #put}, {@link #putIfAbsent},
 * {@link #remove}, {@link #replace}) complete under the same monitor so
 * {@link ConcurrentIntIntMapView} can honor the JDK map contract without a
 * dual store.</p>
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

	private static final int[] EMPTY_ENTRIES = new int[0];

	/**
	 * Number of copy-on-write stripes (power of two). Reduces per-write clone
	 * cost and lock contention during parallel cold DFA warmup by ~16x while
	 * keeping reads lock-free and allocation-free.
	 */
	private static final int STRIPE_COUNT = 16;
	private static final int STRIPE_MASK = STRIPE_COUNT - 1;

	private static final IntIntHashMap EMPTY_MAP = new IntIntHashMap(0);

	private static final class Stripe {
		volatile IntIntHashMap map = EMPTY_MAP;
	}

	private final Stripe[] stripes;

	ConcurrentIntIntMap() {
		Stripe[] s = new Stripe[STRIPE_COUNT];
		for (int i = 0; i < STRIPE_COUNT; i++) {
			s[i] = new Stripe();
		}
		this.stripes = s;
	}

	private Stripe stripeFor(int key) {
		int h = key ^ (key >>> 16);
		return stripes[h & STRIPE_MASK];
	}

	/**
	 * @param key packed map key
	 * @return stored value, or {@link #MISSING} if absent
	 */
	int get(int key) {
		IntIntHashMap snapshot = stripeFor(key).map;
		if (snapshot == EMPTY_MAP) {
			return MISSING;
		}
		// indexOf avoids a second hash on the subsequent indexGet.
		int index = snapshot.indexOf(key);
		if (index < 0) {
			return MISSING;
		}
		return snapshot.indexGet(index);
	}

	/**
	 * Associates {@code key} with {@code value}.
	 *
	 * @return the previous value associated with {@code key}, or {@link #MISSING}
	 * if there was no mapping
	 */
	int put(int key, int value) {
		Stripe stripe = stripeFor(key);
		// Fast path: already present with the same value — no copy.
		IntIntHashMap snapshot = stripe.map;
		if (snapshot != EMPTY_MAP) {
			int index = snapshot.indexOf(key);
			if (index >= 0 && snapshot.indexGet(index) == value) {
				return value;
			}
		}
		synchronized (stripe) {
			snapshot = stripe.map;
			int previous = MISSING;
			if (snapshot != EMPTY_MAP) {
				int index = snapshot.indexOf(key);
				if (index >= 0) {
					previous = snapshot.indexGet(index);
					if (previous == value) {
						return previous;
					}
				}
			}
			IntIntHashMap next = snapshot == EMPTY_MAP ? new IntIntHashMap(8) : snapshot.clone();
			next.put(key, value);
			stripe.map = next;
			return previous;
		}
	}

	/**
	 * Associates {@code key} with {@code value} only if absent.
	 *
	 * @return the existing value if the key was present, or {@link #MISSING}
	 * if the mapping was installed
	 */
	int putIfAbsent(int key, int value) {
		int existing = get(key);
		if (existing != MISSING) {
			return existing;
		}
		Stripe stripe = stripeFor(key);
		synchronized (stripe) {
			IntIntHashMap snapshot = stripe.map;
			if (snapshot != EMPTY_MAP) {
				int index = snapshot.indexOf(key);
				if (index >= 0) {
					return snapshot.indexGet(index);
				}
			}
			IntIntHashMap next = snapshot == EMPTY_MAP ? new IntIntHashMap(8) : snapshot.clone();
			next.put(key, value);
			stripe.map = next;
			return MISSING;
		}
	}

	/**
	 * Removes the mapping for {@code key} if present.
	 *
	 * @return the previous value, or {@link #MISSING} if absent
	 */
	int remove(int key) {
		if (get(key) == MISSING) {
			return MISSING;
		}
		Stripe stripe = stripeFor(key);
		synchronized (stripe) {
			IntIntHashMap snapshot = stripe.map;
			if (snapshot == EMPTY_MAP) {
				return MISSING;
			}
			int index = snapshot.indexOf(key);
			if (index < 0) {
				return MISSING;
			}
			int previous = snapshot.indexGet(index);
			IntIntHashMap next = snapshot.clone();
			next.remove(key);
			stripe.map = next.isEmpty() ? EMPTY_MAP : next;
			return previous;
		}
	}

	/**
	 * Removes the mapping for {@code key} only if it is currently mapped to
	 * {@code expectedValue}.
	 *
	 * @return {@code true} if the entry was removed
	 */
	boolean remove(int key, int expectedValue) {
		if (expectedValue == MISSING) {
			return false;
		}
		if (get(key) != expectedValue) {
			return false;
		}
		Stripe stripe = stripeFor(key);
		synchronized (stripe) {
			IntIntHashMap snapshot = stripe.map;
			if (snapshot == EMPTY_MAP) {
				return false;
			}
			int index = snapshot.indexOf(key);
			if (index < 0 || snapshot.indexGet(index) != expectedValue) {
				return false;
			}
			IntIntHashMap next = snapshot.clone();
			next.remove(key);
			stripe.map = next.isEmpty() ? EMPTY_MAP : next;
			return true;
		}
	}

	/**
	 * Replaces the entry for {@code key} only if currently mapped to
	 * {@code oldValue}.
	 *
	 * @return {@code true} if the value was replaced
	 */
	boolean replace(int key, int oldValue, int newValue) {
		if (oldValue == MISSING) {
			// Domain invariant: stored values are always >= 1; treat as absent match.
			return false;
		}
		Stripe stripe = stripeFor(key);
		synchronized (stripe) {
			IntIntHashMap snapshot = stripe.map;
			if (snapshot == EMPTY_MAP) {
				return false;
			}
			int index = snapshot.indexOf(key);
			if (index < 0 || snapshot.indexGet(index) != oldValue) {
				return false;
			}
			if (oldValue == newValue) {
				return true;
			}
			IntIntHashMap next = snapshot.clone();
			next.put(key, newValue);
			stripe.map = next;
			return true;
		}
	}

	/**
	 * Replaces the entry for {@code key} only if present.
	 *
	 * @return the previous value, or {@link #MISSING} if absent
	 */
	int replace(int key, int newValue) {
		Stripe stripe = stripeFor(key);
		synchronized (stripe) {
			IntIntHashMap snapshot = stripe.map;
			if (snapshot == EMPTY_MAP) {
				return MISSING;
			}
			int index = snapshot.indexOf(key);
			if (index < 0) {
				return MISSING;
			}
			int previous = snapshot.indexGet(index);
			if (previous == newValue) {
				return previous;
			}
			IntIntHashMap next = snapshot.clone();
			next.put(key, newValue);
			stripe.map = next;
			return previous;
		}
	}

	/** Drops every entry (used by {@link ATN#clearDFA()}). */
	void clear() {
		for (int i = 0; i < STRIPE_COUNT; i++) {
			Stripe stripe = stripes[i];
			if (stripe.map != EMPTY_MAP) {
				synchronized (stripe) {
					stripe.map = EMPTY_MAP;
				}
			}
		}
	}

	/** Approximate size of the published snapshot (for diagnostics/tests). */
	int size() {
		int total = 0;
		for (int i = 0; i < STRIPE_COUNT; i++) {
			total += stripes[i].map.size();
		}
		return total;
	}

	/**
	 * Immutable snapshot of all entries as interleaved {@code key, value}
	 * pairs (length {@code 2 * size()}). Used by
	 * {@link ConcurrentIntIntMapView#entrySet()} so the JDK map façade can
	 * iterate without exposing HPPC types.
	 */
	int[] snapshotEntries() {
		IntIntHashMap[] snaps = new IntIntHashMap[STRIPE_COUNT];
		int n = 0;
		for (int i = 0; i < STRIPE_COUNT; i++) {
			IntIntHashMap s = stripes[i].map;
			snaps[i] = s;
			n += s.size();
		}
		if (n == 0) {
			return EMPTY_ENTRIES;
		}
		final int[] entries = new int[n << 1];
		final int[] cursor = new int[1];
		for (int i = 0; i < STRIPE_COUNT; i++) {
			IntIntHashMap s = snaps[i];
			if (!s.isEmpty()) {
				s.forEach((IntIntProcedure) (key, value) -> {
					int c = cursor[0];
					entries[c] = key;
					entries[c + 1] = value;
					cursor[0] = c + 2;
				});
			}
		}
		return entries;
	}

	/**
	 * Whether any published entry has the given value. Scans the current
	 * snapshots (lock-free). Used by the JDK view's {@code containsValue}.
	 */
	boolean containsValue(int value) {
		if (value == MISSING) {
			return false;
		}
		final boolean[] found = new boolean[1];
		for (int i = 0; i < STRIPE_COUNT; i++) {
			IntIntHashMap s = stripes[i].map;
			if (!s.isEmpty()) {
				s.forEach((IntIntPredicate) (key, v) -> {
					if (v == value) {
						found[0] = true;
						return false;
					}
					return true;
				});
				if (found[0]) {
					return true;
				}
			}
		}
		return false;
	}
}
