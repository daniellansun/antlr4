/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.dfa;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 *
 * @author Sam Harwell
 */
public final class ArrayEdgeMap<T> extends AbstractEdgeMap<T> {

	private final AtomicReferenceArray<T> arrayData;
	private final AtomicInteger size;

	@SuppressWarnings("unchecked")
	public ArrayEdgeMap(int minIndex, int maxIndex) {
		super(minIndex, maxIndex);
		arrayData = new AtomicReferenceArray<>(maxIndex - minIndex + 1);
		size = new AtomicInteger();
	}

	@Override
	public int size() {
		return size.get();
	}

	@Override
	public boolean isEmpty() {
		return size() == 0;
	}

	@Override
	public boolean containsKey(int key) {
		return get(key) != null;
	}

	@Override
	public T get(int key) {
		if (key < minIndex || key > maxIndex) {
			return null;
		}

		return arrayData.get(key - minIndex);
	}

	@Override
	public ArrayEdgeMap<T> put(int key, T value) {
		if (key >= minIndex && key <= maxIndex) {
			T existing = arrayData.getAndSet(key - minIndex, value);
			if (existing == null && value != null) {
				size.incrementAndGet();
			} else if (existing != null && value == null) {
				size.decrementAndGet();
			}
		}

		return this;
	}

	@Override
	public ArrayEdgeMap<T> remove(int key) {
		return put(key, null);
	}

	@Override
	@SuppressWarnings("deprecation")
	public ArrayEdgeMap<T> putAll(EdgeMap<? extends T> m) {
		if (m.isEmpty()) {
			return this;
		}

		if (m instanceof HashEdgeMap<?>) {
			HashEdgeMap<? extends T> other = (HashEdgeMap<? extends T>)m;
			AtomicIntegerArray keys = other.getKeys();
			T[] values = other.getValues();
			ArrayEdgeMap<T> result = this;
			for (int i = 0; i < values.length; i++) {
				T value = values[i];
				if (value != null) {
					result = result.put(keys.get(i), value);
				}
			}

			return result;
		} else if (m instanceof ArrayEdgeMap<?>) {
			ArrayEdgeMap<? extends T> other = (ArrayEdgeMap<? extends T>)m;
			int minOverlap = Math.max(minIndex, other.minIndex);
			int maxOverlap = Math.min(maxIndex, other.maxIndex);
			ArrayEdgeMap<T> result = this;
			for (int i = minOverlap; i <= maxOverlap; i++) {
				T value = m.get(i);
				// put(key, null) clears the slot. Holes in the source must not
				// delete keys the destination already holds.
				if (value != null) {
					result = result.put(i, value);
				}
			}

			return result;
		} else if (m instanceof SingletonEdgeMap<?>) {
			SingletonEdgeMap<? extends T> other = (SingletonEdgeMap<? extends T>)m;
			assert !other.isEmpty();
			return put(other.getKey(), other.getValue());
		} else if (m instanceof SparseEdgeMap<?>) {
			SparseEdgeMap<? extends T> other = (SparseEdgeMap<? extends T>)m;
			synchronized (other) {
				int[] keys = other.getKeys();
				List<? extends T> values = other.getValues();
				ArrayEdgeMap<T> result = this;
				for (int i = 0; i < values.size(); i++) {
					result = result.put(keys[i], values.get(i));
				}
				return result;
			}
		} else {
			throw new UnsupportedOperationException(String.format("EdgeMap of type %s is not supported.", m.getClass().getName()));
		}
	}

	@Override
	public EmptyEdgeMap<T> clear() {
		return new EmptyEdgeMap<>(minIndex, maxIndex);
	}

	@Override
	public Map<Integer, T> toMap() {
		if (isEmpty()) {
			return Collections.emptyMap();
		}

		Map<Integer, T> result = new LinkedHashMap<>();
		for (int i = 0; i < arrayData.length(); i++) {
			T element = arrayData.get(i);
			if (element == null) {
				continue;
			}

			result.put(i + minIndex, element);
		}

		return result;
	}

	@Override
	public Set<Map.Entry<Integer, T>> entrySet() {
		return new EntrySet();
	}

	private class EntrySet extends AbstractEntrySet {
		@Override
		public Iterator<Map.Entry<Integer, T>> iterator() {
			return new EntryIterator();
		}
	}

	private class EntryIterator implements Iterator<Map.Entry<Integer, T>> {
		/** Index of the next occupied slot, or {@code -1} when exhausted. */
		private int nextIndex;

		EntryIterator() {
			nextIndex = advance(0);
		}

		private int advance(int start) {
			int length = arrayData.length();
			for (int i = start; i < length; i++) {
				if (arrayData.get(i) != null) {
					return i;
				}
			}
			return -1;
		}

		@Override
		public boolean hasNext() {
			return nextIndex >= 0;
		}

		@Override
		public Map.Entry<Integer, T> next() {
			if (nextIndex < 0) {
				throw new NoSuchElementException();
			}

			final int index = nextIndex;
			final T currentElement = arrayData.get(index);
			nextIndex = advance(index + 1);
			return new Map.Entry<Integer, T>() {
				private final int key = minIndex + index;
				private final T value = currentElement;

				@Override
				public Integer getKey() {
					return key;
				}

				@Override
				public T getValue() {
					return value;
				}

				@Override
				public T setValue(T value) {
					throw new UnsupportedOperationException("Not supported yet.");
				}
			};
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException("Not supported yet.");
		}
	}
}
