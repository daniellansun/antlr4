/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.ParserRuleContext;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ParserATNSimulator#applyPrecedenceFilter}, including
 * the primitive state-number index used to eliminate alt&gt;1 configs that match
 * an alt-1 prediction context for the same ATN state.
 */
public class TestApplyPrecedenceFilter {

	@Test
	public void filterEliminatesMatchingHigherAltOnSameState() {
		ATN atn = new ATN(ATNType.PARSER, 0);
		ParserATNSimulator simulator = new ParserATNSimulator(atn);

		BasicState state = new BasicState();
		state.stateNumber = 7;

		PredictionContext shared = PredictionContext.EMPTY_LOCAL;
		ATNConfig alt1 = ATNConfig.create(state, 1, shared);
		ATNConfig alt2 = ATNConfig.create(state, 2, shared);

		ATNConfigSet input = new ATNConfigSet(4);
		input.add(alt1);
		input.add(alt2);
		assertEquals(2, input.size());

		ATNConfigSet filtered = simulator.applyPrecedenceFilter(input, new ParserRuleContext(), null);
		// alt 2 shares state+context with alt 1 and is not suppressed -> eliminated
		assertEquals(1, filtered.size());
		assertEquals(1, filtered.get(0).getAlt());
		assertEquals(7, filtered.get(0).getState().stateNumber);
	}

	@Test
	public void filterKeepsHigherAltWhenPrecedenceFilterSuppressed() {
		ATN atn = new ATN(ATNType.PARSER, 0);
		ParserATNSimulator simulator = new ParserATNSimulator(atn);

		BasicState state = new BasicState();
		state.stateNumber = 3;

		PredictionContext shared = PredictionContext.EMPTY_LOCAL;
		ATNConfig alt1 = ATNConfig.create(state, 1, shared);
		ATNConfig alt2 = ATNConfig.create(state, 2, shared);
		alt2.setPrecedenceFilterSuppressed(true);

		ATNConfigSet input = new ATNConfigSet(4);
		input.add(alt1);
		input.add(alt2);

		ATNConfigSet filtered = simulator.applyPrecedenceFilter(input, new ParserRuleContext(), null);
		assertEquals(2, filtered.size());
	}

	@Test
	public void filterKeepsHigherAltWhenContextDiffers() {
		ATN atn = new ATN(ATNType.PARSER, 0);
		ParserATNSimulator simulator = new ParserATNSimulator(atn);
		PredictionContextCache cache = new PredictionContextCache();

		BasicState state = new BasicState();
		state.stateNumber = 0; // exercises HPPC empty-key path for IntObjectHashMap

		PredictionContext ctx1 = cache.getChild(PredictionContext.EMPTY_LOCAL, 1);
		PredictionContext ctx2 = cache.getChild(PredictionContext.EMPTY_LOCAL, 2);

		ATNConfig alt1 = ATNConfig.create(state, 1, ctx1);
		ATNConfig alt2 = ATNConfig.create(state, 2, ctx2);

		ATNConfigSet input = new ATNConfigSet(4);
		input.add(alt1, cache);
		input.add(alt2, cache);

		ATNConfigSet filtered = simulator.applyPrecedenceFilter(input, new ParserRuleContext(), cache);
		assertEquals(2, filtered.size());
		assertTrue(containsAlt(filtered, 1));
		assertTrue(containsAlt(filtered, 2));
	}

	@Test
	public void filterIndexesMultipleStatesIndependently() {
		ATN atn = new ATN(ATNType.PARSER, 0);
		ParserATNSimulator simulator = new ParserATNSimulator(atn);

		BasicState s0 = new BasicState();
		s0.stateNumber = 0;
		BasicState s1 = new BasicState();
		s1.stateNumber = 1;

		PredictionContext ctx = PredictionContext.EMPTY_LOCAL;
		ATNConfigSet input = new ATNConfigSet(8);
		input.add(ATNConfig.create(s0, 1, ctx));
		input.add(ATNConfig.create(s0, 2, ctx)); // eliminated against s0/alt1
		input.add(ATNConfig.create(s1, 1, ctx));
		input.add(ATNConfig.create(s1, 3, ctx)); // eliminated against s1/alt1

		ATNConfigSet filtered = simulator.applyPrecedenceFilter(input, new ParserRuleContext(), null);
		assertEquals(2, filtered.size());
		assertTrue(containsStateAlt(filtered, 0, 1));
		assertTrue(containsStateAlt(filtered, 1, 1));
		assertFalse(containsStateAlt(filtered, 0, 2));
		assertFalse(containsStateAlt(filtered, 1, 3));
	}

	private static boolean containsAlt(ATNConfigSet set, int alt) {
		for (ATNConfig c : set) {
			if (c.getAlt() == alt) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsStateAlt(ATNConfigSet set, int stateNumber, int alt) {
		for (ATNConfig c : set) {
			if (c.getState().stateNumber == stateNumber && c.getAlt() == alt) {
				return true;
			}
		}
		return false;
	}
}
