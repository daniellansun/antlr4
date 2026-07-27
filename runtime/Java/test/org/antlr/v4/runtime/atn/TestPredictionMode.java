/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestPredictionMode {

	private static BasicState state(int n) {
		BasicState s = new BasicState();
		s.stateNumber = n;
		return s;
	}

	private static RuleStopState stop(int n) {
		RuleStopState s = new RuleStopState();
		s.stateNumber = n;
		return s;
	}

	@Test
	public void testEnumValues() {
		assertEquals(3, PredictionMode.values().length);
		assertSame(PredictionMode.SLL, PredictionMode.valueOf("SLL"));
		assertSame(PredictionMode.LL, PredictionMode.valueOf("LL"));
		assertSame(PredictionMode.LL_EXACT_AMBIG_DETECTION, PredictionMode.valueOf("LL_EXACT_AMBIG_DETECTION"));
	}

	@Test
	public void testConfigStopStateHelpers() {
		ATNConfigSet set = new ATNConfigSet();
		assertFalse(PredictionMode.hasConfigInRuleStopState(set));
		assertTrue(PredictionMode.allConfigsInRuleStopStates(set)); // empty is vacuously true

		set.add(ATNConfig.create(state(1), 1, PredictionContext.EMPTY_FULL));
		assertFalse(PredictionMode.hasConfigInRuleStopState(set));
		assertFalse(PredictionMode.allConfigsInRuleStopStates(set));

		ATNConfigSet stops = new ATNConfigSet();
		stops.add(ATNConfig.create(stop(1), 1, PredictionContext.EMPTY_FULL));
		assertTrue(PredictionMode.hasConfigInRuleStopState(stops));
		assertTrue(PredictionMode.allConfigsInRuleStopStates(stops));

		stops.add(ATNConfig.create(state(2), 2, PredictionContext.EMPTY_FULL));
		assertTrue(PredictionMode.hasConfigInRuleStopState(stops));
		assertFalse(PredictionMode.allConfigsInRuleStopStates(stops));
	}

	@Test
	public void testAltSetHelpers() {
		BitSet a = new BitSet();
		a.set(1);
		BitSet b = new BitSet();
		b.set(1);
		b.set(2);
		BitSet c = new BitSet();
		c.set(2);

		List<BitSet> sets = Arrays.asList(a, b);
		assertTrue(PredictionMode.hasConflictingAltSet(sets));
		assertTrue(PredictionMode.hasNonConflictingAltSet(sets));
		assertFalse(PredictionMode.allSubsetsConflict(sets));
		assertFalse(PredictionMode.allSubsetsEqual(sets));

		List<BitSet> allConflict = Arrays.asList(b, bitset(1, 3));
		assertTrue(PredictionMode.allSubsetsConflict(allConflict));
		assertFalse(PredictionMode.hasNonConflictingAltSet(allConflict));

		List<BitSet> equal = Arrays.asList(b, (BitSet) b.clone());
		assertTrue(PredictionMode.allSubsetsEqual(equal));

		assertEquals(1, PredictionMode.getUniqueAlt(Arrays.asList(a, a)));
		assertEquals(ATN.INVALID_ALT_NUMBER, PredictionMode.getUniqueAlt(sets));

		BitSet union = PredictionMode.getAlts(sets);
		assertTrue(union.get(1));
		assertTrue(union.get(2));

		assertEquals(1, PredictionMode.getSingleViableAlt(Arrays.asList(a, bitset(1, 2))));
		assertEquals(ATN.INVALID_ALT_NUMBER, PredictionMode.getSingleViableAlt(Arrays.asList(a, c)));
		assertEquals(1, PredictionMode.resolvesToJustOneViableAlt(Arrays.asList(a, bitset(1, 2))));
	}

	@Test
	public void testGetAltsFromConfigsAndSubsets() {
		ATNConfigSet configs = new ATNConfigSet();
		BasicState s = state(1);
		configs.add(ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL));
		configs.add(ATNConfig.create(s, 2, PredictionContext.EMPTY_FULL));
		BitSet alts = PredictionMode.getAlts(configs);
		assertTrue(alts.get(1));
		assertTrue(alts.get(2));

		Collection<BitSet> subsets = PredictionMode.getConflictingAltSubsets(configs);
		assertFalse(subsets.isEmpty());
		// same state+context different alts => one conflicting subset {1,2}
		assertTrue(PredictionMode.hasConflictingAltSet(subsets));

		Map<ATNState, BitSet> map = PredictionMode.getStateToAltMap(configs);
		assertTrue(map.get(s).get(1));
		assertTrue(map.get(s).get(2));
		assertFalse(PredictionMode.hasStateAssociatedWithOneAlt(configs));

		ATNConfigSet unique = new ATNConfigSet();
		unique.add(ATNConfig.create(state(1), 1, PredictionContext.EMPTY_FULL));
		unique.add(ATNConfig.create(state(2), 1, PredictionContext.EMPTY_FULL));
		assertTrue(PredictionMode.hasStateAssociatedWithOneAlt(unique));
	}

	@Test
	public void testHasSLLConflictTerminatingPrediction() {
		// all in rule stop => terminating
		ATNConfigSet stops = new ATNConfigSet();
		stops.add(ATNConfig.create(stop(1), 1, PredictionContext.EMPTY_FULL));
		assertTrue(PredictionMode.hasSLLConflictTerminatingPrediction(PredictionMode.SLL, stops));
		assertTrue(PredictionMode.hasSLLConflictTerminatingPrediction(PredictionMode.LL, stops));

		// conflicting alts same state, no single-alt state => terminating
		ATNConfigSet conflict = new ATNConfigSet();
		BasicState s = state(5);
		conflict.add(ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL));
		conflict.add(ATNConfig.create(s, 2, PredictionContext.EMPTY_FULL));
		assertTrue(PredictionMode.hasSLLConflictTerminatingPrediction(PredictionMode.LL, conflict));

		// has state with one alt => not terminating heuristic
		conflict.add(ATNConfig.create(state(6), 3, PredictionContext.EMPTY_FULL));
		assertFalse(PredictionMode.hasSLLConflictTerminatingPrediction(PredictionMode.LL, conflict));

		// pure SLL with semantic contexts strips preds then checks
		ATNConfigSet withPred = new ATNConfigSet();
		SemanticContext.Predicate p = new SemanticContext.Predicate(0, 0, false);
		withPred.add(ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL, p));
		withPred.add(ATNConfig.create(s, 2, PredictionContext.EMPTY_FULL, p));
		assertTrue(PredictionMode.hasSLLConflictTerminatingPrediction(PredictionMode.SLL, withPred));
	}

	private static BitSet bitset(int... alts) {
		BitSet b = new BitSet();
		for (int a : alts) {
			b.set(a);
		}
		return b;
	}
}
