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
		// fan-out; the primitive merge-map expected size is floored so rehash cost
		// stays reasonable.
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
	public void primitiveMergeMapHandlesZeroStateAndAltKey() {
		// HPPC treats primitive key 0 specially; ensure (state=0, alt=0) still
		// merges and looks up correctly through getKey packing.
		ATNConfigSet set = new ATNConfigSet(4);
		PredictionContextCache cache = new PredictionContextCache();

		BasicState state = new BasicState();
		state.stateNumber = 0;

		PredictionContext ctx1 = cache.getChild(PredictionContext.EMPTY_LOCAL, 1);
		PredictionContext ctx2 = cache.getChild(PredictionContext.EMPTY_LOCAL, 2);

		ATNConfig c1 = ATNConfig.create(state, 0, ctx1);
		ATNConfig c2 = ATNConfig.create(state, 0, ctx2);

		assertTrue(set.add(c1, cache));
		assertTrue(set.add(c2, cache));
		assertEquals(1, set.size());
		assertTrue(set.contains(c1));
		assertTrue(set.contains(c2));
	}

	@Test
	public void removeUpdatesPrimitiveMergeMap() {
		ATNConfigSet set = new ATNConfigSet(4);
		BasicState s0 = new BasicState();
		s0.stateNumber = 0;
		BasicState s1 = new BasicState();
		s1.stateNumber = 1;

		ATNConfig c0 = ATNConfig.create(s0, 1, PredictionContext.EMPTY_LOCAL);
		ATNConfig c1 = ATNConfig.create(s1, 2, PredictionContext.EMPTY_LOCAL);
		set.add(c0);
		set.add(c1);
		assertEquals(2, set.size());

		set.remove(0);
		assertEquals(1, set.size());
		assertFalse(set.contains(c0));
		assertTrue(set.contains(c1));

		// Re-adding after remove must succeed (merge index entry was cleared).
		assertTrue(set.add(c0));
		assertEquals(2, set.size());
	}

	@Test
	public void removeUnmergedOverflowEntryKeepsMergedSibling() {
		// Two configs share (state, alt) but differ in semantic context: first
		// occupies the merge map, second lives in unmerged. Removing the
		// unmerged entry must not drop the merged map entry (indexRemove path).
		ATNConfigSet set = new ATNConfigSet(4);
		BasicState state = new BasicState();
		state.stateNumber = 0;

		SemanticContext.Predicate pred0 = new SemanticContext.Predicate(0, 0, false);
		SemanticContext.Predicate pred1 = new SemanticContext.Predicate(0, 1, false);
		ATNConfig merged = ATNConfig.create(state, 0, PredictionContext.EMPTY_LOCAL, pred0);
		ATNConfig overflow = ATNConfig.create(state, 0, PredictionContext.EMPTY_LOCAL, pred1);

		assertTrue(set.add(merged));
		assertTrue(set.add(overflow));
		assertEquals(2, set.size());

		// overflow is at index 1
		set.remove(1);
		assertEquals(1, set.size());
		assertTrue(set.contains(merged));
		assertFalse(set.contains(overflow));

		// Re-add overflow after remove.
		assertTrue(set.add(overflow));
		assertEquals(2, set.size());
	}

	@Test
	public void distinctStateAltPairsDoNotCollideInMergeMap() {
		ATNConfigSet set = new ATNConfigSet(8);
		for (int stateNumber = 0; stateNumber < 8; stateNumber++) {
			for (int alt = 1; alt <= 4; alt++) {
				BasicState state = new BasicState();
				state.stateNumber = stateNumber;
				assertTrue(set.add(ATNConfig.create(state, alt, PredictionContext.EMPTY_LOCAL)));
			}
		}
		assertEquals(32, set.size());
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

	/**
	 * Retained scratch reuses config sets across predictions. clear() must
	 * drop outermostConfigSet so a later edge can add outer-context configs
	 * without tripping add/setOutermost asserts (CI regression).
	 */
	@Test
	public void clearResetsOutermostConfigSetForScratchReuse() {
		ATNConfigSet set = new ATNConfigSet(4);
		BasicState state = new BasicState();
		state.stateNumber = 0;
		ATNConfig local = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		set.add(local);
		set.setOutermostConfigSet(true);
		assertTrue(set.isOutermostConfigSet());

		set.clear();
		assertFalse(set.isOutermostConfigSet());

		// Config that reaches into outer context must be addable after clear.
		ATNConfig outer = ATNConfig.create(state, 2, PredictionContext.EMPTY_FULL);
		outer.setOuterContextDepth(1);
		assertTrue(set.add(outer));
		assertTrue(set.getDipsIntoOuterContext());
	}

	@Test
	public void retainedConfigSetObtainClearsOutermostBetweenEdges() {
		RetainedConfigSet retained = new RetainedConfigSet(false);
		ATNConfigSet first = retained.obtain(4);
		BasicState state = new BasicState();
		state.stateNumber = 1;
		first.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL));
		first.setOutermostConfigSet(true);
		retained.release();

		ATNConfigSet second = retained.obtain(4);
		assertSame(first, second);
		assertFalse(second.isOutermostConfigSet());
		ATNConfig outer = ATNConfig.create(state, 1, PredictionContext.EMPTY_FULL);
		outer.setOuterContextDepth(2);
		assertTrue(second.add(outer));
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
	public void unmergedPathForSameStateAltDifferentSemanticContext() {
		// Same (state, alt) key with different semantic contexts must not merge
		// into one entry; the overflow (unmerged) list holds the second config.
		// Exercises indexOf hit + canMerge miss + unmerged insert.
		ATNConfigSet set = new ATNConfigSet(4);
		BasicState state = new BasicState();
		state.stateNumber = 8;

		SemanticContext.Predicate pred0 = new SemanticContext.Predicate(0, 0, false);
		SemanticContext.Predicate pred1 = new SemanticContext.Predicate(0, 1, false);

		ATNConfig c0 = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL, pred0);
		ATNConfig c1 = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL, pred1);

		assertTrue(set.add(c0));
		assertTrue(set.add(c1));
		assertEquals(2, set.size());
		assertTrue(set.contains(c0));
		assertTrue(set.contains(c1));
	}

	@Test
	public void containsOnReadonlySetScansConfigs() {
		ATNConfigSet set = new ATNConfigSet(4);
		BasicState state = new BasicState();
		state.stateNumber = 2;
		ATNConfig config = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		set.add(config);

		ATNConfigSet readonly = set.clone(true);
		assertTrue(readonly.isReadOnly());
		assertTrue(readonly.contains(config));
		assertTrue(readonly.contains(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL)));

		BasicState other = new BasicState();
		other.stateNumber = 99;
		assertFalse(readonly.contains(ATNConfig.create(other, 1, PredictionContext.EMPTY_LOCAL)));
		assertFalse(readonly.contains("not-a-config"));
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

	@Test
	public void writableAndReadonlyHashCodesAreStableUntilMutation() {
		ATNConfigSet set = new ATNConfigSet(4);
		BasicState state = new BasicState();
		state.stateNumber = 3;
		set.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL));
		int h1 = set.hashCode();
		int h2 = set.hashCode();
		assertEquals(h1, h2);

		ATNConfigSet readonly = set.clone(true);
		assertTrue(readonly.isReadOnly());
		assertEquals(set.hashCode(), readonly.hashCode());
		assertEquals(readonly.hashCode(), readonly.hashCode());

		BasicState extra = new BasicState();
		extra.stateNumber = 4;
		set.add(ATNConfig.create(extra, 2, PredictionContext.EMPTY_LOCAL));
		assertTrue(set.hashCode() != h1);
	}

	@Test
	public void readonlyCloneTrimsConfigListCapacity() {
		ATNConfigSet set = new ATNConfigSet(64);
		BasicState state = new BasicState();
		state.stateNumber = 1;
		set.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL));
		ATNConfigSet readonly = set.clone(true);
		assertEquals(1, readonly.size());
		assertEquals(set.hashCode(), readonly.hashCode());
		assertTrue(readonly.contains(set.get(0)));
	}

	@Test
	public void noneSemanticContextIsIdentityFastOnAdd() {
		ATNConfigSet set = new ATNConfigSet(4);
		BasicState state = new BasicState();
		state.stateNumber = 8;
		ATNConfig c = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		assertSame(SemanticContext.NONE, c.getSemanticContext());
		assertTrue(set.add(c));
		assertFalse(set.hasSemanticContext());
	}
}
