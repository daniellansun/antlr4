/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.junit.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestPredictionContext {

	@Test
	public void testEmptyContexts() {
		EmptyPredictionContext local = EmptyPredictionContext.LOCAL_CONTEXT;
		EmptyPredictionContext full = EmptyPredictionContext.FULL_CONTEXT;
		assertSame(local, PredictionContext.EMPTY_LOCAL);
		assertSame(full, PredictionContext.EMPTY_FULL);
		assertFalse(local.isFullContext());
		assertTrue(full.isFullContext());
		assertTrue(local.isEmpty());
		assertTrue(full.isEmpty());
		assertTrue(local.hasEmpty());
		assertTrue(full.hasEmpty());
		assertEquals(0, local.size());
		assertEquals(-1, local.findReturnState(1));
		assertEquals(local, local);
		assertNotEquals(local, full);
		assertTrue(PredictionContext.isEmptyLocal(local));
		assertFalse(PredictionContext.isEmptyLocal(full));

		assertSame(local, local.addEmptyContext());
		assertArrayEquals(new String[] { "[]" }, local.toStrings(null, 0));
		assertArrayEquals(new String[] { "[]" }, full.toStrings(null, PredictionContext.EMPTY_FULL, 0));

		try {
			local.getParent(0);
			fail();
		}
		catch (IndexOutOfBoundsException expected) {
			// ok
		}
		try {
			local.getReturnState(0);
			fail();
		}
		catch (IndexOutOfBoundsException expected) {
			// ok
		}
		try {
			local.removeEmptyContext();
			fail();
		}
		catch (UnsupportedOperationException expected) {
			// ok
		}

		// appendContext on empty returns suffix
		PredictionContext child = full.getChild(5);
		assertSame(child, full.appendContext(child, PredictionContextCache.UNCACHED));
		assertTrue(local.appendContext(7, PredictionContextCache.UNCACHED) instanceof SingletonPredictionContext);
	}

	@Test
	public void testSingletonContext() {
		PredictionContext parent = PredictionContext.EMPTY_FULL;
		PredictionContext s = parent.getChild(10);
		assertTrue(s instanceof SingletonPredictionContext);
		SingletonPredictionContext sc = (SingletonPredictionContext) s;
		assertEquals(1, sc.size());
		assertFalse(sc.isEmpty());
		assertFalse(sc.hasEmpty());
		assertEquals(10, sc.getReturnState(0));
		assertSame(parent, sc.getParent(0));
		assertEquals(0, sc.findReturnState(10));
		assertEquals(-1, sc.findReturnState(11));
		assertEquals(s, parent.getChild(10));
		assertEquals(s.hashCode(), parent.getChild(10).hashCode());
		assertNotEquals(s, parent.getChild(11));
		assertNotEquals(s, null);
		assertNotEquals(s, "x");

		PredictionContext withEmpty = sc.addEmptyContext();
		assertTrue(withEmpty instanceof ArrayPredictionContext);
		assertTrue(withEmpty.hasEmpty());
		assertSame(sc, sc.removeEmptyContext());
	}

	@Test
	public void testArrayContextViaJoin() {
		PredictionContext a = PredictionContext.EMPTY_FULL.getChild(1);
		PredictionContext b = PredictionContext.EMPTY_FULL.getChild(3);
		PredictionContext joined = PredictionContext.join(a, b);
		assertTrue(joined instanceof ArrayPredictionContext);
		ArrayPredictionContext arr = (ArrayPredictionContext) joined;
		assertEquals(2, arr.size());
		assertFalse(arr.isEmpty());
		assertFalse(arr.hasEmpty());
		assertEquals(1, arr.getReturnState(0));
		assertEquals(3, arr.getReturnState(1));
		assertTrue(arr.findReturnState(1) >= 0);
		assertEquals(joined, PredictionContext.join(b, a));
		assertEquals(joined.hashCode(), PredictionContext.join(b, a).hashCode());

		PredictionContext withEmpty = arr.addEmptyContext();
		assertTrue(withEmpty.hasEmpty());
		assertSame(withEmpty, withEmpty.addEmptyContext());
		PredictionContext removed = withEmpty.removeEmptyContext();
		assertFalse(removed.hasEmpty());

		// join same
		assertSame(a, PredictionContext.join(a, a));

		// join empty full with singleton adds empty
		PredictionContext j2 = PredictionContext.join(PredictionContext.EMPTY_FULL, a);
		assertTrue(j2.hasEmpty());

		// join empty local wins (returns local)
		assertSame(PredictionContext.EMPTY_LOCAL,
			PredictionContext.join(PredictionContext.EMPTY_LOCAL, a));
		assertSame(PredictionContext.EMPTY_LOCAL,
			PredictionContext.join(a, PredictionContext.EMPTY_LOCAL));
	}

	@Test
	public void testJoinMergesParents() {
		PredictionContext p1 = PredictionContext.EMPTY_FULL.getChild(1);
		PredictionContext p2 = PredictionContext.EMPTY_FULL.getChild(2);
		PredictionContext a = p1.getChild(5);
		PredictionContext b = p2.getChild(5);
		// same return state, different parents -> join parents then child
		PredictionContext joined = PredictionContext.join(a, b);
		assertTrue(joined instanceof SingletonPredictionContext || joined instanceof ArrayPredictionContext
			|| joined.size() == 1);
		assertEquals(5, joined.getReturnState(0));
	}

	@Test
	public void testFromRuleContext() {
		ATN atn = ATNTestHelpers.buildParserAB();
		// empty rule context
		assertSame(PredictionContext.EMPTY_FULL,
			PredictionContext.fromRuleContext(atn, ParserRuleContext.emptyContext()));
		assertSame(PredictionContext.EMPTY_LOCAL,
			PredictionContext.fromRuleContext(atn, ParserRuleContext.emptyContext(), false));

		// non-empty: need invoking state with RuleTransition
		// Build a tiny ATN with a rule call
		ATN callAtn = new ATN(ATNType.PARSER, 1);
		RuleStartState outerStart = new RuleStartState();
		RuleStopState outerStop = new RuleStopState();
		RuleStartState innerStart = new RuleStartState();
		RuleStopState innerStop = new RuleStopState();
		BasicState afterCall = new BasicState();
		outerStart.ruleIndex = 0;
		outerStop.ruleIndex = 0;
		innerStart.ruleIndex = 1;
		innerStop.ruleIndex = 1;
		afterCall.ruleIndex = 0;
		outerStart.stopState = outerStop;
		innerStart.stopState = innerStop;
		callAtn.addState(outerStart);
		callAtn.addState(innerStart);
		callAtn.addState(innerStop);
		callAtn.addState(afterCall);
		callAtn.addState(outerStop);
		outerStart.addTransition(new RuleTransition(innerStart, 1, 0, afterCall));
		innerStart.addTransition(new EpsilonTransition(innerStop));
		afterCall.addTransition(new EpsilonTransition(outerStop));
		callAtn.ruleToStartState = new RuleStartState[] { outerStart, innerStart };
		callAtn.ruleToStopState = new RuleStopState[] { outerStop, innerStop };

		ParserRuleContext outer = new ParserRuleContext();
		ParserRuleContext inner = new ParserRuleContext(outer, outerStart.stateNumber);
		inner.invokingState = outerStart.stateNumber;
		PredictionContext ctx = PredictionContext.fromRuleContext(callAtn, inner);
		assertFalse(ctx.isEmpty());
		assertEquals(afterCall.stateNumber, ctx.getReturnState(0));
	}

	@Test
	public void testGetCachedContext() {
		PredictionContext s = PredictionContext.EMPTY_FULL.getChild(1).getChild(2);
		ConcurrentHashMap<PredictionContext, PredictionContext> cache =
			new ConcurrentHashMap<PredictionContext, PredictionContext>();
		PredictionContext cached = PredictionContext.getCachedContext(s, cache, new PredictionContext.IdentityHashMap());
		assertNotNull(cached);
		// empty short-circuits
		assertSame(PredictionContext.EMPTY_FULL,
			PredictionContext.getCachedContext(PredictionContext.EMPTY_FULL, cache, new PredictionContext.IdentityHashMap()));
	}

	@Test
	public void testAppendContext() {
		PredictionContext s = PredictionContext.EMPTY_FULL.getChild(1);
		PredictionContext appended = s.appendContext(2, new PredictionContextCache());
		assertNotNull(appended);
		PredictionContext suffix = PredictionContext.EMPTY_FULL.getChild(9);
		PredictionContext a2 = s.appendContext(suffix, PredictionContextCache.UNCACHED);
		assertNotNull(a2);
	}

	@Test
	public void testToStrings() {
		PredictionContext s = PredictionContext.EMPTY_FULL.getChild(1);
		String[] strs = s.toStrings(null, 0);
		assertTrue(strs.length >= 1);
	}

	@Test
	public void testPredictionContextCache() {
		PredictionContextCache cache = new PredictionContextCache();
		PredictionContext a = PredictionContext.EMPTY_FULL.getChild(1);
		PredictionContext cached = cache.getAsCached(a);
		assertSame(cached, cache.getAsCached(a));
		assertSame(cached, cache.getAsCached(PredictionContext.EMPTY_FULL.getChild(1)));

		PredictionContext child = cache.getChild(PredictionContext.EMPTY_FULL, 7);
		assertEquals(7, child.getReturnState(0));
		assertSame(child, cache.getChild(PredictionContext.EMPTY_FULL, 7));

		PredictionContext b = PredictionContext.EMPTY_FULL.getChild(3);
		PredictionContext joined = cache.join(a, b);
		assertSame(joined, cache.join(b, a)); // commutative identity cache

		// UNCACHED path
		PredictionContext u1 = PredictionContextCache.UNCACHED.getAsCached(a);
		assertSame(a, u1);
		PredictionContext u2 = PredictionContextCache.UNCACHED.getChild(PredictionContext.EMPTY_FULL, 4);
		assertEquals(4, u2.getReturnState(0));
		PredictionContext u3 = PredictionContextCache.UNCACHED.join(a, b);
		assertEquals(joined, u3);
	}

	@Test
	public void testIdentityHashMapComparator() {
		PredictionContext.IdentityEqualityComparator c = PredictionContext.IdentityEqualityComparator.INSTANCE;
		PredictionContext a = PredictionContext.EMPTY_FULL.getChild(1);
		// IdentityEqualityComparator hashes with value hashCode but equals by identity
		assertEquals(a.hashCode(), c.hashCode(a));
		assertTrue(c.equals(a, a));
		assertFalse(c.equals(a, PredictionContext.EMPTY_FULL.getChild(1)));
		PredictionContext.IdentityHashMap map = new PredictionContext.IdentityHashMap();
		assertNotNull(map);
	}
}
