/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
}
