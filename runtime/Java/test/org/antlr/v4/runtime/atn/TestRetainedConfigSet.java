/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link RetainedConfigSet} obtain/release ownership contract.
 */
public class TestRetainedConfigSet {

	@Test
	public void obtainAlwaysReturnsEmptyAndReusesInstance() {
		RetainedConfigSet retained = new RetainedConfigSet(false);
		assertNull(retained.buffer());

		ATNConfigSet first = retained.obtain(4);
		assertNotNull(first);
		assertTrue(first.isEmpty());
		assertSame(first, retained.buffer());

		BasicState state = new BasicState();
		state.stateNumber = 1;
		first.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL));
		assertEquals(1, first.size());

		ATNConfigSet second = retained.obtain(8);
		assertSame(first, second);
		assertTrue(second.isEmpty());
	}

	@Test
	public void releaseClearsWithoutDroppingIdentity() {
		RetainedConfigSet retained = new RetainedConfigSet(true);
		ATNConfigSet set = retained.obtain(4);
		assertTrue(set instanceof OrderedATNConfigSet);

		BasicState state = new BasicState();
		state.stateNumber = 0;
		set.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_FULL));
		assertFalse(set.isEmpty());

		retained.release();
		assertTrue(set.isEmpty());
		assertSame(set, retained.buffer());
	}

	@Test
	public void releaseBeforeObtainIsNoOp() {
		RetainedConfigSet retained = new RetainedConfigSet(false);
		retained.release();
		assertNull(retained.buffer());
	}

	@Test
	public void obtainAfterReleaseDoesNotLeaveStaleConfigs() {
		// Models LexerATNSimulator.computeTargetState: release empties; next
		// obtain must return empty without paying a non-empty clear.
		RetainedConfigSet retained = new RetainedConfigSet(true);
		ATNConfigSet set = retained.obtain(16);
		for (int i = 0; i < 20; i++) {
			BasicState state = new BasicState();
			state.stateNumber = i;
			set.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_FULL));
		}
		assertEquals(20, set.size());
		retained.release();
		assertTrue(set.isEmpty());

		ATNConfigSet again = retained.obtain(16);
		assertSame(set, again);
		assertTrue(again.isEmpty());
		// Empty obtain clear + empty release clear (double empty clear).
		retained.release();
		assertTrue(again.isEmpty());
	}
}
