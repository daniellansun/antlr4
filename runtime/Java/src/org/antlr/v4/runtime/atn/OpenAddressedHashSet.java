/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import com.carrotsearch.hppc.ObjectHashSet;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Package-private JDK {@link Set} adapter over a {@link ClearableObjectHashSet}
 * (HPPC open addressing underneath).
 *
 * <p>
 * Used on the epsilon-closure busy-set hot path so production code keeps the
 * open-addressed storage (no per-element entry objects) while every
 * {@code public}/{@code protected} API surface speaks only JDK collection
 * interfaces. Subclasses of {@link ParserATNSimulator} therefore override
 * {@link ParserATNSimulator#closure(ATNConfig, ATNConfigSet, ATNConfigSet, Set, boolean, boolean, PredictionContextCache, int, boolean)}
 * with a standard {@link Set} parameter and never depend on HPPC types.</p>
 *
 * <p>
 * PERF: {@link #add}, {@link #contains}, {@link #remove}, {@link #clear},
 * {@link #size}, and {@link #isEmpty} are thin delegates. {@link #clear()} is
 * O(1) when the set is already empty ({@link ClearableObjectHashSet}); only a
 * non-empty table pays HPPC's bulk {@code Arrays.fill}. The adapter object is
 * allocated once per {@link EpsilonClosure} and retained across predictions;
 * only the underlying open-addressed table grows.</p>
 *
 * <p>
 * Not part of the published API. Identity of instances is observable only to
 * same-package tests via {@link EpsilonClosure#retainedBusy()}.</p>
 *
 * @param <E> element type (value equality via {@link Object#equals} /
 * {@link Object#hashCode}, matching HPPC's default strategy)
 */
final class OpenAddressedHashSet<E> extends AbstractSet<E> {

	private final ClearableObjectHashSet<E> delegate;

	/**
	 * @param expectedElements expected element count used as a capacity hint
	 * for the underlying open-addressed table (same contract as
	 * {@link ClearableObjectHashSet#ClearableObjectHashSet(int)})
	 */
	OpenAddressedHashSet(int expectedElements) {
		this.delegate = new ClearableObjectHashSet<E>(expectedElements);
	}

	@Override
	public boolean add(E e) {
		return delegate.add(e);
	}

	@Override
	@SuppressWarnings("unchecked")
	public boolean contains(Object o) {
		// HPPC erases KType to Object; the cast is the standard Set pattern.
		return delegate.contains((E)o);
	}

	@Override
	@SuppressWarnings("unchecked")
	public boolean remove(Object o) {
		return delegate.remove((E)o);
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
		return delegate.isEmpty();
	}

	@Override
	public Object[] toArray() {
		return delegate.toArray();
	}

	/**
	 * Iterator over a snapshot of current members. {@link Iterator#remove()} is
	 * supported by delegating to {@link #remove(Object)}. A snapshot avoids
	 * concurrent-modification hazards against HPPC's open-addressed table
	 * (whose native cursor iterator does not support removal).
	 *
	 * <p>Iteration order is unspecified and may change after structural
	 * modification, matching typical hash-set contracts. Snapshot iterators do
	 * not reflect elements added after the iterator was created.</p>
	 */
	@Override
	public Iterator<E> iterator() {
		return new SnapshotIterator(delegate.toArray());
	}

	/**
	 * Package-private identity of the wrapped open-addressed set for
	 * diagnostics/tests that must assert the HPPC-backed implementation is in
	 * use without exposing HPPC on the {@link Set} type itself.
	 *
	 * <p>Return type is the HPPC {@link ObjectHashSet} supertype so existing
	 * tests that only need {@link ObjectHashSet} methods keep compiling; the
	 * concrete instance is always a {@link ClearableObjectHashSet}.</p>
	 */
	ObjectHashSet<E> delegate() {
		return delegate;
	}

	/**
	 * Snapshot iterator over elements present when {@link #iterator()} was
	 * called. Removal mutates the live set, not the snapshot array.
	 */
	private final class SnapshotIterator implements Iterator<E> {

		private final Object[] elements;
		private int index;
		private E lastReturned;
		private boolean canRemove;

		SnapshotIterator(Object[] elements) {
			this.elements = elements;
		}

		@Override
		public boolean hasNext() {
			return index < elements.length;
		}

		@Override
		@SuppressWarnings("unchecked")
		public E next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			lastReturned = (E)elements[index++];
			canRemove = true;
			return lastReturned;
		}

		@Override
		public void remove() {
			if (!canRemove) {
				throw new IllegalStateException();
			}
			OpenAddressedHashSet.this.remove(lastReturned);
			canRemove = false;
		}
	}
}
