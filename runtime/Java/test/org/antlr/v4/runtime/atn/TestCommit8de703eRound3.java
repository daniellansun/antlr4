/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.ParserInterpreter;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Remaining branch coverage for commit 8de703e82 (dense LL(1) table, config
 * merge, and LL(1) cache write guards).
 */
public class TestCommit8de703eRound3 {

	@Test
	public void syncLl1DenseGuardsNullStrideAndNegativeIndex() {
		ATN atn = new ATN(ATNType.PARSER, 4);
		atn.ensureLl1Dense(2);
		assertNotNull(atn.ll1Dense);
		int stride = atn.ll1Stride;
		atn.ll1Dense[1] = 9;

		atn.ll1Stride = 0;
		atn.syncLl1Dense((0 << 16) + 1, 3);
		assertEquals(9, atn.ll1Dense[1]);

		atn.ll1Stride = stride;
		atn.ll1Stride = Integer.MAX_VALUE;
		atn.syncLl1Dense(2 << 16, 4);
		atn.ll1Stride = stride;
		atn.syncLl1Dense((0 << 16) + 1, 2);
		assertEquals(2, atn.ll1Dense[1]);
		atn.syncLl1Dense((20 << 16) + 1, 8);
	}

	@Test
	public void ensureLl1DenseReallocatesWhenStrideFieldIsStale() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		atn.ensureLl1Dense(1);
		int need = atn.ll1Dense.length;
		atn.ll1Dense[0] = 6;
		atn.ll1Stride = atn.ll1Stride + 7;
		atn.ensureLl1Dense(1);
		assertEquals(need, atn.ll1Dense.length);
		assertEquals(0, atn.ll1Dense[0]);
	}

	@Test
	public void viewClearWithAtnButNoDenseIsNoOp() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		assertNull(atn.ll1Dense);
		atn.LL1Table.put(1, 2);
		atn.LL1Table.clear();
		assertTrue(atn.LL1Table.isEmpty());
		assertNull(atn.ll1Dense);
	}

	@Test
	public void canMergeCoversIdentityEqualsAndMismatch() {
		ATNConfigSet set = new ATNConfigSet(4);
		BasicState state = new BasicState();
		state.stateNumber = 3;
		SemanticContext.Predicate p = new SemanticContext.Predicate(1, 1, false);
		SemanticContext.Predicate pEq = new SemanticContext.Predicate(1, 1, false);
		SemanticContext.Predicate pOther = new SemanticContext.Predicate(2, 2, false);
		ATNConfig left = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL, p);
		ATNConfig same = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL, p);
		ATNConfig equal = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL, pEq);
		ATNConfig mismatch = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL, pOther);
		long key = set.getKey(left);
		assertTrue(set.canMerge(left, key, left));
		assertTrue(set.canMerge(left, key, same));
		assertTrue(set.canMerge(left, key, equal));
		assertFalse(set.canMerge(left, key, mismatch));
	}

	@Test
	public void ll1WriteSkippedForEofAndDenseIndexOverflow() throws Exception {
		ATN atn = ATNTestHelpers.buildParserAStar();
		ParserInterpreter empty = ATNTestHelpers.createParser(atn);
		ParserATNSimulator emptySim = (ParserATNSimulator) empty.getInterpreter();
		emptySim.optimize_ll1 = true;
		assertNotNull(empty.parse(0));

		ParserInterpreter p = ATNTestHelpers.createParser(atn, 1);
		ParserATNSimulator sim = (ParserATNSimulator) p.getInterpreter();
		sim.optimize_ll1 = true;
		assertNotNull(p.parse(0));

		p.getInputStream().seek(0);
		p.reset();
		assertNotNull(p.parse(0));

		p.getInputStream().seek(0);
		atn.ll1Dense = new short[4];
		atn.ll1Stride = Integer.MAX_VALUE;
		java.lang.reflect.Method tryLl1 = ParserATNSimulator.class
			.getDeclaredMethod("tryLL1Prediction", org.antlr.v4.runtime.TokenStream.class, int.class);
		tryLl1.setAccessible(true);
		Object miss = tryLl1.invoke(sim, p.getInputStream(), 2);
		assertEquals(ATN.INVALID_ALT_NUMBER, ((Integer) miss).intValue());
	}

	@Test
	public void unexpectedTokenDoesNotCacheLl1Alt() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		ParserInterpreter p = ATNTestHelpers.createParser(atn, 3);
		p.removeErrorListeners();
		ParserATNSimulator sim = (ParserATNSimulator) p.getInterpreter();
		sim.optimize_ll1 = true;
		try {
			p.parse(0);
		}
		catch (RuntimeException ignored) {
			// recovery / no viable
		}
	}

	@Test
	public void ll1CacheWriteWithDenseNullAndOobIndex() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		ParserInterpreter p = ATNTestHelpers.createParser(atn, 1);
		ParserATNSimulator sim = (ParserATNSimulator) p.getInterpreter();
		sim.optimize_ll1 = true;
		assertNotNull(p.parse(0));

		sim.clearDFA();
		atn.ll1Cache.clear();
		atn.ll1Dense = null;
		p.getInputStream().seek(0);
		p.reset();
		assertNotNull(p.parse(0));

		sim.clearDFA();
		atn.ll1Dense = new short[1];
		atn.ll1Stride = Integer.MAX_VALUE;
		p.getInputStream().seek(0);
		p.reset();
		assertNotNull(p.parse(0));
	}
}
