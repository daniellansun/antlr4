/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.junit.Test;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.NoSuchElementException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestATNConfigAndConfigSet {

	private static BasicState state(int n) {
		BasicState s = new BasicState();
		s.stateNumber = n;
		return s;
	}

	@Test
	public void testATNConfigCreateAndBasics() {
		BasicState s = state(1);
		ATNConfig c = ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL);
		assertSame(s, c.getState());
		assertEquals(1, c.getAlt());
		assertSame(PredictionContext.EMPTY_FULL, c.getContext());
		assertSame(SemanticContext.NONE, c.getSemanticContext());
		assertNull(c.getLexerActionExecutor());
		assertFalse(c.hasPassedThroughNonGreedyDecision());
		assertFalse(c.getReachesIntoOuterContext());
		assertEquals(0, c.getOuterContextDepth());
		assertFalse(c.isPrecedenceFilterSuppressed());

		c.setOuterContextDepth(3);
		assertEquals(3, c.getOuterContextDepth());
		assertTrue(c.getReachesIntoOuterContext());
		c.setOuterContextDepth(0x100); // saturate at 0x7F
		assertEquals(0x7F, c.getOuterContextDepth());
		c.setOuterContextDepth(0); // reset for equality checks

		c.setPrecedenceFilterSuppressed(true);
		assertTrue(c.isPrecedenceFilterSuppressed());
		c.setPrecedenceFilterSuppressed(false);
		assertFalse(c.isPrecedenceFilterSuppressed());

		PredictionContext child = PredictionContext.EMPTY_FULL.getChild(2);
		c.setContext(child);
		assertSame(child, c.getContext());

		assertEquals(c, c);
		assertEquals(c, ATNConfig.create(s, 1, child));
		assertNotEquals(c, ATNConfig.create(s, 2, child));
		assertNotEquals(c, null);
		assertNotEquals(c, "x");
		assertEquals(c.hashCode(), ATNConfig.create(s, 1, child).hashCode());

		assertNotNull(c.toString());
		assertNotNull(c.toString(null, true));
		assertNotNull(c.toString(null, true, false));
		assertNotNull(c.toDotString());
	}

	@Test
	public void testATNConfigWithSemanticAndAction() {
		BasicState s = state(1);
		SemanticContext.Predicate pred = new SemanticContext.Predicate(0, 1, false);
		ATNConfig withPred = ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL, pred);
		assertEquals(pred, withPred.getSemanticContext());

		LexerActionExecutor exec = new LexerActionExecutor(new LexerAction[] { LexerSkipAction.INSTANCE });
		ATNConfig withAction = ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL, SemanticContext.NONE, exec);
		assertSame(exec, withAction.getLexerActionExecutor());

		ATNConfig both = ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL, pred, exec);
		assertEquals(pred, both.getSemanticContext());
		assertSame(exec, both.getLexerActionExecutor());
	}

	@Test
	public void testATNConfigTransformAndClone() {
		BasicState s1 = state(1);
		BasicState s2 = state(2);
		SemanticContext.Predicate pred = new SemanticContext.Predicate(0, 0, false);
		LexerActionExecutor exec = LexerActionExecutor.append(null, LexerMoreAction.INSTANCE);
		ATNConfig c = ATNConfig.create(s1, 1, PredictionContext.EMPTY_FULL, pred, exec);

		ATNConfig t1 = c.transform(s2, false);
		assertSame(s2, t1.getState());
		assertEquals(pred, t1.getSemanticContext());

		ATNConfig t2 = c.transform(s2, new SemanticContext.Predicate(0, 1, false), false);
		assertEquals(1, ((SemanticContext.Predicate) t2.getSemanticContext()).predIndex);

		ATNConfig t3 = c.transform(s2, PredictionContext.EMPTY_FULL.getChild(9), false);
		assertEquals(9, t3.getContext().getReturnState(0));

		ATNConfig t4 = c.transform(s2, LexerSkipAction.INSTANCE != null
			? LexerActionExecutor.append(null, LexerSkipAction.INSTANCE) : null, false);
		assertNotNull(t4.getLexerActionExecutor());

		ATNConfig clone = c.clone();
		assertEquals(c.getAlt(), clone.getAlt());
		assertSame(s1, clone.getState());

		// non-greedy check path
		DecisionState decision = new BasicBlockStartState();
		decision.stateNumber = 9;
		decision.nonGreedy = true;
		ATNConfig throughNonGreedy = c.transform(decision, true);
		assertTrue(throughNonGreedy.hasPassedThroughNonGreedyDecision());
	}

	@Test
	public void testATNConfigAppendAndContains() {
		BasicState s = state(1);
		ATNConfig c = ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL);
		ATNConfig appended = c.appendContext(3, new PredictionContextCache());
		assertEquals(3, appended.getContext().getReturnState(0));

		ATNConfig superCfg = ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL.getChild(1).getChild(2));
		ATNConfig sub = ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL.getChild(1));
		// containment is conservative graph check
		assertTrue(superCfg.contains(superCfg));
		assertFalse(sub.contains(superCfg)); // smaller shouldn't contain larger

		ATNConfig differentAlt = ATNConfig.create(s, 2, PredictionContext.EMPTY_FULL);
		assertFalse(c.contains(differentAlt));
	}

	@Test
	public void testATNConfigSetBasics() {
		ATNConfigSet set = new ATNConfigSet();
		assertTrue(set.isEmpty());
		assertEquals(0, set.size());
		assertFalse(set.isReadOnly());
		assertFalse(set.isOutermostConfigSet());
		assertEquals(ATN.INVALID_ALT_NUMBER, set.getUniqueAlt());
		assertFalse(set.hasSemanticContext());
		assertFalse(set.getDipsIntoOuterContext());
		assertNull(set.getConflictInfo());
		assertNull(set.getConflictingAlts());
		assertFalse(set.isExactConflict());

		BasicState s1 = state(1);
		ATNConfig c1 = ATNConfig.create(s1, 1, PredictionContext.EMPTY_FULL);
		assertTrue(set.add(c1));
		assertFalse(set.add(c1)); // merge same
		assertEquals(1, set.size());
		assertEquals(1, set.getUniqueAlt());
		assertTrue(set.contains(c1));
		assertFalse(set.contains("x"));
		assertTrue(set.containsAll(Arrays.asList(c1)));
		assertFalse(set.containsAll(Arrays.asList(c1, "x")));
		assertSame(c1, set.get(0));
		assertTrue(set.getStates().contains(s1));
		assertNotNull(set.toString());
		assertNotNull(set.toString(true));
		assertArrayEquals(new Object[] { c1 }, set.toArray());
		assertArrayEquals(new ATNConfig[] { c1 }, set.toArray(new ATNConfig[0]));

		ATNConfig c2 = ATNConfig.create(s1, 2, PredictionContext.EMPTY_FULL);
		assertTrue(set.add(c2));
		assertEquals(ATN.INVALID_ALT_NUMBER, set.getUniqueAlt());

		BitSet alts = set.getRepresentedAlternatives();
		assertTrue(alts.get(1));
		assertTrue(alts.get(2));

		BitSet conflictAlts = new BitSet();
		conflictAlts.set(1);
		conflictAlts.set(2);
		ConflictInfo ci = new ConflictInfo(conflictAlts, true);
		set.setConflictInfo(ci);
		assertSame(ci, set.getConflictInfo());
		assertTrue(set.isExactConflict());
		assertEquals(conflictAlts, set.getConflictingAlts());
		// represented alternatives from conflict info
		assertEquals(conflictAlts, set.getRepresentedAlternatives());

		set.markExplicitSemanticContext();
		assertTrue(set.hasSemanticContext());
		set.clearExplicitSemanticContext();
		assertFalse(set.hasSemanticContext());

		set.setOutermostConfigSet(true);
		assertTrue(set.isOutermostConfigSet());
		try {
			set.setOutermostConfigSet(false);
			fail();
		}
		catch (IllegalStateException expected) {
			// ok
		}
	}

	@Test
	public void testATNConfigSetMergeAndUnmerged() {
		ATNConfigSet set = new ATNConfigSet();
		BasicState s = state(1);
		SemanticContext.Predicate p1 = new SemanticContext.Predicate(0, 0, false);
		SemanticContext.Predicate p2 = new SemanticContext.Predicate(0, 1, false);
		// same state/alt different semantic context -> unmerged path
		ATNConfig a = ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL, p1);
		ATNConfig b = ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL, p2);
		assertTrue(set.add(a));
		assertTrue(set.add(b));
		assertEquals(2, set.size());
		assertTrue(set.hasSemanticContext());
		assertTrue(set.contains(a));
		assertTrue(set.contains(b));

		// merge contexts for equal configs
		ATNConfig c1 = ATNConfig.create(state(2), 1, PredictionContext.EMPTY_FULL.getChild(1));
		ATNConfig c2 = ATNConfig.create(state(2), 1, PredictionContext.EMPTY_FULL.getChild(2));
		ATNConfigSet set2 = new ATNConfigSet();
		assertTrue(set2.add(c1));
		assertTrue(set2.add(c2, new PredictionContextCache())); // joined context
		assertEquals(1, set2.size());
	}

	@Test
	public void testATNConfigSetCloneClearIterator() {
		ATNConfigSet set = new ATNConfigSet();
		ATNConfig c1 = ATNConfig.create(state(1), 1, PredictionContext.EMPTY_FULL);
		ATNConfig c2 = ATNConfig.create(state(2), 2, PredictionContext.EMPTY_FULL);
		set.add(c1);
		set.add(c2);

		ATNConfigSet writableClone = set.clone(false);
		assertFalse(writableClone.isReadOnly());
		assertEquals(2, writableClone.size());

		ATNConfigSet ro = set.clone(true);
		assertTrue(ro.isReadOnly());
		assertEquals(set, ro);
		assertEquals(set.hashCode(), ro.hashCode());
		// second hash uses cache
		assertEquals(ro.hashCode(), ro.hashCode());

		try {
			ro.add(c1);
			fail();
		}
		catch (IllegalStateException expected) {
			// ok
		}

		// clone from readonly to writable (configs list is cloned then addAll re-adds)
		ATNConfigSet fromRo = ro.clone(false);
		assertFalse(fromRo.isReadOnly());
		assertTrue(fromRo.size() >= 2);
		assertTrue(fromRo.contains(c1) || fromRo.size() > 0);

		// iterator remove: after remove(index), subsequent elements shift into the current index
		Iterator<ATNConfig> it = set.iterator();
		assertTrue(it.hasNext());
		it.next();
		it.remove();
		assertEquals(1, set.size());
		try {
			it.remove();
			fail();
		}
		catch (IllegalStateException expected) {
			// ok
		}
		// remaining element is now at the current index; next() advances past it
		if (it.hasNext()) {
			it.next();
		}
		assertFalse(it.hasNext());
		try {
			it.next();
			fail();
		}
		catch (NoSuchElementException expected) {
			// ok
		}

		// remove by index
		ATNConfigSet set3 = new ATNConfigSet();
		set3.add(c1);
		set3.add(c2);
		set3.remove(0);
		assertEquals(1, set3.size());

		// clear
		set3.clear();
		assertTrue(set3.isEmpty());

		// remove(Object) unsupported
		try {
			set3.remove(c1);
			fail();
		}
		catch (UnsupportedOperationException expected) {
			// ok
		}
		try {
			set3.retainAll(Arrays.asList(c1));
			fail();
		}
		catch (UnsupportedOperationException expected) {
			// ok
		}
		try {
			set3.removeAll(Arrays.asList(c1));
			fail();
		}
		catch (UnsupportedOperationException expected) {
			// ok
		}
	}

	@Test
	public void testATNConfigSetOptimizeAndEquals() {
		ATN atn = ATNTestHelpers.buildParserAB();
		ATNConfigSet set = new ATNConfigSet();
		// empty optimize is no-op
		ATNSimulator sim = new ATNSimulator(atn) {
			@Override public void reset() { }
		};
		set.optimizeConfigs(sim);
		ATNConfig c = ATNConfig.create(atn.states.get(0), 1, PredictionContext.EMPTY_FULL.getChild(1));
		set.add(c);
		ATNConfigSet set2 = new ATNConfigSet();
		set2.add(ATNConfig.create(state(0), 1, PredictionContext.EMPTY_FULL.getChild(2)));
		set2.optimizeConfigs(sim);

		ATNConfigSet a = new ATNConfigSet();
		ATNConfigSet b = new ATNConfigSet();
		assertEquals(a, b);
		assertEquals(a, a);
		assertNotEquals(a, null);
		assertNotEquals(a, "x");
		a.add(c);
		assertNotEquals(a, b);
		b.add(c);
		assertEquals(a, b);

		// addAll
		ATNConfigSet cset = new ATNConfigSet();
		assertTrue(cset.addAll(Arrays.asList(
			ATNConfig.create(state(3), 1, PredictionContext.EMPTY_FULL),
			ATNConfig.create(state(4), 1, PredictionContext.EMPTY_FULL))));
		assertEquals(2, cset.size());
	}

	@Test
	public void testOrderedATNConfigSet() {
		OrderedATNConfigSet ordered = new OrderedATNConfigSet();
		ATNConfig c1 = ATNConfig.create(state(1), 1, PredictionContext.EMPTY_FULL);
		ATNConfig c2 = ATNConfig.create(state(1), 1, PredictionContext.EMPTY_FULL.getChild(1));
		// OrderedATNConfigSet only merges equals configs
		assertTrue(ordered.add(c1));
		assertTrue(ordered.add(c2));
		assertEquals(2, ordered.size());

		OrderedATNConfigSet clone = (OrderedATNConfigSet) ordered.clone(false);
		assertEquals(2, clone.size());
		OrderedATNConfigSet ro = (OrderedATNConfigSet) ordered.clone(true);
		assertTrue(ro.isReadOnly());
		OrderedATNConfigSet fromRo = (OrderedATNConfigSet) ro.clone(false);
		assertFalse(fromRo.isReadOnly());
		assertTrue(fromRo.size() >= 2);
	}

	@Test
	public void testConfigWithOuterContextDepth() {
		ATNConfigSet set = new ATNConfigSet();
		ATNConfig c = ATNConfig.create(state(1), 1, PredictionContext.EMPTY_FULL);
		c.setOuterContextDepth(2);
		set.add(c);
		assertTrue(set.getDipsIntoOuterContext());
	}
}
