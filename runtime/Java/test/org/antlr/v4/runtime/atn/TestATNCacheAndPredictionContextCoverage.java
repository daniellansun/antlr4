/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.dfa.AbstractEdgeMap;
import org.antlr.v4.runtime.dfa.ArrayEdgeMap;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFASerializer;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.dfa.HashEdgeMap;
import org.antlr.v4.runtime.dfa.LexerDFASerializer;
import org.antlr.v4.runtime.dfa.SingletonEdgeMap;
import org.antlr.v4.runtime.dfa.SparseEdgeMap;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * LL(1) {@link ConcurrentIntIntMap} view contracts, {@link ATN#clearDFA} holes,
 * {@link PredictionContext} helpers, {@link SemanticContext.OR}, and edge maps.
 */
public class TestATNCacheAndPredictionContextCoverage {

	@Test
	public void concurrentMapViewClearGetMissingAndEntryContracts() {
		ConcurrentIntIntMapView view = new ConcurrentIntIntMapView(new ConcurrentIntIntMap());
		assertNull(view.get(99));
		view.put(1, 2);
		view.clear();
		assertTrue(view.isEmpty());
		assertTrue(view.entrySet().isEmpty());

		view.put(3, 4);
		try {
			view.replace(3, null, 5);
			fail();
		}
		catch (NullPointerException expected) {
			assertNotNull(expected);
		}

		Set<Map.Entry<Integer, Integer>> es = view.entrySet();
		assertFalse(es.contains("not-entry"));
		assertFalse(es.contains(new AbstractMap.SimpleEntry<String, Integer>("x", 1)));
		assertFalse(es.contains(new AbstractMap.SimpleEntry<Integer, Integer>(3, 99)));
		assertFalse(es.remove("not-entry"));
		assertFalse(es.remove(new AbstractMap.SimpleEntry<Integer, String>(3, "x")));

		Iterator<Map.Entry<Integer, Integer>> it = es.iterator();
		try {
			it.remove();
			fail();
		}
		catch (IllegalStateException expected) {
			assertNotNull(expected);
		}
		Map.Entry<Integer, Integer> e = it.next();
		assertEquals("3=4", e.toString());
		assertFalse(e.equals("x"));
		assertTrue(e.equals(new AbstractMap.SimpleEntry<Integer, Integer>(3, 4)));
		assertFalse(e.equals(new AbstractMap.SimpleEntry<Integer, Integer>(3, 5)));
		assertFalse(e.equals(new AbstractMap.SimpleEntry<Integer, Integer>(9, 4)));
		try {
			e.setValue(null);
			fail();
		}
		catch (NullPointerException expected) {
			assertNotNull(expected);
		}
		assertFalse(it.hasNext());
		try {
			it.next();
			fail();
		}
		catch (NoSuchElementException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void concurrentIntIntMapRacesAndSameValueReplace() throws Exception {
		ConcurrentIntIntMap map = new ConcurrentIntIntMap();
		assertFalse(map.replace(1, 0, 2)); // oldValue == MISSING
		map.put(5, 6);
		assertEquals(6, map.replace(5, 6)); // previous == newValue

		final ConcurrentIntIntMap raced = new ConcurrentIntIntMap();
		final int threads = 8;
		final int iters = 4000;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		final AtomicInteger ops = new AtomicInteger();
		try {
			for (int t = 0; t < threads; t++) {
				pool.submit(new Runnable() {
					@Override
					public void run() {
						for (int i = 0; i < iters; i++) {
							raced.put(1, 7);
							raced.put(1, 7); // same-value inside monitor
							raced.putIfAbsent(2, 8);
							raced.remove(2);
							raced.put(2, 8);
							raced.remove(2, 8);
							raced.put(3, 9);
							raced.replace(3, 9, 9);
							raced.replace(3, 10);
							ops.incrementAndGet();
						}
					}
				});
			}
		}
		finally {
			pool.shutdown();
			assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
		}
		assertTrue(ops.get() > 0);
	}

	@Test
	public void atnClearDfaNullSlotsAndModeSlots() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		atn.clearDFA();
		assertNotNull(atn.getDecisionToDFA());
		atn.decisionToDFA[0] = null;
		atn.clearDFA();
		assertNotNull(atn.decisionToDFA[0]);

		// length mismatch reallocates
		atn.decisionToDFA = new DFA[0];
		atn.clearDFA();
		assertEquals(atn.decisionToState.size(), atn.decisionToDFA.length);

		ATN lexer = ATNTestHelpers.buildLexerMatchA();
		lexer.clearDFA();
		if (lexer.modeToDFA != null && lexer.modeToDFA.length > 0) {
			lexer.modeToDFA[0] = null;
			lexer.clearDFA();
			assertNotNull(lexer.modeToDFA[0]);
		}
		lexer.modeToDFA = new DFA[0];
		lexer.clearDFA();
		assertEquals(lexer.modeToStartState.size(), lexer.modeToDFA.length);
	}

	@Test
	public void predictionContextFromRuleContextNullParentAndRemoveEmptyStatic() throws Exception {
		ATN atn = ATNTestHelpers.buildParserRuleCall();
		int invoke = 0;
		for (ATNState s : atn.states) {
			for (int i = 0; i < s.getNumberOfTransitions(); i++) {
				if (s.transition(i) instanceof RuleTransition) {
					invoke = s.stateNumber;
				}
			}
		}
		RuleContext ctx = new RuleContext();
		ctx.invokingState = invoke;
		ctx.parent = null;
		PredictionContext full = PredictionContext.fromRuleContext(atn, ctx, true);
		assertNotNull(full);
		PredictionContext local = PredictionContext.fromRuleContext(atn, ctx, false);
		assertNotNull(local);

		Method m = PredictionContext.class.getDeclaredMethod("removeEmptyContext", PredictionContext.class);
		m.setAccessible(true);
		PredictionContext singleton = PredictionContext.EMPTY_FULL.getChild(3);
		assertSame(singleton, m.invoke(null, singleton));

		// join paths: two singletons same return, different parents that merge to one side
		PredictionContext a = PredictionContext.EMPTY_FULL.getChild(1);
		PredictionContext b = PredictionContext.EMPTY_FULL.getChild(2);
		PredictionContext j = PredictionContext.join(a, b);
		assertNotNull(j);
		PredictionContext j2 = PredictionContext.join(b, a);
		assertNotNull(j2);
		// same return state, parents join
		PredictionContext c0 = a.getChild(5);
		PredictionContext c1 = b.getChild(5);
		assertNotNull(PredictionContext.join(c0, c1));

		// toStrings with recognizer
		ParserInterpreter p = ATNTestHelpers.createParser(atn, 1);
		String[] names = j.toStrings(p, 0);
		assertTrue(names.length >= 1);
		String[] names2 = j.toStrings(null, 0);
		assertTrue(names2.length >= 1);
	}

	@Test
	public void semanticContextOrGetOperands() {
		SemanticContext.Predicate p1 = new SemanticContext.Predicate(0, 0, false);
		SemanticContext.Predicate p2 = new SemanticContext.Predicate(0, 1, false);
		SemanticContext or = SemanticContext.or(p1, p2);
		assertTrue(or instanceof SemanticContext.OR);
		assertEquals(2, ((SemanticContext.OR) or).getOperands().size());
	}

	@Test
	public void edgeMapPutNullAndToArrayDefaults() {
		ATN dummyAtn = ATNTestHelpers.buildParserAorB();
		DFA dummyDfa = dummyAtn.decisionToDFA[0];
		DFAState s = new DFAState(dummyDfa, new ATNConfigSet());
		AbstractEdgeMap<DFAState> s2 = new SparseEdgeMap<DFAState>(0, 5, 4);
		assertNull(s2.get(1));
		s2 = s2.put(1, s);
		s2 = s2.put(2, s);
		s2 = s2.put(1, null);
		assertNotNull(s2);

		AbstractEdgeMap<DFAState> hash = new HashEdgeMap<DFAState>(0, 100);
		hash = hash.put(3, s);
		hash = hash.put(4, s);
		hash = hash.put(3, null);
		assertNotNull(hash);

		ArrayEdgeMap<DFAState> arr = new ArrayEdgeMap<DFAState>(0, 8);
		arr = arr.put(1, s);
		// putAll SparseEdgeMap path
		SparseEdgeMap<DFAState> sparse = new SparseEdgeMap<DFAState>(0, 8, 4);
		sparse = (SparseEdgeMap<DFAState>) sparse.put(2, s);
		arr = arr.putAll(sparse);
		arr = arr.put(1, null);
		assertNotNull(arr.toMap());
		assertNotNull(arr.clear());

		SingletonEdgeMap<DFAState> one = new SingletonEdgeMap<DFAState>(0, 8, 2, s);
		assertNotNull(one.put(2, null));

		ATN atn = ATNTestHelpers.buildParserAorB();
		DFA dfa = atn.decisionToDFA[0];
		DFASerializer ser = new DFASerializer(dfa, pVocab(), new String[] { "s" }, atn);
		// empty DFA toString may be null
		ser.toString();
		LexerDFASerializer lser = new LexerDFASerializer(ATNTestHelpers.buildLexerMatchA().modeToDFA[0]);
		lser.toString();
	}

	@Test
	public void dfaStateToStringAndAcceptInfo() {
		ATN dummyAtn = ATNTestHelpers.buildParserAorB();
		DFA dummyDfa = dummyAtn.decisionToDFA[0];
		DFAState st = new DFAState(dummyDfa, new ATNConfigSet());
		st.setAcceptState(new org.antlr.v4.runtime.dfa.AcceptStateInfo(1));
		assertNotNull(st.toString());
		st.setAcceptState(new org.antlr.v4.runtime.dfa.AcceptStateInfo(1, new LexerActionExecutor(new LexerAction[0])));
		assertNotNull(st.toString());
	}

	@Test
	public void getCachedContextChangedParents() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		PredictionContext parent = PredictionContext.EMPTY_FULL.getChild(1);
		PredictionContext child = parent.getChild(2);
		PredictionContext cached = atn.getCachedContext(child);
		assertNotNull(cached);
		// second time hits cache
		assertNotNull(atn.getCachedContext(child));
	}

	private static VocabularyImpl pVocab() {
		return new VocabularyImpl(new String[] { null, "'A'", "'B'" }, new String[] { null, "A", "B" });
	}
}
