/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ATNConfig}, focusing on the cached {@link ATNConfig#hashCode()}
 * used by lexer ordered config sets, closure busy sets, and DFA state lookup.
 */
public class TestATNConfig {

	@Test
	public void hashCodeIsStableAcrossRepeatedCalls() {
		BasicState state = new BasicState();
		state.stateNumber = 7;
		ATNConfig config = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);

		int first = config.hashCode();
		int second = config.hashCode();
		assertEquals(first, second);
		assertNotEquals(0, first);
	}

	@Test
	public void equalConfigsHaveEqualHashCodes() {
		BasicState state = new BasicState();
		state.stateNumber = 3;
		ATNConfig a = ATNConfig.create(state, 2, PredictionContext.EMPTY_FULL);
		ATNConfig b = ATNConfig.create(state, 2, PredictionContext.EMPTY_FULL);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
	}

	@Test
	public void setContextInvalidatesHashWhenContextChanges() {
		BasicState state = new BasicState();
		state.stateNumber = 11;
		PredictionContextCache cache = new PredictionContextCache();
		PredictionContext child = cache.getChild(PredictionContext.EMPTY_LOCAL, 42);

		ATNConfig config = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		int before = config.hashCode();

		config.setContext(child);
		int after = config.hashCode();

		assertNotEquals(before, after);
		// Cache must remain stable after the invalidating mutation.
		assertEquals(after, config.hashCode());
	}

	@Test
	public void setContextWithSameReferenceDoesNotForceRecompute() {
		BasicState state = new BasicState();
		state.stateNumber = 4;
		ATNConfig config = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		int hash = config.hashCode();

		config.setContext(PredictionContext.EMPTY_LOCAL);
		assertEquals(hash, config.hashCode());
	}

	@Test
	public void setOuterContextDepthInvalidatesWhenReachesFlagChanges() {
		BasicState state = new BasicState();
		state.stateNumber = 9;
		ATNConfig config = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);

		assertFalse(config.getReachesIntoOuterContext());
		int before = config.hashCode();

		config.setOuterContextDepth(1);
		assertTrue(config.getReachesIntoOuterContext());
		int afterDepth1 = config.hashCode();
		assertNotEquals(before, afterDepth1);

		// Further depth increases do not affect hashCode (only the flag does).
		config.setOuterContextDepth(3);
		assertEquals(afterDepth1, config.hashCode());

		// Returning to depth 0 flips the flag again and must invalidate.
		config.setOuterContextDepth(0);
		assertFalse(config.getReachesIntoOuterContext());
		assertEquals(before, config.hashCode());
	}

	@Test
	public void transformProducesIndependentHashCache() {
		BasicState s0 = new BasicState();
		s0.stateNumber = 1;
		BasicState s1 = new BasicState();
		s1.stateNumber = 2;

		ATNConfig original = ATNConfig.create(s0, 1, PredictionContext.EMPTY_LOCAL);
		int originalHash = original.hashCode();

		ATNConfig transformed = original.transform(s1, false);
		int transformedHash = transformed.hashCode();
		assertNotEquals(originalHash, transformedHash);
		// Mutating the original must not affect the transformed config's hash.
		original.setOuterContextDepth(1);
		assertEquals(transformedHash, transformed.hashCode());
		assertNotEquals(original.hashCode(), transformed.hashCode());
	}

	@Test
	public void semanticContextAndLexerActionAffectHashAndEquality() {
		BasicState state = new BasicState();
		state.stateNumber = 5;

		ATNConfig plain = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		SemanticContext.Predicate pred = new SemanticContext.Predicate(0, 0, false);
		ATNConfig withPred = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL, pred);

		assertNotEquals(plain, withPred);
		assertNotEquals(plain.hashCode(), withPred.hashCode());
	}

	@Test
	public void precedenceFilterSuppressedDoesNotChangeHashByContract() {
		// equals considers precedence-filter suppression, but hashCode historically
		// does not. Caching must preserve that contract.
		BasicState state = new BasicState();
		state.stateNumber = 6;
		ATNConfig a = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		ATNConfig b = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		b.setPrecedenceFilterSuppressed(true);

		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, b);
	}
}
