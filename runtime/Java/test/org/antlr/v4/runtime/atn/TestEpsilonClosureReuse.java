/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import com.carrotsearch.hppc.ObjectHashSet;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for retained scratch structures inside package-private
 * {@link EpsilonClosure}: busy set and intermediate config-set buffers must be
 * allocated once and reused across {@code close} invocations on the same
 * simulator.
 */
public class TestEpsilonClosureReuse {

	@Test
	public void busySetAndBuffersAreRetainedAcrossClosures() {
		ATN atn = new ATN(ATNType.PARSER, 1);
		BasicState state = new BasicState();
		state.stateNumber = 0;
		state.ruleIndex = 0;
		atn.addState(state);

		ParserATNSimulator simulator = new ParserATNSimulator(atn);
		EpsilonClosure closure = simulator.epsilonClosure();

		ATNConfigSet source = new ATNConfigSet(4);
		source.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL));
		ATNConfigSet configs = new ATNConfigSet(4);

		// Non-predicate path allocates intermediate buffers.
		closure.close(source, configs, false, false, PredictionContextCache.UNCACHED, false);
		assertTrue(configs.size() >= 1);
		assertTrue(closure.hasIntermediateBuffers());

		ATNConfigSet bufferA = closure.retainedBufferA();
		ATNConfigSet bufferB = closure.retainedBufferB();
		ObjectHashSet<ATNConfig> busy = closure.retainedBusy();
		assertNotNull(bufferA);
		assertNotNull(bufferB);
		assertNotNull(busy);

		// Second closure must reuse the same buffer and busy-set instances.
		ATNConfigSet source2 = new ATNConfigSet(4);
		source2.add(ATNConfig.create(state, 2, PredictionContext.EMPTY_LOCAL));
		ATNConfigSet configs2 = new ATNConfigSet(4);
		closure.close(source2, configs2, false, false, PredictionContextCache.UNCACHED, false);

		assertSame(bufferA, closure.retainedBufferA());
		assertSame(bufferB, closure.retainedBufferB());
		assertSame(busy, closure.retainedBusy());
		// End-of-close finally drops config-graph refs while retaining capacity.
		assertTrue(busy.isEmpty());
		assertTrue(bufferA.isEmpty());
		assertTrue(bufferB.isEmpty());
	}

	@Test
	public void predicatePathDoesNotRequireIntermediateBuffers() {
		ATN atn = new ATN(ATNType.PARSER, 1);
		BasicState state = new BasicState();
		state.stateNumber = 0;
		state.ruleIndex = 0;
		atn.addState(state);

		ParserATNSimulator simulator = new ParserATNSimulator(atn);
		EpsilonClosure closure = simulator.epsilonClosure();

		ATNConfigSet source = new ATNConfigSet(4);
		source.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL));
		ATNConfigSet configs = new ATNConfigSet(4);

		closure.close(source, configs, true, false, PredictionContextCache.UNCACHED, false);
		assertFalse(closure.hasIntermediateBuffers());
		assertNull(closure.retainedBufferA());
		assertNull(closure.retainedBufferB());
		assertTrue(configs.size() >= 1);
		assertTrue(closure.retainedBusy().isEmpty());
	}

	@Test
	public void emptySourceIsNoOp() {
		ATN atn = new ATN(ATNType.PARSER, 1);
		ParserATNSimulator simulator = new ParserATNSimulator(atn);
		EpsilonClosure closure = simulator.epsilonClosure();

		ATNConfigSet source = new ATNConfigSet();
		ATNConfigSet configs = new ATNConfigSet();
		closure.close(source, configs, false, false, PredictionContextCache.UNCACHED, false);
		assertTrue(configs.isEmpty());
		assertFalse(closure.hasIntermediateBuffers());
	}

	@Test
	public void busySetIsObjectHashSetWithValueEquality() {
		// Guard: closure busy must use ATNConfig equals/hashCode (not identity),
		// so equal configs built independently still collapse via ObjectHashSet.
		ATN atn = new ATN(ATNType.PARSER, 1);
		BasicState state = new BasicState();
		state.stateNumber = 3;
		atn.addState(state);

		ParserATNSimulator simulator = new ParserATNSimulator(atn);
		ObjectHashSet<ATNConfig> busy = simulator.epsilonClosure().retainedBusy();

		ATNConfig a = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		ATNConfig b = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		assertTrue(a.equals(b));
		assertTrue(busy.add(a));
		assertFalse(busy.add(b));
		assertEqualsSize(1, busy);
		busy.clear();
		assertTrue(busy.isEmpty());
	}

	private static void assertEqualsSize(int expected, ObjectHashSet<?> set) {
		org.junit.Assert.assertEquals(expected, set.size());
	}
}
