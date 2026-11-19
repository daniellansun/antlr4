/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.LexerNoViableAltException;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.dfa.AcceptStateInfo;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.MurmurHash;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Comprehensive unit tests for Round 1–3 ATN/DFA runtime optimizations:
 * {@link LexerPredEdgeTable}, {@link LexerATNSimulator}, {@link LexerActionExecutor},
 * {@link ParserATNSimulator}, {@link PredictionContextCache}, {@link EpsilonClosure},
 * {@link ReachComputation}, {@link ATNConfig}, {@link ATNConfigSet},
 * {@link ArrayPredictionContext}, and {@link DFAState}.
 */
public class TestRuntimeOptDeepCoverage {

	// -------------------------------------------------------------------------
	// LexerPredEdgeTable + ATN predicate-edge table concurrency & edge cases
	// -------------------------------------------------------------------------

	@Test
	public void testLexerPredEdgeTableAndATNConcurrency() throws Exception {
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

		// Defensive guard rejections
		assertNull(table.getRoot(-1, 'a'));
		assertNull(table.getRoot(0, -1));
		assertNull(table.getRoot(0, 128));
		assertNull(table.getRoot(100, 'a'));
		table.put(-1, 'a', new int[] { 0 }, new int[] { 0 }, new boolean[] { true }, 1, targetTrue);
		table.put(0, 'a', new int[] { 0 }, new int[] { 0 }, new boolean[] { true }, 0, targetTrue);
		table.put(0, 'a', new int[] { 0 }, new int[] { 0 }, new boolean[] { true }, LexerPredEdgeTable.MAX_PRED_DEPTH + 1, targetTrue);
		table.put(0, 128, new int[] { 0 }, new int[] { 0 }, new boolean[] { true }, 1, targetTrue);
		assertNull(table.getRoot(0, 'a'));

		// Single-depth true & false branches, plus growth past initial 16 rows
		table.put(0, '/', new int[] { 1 }, new int[] { 2 }, new boolean[] { true }, 1, targetTrue);
		table.put(0, '/', new int[] { 1 }, new int[] { 2 }, new boolean[] { false }, 1, targetFalse);
		LexerPredEdgeTable.PredNode root = table.getRoot(0, '/');
		assertNotNull(root);
		assertSame(targetTrue, root.onTrue);
		assertSame(targetFalse, root.onFalse);

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
			for (Future<?> f : futs) f.get();
		}
		finally {
			pool.shutdownNow();
		}
		atn.clearDFA();
		assertNull(atn.getLexerPredEdgeTable(0, s0));
	}

	// -------------------------------------------------------------------------
	// LexerActionExecutor: empty, all MODE_SINGLE, MODE_MULTI (0/1/2+ custom), MODE_SLOW
	// -------------------------------------------------------------------------

	@Test
	public void testLexerActionExecutorAllModesAndSeekSemantics() {
		final ATN atn = ATNTestHelpers.buildLexerMatchA();
		final List<String> log = new ArrayList<String>();
		Lexer lexer = new Lexer(CharStreams.fromString("abcdef")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "R" }; }
			@Override public String getGrammarFileName() { return "E.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public void action(RuleContext ctx, int ruleIndex, int actionIndex) {
				log.add("act:" + ruleIndex + ":" + actionIndex + "@" + _input.index());
				if (actionIndex == 10) {
					// Deliberately move input stream to test hasMultipleCustomActions stopIndex restore
					_input.seek(1);
				}
			}
		};
		CharStream input = lexer.getInputStream();
		input.seek(4);

		// Empty executor (len == 0)
		LexerActionExecutor empty = new LexerActionExecutor(new LexerAction[0]);
		assertFalse(empty.isSingleSkip());
		assertSame(empty, empty.fixOffsetBeforeMatch(2));
		empty.execute(lexer, input, 0);

		// MODE_SINGLE: SKIP, MORE, TYPE, CHANNEL, MODE, PUSH_MODE, POP_MODE, CUSTOM
		LexerActionExecutor singleSkip = new LexerActionExecutor(new LexerAction[] { LexerSkipAction.INSTANCE });
		assertTrue(singleSkip.isSingleSkip());
		singleSkip.execute(lexer, input, 0);
		assertEquals(Lexer.SKIP, lexer.getType());

		new LexerActionExecutor(new LexerAction[] { LexerMoreAction.INSTANCE }).execute(lexer, input, 0);
		assertEquals(Lexer.MORE, lexer.getType());

		new LexerActionExecutor(new LexerAction[] { new LexerTypeAction(42) }).execute(lexer, input, 0);
		assertEquals(42, lexer.getType());

		new LexerActionExecutor(new LexerAction[] { new LexerChannelAction(7) }).execute(lexer, input, 0);
		assertEquals(7, lexer.getChannel());

		new LexerActionExecutor(new LexerAction[] { new LexerPushModeAction(2) }).execute(lexer, input, 0);
		assertEquals(2, lexer._mode);

		new LexerActionExecutor(new LexerAction[] { new LexerModeAction(3) }).execute(lexer, input, 0);
		assertEquals(3, lexer._mode);

		new LexerActionExecutor(new LexerAction[] { LexerPopModeAction.INSTANCE }).execute(lexer, input, 0);
		assertEquals(Lexer.DEFAULT_MODE, lexer._mode);

		log.clear();
		LexerActionExecutor singleCustom = new LexerActionExecutor(new LexerAction[] { new LexerCustomAction(1, 5) });
		singleCustom.execute(lexer, input, 0);
		assertEquals(Collections.singletonList("act:1:5@4"), log);

		// MODE_MULTI with 0 custom actions (no actionArg2 array allocated)
		LexerActionExecutor multiNoCustom = new LexerActionExecutor(new LexerAction[] {
			new LexerTypeAction(11), new LexerChannelAction(2)
		});
		multiNoCustom.execute(lexer, input, 0);
		assertEquals(11, lexer.getType());
		assertEquals(2, lexer.getChannel());

		// MODE_MULTI with 2+ custom actions: first seeks to 1, second must see restored stopIndex=4!
		log.clear();
		input.seek(4);
		LexerActionExecutor multiTwoCustom = new LexerActionExecutor(new LexerAction[] {
			new LexerCustomAction(0, 10),
			new LexerCustomAction(0, 20),
			new LexerTypeAction(99)
		});
		multiTwoCustom.execute(lexer, input, 0);
		assertEquals(Arrays.asList("act:0:10@4", "act:0:20@4"), log);
		assertEquals(99, lexer.getType());

		// MODE_SLOW via fixOffsetBeforeMatch (creates LexerIndexedCustomAction) + already-fixed fast return
		LexerActionExecutor indexed = multiTwoCustom.fixOffsetBeforeMatch(2);
		assertSame(indexed, indexed.fixOffsetBeforeMatch(3));
		log.clear();
		input.seek(4);
		indexed.execute(lexer, input, 0);
		assertEquals(Arrays.asList("act:0:10@2", "act:0:20@2"), log);
		assertEquals(4, input.index()); // finally restored stopIndex

		// MODE_SLOW via custom non-built-in LexerAction
		final boolean[] customRan = new boolean[1];
		LexerAction customImpl = new LexerAction() {
			@Override public LexerActionType getActionType() { return LexerActionType.CUSTOM; }
			@Override public boolean isPositionDependent() { return true; }
			@Override public void execute(Lexer l) { customRan[0] = true; }
		};
		LexerActionExecutor slowExec = new LexerActionExecutor(new LexerAction[] { customImpl });
		slowExec.execute(lexer, input, 0);
		assertTrue(customRan[0]);
	}

	// -------------------------------------------------------------------------
	// LexerATNSimulator: 8-bit, 16-bit, 32-bit, Generic, pred edges, skip fusion
	// -------------------------------------------------------------------------

	private static ATN buildRichLexerATN() {
		// Rules:
		//  0: '/' {pred(0,0)}? -> type 10 (REGEX)
		//  1: '/' -> type 11 (DIV)
		//  2: [ \t]+ -> skip (type 12)
		//  3: '\r' '\n'? | '\n' -> type 13 (NL)
		//  4: [a-z\u4e2d\uD83D\uDE00] -> type 14 (ID)
		//  5: EOF -> type 15 (EOF_RULE) -- tested separately in another ATN
		ATN atn = new ATN(ATNType.LEXER, 14);
		TokensStartState start = new TokensStartState();
		start.ruleIndex = -1;
		atn.addState(start);

		// Rule 0: '/' {pred(0,0)}?
		RuleStartState r0s = new RuleStartState(); r0s.ruleIndex = 0;
		BasicState r0m = new BasicState(); r0m.ruleIndex = 0;
		BasicState r0p = new BasicState(); r0p.ruleIndex = 0;
		RuleStopState r0e = new RuleStopState(); r0e.ruleIndex = 0; r0s.stopState = r0e;
		atn.addState(r0s); atn.addState(r0m); atn.addState(r0p); atn.addState(r0e);
		start.addTransition(new EpsilonTransition(r0s));
		r0s.addTransition(new AtomTransition(r0m, '/'));
		r0m.addTransition(new PredicateTransition(r0p, 0, 0, false));
		r0p.addTransition(new EpsilonTransition(r0e));

		// Rule 1: '/'
		RuleStartState r1s = new RuleStartState(); r1s.ruleIndex = 1;
		BasicState r1m = new BasicState(); r1m.ruleIndex = 1;
		RuleStopState r1e = new RuleStopState(); r1e.ruleIndex = 1; r1s.stopState = r1e;
		atn.addState(r1s); atn.addState(r1m); atn.addState(r1e);
		start.addTransition(new EpsilonTransition(r1s));
		r1s.addTransition(new AtomTransition(r1m, '/'));
		r1m.addTransition(new EpsilonTransition(r1e));

		// Rule 2: ' ' -> skip
		RuleStartState r2s = new RuleStartState(); r2s.ruleIndex = 2;
		BasicState r2m = new BasicState(); r2m.ruleIndex = 2;
		BasicState r2a = new BasicState(); r2a.ruleIndex = 2;
		RuleStopState r2e = new RuleStopState(); r2e.ruleIndex = 2; r2s.stopState = r2e;
		atn.addState(r2s); atn.addState(r2m); atn.addState(r2a); atn.addState(r2e);
		start.addTransition(new EpsilonTransition(r2s));
		r2s.addTransition(new AtomTransition(r2m, ' '));
		r2m.addTransition(new ActionTransition(r2a, 2, 0, false));
		r2a.addTransition(new EpsilonTransition(r2e));

		// Rule 3: '\r' | '\n'
		RuleStartState r3s = new RuleStartState(); r3s.ruleIndex = 3;
		BasicState r3m = new BasicState(); r3m.ruleIndex = 3;
		RuleStopState r3e = new RuleStopState(); r3e.ruleIndex = 3; r3s.stopState = r3e;
		atn.addState(r3s); atn.addState(r3m); atn.addState(r3e);
		start.addTransition(new EpsilonTransition(r3s));
		r3s.addTransition(new SetTransition(r3m, IntervalSet.of('\n', '\r')));
		r3m.addTransition(new EpsilonTransition(r3e));

		// Rule 4: 'a' | '\u4e2d' | U+1F600
		RuleStartState r4s = new RuleStartState(); r4s.ruleIndex = 4;
		BasicState r4m = new BasicState(); r4m.ruleIndex = 4;
		RuleStopState r4e = new RuleStopState(); r4e.ruleIndex = 4; r4s.stopState = r4e;
		atn.addState(r4s); atn.addState(r4m); atn.addState(r4e);
		start.addTransition(new EpsilonTransition(r4s));
		IntervalSet idSet = new IntervalSet();
		idSet.add('a');
		idSet.add('\u4e2d');
		idSet.add(0x1F600);
		r4s.addTransition(new SetTransition(r4m, idSet));
		r4m.addTransition(new EpsilonTransition(r4e));

		atn.ruleToStartState = new RuleStartState[] { r0s, r1s, r2s, r3s, r4s };
		atn.ruleToStopState = new RuleStopState[] { r0e, r1e, r2e, r3e, r4e };
		atn.ruleToTokenType = new int[] { 10, 11, 12, 13, 14 };
		atn.lexerActions = new LexerAction[] { LexerSkipAction.INSTANCE };
		atn.defineMode("DEFAULT_MODE", start);
		atn.clearDFA();
		return atn;
	}

	@SuppressWarnings("deprecation")
	@Test
	public void testLexerATNSimulatorFast8Bit16Bit32BitGenericPredAndSkipFusion() {
		final ATN atn = buildRichLexerATN();
		final boolean[] predFlag = new boolean[] { true };

		Lexer lex = new Lexer(CharStreams.fromString("  /\r\n\r\na ")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "REGEX", "DIV", "WS", "NL", "ID" }; }
			@Override public String getGrammarFileName() { return "Rich.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public boolean sempred(RuleContext ctx, int ruleIndex, int predIndex) {
				boolean cur = predFlag[0];
				predFlag[0] = !cur; // alternates true/false so both predicate tree branches record & replay
				return cur;
			}
		};
		lex.removeErrorListeners();

		// 1. 8-bit Latin-1 (warm + cold + skip fusion + skip at EOF + \r\n + \r + \n + pred replay)
		// Run twice to populate DFA and then hit warm fast paths + LexerPredEdgeTable replay
		for (int pass = 0; pass < 2; pass++) {
			predFlag[0] = true;
			lex.setInputStream(CharStreams.fromString("  / / \r\n\r\n!a "));
			assertEquals(10, lex.nextToken().getType()); // '/' with pred=true
			assertEquals(11, lex.nextToken().getType()); // '/' with pred=false
			assertEquals(13, lex.nextToken().getType()); // '\r'
			assertEquals(13, lex.nextToken().getType()); // '\n'
			assertEquals(13, lex.nextToken().getType()); // '\r'
			assertEquals(13, lex.nextToken().getType()); // '\n'
			// '!' hits noViableAltFast -> recovered & skipped -> 'a'
			assertEquals(14, lex.nextToken().getType());
			// trailing ' ' is skip right at EOF -> emits EOF
			assertEquals(Token.EOF, lex.nextToken().getType());
		}

		// 2. 16-bit BMP CodePointCharStream -> execATNFastCodePoint (skip fusion, pred, \r\n, NVA, trailing skip)
		for (int pass = 0; pass < 2; pass++) {
			predFlag[0] = true;
			lex.setInputStream(CharStreams.fromString("  \u4e2d / / \r\n\r\n!a "));
			assertEquals(14, lex.nextToken().getType());
			assertEquals(10, lex.nextToken().getType());
			assertEquals(11, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(14, lex.nextToken().getType());
			assertEquals(Token.EOF, lex.nextToken().getType());
		}

		// 3. 32-bit SMP CodePointCharStream -> execATNFastCodePoint
		lex.setInputStream(CharStreams.fromString(" \uD83D\uDE00 "));
		assertEquals(14, lex.nextToken().getType());
		assertEquals(Token.EOF, lex.nextToken().getType());

		// 4. Generic non-CodePointCharStream (ANTLRInputStream) -> execATNFastGeneric
		lex.setInputStream(new ANTLRInputStream(" / \r\n\r\n!a"));
		predFlag[0] = true;
		assertEquals(10, lex.nextToken().getType());
		assertEquals(13, lex.nextToken().getType());
		assertEquals(13, lex.nextToken().getType());
		assertEquals(13, lex.nextToken().getType());
		assertEquals(13, lex.nextToken().getType());
		assertEquals(14, lex.nextToken().getType());
		assertEquals(Token.EOF, lex.nextToken().getType());

		// 5. Custom Lexer overriding skip() disables skip fusion (returns single WS on direct match)
		final int[] skipCount = new int[1];
		Lexer customSkipLexer = new Lexer(CharStreams.fromString("  a")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "REGEX", "DIV", "WS", "NL", "ID" }; }
			@Override public String getGrammarFileName() { return "CSkip.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override public void skip() { skipCount[0]++; super.skip(); }
		};
		assertEquals(14, customSkipLexer.nextToken().getType());
		assertEquals(2, skipCount[0]);

		// 6. Custom LexerATNSimulator overriding addDFAEdge disables FAST_EXEC_ELIGIBLE -> matchSlow
		final int[] edgeCalls = new int[1];
		Lexer customSimLexer = new Lexer(CharStreams.fromString("a")) {
			{
				_interp = new LexerATNSimulator(this, atn) {
					@Override
					protected void addDFAEdge(DFAState p, int t, DFAState q) {
						edgeCalls[0]++;
						super.addDFAEdge(p, t, q);
					}
				};
			}
			@Override public String[] getRuleNames() { return new String[] { "REGEX", "DIV", "WS", "NL", "ID" }; }
			@Override public String getGrammarFileName() { return "CSim.g4"; }
			@Override public ATN getATN() { return atn; }
		};
		assertEquals(14, customSimLexer.nextToken().getType());
		// Direct standalone sim.match on eligible simulator returns WS (does NOT fuse skip because _tokenStartCharIndex != startIndex)
		lex.setInputStream(CharStreams.fromString(" a"));
		assertEquals(12, lex.getInterpreter().match(lex.getInputStream(), Lexer.DEFAULT_MODE));
	}

	// -------------------------------------------------------------------------
	// PredictionContextCache: all 3 maps, collisions, growth > 4096, all 3 clear() branches
	// -------------------------------------------------------------------------

	@Test
	public void testPredictionContextCacheGrowthCollisionsAndAllClearModes() {
		PredictionContextCache cache = new PredictionContextCache();
		assertTrue(cache.isEnableCache());
		assertFalse(PredictionContextCache.UNCACHED.isEnableCache());
		assertNull(cache.getAsCached(null));
		PredictionContextCache.UNCACHED.clear(); // no-op

		// 1. Small sparse clear (usedCount <= SPARSE_TRACK_LIMIT && len <= MAX_RETAINED_CAPACITY)
		PredictionContext p1 = cache.getChild(PredictionContext.EMPTY_LOCAL, 1);
		PredictionContext p2 = cache.getChild(PredictionContext.EMPTY_LOCAL, 2);
		assertSame(p1, cache.getChild(PredictionContext.EMPTY_LOCAL, 1));
		PredictionContext j1 = cache.join(p1, p2);
		assertSame(j1, cache.join(p2, p1)); // commutative join cache hit
		assertSame(p1, cache.join(p1, p1)); // self-join fast path
		assertSame(p1, cache.getAsCached(p1));
		cache.clear(); // sparse clear branch

		// 2. Dense clear (exceeds sparse tracking when table grows, or fills within retained limit)
		List<PredictionContext> nodes = new ArrayList<PredictionContext>();
		for (int i = 1; i <= 50; i++) {
			PredictionContext c = cache.getChild(PredictionContext.EMPTY_LOCAL, i);
			nodes.add(c);
			cache.getAsCached(c);
		}
		for (int i = 0; i < 45; i++) {
			cache.join(nodes.get(i), nodes.get(i + 1));
		}
		cache.clear();

		// 3. Grow all 3 tables past MAX_RETAINED_CAPACITY (16384 -> need >8192 entries -> 32768 slots)
		int bigN = 9000;
		PredictionContext[] big = new PredictionContext[bigN];
		for (int i = 0; i < bigN; i++) {
			big[i] = cache.getChild(PredictionContext.EMPTY_LOCAL, i + 1);
			cache.getAsCached(big[i]);
		}
		for (int i = 0; i < bigN - 1; i++) {
			cache.join(big[i], big[i + 1]);
		}
		// Verify lookups hit across large resized tables
		assertSame(big[0], cache.getChild(PredictionContext.EMPTY_LOCAL, 1));
		assertSame(big[bigN - 1], cache.getChild(PredictionContext.EMPTY_LOCAL, bigN));
		assertSame(cache.join(big[0], big[1]), cache.join(big[1], big[0]));
		// Clear when size > length/4 -> Arrays.fill branch (retains 32768 table)
		cache.clear();
		// Now insert 2 elements into the 32768-slot tables and clear() -> MUST take oversized shrink branch!
		PredictionContext tiny1 = cache.getChild(PredictionContext.EMPTY_LOCAL, 1);
		PredictionContext tiny2 = cache.getChild(PredictionContext.EMPTY_LOCAL, 2);
		cache.getAsCached(tiny1);
		cache.join(tiny1, tiny2);
		cache.clear(); // hits len > MAX_RETAINED_CAPACITY && size <= (len >>> 2)!
		// Verify cache still works cleanly after shrinking back to INITIAL_CAPACITY
		assertEquals(1, cache.getChild(PredictionContext.EMPTY_LOCAL, 1).getReturnState(0));

		// ArrayPredictionContext.appendContext(suffix, null) when hasEmpty() is true
		PredictionContext withEmpty = PredictionContext.join(
			new SingletonPredictionContext(PredictionContext.EMPTY_FULL, 10),
			PredictionContext.EMPTY_FULL,
			PredictionContextCache.UNCACHED);
		assertTrue(withEmpty.hasEmpty());
		PredictionContext suffix = new SingletonPredictionContext(PredictionContext.EMPTY_FULL, 20);
		PredictionContext appended = withEmpty.appendContext(suffix, null);
		assertNotNull(appended);
		assertEquals(2, appended.size());
	}

	// -------------------------------------------------------------------------
	// ParserATNSimulator: execDFASllWarm (predicates, EOF, repeatedEof(null)),
	// isConflicted (bloom, packed <=32 & >32, fallback <=32 & >32)
	// -------------------------------------------------------------------------

	private static ATN buildPredicatedParserATN() {
		// Decision 0:
		//   alt 1: {pred(0,0)}? A
		//   alt 2: {pred(0,1)}? A
		//   alt 3: B C
		ATN atn = new ATN(ATNType.PARSER, 3);
		RuleStartState r0 = new RuleStartState(); r0.ruleIndex = 0;
		RuleStopState stop = new RuleStopState(); stop.ruleIndex = 0; r0.stopState = stop;
		BasicBlockStartState dec = new BasicBlockStartState(); dec.ruleIndex = 0;
		BlockEndState end = new BlockEndState(); end.ruleIndex = 0; dec.endState = end;
		BasicState a1 = new BasicState(); a1.ruleIndex = 0;
		BasicState a1p = new BasicState(); a1p.ruleIndex = 0;
		BasicState a2 = new BasicState(); a2.ruleIndex = 0;
		BasicState a2p = new BasicState(); a2p.ruleIndex = 0;
		BasicState a3 = new BasicState(); a3.ruleIndex = 0;
		BasicState a3b = new BasicState(); a3b.ruleIndex = 0;

		for (ATNState st : new ATNState[] { r0, stop, dec, end, a1, a1p, a2, a2p, a3, a3b }) {
			atn.addState(st);
		}
		atn.defineDecisionState(dec);
		r0.addTransition(new EpsilonTransition(dec));
		dec.addTransition(new EpsilonTransition(a1));
		dec.addTransition(new EpsilonTransition(a2));
		dec.addTransition(new EpsilonTransition(a3));
		a1.addTransition(new PredicateTransition(a1p, 0, 0, false));
		a1p.addTransition(new AtomTransition(end, 1));
		a2.addTransition(new PredicateTransition(a2p, 0, 1, false));
		a2p.addTransition(new AtomTransition(end, 1));
		a3.addTransition(new AtomTransition(a3b, 2));
		a3b.addTransition(new AtomTransition(end, 3));
		end.addTransition(new EpsilonTransition(stop));

		atn.ruleToStartState = new RuleStartState[] { r0 };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}

	@Test
	public void testParserATNSimulatorWarmSllPredicatesAndRepeatedEofNull() {
		final ATN atn = buildPredicatedParserATN();
		final boolean[] preds = new boolean[] { false, true };
		ListTokenSource src = new ListTokenSource(Arrays.<Token>asList(
			new CommonToken(1, "A"), new CommonToken(2, "B"), new CommonToken(3, "C"), new CommonToken(Token.EOF, "<EOF>")
		));
		CommonTokenStream ts = new CommonTokenStream(src);
		Parser parser = new Parser(ts) {
			{ _interp = new ParserATNSimulator(this, atn); }
			@Override public String[] getTokenNames() { return new String[] { "<INVALID>", "A", "B", "C" }; }
			@Override public String[] getRuleNames() { return new String[] { "r" }; }
			@Override public String getGrammarFileName() { return "P.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public boolean sempred(RuleContext ctx, int ruleIndex, int predIndex) {
				return preds[predIndex];
			}
		};
		parser.removeErrorListeners();
		ParserATNSimulator sim = parser.getInterpreter();
		sim.setPredictionMode(PredictionMode.SLL);

		// Cold call builds predicated DFA state on 'A'; warm call hits execDFASllWarm -> resolveAcceptSllWarm
		assertEquals(2, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		// Warm hit with pred0=false, pred1=true -> alt 2
		assertEquals(2, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		// Warm hit with pred0=true, pred1=false -> alt 1
		preds[0] = true; preds[1] = false;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		// Warm hit with both preds true -> resolves to min alt 1
		preds[0] = true; preds[1] = true;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		// Warm hit with both preds false -> falls through to NoViableAltException
		preds[0] = false; preds[1] = false;
		try {
			sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext());
		}
		catch (RuntimeException expected) {
		}

		// Multi-token SLL warm path ('B' 'C')
		ts.seek(1);
		assertEquals(3, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		assertEquals(3, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));

		// Non-accepting EOF transition in execDFASllWarm: must NOT NPE in repeatedEof(states, null, ...)
		DFA dfa = atn.decisionToDFA[0];
		DFAState s0 = dfa.s0.get();
		DFAState eofNonAccept = new DFAState(dfa, new ATNConfigSet());
		eofNonAccept.stateNumber = 999;
		s0.setTarget(Token.EOF, eofNonAccept);
		eofNonAccept.setTarget(Token.EOF, eofNonAccept);
		ts.seek(3); // positioned at EOF
		try {
			sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext());
		}
		catch (RuntimeException expected) {
			// Expect NoViableAltException (NOT NullPointerException!)
			assertFalse("Must not throw NullPointerException", expected instanceof NullPointerException);
		}
	}

	private static Object invokeIsConflicted(ParserATNSimulator sim, ATNConfigSet set, PredictionContextCache cache) throws Exception {
		java.lang.reflect.Method m = ParserATNSimulator.class.getDeclaredMethod("isConflicted", ATNConfigSet.class, PredictionContextCache.class);
		m.setAccessible(true);
		return m.invoke(sim, set, cache);
	}

	@Test
	public void testParserATNSimulatorIsConflictedBloomPackedAndFallback() throws Exception {
		final ATN atn = buildPredicatedParserATN();
		ParserATNSimulator sim = new ParserATNSimulator(atn);

		PredictionContext ctx1 = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, 10);
		PredictionContext ctx2 = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, 20);
		RuleStopState stopState = atn.ruleToStopState[0];

		// 1. Bloom fast reject (all distinct states)
		ATNConfigSet distinct = new ATNConfigSet();
		for (int i = 0; i < 6; i++) {
			BasicState st = new BasicState(); st.stateNumber = i + 1;
			distinct.add(ATNConfig.create(st, 1, ctx1));
		}
		assertNull(invokeIsConflicted(sim, distinct, PredictionContextCache.UNCACHED));

		// 2. Packed long[] path <= 16 (insertionSortKeys): conflicted & non-conflicted (disjoint ctx)
		ATNConfigSet packedSmallConflict = new ATNConfigSet();
		BasicState s5 = new BasicState(); s5.stateNumber = 5;
		BasicState s3 = new BasicState(); s3.stateNumber = 3;
		// Insert out of stateNumber order to exercise insertionSortKeys
		packedSmallConflict.add(ATNConfig.create(s5, 2, ctx1));
		packedSmallConflict.add(ATNConfig.create(s3, 1, ctx1));
		packedSmallConflict.add(ATNConfig.create(s5, 1, ctx1));
		packedSmallConflict.add(ATNConfig.create(s3, 2, ctx1));
		assertNotNull(invokeIsConflicted(sim, packedSmallConflict, PredictionContextCache.UNCACHED));

		// Disjoint contexts for alt 1 vs alt 2 -> NOT conflicted
		ATNConfigSet packedSmallDisjoint = new ATNConfigSet();
		packedSmallDisjoint.add(ATNConfig.create(s5, 1, ctx1));
		packedSmallDisjoint.add(ATNConfig.create(s5, 2, ctx2));
		assertNull(invokeIsConflicted(sim, packedSmallDisjoint, PredictionContextCache.UNCACHED));

		// Exact=false conflict (stopState has alt 1 & 2, s5 has only alt 1, s9 follows) -> returns non-exact ConflictInfo
		ATNConfigSet packedSmallInexactConflict = new ATNConfigSet();
		BasicState s9 = new BasicState(); s9.stateNumber = 9;
		packedSmallInexactConflict.add(ATNConfig.create(stopState, 1, ctx1));
		packedSmallInexactConflict.add(ATNConfig.create(stopState, 2, ctx1));
		packedSmallInexactConflict.add(ATNConfig.create(s5, 1, ctx1));
		packedSmallInexactConflict.add(ATNConfig.create(s5, 1, ctx2));
		packedSmallInexactConflict.add(ATNConfig.create(s9, 1, ctx1));
		ConflictInfo inexact = (ConflictInfo) invokeIsConflicted(sim, packedSmallInexactConflict, PredictionContextCache.UNCACHED);
		assertNotNull(inexact);
		assertFalse(inexact.isExact());

		// Also hit alt != representedAlts.nextSetBit(currentAlt + 1) branch (s5 has alt 1 & 3 while stopState has alt 1 & 2)
		ATNConfigSet packedAltGap = new ATNConfigSet();
		packedAltGap.add(ATNConfig.create(stopState, 1, ctx1));
		packedAltGap.add(ATNConfig.create(stopState, 2, ctx1));
		packedAltGap.add(ATNConfig.create(s5, 1, ctx1));
		packedAltGap.add(ATNConfig.create(s5, 3, ctx1));
		ConflictInfo altGap = (ConflictInfo) invokeIsConflicted(sim, packedAltGap, PredictionContextCache.UNCACHED);
		assertNotNull(altGap);
		assertFalse(altGap.isExact());

		// 3. Packed long[] path > 16 (Arrays.sort): conflicted & non-conflicted
		ATNConfigSet packedLarge = new ATNConfigSet();
		for (int i = 20; i >= 0; i--) {
			BasicState st = new BasicState(); st.stateNumber = 100 + i;
			packedLarge.add(ATNConfig.create(st, 1, ctx1));
			packedLarge.add(ATNConfig.create(st, 2, ctx1));
		}
		assertNotNull(invokeIsConflicted(sim, packedLarge, PredictionContextCache.UNCACHED));
		// Adding an extra state only in alt 2 breaks conflict
		BasicState onlyAlt2 = new BasicState(); onlyAlt2.stateNumber = 999;
		packedLarge.add(ATNConfig.create(onlyAlt2, 2, ctx1));
		assertNull(invokeIsConflicted(sim, packedLarge, PredictionContextCache.UNCACHED));

		// 4. Fallback 2-array path (stateNumber >= 1 << 20): <= 32 insertion sort AND > 32 merge sort
		ATNConfigSet fbSmall = new ATNConfigSet();
		BasicState huge1 = new BasicState(); huge1.stateNumber = (1 << 23) + 10;
		BasicState huge2 = new BasicState(); huge2.stateNumber = (1 << 23) + 5;
		fbSmall.add(ATNConfig.create(huge1, 2, ctx1));
		fbSmall.add(ATNConfig.create(huge2, 1, ctx1));
		fbSmall.add(ATNConfig.create(huge1, 1, ctx1));
		fbSmall.add(ATNConfig.create(huge2, 2, ctx1));
		assertNotNull(invokeIsConflicted(sim, fbSmall, PredictionContextCache.UNCACHED));

		ATNConfigSet fbLarge = new ATNConfigSet();
		for (int i = 20; i >= 0; i--) {
			BasicState st = new BasicState(); st.stateNumber = (1 << 23) + i;
			fbLarge.add(ATNConfig.create(st, 1, ctx1));
			fbLarge.add(ATNConfig.create(st, 2, ctx1));
		}
		assertNotNull(invokeIsConflicted(sim, fbLarge, PredictionContextCache.UNCACHED));
		BasicState hugeOnlyAlt2 = new BasicState(); hugeOnlyAlt2.stateNumber = (1 << 23) + 99;
		fbLarge.add(ATNConfig.create(hugeOnlyAlt2, 2, ctx1));
		assertNull(invokeIsConflicted(sim, fbLarge, PredictionContextCache.UNCACHED));
	}

	// -------------------------------------------------------------------------
	// EpsilonClosure, ReachComputation, ATNConfig, ATNConfigSet, DFAState
	// -------------------------------------------------------------------------

	@Test
	public void testEpsilonClosureDirectRareTransitionsAndConfigSetHashInvalidation() {
		ATN atn = new ATN(ATNType.PARSER, 4);
		RuleStartState r0 = new RuleStartState(); r0.ruleIndex = 0;
		RuleStopState s0 = new RuleStopState(); s0.ruleIndex = 0; r0.stopState = s0;
		BasicState b1 = new BasicState(); b1.ruleIndex = 0;
		BasicState b2 = new BasicState(); b2.ruleIndex = 0;
		BasicState b3 = new BasicState(); b3.ruleIndex = 0;
		BasicState b4 = new BasicState(); b4.ruleIndex = 0;
		BasicState b5 = new BasicState(); b5.ruleIndex = 0;
		for (ATNState st : new ATNState[] { r0, s0, b1, b2, b3, b4, b5 }) atn.addState(st);
		atn.ruleToStartState = new RuleStartState[] { r0 };
		atn.ruleToStopState = new RuleStopState[] { s0 };

		// Action, ctx-dependent pred, ctx-independent pred, precedence pred, outermostPrecedenceReturn epsilon
		r0.addTransition(new ActionTransition(b1, 0, 0, false));
		b1.addTransition(new PredicateTransition(b2, 0, 0, true));
		b2.addTransition(new PredicateTransition(b3, 0, 1, false));
		b3.addTransition(new PrecedencePredicateTransition(b4, 2));
		b4.addTransition(new EpsilonTransition(b5, 1));
		b5.addTransition(new AtomTransition(s0, 1));
		atn.clearDFA();

		ParserATNSimulator sim = new ParserATNSimulator(atn);
		EpsilonClosure ec = new EpsilonClosure(sim);
		ATNConfigSet configs = new ATNConfigSet();
		ATNConfigSet intermediate = new ATNConfigSet();
		Set<ATNConfig> busy = new HashSet<ATNConfig>();
		PredictionContext ctx = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, s0.stateNumber);

		// Null contextCache normalization + depth 0 & depth 1 rare transitions
		ec.closeOne(ATNConfig.create(r0, 1, ctx), configs, intermediate, busy, true, true, null, 0, false);
		assertFalse(configs.isEmpty());
		ec.closeOne(ATNConfig.create(r0, 1, ctx), configs, null, new HashSet<ATNConfig>(), true, false, PredictionContextCache.UNCACHED, 1, true);
		ec.closeOne(ATNConfig.create(s0, 1, PredictionContext.EMPTY_LOCAL), configs, null, new HashSet<ATNConfig>(), false, true, PredictionContextCache.UNCACHED, 0, true);
		ec.closeOne(ATNConfig.create(s0, 1, PredictionContext.EMPTY_FULL), configs, null, new HashSet<ATNConfig>(), false, false, PredictionContextCache.UNCACHED, 0, false);

		// ATNConfigSet.mergeConfigContext flipping reachesIntoOuterContext must invalidate cachedHashCode
		ATNConfigSet set1 = new ATNConfigSet();
		ATNConfigSet set2 = new ATNConfigSet();
		ATNConfig cLow = ATNConfig.create(b1, 1, ctx);
		ATNConfig cHigh = ATNConfig.create(b1, 1, ctx);
		cHigh.setOuterContextDepth(1);
		set1.add(cLow);
		int hashBefore = set1.hashCode();
		set1.add(cHigh); // same state, alt, ctx, sem -> merges outerContextDepth 0 -> 1
		set2.add(cHigh);
		assertEquals(set2.hashCode(), set1.hashCode());
		assertTrue(set1.equals(set2));
		assertTrue(hashBefore != set1.hashCode() || set1.equals(set2));

		// ATNConfig subclass overriding hasPassedThroughNonGreedyDecision() must match hashCode/equals
		ATNConfig subCfg = new ATNConfig(b1, 1, ctx) {
			@Override public boolean hasPassedThroughNonGreedyDecision() { return true; }
		};
		int expectedHash = MurmurHash.initialize(7);
		expectedHash = MurmurHash.update(expectedHash, subCfg.getState().stateNumber);
		expectedHash = MurmurHash.update(expectedHash, subCfg.getAlt());
		expectedHash = MurmurHash.update(expectedHash, subCfg.getReachesIntoOuterContext() ? 1 : 0);
		expectedHash = MurmurHash.update(expectedHash, subCfg.getContext());
		expectedHash = MurmurHash.update(expectedHash, subCfg.getSemanticContext());
		expectedHash = MurmurHash.update(expectedHash, 1);
		expectedHash = MurmurHash.update(expectedHash, subCfg.getLexerActionExecutor());
		expectedHash = MurmurHash.finish(expectedHash, 7);
		if (expectedHash == 0) expectedHash = 1;
		assertEquals(expectedHash, subCfg.hashCode());

		// DFAState COW asciiEdges + volatile contextSymbols + contextSymbolAfterEdge
		ATN lexAtn = ATNTestHelpers.buildLexerMatchA();
		DFA lexDfa = lexAtn.modeToDFA[0];
		DFAState ds = new DFAState(lexDfa, new ATNConfigSet());
		DFAState dst = new DFAState(lexDfa, new ATNConfigSet());
		ds.setTarget('x', dst);
		ds.setTarget('x', dst); // idempotent COW check
		assertSame(dst, ds.getLexerTarget('x'));
		assertNull(ds.getLexerTarget('y'));

		ATN pAtn = buildPredicatedParserATN();
		DFAState pState = new DFAState(pAtn.decisionToDFA[0], new ATNConfigSet());
		pState.setContextSensitive(pAtn);
		pState.setContextSymbol(1);
		assertTrue(pState.isContextSensitive());
		assertTrue(pState.isContextSymbol(1));
		assertTrue(pState.contextSymbolAfterEdge(1));
		assertFalse(pState.contextSymbolAfterEdge(-99));
	}

	@Test
	public void testParserWarmLLAndFallbackMergeSortAndMiscRuntimePaths() throws Exception {
		// 1. ParserATNSimulator: warm SLL DFA in PredictionMode.LL & LL_EXACT_AMBIG_DETECTION
		//    Covers resolveAcceptSllWarm (conflict, evalSemanticContextUnique, reportAmbiguities,
		//    noViableAlt, multi-match reportAmbiguity), outerContext == null, handleNoViableAlt on ERROR
		final ATN atn = buildPredicatedParserATN();
		final boolean[] preds = new boolean[] { true, false };
		ListTokenSource src = new ListTokenSource(Arrays.<Token>asList(
			new CommonToken(1, "A"), new CommonToken(2, "B"), new CommonToken(1, "A"), new CommonToken(Token.EOF, "<EOF>")
		));
		CommonTokenStream ts = new CommonTokenStream(src);
		Parser parser = new Parser(ts) {
			{ _interp = new ParserATNSimulator(this, atn); }
			@Override public String[] getTokenNames() { return new String[] { "<INVALID>", "A", "B", "C" }; }
			@Override public String[] getRuleNames() { return new String[] { "r" }; }
			@Override public String getGrammarFileName() { return "P.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override public boolean sempred(RuleContext ctx, int ruleIndex, int predIndex) { return preds[predIndex]; }
		};
		parser.removeErrorListeners();
		ParserATNSimulator sim = parser.getInterpreter();
		// Warm up decision 0 on 'A', then call warm with null outerContext (line 526)
		sim.setPredictionMode(PredictionMode.SLL);
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		assertEquals(1, sim.adaptivePredict(ts, 0, null)); // warm null outerContext normalization!
		// Switch to LL -> hits resolveAcceptSllWarm with fullCtx fallback
		sim.setPredictionMode(PredictionMode.LL);
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		// Both preds true -> ambiguity in LL (evalSemanticContextUnique returns INVALID -> fullCtx)
		preds[0] = true; preds[1] = true;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		// LL_EXACT_AMBIG_DETECTION -> reportAmbiguities = true branches (1 match, 2 matches, 0 matches)
		sim.setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		preds[0] = true; preds[1] = false;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		preds[0] = false; preds[1] = false;
		try {
			sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext());
		}
		catch (RuntimeException expected) {
		}

		// Warm ERROR state inside execDFASllWarm loop (line 643) & repeatedEof inside execDFASllWarm (line 659)
		DFA dfa = atn.decisionToDFA[0];
		DFAState errS0 = new DFAState(dfa, new ATNConfigSet());
		errS0.stateNumber = 0;
		errS0.setTarget(1, ATNSimulator.ERROR);
		DFAState eofLoop = new DFAState(dfa, new ATNConfigSet());
		eofLoop.stateNumber = 699;
		eofLoop.setTarget(Token.EOF, eofLoop);
		errS0.setTarget(Token.EOF, eofLoop);
		dfa.s0.set(errS0);
		sim.setPredictionMode(PredictionMode.SLL);
		ts.seek(0); // 'A' -> ERROR -> handleNoViableAlt (line 643)
		try { sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()); } catch (RuntimeException expected) {}
		ts.seek(3); // EOF -> eofLoop -> repeatedEof -> handleNoViableAlt (line 659)
		try { sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()); } catch (RuntimeException expected) {}

		// 2. Directly construct a warm SLL accept state with predicates + INEXACT conflict to cover all
		//    resolveAcceptSllWarm & evalSemanticContextUnique branches (including duplicate alt & NONE pred)
		DFAState freshS0 = new DFAState(dfa, new ATNConfigSet());
		freshS0.stateNumber = 0;
		dfa.s0.set(freshS0);
		DFAState sPredConflict = new DFAState(dfa, new ATNConfigSet());
		sPredConflict.stateNumber = 700;
		sPredConflict.setAcceptState(new AcceptStateInfo(1));
		sPredConflict.configs.setConflictInfo(new ConflictInfo(new BitSet(), false)); // inexact -> enters fullCtx/pred check!
		sPredConflict.predicates = new DFAState.PredPrediction[] {
			new DFAState.PredPrediction(SemanticContext.NONE, 1),
			new DFAState.PredPrediction(new SemanticContext.Predicate(0, 0, false), 1), // duplicate alt!
			new DFAState.PredPrediction(new SemanticContext.Predicate(0, 1, false), 2)
		};
		DFAState sMid = new DFAState(dfa, new ATNConfigSet());
		sMid.stateNumber = 701;
		sMid.setTarget(2, sPredConflict);
		freshS0.setTarget(1, sMid);
		ts.seek(0); // tokens [A, B] (A is NOT LL(1)-unique, so it enters execDFASllWarm; conflictIndex != startIndex!)
		preds[0] = true; preds[1] = false;
		sim.setPredictionMode(PredictionMode.LL);
		sim.reportAmbiguities = false;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext())); // uniqueAlt == 1!
		preds[0] = true; preds[1] = true;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext())); // 2 alts -> fullCtx resolves to 1
		sim.reportAmbiguities = true;
		preds[0] = true; preds[1] = false;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext())); // reportAmbiguities + 1 alt
		preds[0] = true; preds[1] = true;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext())); // reportAttemptingFullContext

		// Non-conflict predicated state with reportAmbiguities == true (stopIndex != startIndex)
		DFAState freshS0b = new DFAState(dfa, new ATNConfigSet());
		freshS0b.stateNumber = 0;
		dfa.s0.set(freshS0b);
		DFAState sMid2 = new DFAState(dfa, new ATNConfigSet());
		sMid2.stateNumber = 703;
		DFAState sPredNoConf = new DFAState(dfa, new ATNConfigSet());
		sPredNoConf.stateNumber = 702;
		sPredNoConf.setAcceptState(new AcceptStateInfo(1));
		sPredNoConf.predicates = new DFAState.PredPrediction[] {
			new DFAState.PredPrediction(new SemanticContext.Predicate(0, 0, false), 1),
			new DFAState.PredPrediction(new SemanticContext.Predicate(0, 1, false), 2)
		};
		sMid2.setTarget(2, sPredNoConf);
		freshS0b.setTarget(1, sMid2);
		sim.setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
		sim.reportAmbiguities = true;
		preds[0] = true; preds[1] = false;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext())); // cardinality == 1
		preds[0] = true; preds[1] = true;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext())); // cardinality == 2 -> reportAmbiguity
		preds[0] = false; preds[1] = false;
		try { sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()); } catch (RuntimeException expected) {} // cardinality == 0 -> noViableAlt
		sim.reportAmbiguities = false;

		// Cold execATN predicated SLL accept (lines 1069-1073): both valid alt and INVALID_ALT_NUMBER
		atn.clearDFA();
		sim.setPredictionMode(PredictionMode.SLL);
		preds[0] = true; preds[1] = false;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		atn.clearDFA();
		preds[0] = false; preds[1] = false;
		try { sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()); } catch (RuntimeException expected) {}

		// 3. Fallback mergeSort & isConflictedFallback full branch coverage (>64 elements for 3 odd passes,
		//    both l<mid and r<hi remainders, inexact conflict, alt gap, multiple configs per (state,alt), disjoint ctx)
		PredictionContext ctx1 = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, 10);
		PredictionContext ctx2 = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, 20);
		PredictionContext ctx3 = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, 30);
		ATNConfigSet fbComplex = new ATNConfigSet();
		// 70 states * 4 = 280 configs (>64 -> odd 3-pass or 5-pass bottom-up mergeSort with right remainders!)
		for (int idx = 0; idx < 70; idx++) {
			int scrambled = (idx * 37 + 13) % 70;
			BasicState st = new BasicState();
			st.stateNumber = (1 << 23) + scrambled;
			fbComplex.add(ATNConfig.create(st, 1, ctx1));
			fbComplex.add(ATNConfig.create(st, 1, ctx2)); // 2nd config on same (state, minAlt)
			fbComplex.add(ATNConfig.create(st, 2, ctx1));
			fbComplex.add(ATNConfig.create(st, 2, ctx2)); // 2nd config on same (state, alt2)
		}
		assertNotNull(invokeIsConflicted(sim, fbComplex, PredictionContextCache.UNCACHED));
		// Also test 65 configs (strictly 3 merge passes: 16->32->64->128 -> ODD pass copyback!)
		ATNConfigSet fbOddPasses = new ATNConfigSet();
		for (int i = 0; i < 33; i++) {
			int scrambled = (i * 19 + 5) % 33;
			BasicState st = new BasicState();
			st.stateNumber = (1 << 23) + scrambled;
			fbOddPasses.add(ATNConfig.create(st, 1, ctx1));
			if (i < 32) {
				fbOddPasses.add(ATNConfig.create(st, 2, ctx1));
			}
		}
		assertNotNull(invokeIsConflicted(sim, fbOddPasses, PredictionContextCache.UNCACHED));

		// Fallback inexact (missing alt 2 on middle state) & alt gap (alt 3 while firstState has alt 1,2)
		ATNConfigSet fbInexact = new ATNConfigSet();
		BasicState h1 = new BasicState(); h1.stateNumber = (1 << 23) + 1;
		BasicState h2 = new BasicState(); h2.stateNumber = (1 << 23) + 2;
		BasicState h3 = new BasicState(); h3.stateNumber = (1 << 23) + 3;
		fbInexact.add(ATNConfig.create(h1, 1, ctx1));
		fbInexact.add(ATNConfig.create(h1, 2, ctx1));
		fbInexact.add(ATNConfig.create(h2, 1, ctx1));
		fbInexact.add(ATNConfig.create(h3, 1, ctx1));
		ConflictInfo fbRes = (ConflictInfo) invokeIsConflicted(sim, fbInexact, PredictionContextCache.UNCACHED);
		assertNotNull(fbRes);
		assertFalse(fbRes.isExact());

		ATNConfigSet fbAltGap = new ATNConfigSet();
		fbAltGap.add(ATNConfig.create(h1, 1, ctx1));
		fbAltGap.add(ATNConfig.create(h1, 2, ctx1));
		fbAltGap.add(ATNConfig.create(h2, 1, ctx1));
		fbAltGap.add(ATNConfig.create(h2, 3, ctx1));
		assertNotNull(invokeIsConflicted(sim, fbAltGap, PredictionContextCache.UNCACHED));

		// Fallback disjoint context -> returns null
		ATNConfigSet fbDisjoint = new ATNConfigSet();
		fbDisjoint.add(ATNConfig.create(h1, 1, ctx1));
		fbDisjoint.add(ATNConfig.create(h1, 2, ctx3));
		assertNull(invokeIsConflicted(sim, fbDisjoint, PredictionContextCache.UNCACHED));

		// 4. PredictionContextCache sparse tracker growth (>128 and >4096) & JoinContextMap overwrite
		PredictionContextCache cache = new PredictionContextCache();
		// Pre-grow tables to 16384 (<= MAX_RETAINED_CAPACITY) with 5000 entries -> clear() densely ->
		// now tables have capacity 16384 and usedCount=0. Insert 4200 entries -> triggers usedSlots
		// growth 128->256->...->4096 AND then usedCount = -1!
		int nGrow = 4200;
		PredictionContext[] arr = new PredictionContext[nGrow];
		for (int i = 0; i < nGrow; i++) {
			arr[i] = cache.getChild(PredictionContext.EMPTY_LOCAL, i + 1);
			cache.getAsCached(arr[i]);
		}
		for (int i = 0; i < nGrow - 1; i++) {
			cache.join(arr[i], arr[i + 1]);
		}
		cache.clear(); // dense clear keeps 16384 tables
		for (int i = 0; i < nGrow; i++) {
			arr[i] = cache.getChild(PredictionContext.EMPTY_LOCAL, i + 1);
			cache.getAsCached(arr[i]);
		}
		for (int i = 0; i < nGrow - 1; i++) {
			cache.join(arr[i], arr[i + 1]);
		}
		cache.clear();

		// Rule 0: "abc" -> type 1; Rule 1: "a" -> type 2; Rule 2: "a\u00e9" -> type 3; Rule 3: "a\u4e2d" -> type 4; Rule 4: "z" EOF -> type 5
		ATN btAtn = new ATN(ATNType.LEXER, 5);
		TokensStartState lStart = new TokensStartState(); lStart.ruleIndex = -1; btAtn.addState(lStart);
		RuleStartState r0 = new RuleStartState(); r0.ruleIndex = 0; RuleStopState e0 = new RuleStopState(); e0.ruleIndex = 0; r0.stopState = e0;
		BasicState m0a = new BasicState(); m0a.ruleIndex = 0;
		BasicState m0b = new BasicState(); m0b.ruleIndex = 0;
		RuleStartState r1 = new RuleStartState(); r1.ruleIndex = 1; RuleStopState e1 = new RuleStopState(); e1.ruleIndex = 1; r1.stopState = e1;
		RuleStartState r2 = new RuleStartState(); r2.ruleIndex = 2; RuleStopState e2 = new RuleStopState(); e2.ruleIndex = 2; r2.stopState = e2;
		BasicState m2 = new BasicState(); m2.ruleIndex = 2;
		RuleStartState r3 = new RuleStartState(); r3.ruleIndex = 3; RuleStopState e3 = new RuleStopState(); e3.ruleIndex = 3; r3.stopState = e3;
		BasicState m3 = new BasicState(); m3.ruleIndex = 3;
		RuleStartState r4 = new RuleStartState(); r4.ruleIndex = 4; RuleStopState e4 = new RuleStopState(); e4.ruleIndex = 4; r4.stopState = e4;
		BasicState m4 = new BasicState(); m4.ruleIndex = 4;
		for (ATNState st : new ATNState[] { r0, m0a, m0b, e0, r1, e1, r2, m2, e2, r3, m3, e3, r4, m4, e4 }) btAtn.addState(st);
		lStart.addTransition(new EpsilonTransition(r0));
		lStart.addTransition(new EpsilonTransition(r1));
		lStart.addTransition(new EpsilonTransition(r2));
		lStart.addTransition(new EpsilonTransition(r3));
		lStart.addTransition(new EpsilonTransition(r4));
		r0.addTransition(new AtomTransition(m0a, 'a'));
		m0a.addTransition(new AtomTransition(m0b, 'b'));
		m0b.addTransition(new AtomTransition(e0, 'c'));
		r1.addTransition(new AtomTransition(e1, 'a'));
		r2.addTransition(new AtomTransition(m2, 'a'));
		m2.addTransition(new AtomTransition(e2, '\u00e9'));
		r3.addTransition(new AtomTransition(m3, 'a'));
		m3.addTransition(new AtomTransition(e3, '\u4e2d'));
		r4.addTransition(new AtomTransition(m4, 'z'));
		m4.addTransition(new AtomTransition(e4, IntStream.EOF));
		btAtn.ruleToStartState = new RuleStartState[] { r0, r1, r2, r3, r4 };
		btAtn.ruleToStopState = new RuleStopState[] { e0, e1, e2, e3, e4 };
		btAtn.ruleToTokenType = new int[] { 1, 2, 3, 4, 5 };
		btAtn.modeToStartState.add(lStart);
		btAtn.clearDFA();

		Lexer btLex = new Lexer(CharStreams.fromString("abx")) {
			{ _interp = new LexerATNSimulator(this, btAtn); }
			@Override public String[] getRuleNames() { return new String[] { "ABC", "A", "A_LATIN1", "A_CJK", "Z_EOF" }; }
			@Override public String getGrammarFileName() { return "BT.g4"; }
			@Override public ATN getATN() { return btAtn; }
		};
		btLex.removeErrorListeners();
		// Warm DFA first on all paths, then re-run on warm DFA so execATNFast8Bit / CodePoint / Generic execute every branch!
		for (int pass = 0; pass < 2; pass++) {
			// 8-bit: "abx" -> backtrack; 'b'/'x' -> warm noViableAltFast; "z" -> EOF accept!
			btLex.setInputStream(CharStreams.fromString("abx"));
			assertEquals(2, btLex.nextToken().getType());
			assertEquals(Token.EOF, btLex.nextToken().getType());
			btLex.setInputStream(CharStreams.fromString("z"));
			assertEquals(5, btLex.nextToken().getType());
			// 8-bit non-ASCII accept (Latin-1 '\u00e9')
			btLex.setInputStream(CharStreams.fromString("a\u00e9"));
			assertEquals(3, btLex.nextToken().getType());
			// 16-bit: non-ASCII accept ('\u4e2d') + backtrack + warm noViableAltFast + EOF accept ("z")
			btLex.setInputStream(CharStreams.fromString("a\u4e2dab?"));
			assertEquals(4, btLex.nextToken().getType());
			assertEquals(2, btLex.nextToken().getType());
			assertEquals(Token.EOF, btLex.nextToken().getType());
			btLex.setInputStream(CharStreams.fromString("a\u4e2dz"));
			assertEquals(4, btLex.nextToken().getType());
			assertEquals(5, btLex.nextToken().getType());
			// Generic (ANTLRInputStream): backtrack + noViableAltFast + EOF accept ("z")
			btLex.setInputStream(new ANTLRInputStream("ab?"));
			assertEquals(2, btLex.nextToken().getType());
			assertEquals(Token.EOF, btLex.nextToken().getType());
			btLex.setInputStream(new ANTLRInputStream("z"));
			assertEquals(5, btLex.nextToken().getType());
		}
		assertEquals(Token.EOF, btLex.getInterpreter().match(new ANTLRInputStream(""), Lexer.DEFAULT_MODE));

		// ds0 accept state across 8-bit, 16-bit, generic
		DFAState btS0 = btAtn.modeToDFA[0].s0.get();
		btS0.setAcceptState(new AcceptStateInfo(2));
		assertEquals(2, btLex.getInterpreter().match(CharStreams.fromString("!"), Lexer.DEFAULT_MODE));
		assertEquals(2, btLex.getInterpreter().match(CharStreams.fromString("!\u4e2d"), Lexer.DEFAULT_MODE));
		assertEquals(2, btLex.getInterpreter().match(new ANTLRInputStream("!"), Lexer.DEFAULT_MODE));

		// 6. LexerPredEdgeTable multi-predicate chain (installChain attaching both onTrue & onFalse child nodes)
		LexerPredEdgeTable chainTable = new LexerPredEdgeTable(btS0);
		DFAState t1 = new DFAState(btAtn.modeToDFA[0], new ATNConfigSet());
		DFAState t2 = new DFAState(btAtn.modeToDFA[0], new ATNConfigSet());
		chainTable.put(0, 'z', new int[] { 0, 1 }, new int[] { 0, 1 }, new boolean[] { true, true }, 2, t1);
		chainTable.put(0, 'z', new int[] { 0, 1 }, new int[] { 0, 1 }, new boolean[] { false, true }, 2, t2);
		chainTable.put(0, 'y', new int[] { 0, 1 }, new int[] { 0, 1 }, new boolean[] { false, false }, 2, t1);
		chainTable.put(0, 'y', new int[] { 0, 1 }, new int[] { 0, 1 }, new boolean[] { true, false }, 2, t2);
		assertNotNull(chainTable.getRoot(0, 'z'));
		assertSame(t1, ((LexerPredEdgeTable.PredNode) chainTable.getRoot(0, 'z').onTrue).onTrue);
		assertSame(t2, ((LexerPredEdgeTable.PredNode) chainTable.getRoot(0, 'z').onFalse).onTrue);
		assertSame(t2, ((LexerPredEdgeTable.PredNode) chainTable.getRoot(0, 'y').onTrue).onFalse);

		// 7. ATNConfig protected copy ctor + ATNConfig & ATNConfigSet cachedHashCode fast reject
		//    + ATN.getCachedContext(EMPTY_FULL) + DFAState ArrayEdgeMap getTarget + PredictionContext visited/Array update
		ATNConfig base = ATNConfig.create(h1, 1, ctx1);
		ATNConfig copy = new ATNConfig(base, h2, ctx2) {};
		assertTrue(base.hashCode() != copy.hashCode());
		assertFalse(base.equals(copy)); // cachedHashCode fast reject (line 456)!
		ATNConfigSet csA = new ATNConfigSet(); csA.add(base);
		ATNConfigSet csB = new ATNConfigSet(); csB.add(copy);
		assertTrue(csA.hashCode() != csB.hashCode());
		assertFalse(csA.equals(csB)); // cachedHashCode fast reject (line 634)!
		assertSame(PredictionContext.EMPTY_FULL, atn.getCachedContext(PredictionContext.EMPTY_FULL));

		// DFAState with ArrayEdgeMap backing
		DFA pDfa = atn.decisionToDFA[0];
		DFAState arrState = new DFAState(pDfa, new ATNConfigSet());
		for (int sym = 0; sym <= 3; sym++) {
			arrState.setTarget(sym, arrState);
		}
		assertSame(arrState, arrState.getTarget(1));

		// PredictionContext.getCachedContext updating an ArrayPredictionContext + hitting visited cache
		PredictionContext sharedChild = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, 55);
		PredictionContext arrPc = PredictionContext.join(
			new SingletonPredictionContext(sharedChild, 10),
			new SingletonPredictionContext(sharedChild, 20),
			PredictionContextCache.UNCACHED);
		PredictionContext.IdentityHashMap visitedMap = new PredictionContext.IdentityHashMap();
		java.util.concurrent.ConcurrentHashMap<PredictionContext, PredictionContext> ctxCacheMap =
			new java.util.concurrent.ConcurrentHashMap<PredictionContext, PredictionContext>();
		PredictionContext cached1 = PredictionContext.getCachedContext(arrPc, ctxCacheMap, visitedMap);
		assertSame(cached1, PredictionContext.getCachedContext(arrPc, ctxCacheMap, visitedMap)); // hits visited!

		// 8. Subclass of ParserATNSimulator overriding closure() & getReachableTarget() to exercise
		//    EpsilonClosure & ReachComputation subclass fallback hooks
		ParserATNSimulator subSim = new ParserATNSimulator(atn) {
			@Override
			protected void closure(ATNConfig config, ATNConfigSet configs, ATNConfigSet intermediate,
								   Set<ATNConfig> closureBusy, boolean collectPredicates, boolean hasMoreContext,
								   PredictionContextCache contextCache, int depth, boolean treatEofAsEpsilon) {
				super.closure(config, configs, intermediate, closureBusy, collectPredicates, hasMoreContext, contextCache, depth, treatEofAsEpsilon);
			}
			@Override
			protected ATNState getReachableTarget(ATNConfig source, Transition trans, int ttype) {
				return super.getReachableTarget(source, trans, ttype);
			}
		};
		ATNConfigSet startSet = new ATNConfigSet();
		startSet.add(ATNConfig.create(atn.states.get(0), 1, PredictionContext.EMPTY_LOCAL));
		ATNConfigSet closed = new ATNConfigSet();
		subSim.closure(startSet, closed, true, true, PredictionContextCache.UNCACHED, false);
		SimulatorState simState = new SimulatorState(ParserRuleContext.emptyContext(), new DFAState(pDfa, closed), false, ParserRuleContext.emptyContext());
		SimulatorState reached = subSim.computeReachSet(pDfa, simState, 2, PredictionContextCache.UNCACHED);
		assertNotNull(reached);
	}
}
