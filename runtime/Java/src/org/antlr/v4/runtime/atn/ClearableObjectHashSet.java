/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import com.carrotsearch.hppc.ObjectHashSet;

/**
 * Package-private {@link ObjectHashSet} tuned for retained epsilon-closure
 * busy-set reuse.
 *
 * <p>
 * HPPC's default {@link ObjectHashSet#clear()} always
 * {@link java.util.Arrays#fill fills} the entire {@code keys} table
 * (O(capacity)), even when the set is already empty. The busy set is cleared
 * after every {@link EpsilonClosure#close} invocation; empty bulk fills were
 * pure overhead once the set had grown.</p>
 *
 * <p>
 * Empty clear is O(1). Non-empty clear keeps HPPC's bulk fill. HPPC types never
 * appear in {@code public}/{@code protected} APIs — this set is only reached
 * through {@link OpenAddressedHashSet}'s JDK {@link java.util.Set} adapter.</p>
 *
 * @param <KType> element type
 */
final class ClearableObjectHashSet<KType> extends ObjectHashSet<KType> {

	/**
	 * @param expectedElements expected element count used as a capacity hint
	 * (same contract as {@link ObjectHashSet#ObjectHashSet(int)})
	 */
	ClearableObjectHashSet(int expectedElements) {
		super(expectedElements);
	}

	/**
	 * Clears all elements. When the set is already empty this method returns
	 * immediately without touching the backing array.
	 *
	 * <p>Uses {@link #isEmpty()} so the fast path does not hard-code HPPC
	 * occupancy field names. Non-empty clear still delegates to HPPC's bulk
	 * {@code Arrays.fill}.</p>
	 */
	@Override
	public void clear() {
		if (isEmpty()) {
			return;
		}
		super.clear();
	}
}
