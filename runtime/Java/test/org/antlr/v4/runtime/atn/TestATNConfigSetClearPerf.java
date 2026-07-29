/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import java.util.BitSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Functional coverage for {@link ATNConfigSet#clear()} performance policies:
 * empty fast-path, sparse key removal when occupancy is low vs table capacity,
 * dense bulk clear, flag reset, and retained obtain/release double-clear.
 * Style matches other runtime ATN unit tests.
 */
public class TestATNConfigSetClearPerf {

	private static BasicState state(int number) {
		BasicState s = new BasicState();
		s.stateNumber = number;
		return s;
	}

	private static ATNConfig config(int stateNumber, int alt) {
		return ATNConfig.create(state(stateNumber), alt, PredictionContext.EMPTY_LOCAL);
	}

	@Test
	public void emptyClearIsIdempotentAndResetsFlags() {
		ATNConfigSet set = new ATNConfigSet(8);
		set.setOutermostConfigSet(true);
		assertTrue(set.isOutermostConfigSet());
		assertTrue(set.isEmpty());

		set.clear();
		set.clear();
		assertTrue(set.isEmpty());
		assertFalse(set.isOutermostConfigSet());
		assertEquals(ATN.INVALID_ALT_NUMBER, set.getUniqueAlt());
		assertFalse(set.hasSemanticContext());
		assertFalse(set.getDipsIntoOuterContext());
	}

	@Test
	public void clearWithFewElementsAllowsReuse() {
		ATNConfigSet set = new ATNConfigSet(8);
		assertTrue(set.add(config(1, 1)));
		assertTrue(set.add(config(2, 1)));
		assertEquals(2, set.size());

		set.clear();
		assertTrue(set.isEmpty());
		assertFalse(set.contains(config(1, 1)));

		assertTrue(set.add(config(3, 2)));
		assertEquals(1, set.size());
		assertEquals(2, set.getUniqueAlt());
		assertTrue(set.contains(config(3, 2)));
	}

	/**
	 * Grow the merge map, clear (bulk path when dense), then hold only a few
	 * configs so the next clear takes the sparse O(n) path
	 * ({@code size * }{@link ATNConfigSet#SPARSE_CLEAR_CAPACITY_FACTOR}
	 * {@code < tableLength}).
	 */
	@Test
	public void sparseClearPathAfterCapacityGrowth() {
		// Mirror the capacity growth ATNConfigSet experiences, then prove the
		// sparse-clear predicate holds and that clear leaves a correct empty set.
		ClearableLongObjectHashMap<ATNConfig> probe = new ClearableLongObjectHashMap<ATNConfig>(4);
		final int fill = 48;
		for (int i = 0; i < fill; i++) {
			probe.put(((long)(i + 1) << 12) | 1L, config(i + 1, 1));
		}
		probe.clear();
		probe.put((100L << 12) | 1L, config(100, 1));
		probe.put((101L << 12) | 2L, config(101, 2));
		assertEquals(2, probe.size());
		assertTrue("sparse path requires size*factor < tableLength",
			2 * ATNConfigSet.SPARSE_CLEAR_CAPACITY_FACTOR < probe.tableLength());

		ATNConfigSet set = new ATNConfigSet(4);
		for (int i = 0; i < fill; i++) {
			assertTrue(set.add(config(i + 1, 1)));
		}
		assertEquals(fill, set.size());

		set.clear();
		assertTrue(set.isEmpty());

		// Few survivors in a still-large table → sparse clear on next clear().
		assertTrue(set.add(config(100, 1)));
		assertTrue(set.add(config(101, 2)));
		assertEquals(2, set.size());

		set.clear();
		assertTrue(set.isEmpty());
		assertFalse(set.contains(config(100, 1)));
		assertFalse(set.contains(config(101, 2)));

		// Reuse after sparse clear.
		assertTrue(set.add(config(200, 1)));
		assertEquals(1, set.size());
		assertTrue(set.contains(config(200, 1)));
	}

	/**
	 * Same (state, alt) with different semantic contexts forces unmerged
	 * overflow; sparse clear after growth must drop both the map entry and
	 * unmerged siblings so reuse cannot see ghosts.
	 */
	@Test
	public void sparseClearWithUnmergedSemanticContextSiblings() {
		ATNConfigSet set = new ATNConfigSet(4);
		final int fill = 48;
		for (int i = 0; i < fill; i++) {
			assertTrue(set.add(config(i + 1, 1)));
		}
		set.clear();

		BasicState shared = state(900);
		// NONE vs a precedence predicate → same getKey, cannot merge.
		ATNConfig withNone = ATNConfig.create(shared, 1, PredictionContext.EMPTY_LOCAL);
		ATNConfig withPred = ATNConfig.create(shared, 1, PredictionContext.EMPTY_LOCAL,
			new SemanticContext.PrecedencePredicate(1));
		assertTrue(set.add(withNone));
		assertTrue(set.add(withPred));
		assertEquals(2, set.size());
		assertTrue(set.contains(withNone));
		assertTrue(set.contains(withPred));

		set.clear();
		assertTrue(set.isEmpty());
		assertFalse(set.contains(withNone));
		assertFalse(set.contains(withPred));

		// Re-add only one sibling; the other must not reappear via stale merge index.
		assertTrue(set.add(withNone));
		assertEquals(1, set.size());
		assertTrue(set.contains(withNone));
		assertFalse(set.contains(withPred));
	}

	/**
	 * Packed key {@code 0} (state 0, alt 0) hits HPPC's empty-key slot; sparse
	 * remove must clear that slot via {@code remove(0)}.
	 */
	@Test
	public void sparseClearRemovesPackedKeyZero() {
		ClearableLongObjectHashMap<ATNConfig> probe = new ClearableLongObjectHashMap<ATNConfig>(4);
		for (int i = 0; i < 48; i++) {
			probe.put(((long)(i + 1) << 12) | 1L, config(i + 1, 1));
		}
		probe.clear();
		// Key 0 after growth: sparse predicate for a single entry holds.
		probe.put(0L, config(0, 0));
		assertEquals(1, probe.size());
		assertTrue(1 * ATNConfigSet.SPARSE_CLEAR_CAPACITY_FACTOR < probe.tableLength());
		probe.remove(0L);
		assertTrue(probe.isEmpty());

		ATNConfigSet set = new ATNConfigSet(4);
		for (int i = 0; i < 48; i++) {
			assertTrue(set.add(config(i + 1, 1)));
		}
		set.clear();
		ATNConfig zeroKey = config(0, 0);
		assertTrue(set.add(zeroKey));
		assertEquals(1, set.size());
		assertTrue(set.contains(zeroKey));

		set.clear();
		assertTrue(set.isEmpty());
		assertFalse(set.contains(zeroKey));
		assertTrue(set.add(config(1, 1)));
		assertFalse(set.contains(zeroKey));
	}

	@Test
	public void denseClearPathWhenOccupancyHigh() {
		// expectedSize large enough that size * factor is not < tableLength
		// for a full set (table is sized ~ for expected elements).
		final int n = 16;
		ATNConfigSet set = new ATNConfigSet(n);
		for (int i = 0; i < n; i++) {
			assertTrue(set.add(config(i + 1, 1)));
		}
		assertEquals(n, set.size());
		// n * 4 is typically >= tableLength for a map sized for n elements.
		set.clear();
		assertTrue(set.isEmpty());
		for (int i = 0; i < n; i++) {
			assertFalse(set.contains(config(i + 1, 1)));
		}
		assertTrue(set.add(config(1, 1)));
	}

	@Test
	public void clearResetsConflictAndSemanticFlags() {
		ATNConfigSet set = new ATNConfigSet(4);
		ATNConfig c = config(1, 1);
		// Force semantic context via a precedence-style marker on a fresh config
		// by using SemanticContext.NONE path first, then markExplicit.
		assertTrue(set.add(c));
		set.markExplicitSemanticContext();
		assertTrue(set.hasSemanticContext());
		BitSet alts = new BitSet();
		alts.set(1);
		alts.set(2);
		set.setConflictInfo(new ConflictInfo(alts, true));
		assertTrue(set.isExactConflict());

		set.clear();
		assertFalse(set.hasSemanticContext());
		assertNull(set.getConflictInfo());
		assertFalse(set.isExactConflict());
	}

	@Test
	public void orderedSetSparseClearAndReuse() {
		OrderedATNConfigSet set = new OrderedATNConfigSet(4);
		for (int i = 0; i < 40; i++) {
			assertTrue(set.add(config(i + 1, 1)));
		}
		set.clear();
		assertTrue(set.add(config(1, 1)));
		assertTrue(set.add(config(2, 1)));
		set.clear();
		assertTrue(set.isEmpty());
		assertTrue(set.add(config(3, 1)));
		assertEquals(1, set.size());
	}

	@Test
	public void retainedObtainReleaseDoubleClearLeavesEmptyReusableBuffer() {
		RetainedConfigSet retained = new RetainedConfigSet(true);
		ATNConfigSet first = retained.obtain(4);
		for (int i = 0; i < 32; i++) {
			assertTrue(first.add(config(i + 1, 1)));
		}
		retained.release();
		assertTrue(first.isEmpty());

		// obtain after release: empty clear (must be free / correct).
		ATNConfigSet second = retained.obtain(4);
		assertSame(first, second);
		assertTrue(second.isEmpty());
		// release of already-empty buffer (second clear of empty).
		retained.release();
		assertTrue(second.isEmpty());

		ATNConfigSet third = retained.obtain(8);
		assertSame(first, third);
		assertTrue(third.add(config(99, 1)));
		assertEquals(1, third.size());
		retained.release();
		assertTrue(third.isEmpty());
	}

	@Test
	public void retainedLexerComputeTargetStatePoolStaysEmptyAfterMatch() {
		// Integration: LexerATNSimulator.computeTargetState obtain/release pair.
		ATN atn = buildTinyLexerAtn();
		LexerATNSimulator sim = new LexerATNSimulator(atn);
		assertEquals(1, sim.match(org.antlr.v4.runtime.CharStreams.fromString("a"),
			org.antlr.v4.runtime.Lexer.DEFAULT_MODE));
		ATNConfigSet reach = sim.retainedReachBuffer();
		assertTrue(reach != null && reach.isEmpty());
		// Second match reuses the same buffer; still empty after release.
		assertEquals(2, sim.match(org.antlr.v4.runtime.CharStreams.fromString("b"),
			org.antlr.v4.runtime.Lexer.DEFAULT_MODE));
		assertSame(reach, sim.retainedReachBuffer());
		assertTrue(sim.retainedReachBuffer().isEmpty());
	}

	/** Same tiny lexer ATN as {@link TestLexerATNSimulatorHotPaths}. */
	private static ATN buildTinyLexerAtn() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);

		TokensStartState tokensStart = new TokensStartState();
		tokensStart.ruleIndex = -1;
		atn.addState(tokensStart);

		RuleStartState aStart = new RuleStartState();
		aStart.ruleIndex = 0;
		atn.addState(aStart);
		BasicState aMid = new BasicState();
		aMid.ruleIndex = 0;
		atn.addState(aMid);
		RuleStopState aStop = new RuleStopState();
		aStop.ruleIndex = 0;
		atn.addState(aStop);
		aStart.stopState = aStop;
		aStart.addTransition(new AtomTransition(aMid, 'a'));
		aMid.addTransition(new EpsilonTransition(aStop));

		RuleStartState bStart = new RuleStartState();
		bStart.ruleIndex = 1;
		atn.addState(bStart);
		BasicState bMid = new BasicState();
		bMid.ruleIndex = 1;
		atn.addState(bMid);
		RuleStopState bStop = new RuleStopState();
		bStop.ruleIndex = 1;
		atn.addState(bStop);
		bStart.stopState = bStop;
		bStart.addTransition(new AtomTransition(bMid, 'b'));
		bMid.addTransition(new EpsilonTransition(bStop));

		tokensStart.addTransition(new EpsilonTransition(aStart));
		tokensStart.addTransition(new EpsilonTransition(bStart));

		atn.ruleToStartState = new RuleStartState[] { aStart, bStart };
		atn.ruleToStopState = new RuleStopState[] { aStop, bStop };
		atn.ruleToTokenType = new int[] { 1, 2 };
		atn.defineMode("DEFAULT_MODE", tokensStart);
		return atn;
	}
}
