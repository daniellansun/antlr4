/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

/**
 * JDK {@link ConcurrentMap} view over {@link ConcurrentIntIntMap}.
 *
 * <p>Preserves the historical {@code protected ConcurrentMap<Integer,Integer>}
 * surface on {@link ATN#LL1Table} for subclasses and tests, while the
 * prediction hot path reads/writes the primitive map without boxing. Mutating
 * this view updates the same underlying store.</p>
 *
 * <p>Entry, key, and value views are backed by the live primitive store:
 * {@link #size()}, {@link #isEmpty()}, {@link #entrySet()}, {@link #keySet()},
 * and {@link #values()} stay consistent with each other. Iterators walk an
 * immutable snapshot of the published COW table (weakly consistent under
 * concurrent writers, matching typical concurrent-map expectations for
 * bulk views). Atomic single-key mutations
 * ({@link #put}, {@link #putIfAbsent}, {@link #remove}, {@link #replace})
 * delegate to monitor-guarded operations on {@link ConcurrentIntIntMap}.</p>
 *
 * <p>Null keys and null values are rejected ({@link NullPointerException}),
 * matching {@link java.util.concurrent.ConcurrentHashMap} and the LL(1)
 * domain (alternatives are always {@code ≥ 1}).</p>
 *
 * <p>Package-private; not part of any public API surface. HPPC types never
 * appear in this class's signatures.</p>
 */
final class ConcurrentIntIntMapView extends AbstractMap<Integer, Integer>
	implements ConcurrentMap<Integer, Integer> {

	private final ConcurrentIntIntMap delegate;

	/**
	 * Owning ATN when this view is {@link ATN#LL1Table}; {@code null} for
	 * standalone test instances. Dense LL(1) writes go through
	 * {@link ATN#syncLl1Dense}.
	 */
	private final ATN atn;

	/**
	 * Lazily created entry-set view. Stateless wrapper over {@link #delegate};
	 * safe to publish once.
	 */
	private transient Set<Entry<Integer, Integer>> entrySet;

	ConcurrentIntIntMapView(ConcurrentIntIntMap delegate) {
		this(null, delegate);
	}

	ConcurrentIntIntMapView(ATN atn) {
		this(atn, atn.ll1Cache);
	}

	private ConcurrentIntIntMapView(ATN atn, ConcurrentIntIntMap delegate) {
		this.atn = atn;
		this.delegate = delegate;
	}

	private void syncDense(int packedKey, int alt) {
		if (atn != null) {
			atn.syncLl1Dense(packedKey, alt);
		}
	}

	@Override
	public Integer get(Object key) {
		if (!(key instanceof Integer)) {
			return null;
		}
		int v = delegate.get(((Integer) key).intValue());
		return v == ConcurrentIntIntMap.MISSING ? null : Integer.valueOf(v);
	}

	@Override
	public Integer put(Integer key, Integer value) {
		requireKeyValue(key, value);
		int k = key.intValue();
		int v = value.intValue();
		int prev = delegate.put(k, v);
		syncDense(k, v);
		return prev == ConcurrentIntIntMap.MISSING ? null : Integer.valueOf(prev);
	}

	@Override
	public Integer remove(Object key) {
		if (!(key instanceof Integer)) {
			return null;
		}
		int k = ((Integer) key).intValue();
		int prev = delegate.remove(k);
		if (prev != ConcurrentIntIntMap.MISSING) {
			syncDense(k, 0);
		}
		return prev == ConcurrentIntIntMap.MISSING ? null : Integer.valueOf(prev);
	}

	@Override
	public void clear() {
		delegate.clear();
		if (atn != null && atn.ll1Dense != null) {
			java.util.Arrays.fill(atn.ll1Dense, (short) 0);
		}
	}

	@Override
	public int size() {
		return delegate.size();
	}

	@Override
	public boolean isEmpty() {
		return delegate.size() == 0;
	}

	@Override
	public boolean containsKey(Object key) {
		if (!(key instanceof Integer)) {
			return false;
		}
		return delegate.get(((Integer) key).intValue()) != ConcurrentIntIntMap.MISSING;
	}

	@Override
	public boolean containsValue(Object value) {
		if (!(value instanceof Integer)) {
			return false;
		}
		return delegate.containsValue(((Integer) value).intValue());
	}

	@Override
	public Integer putIfAbsent(Integer key, Integer value) {
		requireKeyValue(key, value);
		int k = key.intValue();
		int v = value.intValue();
		int existing = delegate.putIfAbsent(k, v);
		if (existing == ConcurrentIntIntMap.MISSING) {
			syncDense(k, v);
		}
		return existing == ConcurrentIntIntMap.MISSING ? null : Integer.valueOf(existing);
	}

	@Override
	public boolean remove(Object key, Object value) {
		if (!(key instanceof Integer) || !(value instanceof Integer)) {
			return false;
		}
		return delegate.remove(
			((Integer) key).intValue(),
			((Integer) value).intValue());
	}

	@Override
	public boolean replace(Integer key, Integer oldValue, Integer newValue) {
		requireKeyValue(key, newValue);
		if (oldValue == null) {
			throw new NullPointerException();
		}
		boolean replaced = delegate.replace(key.intValue(), oldValue.intValue(), newValue.intValue());
		if (replaced) {
			syncDense(key.intValue(), newValue.intValue());
		}
		return replaced;
	}

	@Override
	public Integer replace(Integer key, Integer value) {
		requireKeyValue(key, value);
		int k = key.intValue();
		int v = value.intValue();
		int prev = delegate.replace(k, v);
		if (prev != ConcurrentIntIntMap.MISSING) {
			syncDense(k, v);
		}
		return prev == ConcurrentIntIntMap.MISSING ? null : Integer.valueOf(prev);
	}

	@Override
	public Set<Entry<Integer, Integer>> entrySet() {
		Set<Entry<Integer, Integer>> es = entrySet;
		if (es == null) {
			entrySet = es = new EntrySet();
		}
		return es;
	}

	private static void requireKeyValue(Integer key, Integer value) {
		if (key == null || value == null) {
			throw new NullPointerException();
		}
	}

	/**
	 * Live entry-set view. {@link #size()} and {@link #isEmpty()} track the
	 * primitive store; iterators capture a COW snapshot at creation time.
	 */
	private final class EntrySet extends AbstractSet<Entry<Integer, Integer>> {

		@Override
		public int size() {
			return delegate.size();
		}

		@Override
		public boolean isEmpty() {
			return delegate.size() == 0;
		}

		@Override
		public void clear() {
			delegate.clear();
		}

		@Override
		public boolean contains(Object o) {
			if (!(o instanceof Entry)) {
				return false;
			}
			Entry<?, ?> e = (Entry<?, ?>) o;
			Object key = e.getKey();
			Object value = e.getValue();
			if (!(key instanceof Integer) || !(value instanceof Integer)) {
				return false;
			}
			int stored = delegate.get(((Integer) key).intValue());
			return stored != ConcurrentIntIntMap.MISSING
				&& stored == ((Integer) value).intValue();
		}

		@Override
		public boolean remove(Object o) {
			if (!(o instanceof Entry)) {
				return false;
			}
			Entry<?, ?> e = (Entry<?, ?>) o;
			Object key = e.getKey();
			Object value = e.getValue();
			if (!(key instanceof Integer) || !(value instanceof Integer)) {
				return false;
			}
			return delegate.remove(
				((Integer) key).intValue(),
				((Integer) value).intValue());
		}

		@Override
		public Iterator<Entry<Integer, Integer>> iterator() {
			return new EntryIterator(delegate.snapshotEntries());
		}
	}

	/**
	 * Snapshot iterator over interleaved key/value pairs. {@link #remove()}
	 * deletes the last returned key from the live store (not only the snapshot).
	 */
	private final class EntryIterator implements Iterator<Entry<Integer, Integer>> {

		private final int[] entries;
		private int index;
		private int lastKey = ConcurrentIntIntMap.MISSING;
		private boolean canRemove;

		EntryIterator(int[] entries) {
			this.entries = entries;
		}

		@Override
		public boolean hasNext() {
			return index < entries.length;
		}

		@Override
		public Entry<Integer, Integer> next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			final int key = entries[index++];
			final int value = entries[index++];
			lastKey = key;
			canRemove = true;
			return new LiveEntry(key, value);
		}

		@Override
		public void remove() {
			if (!canRemove) {
				throw new IllegalStateException();
			}
			delegate.remove(lastKey);
			canRemove = false;
		}
	}

	/**
	 * Map entry that writes {@link #setValue} through to the primitive store.
	 */
	private final class LiveEntry implements Entry<Integer, Integer> {

		private final int key;
		private int value;

		LiveEntry(int key, int value) {
			this.key = key;
			this.value = value;
		}

		@Override
		public Integer getKey() {
			return Integer.valueOf(key);
		}

		@Override
		public Integer getValue() {
			return Integer.valueOf(value);
		}

		@Override
		public Integer setValue(Integer newValue) {
			if (newValue == null) {
				throw new NullPointerException();
			}
			int prev = value;
			delegate.put(key, newValue.intValue());
			value = newValue.intValue();
			return Integer.valueOf(prev);
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Entry)) {
				return false;
			}
			Entry<?, ?> e = (Entry<?, ?>) o;
			return Objects.equals(getKey(), e.getKey())
				&& Objects.equals(getValue(), e.getValue());
		}

		@Override
		public int hashCode() {
			return Integer.hashCode(key) ^ Integer.hashCode(value);
		}

		@Override
		public String toString() {
			return key + "=" + value;
		}
	}
}
