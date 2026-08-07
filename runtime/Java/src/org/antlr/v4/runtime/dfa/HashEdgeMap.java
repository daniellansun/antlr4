/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.dfa;

import org.antlr.v4.runtime.misc.NotNull;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Sparse open-addressed edge map used while a DFA state has only a few outgoing
 * edges. Grows by doubling and promotes to {@link ArrayEdgeMap} when density
 * approaches half of the allowed symbol range.
 *
 * <p>PERF: {@link #isEmpty()} / {@link #size()} are O(1) via a retained
 * occupancy counter (updated only under the same monitor as structural
 * mutations). {@link #toMap()} remains a cold diagnostic path (sorted
 * {@link TreeMap} for stable {@link DFASerializer} output); hot emptiness
 * checks must use {@link #isEmpty()} directly rather than
 * {@code toMap().isEmpty()}.</p>
 *
 * @author Sam Harwell
 */
public final class HashEdgeMap<T> extends AbstractEdgeMap<T> {
	/**
	 * Initial open-addressed table length (power of two). Raised from 2 to 8 so
	 * the common "handful of outgoing edges" case (parser token edges, short
	 * lexer loops) avoids collision-driven resize churn on the first few
	 * {@link #put} calls. Tables still promote to {@link ArrayEdgeMap} once
	 * density approaches half of the allowed symbol range.
	 */
	private static final int DEFAULT_MAX_SIZE = 8;

	private final AtomicIntegerArray keys;
	private final T[] values;
	/**
	 * Number of non-null slots. Updated only inside {@code synchronized (this)}
	 * mutation paths; read without the monitor for O(1) {@link #size()} /
	 * {@link #isEmpty()} (same visibility model as {@link ArrayEdgeMap}).
	 */
	private final AtomicInteger size;

	public HashEdgeMap(int minIndex, int maxIndex) {
		this(minIndex, maxIndex, DEFAULT_MAX_SIZE);
	}

	@SuppressWarnings("unchecked")
	public HashEdgeMap(int minIndex, int maxIndex, int maxSparseSize) {
		super(minIndex, maxIndex);
		this.keys = new AtomicIntegerArray(maxSparseSize);
		this.values = (T[])new Object[maxSparseSize];
		this.size = new AtomicInteger();
	}

	@SuppressWarnings("unchecked")
	private HashEdgeMap(@NotNull HashEdgeMap<T> map, int maxSparseSize) {
		super(map.minIndex, map.maxIndex);
		synchronized (map) {
			if (maxSparseSize < map.values.length) {
				throw new IllegalArgumentException();
			}

			keys = new AtomicIntegerArray(maxSparseSize);
			values = (T[])new Object[maxSparseSize];
			int occupied = 0;
			for (int i = 0; i < map.values.length; i++) {
				int key = map.keys.get(i);
				T value = map.values[i];
				if (value != null) {
					int bucket = bucket(key);
					keys.set(bucket, key);
					values[bucket] = value;
					occupied++;
				}
			}
			this.size = new AtomicInteger(occupied);
		}
	}

	private static int bucket(int length, int key) {
		// Note: this returns a valid array index even if key is outside the
		// allowed range or the minIndex is negative.
		return key & (length - 1);
	}

	private int bucket(int key) {
		// Note: this returns a valid array index even if key is outside the
		// allowed range or the minIndex is negative.
		return key & (values.length - 1);
	}

	@NotNull
	/*package*/ AtomicIntegerArray getKeys() {
		return keys;
	}

	@NotNull
	/*package*/ T[] getValues() {
		return values;
	}

	@Override
	public int size() {
		return size.get();
	}

	@Override
	public boolean isEmpty() {
		return size.get() == 0;
	}

	@Override
	public boolean containsKey(int key) {
		return get(key) != null;
	}

	@Override
	public T get(int key) {
		// Hot path: DFA edge walk during adaptivePredict / lexer match.
		// Do not gate on size.get() — that is an extra atomic read on every
		// warm edge lookup; empty maps already miss via values[bucket] == null.
		int bucket = bucket(key);

		// Read the value first
		T value = values[bucket];
		if (value == null || keys.get(bucket) != key) {
			return null;
		}

		return value;
	}

	@Override
	public AbstractEdgeMap<T> put(int key, T value) {
		if (key < minIndex || key > maxIndex) {
			return this;
		}

		if (value == null) {
			return remove(key);
		}

		synchronized (this) {
			int bucket = bucket(key);
			int currentKey = keys.get(bucket);
			if (currentKey == key) {
				// Same key: replace value; occupancy unchanged if slot was occupied.
				// keys are only written when inserting into an empty slot, so a
				// matching key implies a live entry (size > 0).
				if (values[bucket] == null) {
					values[bucket] = value;
					size.incrementAndGet();
				}
				else {
					values[bucket] = value;
				}
				return this;
			}

			T currentValue = values[bucket];
			if (currentValue == null) {
				// Write the key first
				keys.set(bucket, key);
				values[bucket] = value;
				size.incrementAndGet();
				return this;
			}

			// Resize on collision
			int newSize = values.length;
			while (true) {
				newSize *= 2;
				if (newSize >= (maxIndex - minIndex + 1) / 2) {
					ArrayEdgeMap<T> arrayMap = new ArrayEdgeMap<T>(minIndex, maxIndex);
					arrayMap = arrayMap.putAll(this);
					arrayMap.put(key, value);
					return arrayMap;
				}

				// Check for another collision
				if (bucket(newSize, currentKey) != bucket(newSize, key)) {
					break;
				}
			}

			HashEdgeMap<T> resized = new HashEdgeMap<T>(this, newSize);
			resized.put(key, value);
			return resized;
		}
	}

	@Override
	public HashEdgeMap<T> remove(int key) {
		if (get(key) == null) {
			return this;
		}

		HashEdgeMap<T> result = new HashEdgeMap<T>(this, values.length);
		int bucket = result.bucket(key);
		if (result.values[bucket] != null) {
			result.keys.set(bucket, 0);
			result.values[bucket] = null;
			result.size.decrementAndGet();
		}
		return result;
	}

	@Override
	public AbstractEdgeMap<T> clear() {
		if (isEmpty()) {
			return this;
		}

		return new EmptyEdgeMap<T>(minIndex, maxIndex);
	}

	@Override
	public Map<Integer, T> toMap() {
		if (isEmpty()) {
			return Collections.emptyMap();
		}

		// TreeMap: sorted keys for stable DFASerializer / diagnostic dumps.
		// This path is cold — never call toMap() from adaptivePredict hot paths
		// (use isEmpty()/size() instead; see DFA.isEmpty).
		synchronized (this) {
			Map<Integer, T> result = new TreeMap<Integer, T>();
			for (int i = 0; i < values.length; i++) {
				T value = values[i];
				if (value != null) {
					result.put(keys.get(i), value);
				}
			}

			return result;
		}
	}

	@Override
	public Set<Map.Entry<Integer, T>> entrySet() {
		return toMap().entrySet();
	}
}
