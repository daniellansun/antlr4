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
 * Unit tests for {@link ATNConfigSet}, including the capacity constructor used
 * by closure/reach hot paths.
 */
public class TestATNConfigSet {

	@Test
	public void defaultConstructorIsWritableAndEmpty() {
		ATNConfigSet set = new ATNConfigSet();
		assertTrue(set.isEmpty());
		assertEquals(0, set.size());
		assertFalse(set.isReadOnly());
		assertEquals(ATN.INVALID_ALT_NUMBER, set.getUniqueAlt());
	}

	@Test
	public void capacityConstructorAcceptsZeroAndPositive() {
		ATNConfigSet zero = new ATNConfigSet(0);
		assertTrue(zero.isEmpty());

		ATNConfigSet sized = new ATNConfigSet(32);
		assertTrue(sized.isEmpty());
		assertFalse(sized.isReadOnly());
	}

	@Test
	public void capacityConstructorAcceptsNegativeAsDefault() {
		// Non-positive hints use default map/list capacities (no exception).
		ATNConfigSet negative = new ATNConfigSet(-1);
		assertTrue(negative.isEmpty());
		assertFalse(negative.isReadOnly());
	}

	@Test
	public void smallCapacityHintStillSupportsGrowth() {
		// Small expected-size hints must not prevent expansion during closure-like
		// fan-out; HashMap capacity is floored so rehash cost stays reasonable.
		ATNConfigSet set = new ATNConfigSet(1);
		PredictionContextCache cache = new PredictionContextCache();
		for (int i = 0; i < 64; i++) {
			BasicState state = new BasicState();
			state.stateNumber = i;
			assertTrue(set.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL), cache));
		}
		assertEquals(64, set.size());
	}

	@Test
	public void clearAllowsReuseAsDoubleBuffer() {
		ATNConfigSet buffer = new ATNConfigSet(8);
		BasicState s0 = new BasicState();
		s0.stateNumber = 0;
		buffer.add(ATNConfig.create(s0, 1, PredictionContext.EMPTY_LOCAL));
		assertEquals(1, buffer.size());

		buffer.clear();
		assertTrue(buffer.isEmpty());
		assertEquals(ATN.INVALID_ALT_NUMBER, buffer.getUniqueAlt());

		BasicState s1 = new BasicState();
		s1.stateNumber = 1;
		buffer.add(ATNConfig.create(s1, 2, PredictionContext.EMPTY_LOCAL));
		assertEquals(1, buffer.size());
		assertEquals(2, buffer.getUniqueAlt());
	}

	@Test
	public void addMergesSameStateAndAlt() {
		ATNConfigSet set = new ATNConfigSet(4);
		PredictionContextCache cache = new PredictionContextCache();

		BasicState state = new BasicState();
		state.stateNumber = 10;

		PredictionContext ctx1 = cache.getChild(PredictionContext.EMPTY_LOCAL, 1);
		PredictionContext ctx2 = cache.getChild(PredictionContext.EMPTY_LOCAL, 2);

		ATNConfig c1 = ATNConfig.create(state, 1, ctx1);
		ATNConfig c2 = ATNConfig.create(state, 1, ctx2);

		assertTrue(set.add(c1, cache));
		// Second add merges prediction contexts for the same (state, alt).
		assertTrue(set.add(c2, cache));
		assertEquals(1, set.size());

		PredictionContext joined = set.get(0).getContext();
		assertEquals(2, joined.size());
	}

	@Test
	public void uniqueAltTracksSingleAndMultipleAlts() {
		ATNConfigSet set = new ATNConfigSet(8);
		BasicState s1 = new BasicState();
		s1.stateNumber = 1;
		BasicState s2 = new BasicState();
		s2.stateNumber = 2;

		set.add(ATNConfig.create(s1, 1, PredictionContext.EMPTY_LOCAL));
		assertEquals(1, set.getUniqueAlt());

		set.add(ATNConfig.create(s2, 2, PredictionContext.EMPTY_LOCAL));
		assertEquals(ATN.INVALID_ALT_NUMBER, set.getUniqueAlt());
	}

	@Test
	public void clearResetsProperties() {
		ATNConfigSet set = new ATNConfigSet(4);
		BasicState state = new BasicState();
		state.stateNumber = 5;
		set.add(ATNConfig.create(state, 3, PredictionContext.EMPTY_FULL));
		assertEquals(1, set.size());
		assertEquals(3, set.getUniqueAlt());

		set.clear();
		assertTrue(set.isEmpty());
		assertEquals(ATN.INVALID_ALT_NUMBER, set.getUniqueAlt());
		assertFalse(set.hasSemanticContext());
		assertFalse(set.getDipsIntoOuterContext());
	}

	@Test
	public void cloneWritablePreservesConfigs() {
		ATNConfigSet set = new ATNConfigSet(4);
		BasicState state = new BasicState();
		state.stateNumber = 9;
		ATNConfig config = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		set.add(config);

		ATNConfigSet copy = set.clone(false);
		assertFalse(copy.isReadOnly());
		assertEquals(1, copy.size());
		assertEquals(config.getState().stateNumber, copy.get(0).getState().stateNumber);
	}

	@Test
	public void cloneReadonlyIsReadOnly() {
		ATNConfigSet set = new ATNConfigSet(4);
		BasicState state = new BasicState();
		state.stateNumber = 3;
		set.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL));

		ATNConfigSet readonly = set.clone(true);
		assertTrue(readonly.isReadOnly());
		assertEquals(1, readonly.size());
	}

	@Test
	public void doubleBufferClearPatternMatchesClosureReuse() {
		// Mirrors ParserATNSimulator.closure double-buffering of intermediate sets.
		ATNConfigSet bufferA = new ATNConfigSet(4);
		ATNConfigSet bufferB = new ATNConfigSet(4);

		BasicState s0 = new BasicState();
		s0.stateNumber = 0;
		BasicState s1 = new BasicState();
		s1.stateNumber = 1;

		bufferA.add(ATNConfig.create(s0, 1, PredictionContext.EMPTY_LOCAL));
		assertEquals(1, bufferA.size());

		// Layer 1 -> bufferB
		for (ATNConfig c : bufferA) {
			bufferB.add(ATNConfig.create(s1, c.getAlt(), c.getContext()));
		}
		assertEquals(1, bufferB.size());

		// Swap and clear for reuse (as closure does between layers).
		ATNConfigSet nextCurrent = bufferB;
		bufferA.clear();
		ATNConfigSet intermediate = bufferA;
		ATNConfigSet current = nextCurrent;

		assertSame(bufferB, current);
		assertSame(bufferA, intermediate);
		assertTrue(intermediate.isEmpty());
		assertEquals(1, current.size());

		// Reuse intermediate for next layer without allocating a new set.
		intermediate.add(ATNConfig.create(s0, 2, PredictionContext.EMPTY_LOCAL));
		assertEquals(1, intermediate.size());
		assertTrue(current.get(0).getAlt() != intermediate.get(0).getAlt());
	}
}
