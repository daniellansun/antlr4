/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link OrderedATNConfigSet}, the lexer config-set type that
 * keys configs by {@link ATNConfig#hashCode()} and merges only on full equality.
 */
public class TestOrderedATNConfigSet {

	@Test
	public void defaultConstructorIsWritableAndEmpty() {
		OrderedATNConfigSet set = new OrderedATNConfigSet();
		assertTrue(set.isEmpty());
		assertFalse(set.isReadOnly());
	}

	@Test
	public void capacityConstructorAcceptsHints() {
		OrderedATNConfigSet zero = new OrderedATNConfigSet(0);
		assertTrue(zero.isEmpty());

		OrderedATNConfigSet sized = new OrderedATNConfigSet(32);
		assertTrue(sized.isEmpty());
		assertFalse(sized.isReadOnly());

		OrderedATNConfigSet negative = new OrderedATNConfigSet(-1);
		assertTrue(negative.isEmpty());
	}

	@Test
	public void mergesOnlyWhenFullyEqual() {
		OrderedATNConfigSet set = new OrderedATNConfigSet(8);
		BasicState state = new BasicState();
		state.stateNumber = 1;

		ATNConfig c1 = ATNConfig.create(state, 1, PredictionContext.EMPTY_FULL);
		ATNConfig c2 = ATNConfig.create(state, 1, PredictionContext.EMPTY_FULL);
		ATNConfig c3 = ATNConfig.create(state, 2, PredictionContext.EMPTY_FULL);

		assertTrue(set.add(c1));
		// Fully equal config merges (no size increase).
		assertFalse(set.add(c2));
		assertEquals(1, set.size());

		// Different alt does not merge.
		assertTrue(set.add(c3));
		assertEquals(2, set.size());
	}

	@Test
	public void differentContextsDoNotMerge() {
		OrderedATNConfigSet set = new OrderedATNConfigSet(4);
		PredictionContextCache cache = new PredictionContextCache();
		BasicState state = new BasicState();
		state.stateNumber = 10;

		PredictionContext ctx1 = cache.getChild(PredictionContext.EMPTY_FULL, 1);
		PredictionContext ctx2 = cache.getChild(PredictionContext.EMPTY_FULL, 2);

		assertTrue(set.add(ATNConfig.create(state, 1, ctx1)));
		assertTrue(set.add(ATNConfig.create(state, 1, ctx2)));
		assertEquals(2, set.size());
	}

	@Test
	public void clearAllowsReuseLikeLexerReachBuffer() {
		OrderedATNConfigSet reach = new OrderedATNConfigSet(8);
		BasicState s0 = new BasicState();
		s0.stateNumber = 0;
		reach.add(ATNConfig.create(s0, 1, PredictionContext.EMPTY_FULL));
		assertEquals(1, reach.size());

		reach.clear();
		assertTrue(reach.isEmpty());

		BasicState s1 = new BasicState();
		s1.stateNumber = 1;
		reach.add(ATNConfig.create(s1, 2, PredictionContext.EMPTY_FULL));
		assertEquals(1, reach.size());
		assertEquals(2, reach.get(0).getAlt());
	}

	@Test
	public void cloneReadonlyPreservesOrderAndEqualityKeying() {
		OrderedATNConfigSet set = new OrderedATNConfigSet(4);
		BasicState s0 = new BasicState();
		s0.stateNumber = 0;
		BasicState s1 = new BasicState();
		s1.stateNumber = 1;
		ATNConfig c0 = ATNConfig.create(s0, 1, PredictionContext.EMPTY_FULL);
		ATNConfig c1 = ATNConfig.create(s1, 2, PredictionContext.EMPTY_FULL);
		set.add(c0);
		set.add(c1);

		ATNConfigSet readonly = set.clone(true);
		assertTrue(readonly.isReadOnly());
		assertEquals(2, readonly.size());
		assertEquals(c0.getState().stateNumber, readonly.get(0).getState().stateNumber);
		assertEquals(c1.getState().stateNumber, readonly.get(1).getState().stateNumber);

		ATNConfigSet writable = set.clone(false);
		assertFalse(writable.isReadOnly());
		assertEquals(2, writable.size());
	}

	@Test
	public void hashCodeCacheIsUsedAsMergeKey() {
		// Adding a config after its hash was computed must still locate merges.
		OrderedATNConfigSet set = new OrderedATNConfigSet(4);
		BasicState state = new BasicState();
		state.stateNumber = 99;
		ATNConfig c1 = ATNConfig.create(state, 1, PredictionContext.EMPTY_FULL);
		int hash = c1.hashCode();
		assertTrue(set.add(c1));

		ATNConfig c2 = ATNConfig.create(state, 1, PredictionContext.EMPTY_FULL);
		assertEquals(hash, c2.hashCode());
		assertFalse(set.add(c2));
		assertEquals(1, set.size());
		assertSame(c1, set.get(0));
	}
}
