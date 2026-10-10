/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.VocabularyImpl;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Precedence LL(1) table: direct stores, and the warm SLL path on a
 * precedence decision.
 */
public class TestPrecedenceLL1 {

	private static ATN fresh() {
		ATN atn = new ATN(ATNType.PARSER, 7);
		atn.ll1Stride = 4;
		return atn;
	}

	private static int nonzero(ATN atn) {
		short[][] tables = atn.precedenceLL1;
		if (tables == null) {
			return 0;
		}
		int n = 0;
		for (int i = 0; i < tables.length; i++) {
			short[] table = tables[i];
			if (table == null) {
				continue;
			}
			for (int j = 0; j < table.length; j++) {
				if (table[j] != 0) {
					n++;
				}
			}
		}
		return n;
	}

	@Test
	public void storesAltAndMissesNeighbors() {
		ATN atn = fresh();
		assertEquals(0, atn.cachedPrecedenceAlt(0, 1, 2));
		atn.cachePrecedenceAlt(0, 1, 2, 3);
		assertEquals(3, atn.cachedPrecedenceAlt(0, 1, 2));
		assertEquals(0, atn.cachedPrecedenceAlt(0, 1, 1));
		assertEquals(0, atn.cachedPrecedenceAlt(0, 2, 2));
		assertEquals(0, atn.cachedPrecedenceAlt(1, 1, 2));
		assertEquals(0, atn.cachedPrecedenceAlt(-1, 1, 2));
		assertEquals(0, atn.cachedPrecedenceAlt(0, -1, 2));
		assertEquals(0, atn.cachedPrecedenceAlt(0, ATN.MAX_PRECEDENCE_LL1 + 1, 2));
		assertEquals(0, atn.cachedPrecedenceAlt(0, 1, -1));
		assertEquals(0, atn.cachedPrecedenceAlt(0, 1, 4));
		assertEquals(1, nonzero(atn));

		atn.precedenceLL1 = new short[][] { null, new short[0] };
		assertEquals(0, atn.cachedPrecedenceAlt(0, 0, 0));
		assertEquals(0, atn.cachedPrecedenceAlt(1, 0, 0));
	}

	@Test
	public void growsExistingTableAndAllowsNullDecisionArray() {
		ATN atn = fresh();
		atn.cachePrecedenceAlt(0, 0, 1, 2);
		atn.cachePrecedenceAlt(3, 0, 1, 4);
		assertEquals(2, atn.cachedPrecedenceAlt(0, 0, 1));
		assertEquals(4, atn.cachedPrecedenceAlt(3, 0, 1));
		assertEquals(0, atn.cachedPrecedenceAlt(2, 0, 1));

		atn.decisionToDFA = null;
		atn.cachePrecedenceAlt(5, 1, 1, 8);
		assertEquals(8, atn.cachedPrecedenceAlt(5, 1, 1));
		assertEquals(2, atn.cachedPrecedenceAlt(0, 0, 1));
	}

	@Test
	public void rejectsOutOfRangeAndKeepsFirstAlt() {
		ATN atn = fresh();
		atn.cachePrecedenceAlt(0, 1, 2, 0);
		atn.cachePrecedenceAlt(0, 1, 2, -1);
		atn.cachePrecedenceAlt(0, 1, 2, Short.MAX_VALUE + 1);
		atn.cachePrecedenceAlt(-1, 1, 2, 4);
		atn.cachePrecedenceAlt(0, -1, 2, 4);
		atn.cachePrecedenceAlt(0, ATN.MAX_PRECEDENCE_LL1 + 1, 2, 4);
		atn.cachePrecedenceAlt(0, 1, -1, 4);
		atn.cachePrecedenceAlt(0, 1, 4, 4); // token == stride
		assertEquals(0, nonzero(atn));

		atn.cachePrecedenceAlt(0, ATN.MAX_PRECEDENCE_LL1, 3, 6);
		assertEquals(6, atn.cachedPrecedenceAlt(0, ATN.MAX_PRECEDENCE_LL1, 3));
		atn.cachePrecedenceAlt(0, ATN.MAX_PRECEDENCE_LL1, 3, 9);
		assertEquals(6, atn.cachedPrecedenceAlt(0, ATN.MAX_PRECEDENCE_LL1, 3));

		atn.ll1Stride = 0;
		atn.cachePrecedenceAlt(0, 0, 0, 2);
		atn.ll1Stride = 4097;
		atn.cachePrecedenceAlt(1, 0, 0, 2);
		assertEquals(0, atn.cachedPrecedenceAlt(1, 0, 0));
		assertEquals(6, atn.cachedPrecedenceAlt(0, ATN.MAX_PRECEDENCE_LL1, 3));
	}

	@Test
	public void clearDfaDropsPrecedenceAnswers() {
		ATN atn = fresh();
		atn.cachePrecedenceAlt(2, 0, 1, 5);
		assertEquals(5, atn.cachedPrecedenceAlt(2, 0, 1));
		atn.clearDFA();
		assertEquals(0, nonzero(atn));
		assertEquals(0, atn.cachedPrecedenceAlt(2, 0, 1));
	}

	@Test
	public void strideChangeAllocatesAFreshTable() {
		ATN atn = fresh();
		atn.cachePrecedenceAlt(0, 1, 2, 3);
		atn.ll1Stride = 5;
		atn.cachePrecedenceAlt(0, 1, 2, 4);
		assertEquals(4, atn.cachedPrecedenceAlt(0, 1, 2));
		assertEquals(0, atn.cachedPrecedenceAlt(0, 1, 4));
	}

	@Test
	public void concurrentStoresKeepASingleAlt() throws Exception {
		final ATN atn = fresh();
		final AtomicInteger failures = new AtomicInteger();
		Thread[] threads = new Thread[4];
		for (int i = 0; i < threads.length; i++) {
			threads[i] = new Thread(new Runnable() {
				@Override
				public void run() {
					for (int n = 0; n < 1000; n++) {
						atn.cachePrecedenceAlt(0, 3, 1, 7);
						if (atn.cachedPrecedenceAlt(0, 3, 1) != 7) {
							failures.incrementAndGet();
						}
					}
				}
			});
		}
		for (int i = 0; i < threads.length; i++) {
			threads[i].start();
		}
		for (int i = 0; i < threads.length; i++) {
			threads[i].join();
		}
		assertEquals(0, failures.get());
		assertEquals(7, atn.cachedPrecedenceAlt(0, 3, 1));
	}

	@Test
	public void precedenceDecisionAgreesWithCacheDisabled() {
		int[][] inputs = new int[][] { {2}, {2, 1}, {2, 1, 1} };
		for (int i = 0; i < inputs.length; i++) {
			int[] toks = inputs[i];
			ATN cached = TestDeepCoveragePush3.buildPrecedencePrimaryThenStarA();
			ATN plain = TestDeepCoveragePush3.buildPrecedencePrimaryThenStarA();
			String with = tree(cached, true, toks);
			String without = tree(plain, false, toks);
			assertEquals(without, with);
			assertEquals(with, tree(cached, true, toks));
			cached.clearDFA();
			assertEquals(0, nonzero(cached));
			assertEquals(with, tree(cached, true, toks));
		}
	}

	@Test
	public void fullContextFlagsDoNotReuseSllPrecedenceEntry() {
		int[] toks = new int[] { 2, 1 };
		ATN warmed = TestDeepCoveragePush3.buildPrecedencePrimaryThenStarA();
		assertEquals(tree(TestDeepCoveragePush3.buildPrecedencePrimaryThenStarA(), false, toks),
			tree(warmed, true, toks));
		assertTrue(nonzero(warmed) > 0);

		// A warmed SLL entry must not answer a later full-context call.
		assertEquals(
			tree(TestDeepCoveragePush3.buildPrecedencePrimaryThenStarA(), false, true, true, toks),
			tree(warmed, true, true, true, toks));
		assertEquals(
			tree(TestDeepCoveragePush3.buildPrecedencePrimaryThenStarA(), false, false, false, toks),
			tree(warmed, true, false, false, toks));
	}

	@Test
	public void positiveLookaheadFillsPrecedenceCache() {
		ATN atn = TestDeepCoveragePush3.buildPrecedencePrimaryThenStarA();
		String with = tree(atn, true, 2, 1);
		ATN plain = TestDeepCoveragePush3.buildPrecedencePrimaryThenStarA();
		assertEquals(tree(plain, false, 2, 1), with);
		assertTrue(nonzero(atn) > 0);
		assertEquals(with, tree(atn, true, 2, 1));
	}

	private static String tree(ATN atn, boolean optimizeLl1, int... toks) {
		return tree(atn, optimizeLl1, PredictionMode.SLL, false, true, toks);
	}

	private static String tree(ATN atn, boolean optimizeLl1, boolean forceGlobal,
							   boolean alwaysLocal, int... toks) {
		return tree(atn, optimizeLl1, PredictionMode.LL, forceGlobal, alwaysLocal, toks);
	}

	private static String tree(ATN atn, boolean optimizeLl1, PredictionMode mode,
							   boolean forceGlobal, boolean alwaysLocal, int... toks) {
		Vocabulary vocab = new VocabularyImpl(
			new String[] { null, "'A'", "'B'", "'C'" },
			new String[] { null, "A", "B", "C" });
		ParserInterpreter parser = ATNTestHelpers.createParser(
			atn, vocab, Collections.singletonList("e"), toks);
		parser.removeErrorListeners();
		ParserATNSimulator sim = parser.getInterpreter();
		sim.optimize_ll1 = optimizeLl1;
		sim.force_global_context = forceGlobal;
		sim.always_try_local_context = alwaysLocal;
		sim.setPredictionMode(mode);
		ParserRuleContext ctx = parser.parse(0);
		assertEquals(0, parser.getNumberOfSyntaxErrors());
		return ctx.toStringTree(parser);
	}
}
