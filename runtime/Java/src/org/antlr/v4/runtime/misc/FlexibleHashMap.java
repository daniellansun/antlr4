/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.misc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A limited map (many unsupported operations) that lets me use
 *  varying hashCode/equals.
 */
public class FlexibleHashMap<K,V> implements Map<K, V> {
	public static final int INITAL_CAPACITY = 16; // must be power of 2
	public static final int INITAL_BUCKET_CAPACITY = 8;
	public static final double LOAD_FACTOR = 0.75;

	public static class Entry<K, V> {
		public final K key;
		public V value;

		public Entry(K key, V value) { this.key = key; this.value = value; }

		@Override
		public String toString() {
			return key.toString()+":"+value.toString();
		}
	}

	@NotNull
	protected final AbstractEqualityComparator<? super K> comparator;

	protected LinkedList<Entry<K, V>>[] buckets;

	/** How many elements in set */
	protected int n = 0;

	protected int currentPrime = 1; // jump by 4 primes each expand or whatever

	/** when to expand */
	protected int threshold;
	protected final int initialCapacity;
	protected final int initialBucketCapacity;

	public FlexibleHashMap() {
		this(null, INITAL_CAPACITY, INITAL_BUCKET_CAPACITY);
	}

	public FlexibleHashMap(@Nullable AbstractEqualityComparator<? super K> comparator) {
		this(comparator, INITAL_CAPACITY, INITAL_BUCKET_CAPACITY);
	}

	public FlexibleHashMap(@Nullable AbstractEqualityComparator<? super K> comparator, int initialCapacity, int initialBucketCapacity) {
		if (comparator == null) {
			comparator = ObjectEqualityComparator.INSTANCE;
		}

		this.comparator = comparator;
		this.initialCapacity = initialCapacity;
		this.initialBucketCapacity = initialBucketCapacity;
		this.threshold = (int)Math.floor(initialCapacity * LOAD_FACTOR);
		this.buckets = createEntryListArray(initialBucketCapacity);
	}

	private static <K, V> LinkedList<Entry<K, V>>[] createEntryListArray(int length) {
		@SuppressWarnings("unchecked")
		LinkedList<Entry<K, V>>[] result = (LinkedList<Entry<K, V>>[])new LinkedList<?>[length];
		return result;
	}

	protected int getBucket(K key) {
		int hash = comparator.hashCode(key);
		int b = hash & (buckets.length-1); // assumes len is power of 2
		return b;
	}

	@Override
	public V get(Object key) {
		@SuppressWarnings("unchecked")
		K typedKey = (K)key;
		if ( key==null ) return null;
		int b = getBucket(typedKey);
		LinkedList<Entry<K, V>> bucket = buckets[b];
		if ( bucket==null ) return null; // no bucket
		for (Entry<K, V> e : bucket) {
			if ( comparator.equals(e.key, typedKey) ) {
				return e.value;
			}
		}
		return null;
	}

	@Override
	public V put(K key, V value) {
		if ( key==null ) return null;
		if ( n > threshold ) expand();
		int b = getBucket(key);
		LinkedList<Entry<K, V>> bucket = buckets[b];
		if ( bucket==null ) {
			bucket = buckets[b] = new LinkedList<Entry<K, V>>();
		}
		for (Entry<K, V> e : bucket) {
			if ( comparator.equals(e.key, key) ) {
				V prev = e.value;
				e.value = value;
				n++;
				return prev;
			}
		}
		// not there
		bucket.add(new Entry<K, V>(key, value));
		n++;
		return null;
	}

	@Override
	public V remove(Object key) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Set<K> keySet() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Collection<V> values() {
		List<V> a = new ArrayList<V>(size());
		for (LinkedList<Entry<K, V>> bucket : buckets) {
			if ( bucket==null ) continue;
			for (Entry<K, V> e : bucket) {
				a.add(e.value);
			}
		}
		return a;
	}

	@Override
	public Set<Map.Entry<K, V>> entrySet() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean containsKey(Object key) {
		return get(key)!=null;
	}

	@Override
	public boolean containsValue(Object value) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int hashCode() {
		int hash = MurmurHash.initialize();
		for (LinkedList<Entry<K, V>> bucket : buckets) {
			if ( bucket==null ) continue;
			for (Entry<K, V> e : bucket) {
				if ( e==null ) break;
				hash = MurmurHash.update(hash, comparator.hashCode(e.key));
			}
		}

		hash = MurmurHash.finish(hash, size());
		return hash;
	}

	@Override
	public boolean equals(Object o) {
		throw new UnsupportedOperationException();
	}

	protected void expand() {
		LinkedList<Entry<K, V>>[] old = buckets;
		currentPrime += 4;
		int newCapacity = buckets.length * 2;
		LinkedList<Entry<K, V>>[] newTable = createEntryListArray(newCapacity);
		buckets = newTable;
		threshold = (int)(newCapacity * LOAD_FACTOR);
//		System.out.println("new size="+newCapacity+", thres="+threshold);
		// rehash all existing entries
		int oldSize = size();
		for (LinkedList<Entry<K, V>> bucket : old) {
			if ( bucket==null ) continue;
			for (Entry<K, V> e : bucket) {
				if ( e==null ) break;
				put(e.key, e.value);
			}
		}
		n = oldSize;
	}

	@Override
	public int size() {
		return n;
	}

	@Override
	public boolean isEmpty() {
		return n==0;
	}

	@Override
	public void clear() {
		buckets = createEntryListArray(this.initialCapacity);
		n = 0;
		threshold = (int)Math.floor(this.initialCapacity * LOAD_FACTOR);
	}

	@Override
	public String toString() {
		if ( size()==0 ) return "{}";

		StringBuilder buf = new StringBuilder();
		buf.append('{');
		boolean first = true;
		for (LinkedList<Entry<K, V>> bucket : buckets) {
			if ( bucket==null ) continue;
			for (Entry<K, V> e : bucket) {
				if ( e==null ) break;
				if ( first ) first=false;
				else buf.append(", ");
				buf.append(e);
			}
		}
		buf.append('}');
		return buf.toString();
	}

	public String toTableString() {
		StringBuilder buf = new StringBuilder();
		for (LinkedList<Entry<K, V>> bucket : buckets) {
			if ( bucket==null ) {
				buf.append("null\n");
				continue;
			}
			buf.append('[');
			boolean first = true;
			for (Entry<K, V> e : bucket) {
				if ( first ) first=false;
				else buf.append(" ");
				if ( e==null ) buf.append("_");
				else buf.append(e);
			}
			buf.append("]\n");
		}
		return buf.toString();
	}
}
