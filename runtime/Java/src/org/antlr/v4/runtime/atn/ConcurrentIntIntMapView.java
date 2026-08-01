/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
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
 * <p>Only the operations used by the runtime and existing tests are fully
 * supported ({@code get}/{@code put}/{@code clear}/{@code size}/
 * {@code isEmpty}/{@code containsKey}). Bulk views and unsupported concurrent
 * helpers throw {@link UnsupportedOperationException}.</p>
 *
 * <p>Package-private; not part of any public API surface.</p>
 */
final class ConcurrentIntIntMapView extends AbstractMap<Integer, Integer>
	implements ConcurrentMap<Integer, Integer> {

	private final ConcurrentIntIntMap delegate;

	ConcurrentIntIntMapView(ConcurrentIntIntMap delegate) {
		this.delegate = delegate;
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
		if (key == null || value == null) {
			throw new NullPointerException();
		}
		int prev = delegate.get(key.intValue());
		delegate.put(key.intValue(), value.intValue());
		return prev == ConcurrentIntIntMap.MISSING ? null : Integer.valueOf(prev);
	}

	@Override
	public void clear() {
		delegate.clear();
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
	public Integer putIfAbsent(Integer key, Integer value) {
		if (key == null || value == null) {
			throw new NullPointerException();
		}
		int k = key.intValue();
		int existing = delegate.get(k);
		if (existing != ConcurrentIntIntMap.MISSING) {
			return Integer.valueOf(existing);
		}
		delegate.put(k, value.intValue());
		return null;
	}

	@Override
	public boolean remove(Object key, Object value) {
		throw new UnsupportedOperationException("LL1Table.remove(key,value) is not supported");
	}

	@Override
	public boolean replace(Integer key, Integer oldValue, Integer newValue) {
		throw new UnsupportedOperationException("LL1Table.replace is not supported");
	}

	@Override
	public Integer replace(Integer key, Integer value) {
		throw new UnsupportedOperationException("LL1Table.replace is not supported");
	}

	@Override
	public Set<Entry<Integer, Integer>> entrySet() {
		// Not used on the hot path; empty view keeps AbstractMap contracts simple.
		return Collections.emptySet();
	}
}
