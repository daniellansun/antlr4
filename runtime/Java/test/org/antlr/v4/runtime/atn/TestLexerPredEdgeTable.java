/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Focused unit tests for {@link LexerPredEdgeTable}, {@link LexerPredEdgeTable.PredTrace},
 * and {@link ATN}'s per-mode predicate-edge table lifecycle and concurrency.
 */
public class TestLexerPredEdgeTable {

	@Test
	public void testTableGuardsMultiDepthGrowthAndConcurrency() throws Exception {
		ATN atn = ATNTestHelpers.buildLexerMatchA();
		DFA dfa = atn.modeToDFA[0];
		DFAState s0 = new DFAState(dfa, new ATNConfigSet());
		s0.stateNumber = 0;
		DFAState targetTrue = new DFAState(dfa, new ATNConfigSet());
		targetTrue.stateNumber = 1;
		DFAState targetFalse = new DFAState(dfa, new ATNConfigSet());
		targetFalse.stateNumber = 2;
		DFAState targetDeep = new DFAState(dfa, new ATNConfigSet());
		targetDeep.stateNumber = 3;

		assertNull(atn.getLexerPredEdgeTable(0, s0));
		assertNull(atn.getLexerPredEdgeTable(-1, s0));
		LexerPredEdgeTable table = atn.getOrCreateLexerPredEdgeTable(0, s0);
		assertSame(table, atn.getOrCreateLexerPredEdgeTable(0, s0));
		assertSame(table, atn.getLexerPredEdgeTable(0, s0));

		// Stale s0 identity returns null and recreates on getOrCreate
		DFAState otherS0 = new DFAState(dfa, new ATNConfigSet());
		assertNull(atn.getLexerPredEdgeTable(0, otherS0));
		LexerPredEdgeTable recreated = atn.getOrCreateLexerPredEdgeTable(0, otherS0);
		assertSame(recreated, atn.getLexerPredEdgeTable(0, otherS0));
		table = atn.getOrCreateLexerPredEdgeTable(0, s0);

		// Defensive guard rejections
		assertNull(table.getRoot(-1, 'a'));
		assertNull(table.getRoot(0, -1));
		assertNull(table.getRoot(0, 128));
		assertNull(table.getRoot(100, 'a'));
		table.put(-1, 'a', new int[] { 0 }, new int[] { 0 }, new boolean[] { true }, 1, targetTrue);
		table.put(0, 'a', new int[] { 0 }, new int[] { 0 }, new boolean[] { true }, 0, targetTrue);
		table.put(0, 'a', new int[] { 0 }, new int[] { 0 }, new boolean[] { true }, LexerPredEdgeTable.MAX_PRED_DEPTH + 1, targetTrue);
		table.put(0, 128, new int[] { 0 }, new int[] { 0 }, new boolean[] { true }, 1, targetTrue);
		table.put(0, -1, new int[] { 0 }, new int[] { 0 }, new boolean[] { true }, 1, targetTrue);
		assertNull(table.getRoot(0, 'a'));

		// Single-depth true & false branches on existing and new rows
		table.put(0, '/', new int[] { 1 }, new int[] { 2 }, new boolean[] { true }, 1, targetTrue);
		table.put(0, '/', new int[] { 1 }, new int[] { 2 }, new boolean[] { false }, 1, targetFalse);
		table.put(0, '+', new int[] { 1 }, new int[] { 3 }, new boolean[] { false }, 1, targetFalse);
		LexerPredEdgeTable.PredNode root = table.getRoot(0, '/');
		assertNotNull(root);
		assertSame(targetTrue, root.onTrue);
		assertSame(targetFalse, root.onFalse);
		assertSame(targetFalse, table.getRoot(0, '+').onFalse);

		// Multi-depth decision tree (depth 3, both true and false branches) + row growth > 16
		table.put(25, '\'', new int[] { 1, 2, 3 }, new int[] { 0, 1, 2 }, new boolean[] { true, false, true }, 3, targetDeep);
		table.put(25, '\'', new int[] { 1, 2 }, new int[] { 0, 1 }, new boolean[] { true, true }, 2, targetTrue);
		table.put(25, '\'', new int[] { 1, 2, 3 }, new int[] { 0, 1, 2 }, new boolean[] { true, false, false }, 3, targetFalse);
		table.put(25, '\'', new int[] { 1 }, new int[] { 0 }, new boolean[] { false }, 1, ATNSimulator.ERROR);
		// Non-matching predicate order -> defensive no-op
		table.put(25, '\'', new int[] { 99 }, new int[] { 0 }, new boolean[] { true }, 1, targetTrue);
		// Existing shorter leaf at depth 1 (onFalse == ERROR) -> left intact
		table.put(25, '\'', new int[] { 1, 2 }, new int[] { 0, 1 }, new boolean[] { false, true }, 2, targetTrue);
		// Duplicate exact path -> left intact
		table.put(25, '\'', new int[] { 1, 2, 3 }, new int[] { 0, 1, 2 }, new boolean[] { true, false, true }, 3, targetFalse);

		LexerPredEdgeTable.PredNode n0 = table.getRoot(25, '\'');
		assertNotNull(n0);
		assertSame(ATNSimulator.ERROR, n0.onFalse);
		LexerPredEdgeTable.PredNode n1 = (LexerPredEdgeTable.PredNode) n0.onTrue;
		assertSame(targetTrue, n1.onTrue);
		LexerPredEdgeTable.PredNode n2 = (LexerPredEdgeTable.PredNode) n1.onFalse;
		assertSame(targetDeep, n2.onTrue);
		assertSame(targetFalse, n2.onFalse);

		// Multi-predicate chain attaching both onTrue & onFalse child nodes
		LexerPredEdgeTable chainTable = new LexerPredEdgeTable(s0);
		chainTable.put(0, 'z', new int[] { 0, 1 }, new int[] { 0, 1 }, new boolean[] { true, true }, 2, targetTrue);
		chainTable.put(0, 'z', new int[] { 0, 1 }, new int[] { 0, 1 }, new boolean[] { false, true }, 2, targetFalse);
		chainTable.put(0, 'y', new int[] { 0, 1 }, new int[] { 0, 1 }, new boolean[] { false, false }, 2, targetTrue);
		chainTable.put(0, 'y', new int[] { 0, 1 }, new int[] { 0, 1 }, new boolean[] { true, false }, 2, targetFalse);
		assertSame(targetTrue, ((LexerPredEdgeTable.PredNode) chainTable.getRoot(0, 'z').onTrue).onTrue);
		assertSame(targetFalse, ((LexerPredEdgeTable.PredNode) chainTable.getRoot(0, 'z').onFalse).onTrue);
		assertSame(targetFalse, ((LexerPredEdgeTable.PredNode) chainTable.getRoot(0, 'y').onTrue).onFalse);
		assertSame(targetTrue, ((LexerPredEdgeTable.PredNode) chainTable.getRoot(0, 'y').onFalse).onFalse);

		// Mode resize beyond modeToDFA.length + concurrent stress with clearDFA
		LexerPredEdgeTable mode3 = atn.getOrCreateLexerPredEdgeTable(3, s0);
		assertSame(mode3, atn.getLexerPredEdgeTable(3, s0));
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			final CountDownLatch latch = new CountDownLatch(1);
			List<Future<?>> futs = new ArrayList<Future<?>>();
			for (int t = 0; t < 4; t++) {
				final int tid = t;
				futs.add(pool.submit(new Runnable() {
					@Override
					public void run() {
						try { latch.await(); } catch (InterruptedException ignored) {}
						for (int i = 0; i < 100; i++) {
							LexerPredEdgeTable tbl = atn.getOrCreateLexerPredEdgeTable(0, s0);
							tbl.put(tid * 8 + (i & 7), 'a' + (i & 15),
								new int[] { 0 }, new int[] { 0 }, new boolean[] { (i & 1) == 0 }, 1, targetTrue);
							tbl.getRoot(tid * 8 + (i & 7), 'a' + (i & 15));
						}
					}
				}));
			}
			latch.countDown();
			for (Future<?> f : futs) {
				f.get();
			}
		}
		finally {
			pool.shutdownNow();
		}
		atn.clearDFA();
		assertNull(atn.getLexerPredEdgeTable(0, s0));
	}

	@Test
	public void testPredTraceReplayMismatchOverflowAndResolveTargetGuards() {
		// Build a lexer ATN with a chain of MAX_PRED_DEPTH + 2 predicates on 'p'
		// plus a 2-predicate rule on '/' so we exercise PredTrace replay,
		// replay mismatch abort, depth > MAX_PRED_DEPTH abort, and resolveTarget guards.
		final ATN atn = new ATN(ATNType.LEXER, 2);
		TokensStartState start = new TokensStartState();
		start.ruleIndex = -1;
		atn.addState(start);

		RuleStartState r0s = new RuleStartState(); r0s.ruleIndex = 0;
		RuleStopState r0e = new RuleStopState(); r0e.ruleIndex = 0; r0s.stopState = r0e;
		BasicState prev = new BasicState(); prev.ruleIndex = 0;
		atn.addState(r0s); atn.addState(prev); atn.addState(r0e);
		start.addTransition(new EpsilonTransition(r0s));
		r0s.addTransition(new AtomTransition(prev, 'p'));
		int predCount = LexerPredEdgeTable.MAX_PRED_DEPTH + 2;
		for (int i = 0; i < predCount; i++) {
			BasicState next = new BasicState(); next.ruleIndex = 0;
			atn.addState(next);
			prev.addTransition(new PredicateTransition(next, 0, i, false));
			prev = next;
		}
		prev.addTransition(new EpsilonTransition(r0e));

		RuleStartState r1s = new RuleStartState(); r1s.ruleIndex = 1;
		BasicState r1m = new BasicState(); r1m.ruleIndex = 1;
		BasicState r1p = new BasicState(); r1p.ruleIndex = 1;
		RuleStopState r1e = new RuleStopState(); r1e.ruleIndex = 1; r1s.stopState = r1e;
		atn.addState(r1s); atn.addState(r1m); atn.addState(r1p); atn.addState(r1e);
		start.addTransition(new EpsilonTransition(r1s));
		r1s.addTransition(new AtomTransition(r1m, '/'));
		r1m.addTransition(new PredicateTransition(r1p, 1, 0, false));
		r1p.addTransition(new EpsilonTransition(r1e));

		atn.ruleToStartState = new RuleStartState[] { r0s, r1s };
		atn.ruleToStopState = new RuleStopState[] { r0e, r1e };
		atn.ruleToTokenType = new int[] { 1, 2 };
		atn.defineMode("DEFAULT_MODE", start);
		atn.clearDFA();

		final boolean[] allowSlash = new boolean[] { true };
		Lexer lex = new Lexer(CharStreams.fromString("p//")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "P", "S" }; }
			@Override public String getGrammarFileName() { return "PT.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public boolean sempred(RuleContext ctx, int ruleIndex, int predIndex) {
				if (ruleIndex == 1) {
					return allowSlash[0];
				}
				return true;
			}
		};
		lex.removeErrorListeners();

		// 'p' traverses > MAX_PRED_DEPTH predicates -> hits recordAbort = true (not cached)
		assertEquals(1, lex.nextToken().getType());
		// '/' records true branch, replays true branch, then records false -> ERROR branch
		assertEquals(2, lex.nextToken().getType());
		assertEquals(2, lex.nextToken().getType());
		allowSlash[0] = false;
		lex.setInputStream(CharStreams.fromString("/"));
		assertEquals(Token.EOF, lex.nextToken().getType()); // recovered after ERROR

		// Direct resolveFast guard paths (non-ASCII, negative stateNumber, recog == null, optimize_tail_calls == false)
		LexerATNSimulator sim = lex.getInterpreter();
		DFAState ds0 = atn.modeToDFA[0].s0.get();
		assertNotNull(ds0);
		assertSame(ATNSimulator.ERROR, LexerPredEdgeTable.resolveFast(sim, CharStreams.fromString("\u00e9"), ds0, ds0, '\u00e9'));

		DFAState negState = new DFAState(atn.modeToDFA[0], ds0.configs);
		negState.stateNumber = -1;
		assertNotNull(LexerPredEdgeTable.resolveFast(sim, CharStreams.fromString("/"), ds0, negState, '/'));

		sim.optimize_tail_calls = false;
		assertNotNull(LexerPredEdgeTable.resolveFast(sim, CharStreams.fromString("/"), ds0, ds0, '/'));
		sim.optimize_tail_calls = true;

		// Replay mismatch & 2-predicate warm hit through resolveMultiOrMiss
		atn.clearDFA();
		lex.setInputStream(CharStreams.fromString("/"));
		allowSlash[0] = true;
		assertEquals(2, lex.nextToken().getType());
		DFAState freshDs0 = atn.modeToDFA[0].s0.get();
		LexerPredEdgeTable tbl = atn.getOrCreateLexerPredEdgeTable(0, freshDs0);
		// 2-predicate warm chain (state 8, '/') hitting resolveMultiOrMiss -> DFAState
		DFAState multiState = new DFAState(atn.modeToDFA[0], freshDs0.configs);
		multiState.stateNumber = 8;
		tbl.put(8, '/', new int[] { 1, 1 }, new int[] { 0, 1 }, new boolean[] { true, true }, 2, freshDs0);
		assertSame(freshDs0, LexerPredEdgeTable.resolveFast(sim, CharStreams.fromString("/"), freshDs0, multiState, '/'));

		// Put a mismatched root on state 7 for '/' with onTrue == null (only onFalse populated)
		DFAState fakeState = new DFAState(atn.modeToDFA[0], freshDs0.configs);
		fakeState.stateNumber = 7;
		tbl.put(7, '/', new int[] { 99 }, new int[] { 99 }, new boolean[] { false }, 1, ATNSimulator.ERROR);
		// Evaluate with allowSlash=true -> root (99,99) returns true -> onTrue is null -> falls into capture with replayLen=1
		// Then getReachableConfigSet hits real pred (1, 0) != (99, 99) -> replay mismatch!
		assertNotNull(LexerPredEdgeTable.resolveFast(sim, CharStreams.fromString("/"), freshDs0, fakeState, '/'));

		LexerATNSimulator noRecogSim = new LexerATNSimulator(atn);
		assertNotNull(LexerPredEdgeTable.resolveFast(noRecogSim, CharStreams.fromString("/"), freshDs0, freshDs0, '/'));
	}
}
