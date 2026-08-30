/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestATNStateFreeze {

	@Test
	public void freezeOptimizedTransitionsUsesArrayHotPath() {
		BasicState state = new BasicState();
		BasicState t1 = new BasicState();
		BasicState t2 = new BasicState();
		t1.stateNumber = 1;
		t2.stateNumber = 2;
		state.addTransition(new EpsilonTransition(t1));
		state.addTransition(new EpsilonTransition(t2));
		assertEquals(2, state.getNumberOfOptimizedTransitions());
		assertSame(t1, state.getOptimizedTransition(0).target);
		assertSame(t2, state.getOptimizedTransition(1).target);

		state.freezeOptimizedTransitions();
		assertEquals(2, state.getNumberOfOptimizedTransitions());
		assertSame(t1, state.getOptimizedTransition(0).target);
		assertSame(t2, state.getOptimizedTransition(1).target);

		// Mutation invalidates freeze; still consistent via list path
		BasicState t3 = new BasicState();
		t3.stateNumber = 3;
		state.addOptimizedTransition(new EpsilonTransition(t3));
		assertEquals(1, state.getNumberOfOptimizedTransitions()); // only optimized list has t3
		// After addOptimizedTransition, isOptimized is true and list has one element
		assertTrue(state.isOptimized());
		assertSame(t3, state.getOptimizedTransition(0).target);

		state.freezeOptimizedTransitions();
		assertEquals(1, state.getNumberOfOptimizedTransitions());
		assertSame(t3, state.getOptimizedTransition(0).target);
	}

	@Test
	public void freezeEmptyTransitionsUsesSharedEmptyArray() {
		BasicState state = new BasicState();
		state.freezeOptimizedTransitions();
		assertEquals(0, state.getNumberOfOptimizedTransitions());
	}

	/**
	 * Hand-built ATNs without freeze still answer optimized transitions via the
	 * list path (construction / tests). Production freezes at deserialize.
	 */
	@Test
	public void unfrozenStateUsesListPathUntilExplicitFreeze() {
		BasicState state = new BasicState();
		BasicState t1 = new BasicState();
		t1.stateNumber = 1;
		state.addTransition(new AtomTransition(t1, 42));
		assertSame(t1, state.getOptimizedTransition(0).target);
		assertEquals(1, state.getNumberOfOptimizedTransitions());
		state.freezeOptimizedTransitions();
		assertSame(t1, state.getOptimizedTransition(0).target);
		assertSame(state.frozenOptimizedTransitions()[0], state.getOptimizedTransition(0));
	}

	@Test
	public void freezeStatesSnapshotServesGetCachedState() {
		ATN atn = new ATN(ATNType.PARSER, 4);
		BasicState s0 = new BasicState();
		BasicState s1 = new BasicState();
		atn.addState(s0);
		atn.addState(s1);
		assertNull(atn.statesSnapshot);
		assertSame(s0, atn.getCachedState(0));
		atn.freezeStatesSnapshot();
		assertEquals(2, atn.statesSnapshot.length);
		assertSame(s0, atn.getCachedState(0));
		assertSame(s1, atn.getCachedState(1));
		try {
			atn.getCachedState(-1);
		}
		catch (IndexOutOfBoundsException expected) {
			// snap is live but the index is not in-range; falls back to List#get
		}
		try {
			atn.getCachedState(99);
		}
		catch (IndexOutOfBoundsException expected) {
			// same fallback for index past the snapshot
		}
		BasicState beforeFreeze = new BasicState();
		ATN scratch = new ATN(ATNType.PARSER, 2);
		scratch.addState(beforeFreeze);
		scratch.removeState(beforeFreeze);
		assertNull(scratch.states.get(beforeFreeze.stateNumber));

		atn.removeState(s1);
		assertNull(atn.statesSnapshot[1]);
		ATNState[] shortSnap = new ATNState[0];
		atn.statesSnapshot = shortSnap;
		atn.removeState(s0);
		assertSame(shortSnap, atn.statesSnapshot);
		BasicState s2 = new BasicState();
		atn.addState(s2);
		assertNull(atn.statesSnapshot);
		assertSame(s2, atn.getCachedState(2));
	}
}
