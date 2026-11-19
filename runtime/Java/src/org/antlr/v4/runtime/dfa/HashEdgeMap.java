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
import java.util.concurrent.atomic.AtomicReferenceArray;

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
 * <p>Thread safety / JMM: {@link #put} writes {@code keys[bucket]} before
 * release-publishing the target via {@link AtomicReferenceArray#lazySet} on
 * {@link #values}. Lock-free readers ({@link #get}, {@link #getDirect},
 * {@link DFAState#getLexerTarget}) acquire-load from {@link #values} first,
 * establishing release/acquire happens-before for both {@code keys[bucket]} and
 * the published target object.</p>
 *
 * <p>Lexer maps whose keys all lie in {@code [0, tableLength)} may be stored
 * identity-indexed ({@link #isDirectIndex()}). {@link #get} does not branch
 * on that layout — parser maps stay on the hashed probe. {@link DFAState}
 * publishes {@link #directArray()} for the lexer hot path.</p>
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

	private final int[] keys;
	private final AtomicReferenceArray<T> values;
	/**
	 * Number of non-null slots. Updated only inside {@code synchronized (this)}
	 * mutation paths; read without the monitor for O(1) {@link #size()} /
	 * {@link #isEmpty()} (same visibility model as {@link ArrayEdgeMap}).
	 */
	private final AtomicInteger size;

	/**
	 * When {@code true}, slot {@code i} holds key {@code i} and {@link #getDirect}
	 * is a direct indexed load. Final: an out-of-range {@link #put} publishes a new
	 * hashed map rather than flipping this flag. Public constructors stay hashed
	 * so the first inserts mutate in place. Parser maps ({@code minIndex < 0},
	 * EOF is a key) never set this.
	 */
	private final boolean directIndex;

	public HashEdgeMap(int minIndex, int maxIndex) {
		this(minIndex, maxIndex, DEFAULT_MAX_SIZE);
	}

	public HashEdgeMap(int minIndex, int maxIndex, int maxSparseSize) {
		super(minIndex, maxIndex);
		this.keys = new int[maxSparseSize];
		this.values = new AtomicReferenceArray<>(maxSparseSize);
		this.size = new AtomicInteger();
		this.directIndex = false;
	}

	private HashEdgeMap(@NotNull HashEdgeMap<T> map, int maxSparseSize) {
		this(map, maxSparseSize, true);
	}

	private HashEdgeMap(@NotNull HashEdgeMap<T> map, int maxSparseSize, boolean allowDirect) {
		super(map.minIndex, map.maxIndex);
		synchronized (map) {
			final int srcLen = map.values.length();
			if (maxSparseSize < srcLen) {
				throw new IllegalArgumentException();
			}

			keys = new int[maxSparseSize];
			values = new AtomicReferenceArray<>(maxSparseSize);
			int occupied = 0;
			for (int i = 0; i < srcLen; i++) {
				T value = map.values.get(i);
				if (value != null) {
					int key = map.keys[i];
					int bucket = bucket(key);
					keys[bucket] = key;
					values.lazySet(bucket, value);
					occupied++;
				}
			}
			this.size = new AtomicInteger(occupied);
			this.directIndex = allowDirect && minIndex >= 0 && keysFit(map, maxSparseSize);
		}
	}

	/** Identity-indexed lexer layout. Package-private; not part of the public API. */
	boolean isDirectIndex() {
		return directIndex;
	}

	/** Table length. Package-private; used by tests and {@link #getDirect}. */
	int tableLength() {
		return values.length();
	}

	/**
	 * Values array for an identity-indexed map, or {@code null} when this map
	 * is hashed. Slot {@code i} holds key {@code i}. Package-private.
	 */
	AtomicReferenceArray<T> directArray() {
		return directIndex ? values : null;
	}

	/**
	 * Identity lookup. Valid only when {@link #isDirectIndex()} is true.
	 * Keys outside {@code [0, tableLength)} miss. Does not read the key word,
	 * so a hashed map must not be queried through this method.
	 */
	final T getDirect(int key) {
		final AtomicReferenceArray<T> vals = values;
		if (key >= 0 && key < vals.length()) {
			return vals.get(key);
		}
		return null;
	}

	private static boolean keysFit(HashEdgeMap<?> src, int length) {
		AtomicReferenceArray<?> vals = src.values;
		int[] srcKeys = src.keys;
		for (int i = 0, len = vals.length(); i < len; i++) {
			if (vals.get(i) == null) {
				continue;
			}
			int key = srcKeys[i];
			if (key < 0 || key >= length) {
				return false;
			}
		}
		return true;
	}

	private static int bucket(int length, int key) {
		// Note: this returns a valid array index even if key is outside the
		// allowed range or the minIndex is negative.
		return key & (length - 1);
	}

	private int bucket(int key) {
		// Note: this returns a valid array index even if key is outside the
		// allowed range or the minIndex is negative.
		return key & (values.length() - 1);
	}

	@NotNull
	/*package*/ int[] getKeys() {
		return keys;
	}

	@NotNull
	/*package*/ AtomicReferenceArray<T> getValues() {
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
		// Acquire-load from AtomicReferenceArray first so a non-null value
		// establishes happens-before for keys[bucket] and the target state.
		final AtomicReferenceArray<T> vals = values;
		final int bucket = key & (vals.length() - 1);
		final T value = vals.get(bucket);
		if (value == null) {
			return null;
		}
		return keys[bucket] == key ? value : null;
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
			final int len = values.length();
			// Identity layout cannot hold this key. Publish a hashed map of the
			// same length and insert there (that insert may resize further).
			if (directIndex && (key < 0 || key >= len)) {
				HashEdgeMap<T> hashed = new HashEdgeMap<T>(this, len, false);
				return hashed.put(key, value);
			}

			int bucket = bucket(key);
			T currentValue = values.get(bucket);
			int currentKey = keys[bucket];
			// Empty slots keep key word 0. A live replace requires a stored value,
			// otherwise put(0) into an empty slot would skip the size update.
			if (currentValue != null && currentKey == key) {
				values.lazySet(bucket, value);
				return this;
			}

			if (currentValue == null) {
				// Write the key before release-publishing the value.
				// lazySet provides release ordering (StoreStore/LoadStore)
				// without a per-write x86 StoreLoad fence; size.incrementAndGet()
				// and monitor exit also provide full visibility.
				keys[bucket] = key;
				values.lazySet(bucket, value);
				size.incrementAndGet();
				return this;
			}

			// Resize on collision
			int newSize = len;
			while (true) {
				newSize *= 2;
				if (newSize >= (maxIndex - minIndex + 1) / 2) {
					ArrayEdgeMap<T> arrayMap = new ArrayEdgeMap<>(minIndex, maxIndex);
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
			return resized.put(key, value);
		}
	}

	@Override
	public HashEdgeMap<T> remove(int key) {
		if (get(key) == null) {
			return this;
		}

		HashEdgeMap<T> result = new HashEdgeMap<>(this, values.length());
		int bucket = result.bucket(key);
		if (result.values.get(bucket) != null) {
			result.values.lazySet(bucket, null);
			result.keys[bucket] = 0;
			result.size.decrementAndGet();
		}
		return result;
	}

	@Override
	public AbstractEdgeMap<T> clear() {
		if (isEmpty()) {
			return this;
		}

		return new EmptyEdgeMap<>(minIndex, maxIndex);
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
			Map<Integer, T> result = new TreeMap<>();
			for (int i = 0, len = values.length(); i < len; i++) {
				T value = values.get(i);
				if (value != null) {
					result.put(keys[i], value);
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
