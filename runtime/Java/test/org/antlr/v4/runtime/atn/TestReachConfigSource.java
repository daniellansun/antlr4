/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ReachConfigSource}: zero-copy view until full-context
 * rewrite materializes a mutable list, with capacity-preserving reuse.
 */
public class TestReachConfigSource {

	@Test
	public void viewsSourceSetWithoutMaterializing() {
		ATNConfigSet set = new ATNConfigSet(4);
		BasicState s0 = new BasicState();
		s0.stateNumber = 0;
		BasicState s1 = new BasicState();
		s1.stateNumber = 1;
		set.add(ATNConfig.create(s0, 1, PredictionContext.EMPTY_LOCAL));
		set.add(ATNConfig.create(s1, 2, PredictionContext.EMPTY_LOCAL));

		ReachConfigSource source = new ReachConfigSource();
		source.reset(set);
		assertFalse(source.isMaterialized());
		assertEquals(2, source.size());
		assertEquals(0, source.get(0).getState().stateNumber);
		assertEquals(1, source.get(1).getState().stateNumber);
		assertFalse(source.isMaterialized());
		assertNull(source.rewriteScratch());
	}

	@Test
	public void appendContextMaterializesAndRewrites() {
		ATNConfigSet set = new ATNConfigSet(4);
		PredictionContextCache cache = new PredictionContextCache();
		BasicState state = new BasicState();
		state.stateNumber = 5;
		set.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL));
		set.add(ATNConfig.create(state, 2, PredictionContext.EMPTY_LOCAL));

		ReachConfigSource source = new ReachConfigSource();
		source.reset(set);
		source.appendContext(42, cache);
		assertTrue(source.isMaterialized());
		assertEquals(2, source.size());

		for (int i = 0; i < source.size(); i++) {
			PredictionContext ctx = source.get(i).getContext();
			assertEquals(1, ctx.size());
			assertEquals(42, ctx.getReturnState(0));
		}

		// Second rewrite keeps the same list size (does not drop entries).
		int sizeBefore = source.size();
		source.appendContext(99, cache);
		assertTrue(source.isMaterialized());
		assertEquals(sizeBefore, source.size());
		// Context is non-empty after successive appends (exact stack shape is
		// defined by PredictionContext.appendContext).
		assertTrue(source.get(0).getContext().size() >= 1);
	}

	@Test
	public void releaseClearsRewriteScratchAndAllowsReuse() {
		ATNConfigSet set = new ATNConfigSet(4);
		PredictionContextCache cache = new PredictionContextCache();
		BasicState state = new BasicState();
		state.stateNumber = 1;
		set.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL));

		ReachConfigSource source = new ReachConfigSource();
		source.reset(set);
		source.appendContext(7, cache);
		assertTrue(source.isMaterialized());
		ArrayList<ATNConfig> scratch = source.rewriteScratch();
		assertNotNull(scratch);
		assertEquals(1, scratch.size());

		source.release();
		assertFalse(source.isMaterialized());
		assertTrue(scratch.isEmpty());
		assertSame(scratch, source.rewriteScratch());

		// Next edge reuses the same ArrayList instance.
		ATNConfigSet set2 = new ATNConfigSet(4);
		BasicState s2 = new BasicState();
		s2.stateNumber = 2;
		set2.add(ATNConfig.create(s2, 2, PredictionContext.EMPTY_LOCAL));
		set2.add(ATNConfig.create(s2, 3, PredictionContext.EMPTY_LOCAL));
		source.reset(set2);
		assertFalse(source.isMaterialized());
		source.appendContext(11, cache);
		assertTrue(source.isMaterialized());
		assertSame(scratch, source.rewriteScratch());
		assertEquals(2, source.size());
	}

	@Test
	public void simulatorRetainsReachConfigSourceAcrossEdges() {
		ATN atn = new ATN(ATNType.PARSER, 1);
		ParserATNSimulator sim = new ParserATNSimulator(atn);
		ReachConfigSource first = sim.retainedReachConfigs();
		assertNotNull(first);
		assertSame(first, sim.retainedReachConfigs());
	}

	@Test(expected = IllegalStateException.class)
	public void sizeAfterReleaseWithoutResetThrows() {
		ReachConfigSource source = new ReachConfigSource();
		source.release();
		source.size();
	}

	@Test(expected = IllegalStateException.class)
	public void getAfterReleaseWithoutResetThrows() {
		ReachConfigSource source = new ReachConfigSource();
		source.release();
		source.get(0);
	}

	@Test
	public void releaseAfterMaterializeLeavesScratchEmptyAndReusable() {
		// Models finally-block release after unique-closure early return or
		// full-context rewrite: set unbound, scratch empty, instance kept.
		ATNConfigSet set = new ATNConfigSet(2);
		PredictionContextCache cache = new PredictionContextCache();
		BasicState state = new BasicState();
		state.stateNumber = 0;
		set.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL));

		ReachConfigSource source = new ReachConfigSource();
		source.reset(set);
		source.appendContext(3, cache);
		assertTrue(source.isMaterialized());
		ArrayList<ATNConfig> scratch = source.rewriteScratch();
		assertNotNull(scratch);
		assertFalse(scratch.isEmpty());

		source.release();
		assertFalse(source.isMaterialized());
		assertTrue(scratch.isEmpty());
		assertSame(scratch, source.rewriteScratch());
	}
}
