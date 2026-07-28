/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

/**
 * Tests for retained scratch structures inside package-private
 * {@link EpsilonClosure}: busy set and intermediate config-set buffers must be
 * allocated once and reused across {@code close} invocations on the same
 * simulator. The busy set is typed as {@link Set} (backed by
 * {@link OpenAddressedHashSet}) so HPPC never leaks into the protected SPI.
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
		Set<ATNConfig> busy = closure.retainedBusy();
		assertNotNull(bufferA);
		assertNotNull(bufferB);
		assertNotNull(busy);
		assertTrue(busy instanceof OpenAddressedHashSet);

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
	public void busySetUsesValueEqualityViaSetInterface() {
		// Guard: closure busy must use ATNConfig equals/hashCode (not identity),
		// so equal configs built independently still collapse via Set.add.
		ATN atn = new ATN(ATNType.PARSER, 1);
		BasicState state = new BasicState();
		state.stateNumber = 3;
		atn.addState(state);

		ParserATNSimulator simulator = new ParserATNSimulator(atn);
		Set<ATNConfig> busy = simulator.epsilonClosure().retainedBusy();

		ATNConfig a = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		ATNConfig b = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		assertTrue(a.equals(b));
		assertTrue(busy.add(a));
		assertFalse(busy.add(b));
		assertEquals(1, busy.size());
		busy.clear();
		assertTrue(busy.isEmpty());
	}

	/**
	 * Subclasses that override the recursive {@code closure} entry point must
	 * receive a JDK {@link Set} (not an HPPC type) and may replace the busy set
	 * with a {@link java.util.HashSet} without breaking the engine.
	 */
	@Test
	public void subclassOverrideMaySubstituteJdkHashSet() {
		ATN atn = new ATN(ATNType.PARSER, 1);
		BasicState state = new BasicState();
		state.stateNumber = 0;
		state.ruleIndex = 0;
		atn.addState(state);

		// Rule stop + epsilon back-edge that re-enters with an equal config
		// exercises closureBusy membership (right-recursion style guard).
		RuleStopState stop = new RuleStopState();
		stop.stateNumber = 1;
		stop.ruleIndex = 0;
		atn.addState(stop);
		state.addTransition(new EpsilonTransition(stop));
		// From stop, epsilon back to state so depth-tracking can use busy set.
		// Keep a non-epsilon atom so closure still terminates for pure leaves.
		BasicState leaf = new BasicState();
		leaf.stateNumber = 2;
		leaf.ruleIndex = 0;
		atn.addState(leaf);
		stop.addTransition(new EpsilonTransition(leaf));

		final boolean[] substituted = { false };
		ParserATNSimulator simulator = new ParserATNSimulator(atn) {
			@Override
			protected void closure(ATNConfig config,
								   ATNConfigSet configs,
								   ATNConfigSet intermediate,
								   Set<ATNConfig> closureBusy,
								   boolean collectPredicates,
								   boolean hasMoreContexts,
								   PredictionContextCache contextCache,
								   int depth,
								   boolean treatEofAsEpsilon) {
				// Production passes OpenAddressedHashSet; subclasses may swap in
				// any Set implementation. Seed a HashSet with current members so
				// membership semantics stay equivalent for this invocation.
				Set<ATNConfig> jdkBusy = new java.util.HashSet<ATNConfig>(closureBusy);
				substituted[0] = true;
				super.closure(config, configs, intermediate, jdkBusy,
					collectPredicates, hasMoreContexts, contextCache, depth, treatEofAsEpsilon);
			}
		};

		ATNConfigSet source = new ATNConfigSet(4);
		source.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL));
		ATNConfigSet configs = new ATNConfigSet(4);
		simulator.epsilonClosure().close(source, configs, true, false, PredictionContextCache.UNCACHED, false);
		assertTrue(substituted[0]);
		assertTrue(configs.size() >= 1);
	}
}
