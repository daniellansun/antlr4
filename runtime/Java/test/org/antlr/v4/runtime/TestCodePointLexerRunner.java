/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.ActionTransition;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.antlr.v4.runtime.atn.LexerAction;
import org.antlr.v4.runtime.atn.LexerSkipAction;
import org.antlr.v4.runtime.atn.PredicateTransition;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.SetTransition;
import org.antlr.v4.runtime.atn.TokensStartState;
import org.antlr.v4.runtime.dfa.AcceptStateInfo;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Zero-reflection unit tests for package-private {@link CodePointLexerRunner}
 * and {@link LexerATNSimulator#isFastDFAEligible()} / {@link LexerATNSimulator#fastDfaEligibleClass()}.
 */
public class TestCodePointLexerRunner {

	private static ATN buildRichLexerATN() {
		ATN atn = new ATN(ATNType.LEXER, 14);
		TokensStartState start = new TokensStartState();
		start.ruleIndex = -1;
		atn.addState(start);

		// Rule 0: '/' {pred(0,0)}? -> 10
		RuleStartState r0s = new RuleStartState(); r0s.ruleIndex = 0;
		BasicState r0m = new BasicState(); r0m.ruleIndex = 0;
		BasicState r0p = new BasicState(); r0p.ruleIndex = 0;
		RuleStopState r0e = new RuleStopState(); r0e.ruleIndex = 0; r0s.stopState = r0e;
		atn.addState(r0s); atn.addState(r0m); atn.addState(r0p); atn.addState(r0e);
		start.addTransition(new EpsilonTransition(r0s));
		r0s.addTransition(new AtomTransition(r0m, '/'));
		r0m.addTransition(new PredicateTransition(r0p, 0, 0, false));
		r0p.addTransition(new EpsilonTransition(r0e));

		// Rule 1: '/' -> 11
		RuleStartState r1s = new RuleStartState(); r1s.ruleIndex = 1;
		BasicState r1m = new BasicState(); r1m.ruleIndex = 1;
		RuleStopState r1e = new RuleStopState(); r1e.ruleIndex = 1; r1s.stopState = r1e;
		atn.addState(r1s); atn.addState(r1m); atn.addState(r1e);
		start.addTransition(new EpsilonTransition(r1s));
		r1s.addTransition(new AtomTransition(r1m, '/'));
		r1m.addTransition(new EpsilonTransition(r1e));

		// Rule 2: ' ' -> skip (12)
		RuleStartState r2s = new RuleStartState(); r2s.ruleIndex = 2;
		BasicState r2m = new BasicState(); r2m.ruleIndex = 2;
		BasicState r2a = new BasicState(); r2a.ruleIndex = 2;
		RuleStopState r2e = new RuleStopState(); r2e.ruleIndex = 2; r2s.stopState = r2e;
		atn.addState(r2s); atn.addState(r2m); atn.addState(r2a); atn.addState(r2e);
		start.addTransition(new EpsilonTransition(r2s));
		r2s.addTransition(new AtomTransition(r2m, ' '));
		r2m.addTransition(new ActionTransition(r2a, 2, 0, false));
		r2a.addTransition(new EpsilonTransition(r2e));

		// Rule 3: '\r' | '\n' -> 13
		RuleStartState r3s = new RuleStartState(); r3s.ruleIndex = 3;
		BasicState r3m = new BasicState(); r3m.ruleIndex = 3;
		RuleStopState r3e = new RuleStopState(); r3e.ruleIndex = 3; r3s.stopState = r3e;
		atn.addState(r3s); atn.addState(r3m); atn.addState(r3e);
		start.addTransition(new EpsilonTransition(r3s));
		r3s.addTransition(new SetTransition(r3m, IntervalSet.of('\n', '\r')));
		r3m.addTransition(new EpsilonTransition(r3e));

		// Rule 4: 'a' | '\u4e2d' | U+1F600 -> 14
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

	private static Lexer newLexer(final ATN atn, CharStream input) {
		return new Lexer(input) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "REGEX", "DIV", "WS", "NL", "ID" }; }
			@Override public String getGrammarFileName() { return "Rich.g4"; }
			@Override public ATN getATN() { return atn; }
		};
	}

	@SuppressWarnings("deprecation")
	@Test
	public void test8Bit16Bit32BitAndGenericLoopsWithPredSyncAndSkipFusion() {
		final ATN atn = buildRichLexerATN();
		final boolean[] predFlag = new boolean[] { true };
		final int[] observedLine = new int[1];
		final int[] observedCol = new int[1];

		Lexer lex = new Lexer(CharStreams.fromString("  /\r\n\r\na ")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "REGEX", "DIV", "WS", "NL", "ID" }; }
			@Override public String getGrammarFileName() { return "Rich.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public boolean sempred(RuleContext ctx, int ruleIndex, int predIndex) {
				observedLine[0] = getLine();
				observedCol[0] = getCharPositionInLine();
				boolean cur = predFlag[0];
				predFlag[0] = !cur;
				return cur;
			}
		};
		lex.removeErrorListeners();

		// 1. 8-bit Latin-1 (cold ds0==null fallback + warm CodePointLexerRunner + pred line/col sync)
		for (int pass = 0; pass < 2; pass++) {
			predFlag[0] = true;
			lex.setInputStream(CharStreams.fromString("  / / \r\n\r\n!a "));
			Token t0 = lex.nextToken();
			assertEquals(10, t0.getType());
			assertEquals(1, observedLine[0]);
			assertEquals(3, observedCol[0]); // speculative consume of '/' at col 2 -> col 3
			assertEquals(11, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(14, lex.nextToken().getType());
			assertEquals(Token.EOF, lex.nextToken().getType());
		}

		// 2. 16-bit BMP loop (exec16Bit): non-ASCII, pred, \r\n, \r, \n, NVA, trailing skip
		for (int pass = 0; pass < 2; pass++) {
			predFlag[0] = true;
			lex.setInputStream(CharStreams.fromString("  \u4e2d / / \r\n\r!a "));
			assertEquals(14, lex.nextToken().getType());
			assertEquals(10, lex.nextToken().getType());
			assertEquals(11, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(14, lex.nextToken().getType());
			assertEquals(Token.EOF, lex.nextToken().getType());
		}

		// 3. 32-bit SMP loop (exec32Bit): skip fusion, \r\n, \r, \n, NVA, EOF
		for (int pass = 0; pass < 2; pass++) {
			predFlag[0] = true;
			lex.setInputStream(CharStreams.fromString(" \uD83D\uDE00 / \r\n\r!a "));
			assertEquals(14, lex.nextToken().getType());
			assertEquals(10, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(13, lex.nextToken().getType());
			assertEquals(14, lex.nextToken().getType());
			assertEquals(Token.EOF, lex.nextToken().getType());
		}

		// 4. Generic CharStream (ANTLRInputStream)
		lex.setInputStream(new ANTLRInputStream(" / \r\n!a"));
		predFlag[0] = true;
		assertEquals(10, lex.nextToken().getType());
		assertEquals(13, lex.nextToken().getType());
		assertEquals(13, lex.nextToken().getType());
		assertEquals(14, lex.nextToken().getType());
		assertEquals(Token.EOF, lex.nextToken().getType());

		// 5. Standalone sim.match does not skip-fuse
		lex.setInputStream(CharStreams.fromString(" a"));
		assertEquals(12, lex.getInterpreter().match(lex.getInputStream(), Lexer.DEFAULT_MODE));
	}

	@Test
	public void testBacktrackEofAcceptAndDs0AcceptAcrossAllWidths() {
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

		for (int pass = 0; pass < 2; pass++) {
			btLex.setInputStream(CharStreams.fromString("abx"));
			assertEquals(2, btLex.nextToken().getType());
			assertEquals(Token.EOF, btLex.nextToken().getType());
			btLex.setInputStream(CharStreams.fromString("z"));
			assertEquals(5, btLex.nextToken().getType());
			btLex.setInputStream(CharStreams.fromString("a\u00e9"));
			assertEquals(3, btLex.nextToken().getType());
			btLex.setInputStream(CharStreams.fromString("a\u4e2dz"));
			assertEquals(4, btLex.nextToken().getType());
			assertEquals(5, btLex.nextToken().getType());
			btLex.setInputStream(CharStreams.fromString("\uD83D\uDE00z"));
			assertEquals(5, btLex.nextToken().getType());
		}

		// Empty CodePointCharStream returns EOF directly via handleFastNoViableAlt
		for (String s : new String[] { "", "\u4e2d", "\uD83D\uDE00" }) {
			CodePointCharStream cs = CharStreams.fromString(s);
			cs.seek(cs.size());
			btLex.setInputStream(cs);
			assertEquals(Token.EOF, btLex.nextToken().getType());
		}

		// ds0 accept state across 8-bit, 16-bit, 32-bit
		DFAState btS0 = btAtn.modeToDFA[0].s0.get();
		btS0.setAcceptState(new AcceptStateInfo(2));
		btLex.setInputStream(CharStreams.fromString("!"));
		assertEquals(2, btLex.nextToken().getType());
		btLex.setInputStream(CharStreams.fromString("!\u4e2d"));
		assertEquals(2, btLex.nextToken().getType());
		btLex.setInputStream(CharStreams.fromString("!\uD83D\uDE00"));
		assertEquals(2, btLex.nextToken().getType());
	}

	@Test
	public void testSkipFusionBailoutsForCustomSkipOverrides() {
		final ATN atn = buildRichLexerATN();
		// Warm DFA first
		Lexer warm = newLexer(atn, CharStreams.fromString(" a"));
		assertEquals(14, warm.nextToken().getType());

		// Case 1: skip() sets channel to HIDDEN instead of SKIP -> must emit hidden token
		Lexer hiddenSkip = new Lexer(CharStreams.fromString(" a")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "REGEX", "DIV", "WS", "NL", "ID" }; }
			@Override public String getGrammarFileName() { return "S1.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override public void skip() { setChannel(Token.HIDDEN_CHANNEL); }
		};
		Token t1 = hiddenSkip.nextToken();
		assertEquals(12, t1.getType());
		assertEquals(Token.HIDDEN_CHANNEL, t1.getChannel());

		// Case 2: skip() keeps SKIP but mutates _text / _channel / _mode / _hitEOF / _token / input.position / line / col
		final int[] mode = new int[1];
		Lexer mutatingSkip = new Lexer(CharStreams.fromString(" a")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "REGEX", "DIV", "WS", "NL", "ID" }; }
			@Override public String getGrammarFileName() { return "S2.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public void skip() {
				super.skip();
				switch (mode[0]) {
				case 0: _channel = 1; break;
				case 1: _text = "mut"; break;
				case 2: _mode = 1; break;
				case 3: _hitEOF = true; break;
				case 4: _token = new CommonToken(99, "forced"); break;
				case 5: _input = CharStreams.fromString("a"); break;
				case 6: _input.seek(0); break;
				case 7: getInterpreter().setLine(99); break;
				case 8: getInterpreter().setCharPositionInLine(99); break;
				default: break;
				}
			}
		};
		for (int c = 0; c <= 8; c++) {
			mode[0] = c;
			mutatingSkip.setInputStream(CharStreams.fromString(" a"));
			mutatingSkip._mode = Lexer.DEFAULT_MODE;
			mutatingSkip._hitEOF = false;
			if (c == 2) {
				// Restore mode 0 on next pass; calling nextToken would index mode 1 otherwise
				mutatingSkip._tokenStartCharIndex = 0;
				mutatingSkip._type = Token.INVALID_TYPE;
				mutatingSkip._token = null;
				mutatingSkip._channel = Token.DEFAULT_CHANNEL;
				mutatingSkip._text = null;
				int res = CodePointLexerRunner.match(mutatingSkip, (CodePointCharStream) mutatingSkip.getInputStream(),
					mutatingSkip.getInterpreter(), Lexer.DEFAULT_MODE);
				assertEquals(12, res);
			}
			else if (c == 6) {
				mutatingSkip._tokenStartCharIndex = 0;
				mutatingSkip._type = Token.INVALID_TYPE;
				mutatingSkip._token = null;
				mutatingSkip._channel = Token.DEFAULT_CHANNEL;
				mutatingSkip._text = null;
				int res = CodePointLexerRunner.match(mutatingSkip, (CodePointCharStream) mutatingSkip.getInputStream(),
					mutatingSkip.getInterpreter(), Lexer.DEFAULT_MODE);
				assertEquals(12, res);
			}
			else {
				Token tok = mutatingSkip.nextToken();
				assertNotNull(tok);
			}
		}

		// Custom skip() override that counts skips and delegates cleanly to super.skip()
		// still fuses via finishCustomSkip() -> CONTINUE_SKIP without dropping side effects.
		final int[] skipCount = new int[1];
		Lexer countingSkip = new Lexer(CharStreams.fromString("  a")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "REGEX", "DIV", "WS", "NL", "ID" }; }
			@Override public String getGrammarFileName() { return "CountSkip.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public void skip() {
				skipCount[0]++;
				super.skip();
			}
		};
		assertEquals(14, countingSkip.nextToken().getType());
		assertEquals(2, skipCount[0]);
		assertEquals(Token.EOF, countingSkip.nextToken().getType());
	}

	static class HelperOnlySim extends LexerATNSimulator {
		HelperOnlySim(Lexer recog, ATN atn) { super(recog, atn); }
		void helperOnly() {}
		@SuppressWarnings("unused")
		private void accept(int unrelatedOverload) {}
	}

	static class HookOverridingSim extends LexerATNSimulator {
		HookOverridingSim(Lexer recog, ATN atn) { super(recog, atn); }
		@Override protected void addDFAEdge(DFAState p, int t, DFAState q) { super.addDFAEdge(p, t, q); }
	}

	static class ExplicitOptInSim extends LexerATNSimulator {
		ExplicitOptInSim(Lexer recog, ATN atn) { super(recog, atn); }
		@Override public void reset() { super.reset(); }
		@Override protected Class<? extends LexerATNSimulator> fastDfaEligibleClass() { return ExplicitOptInSim.class; }
	}

	static class SubOfOptInSim extends ExplicitOptInSim {
		SubOfOptInSim(Lexer recog, ATN atn) { super(recog, atn); }
	}

	static class ExplicitOptOutSim extends LexerATNSimulator {
		ExplicitOptOutSim(Lexer recog, ATN atn) { super(recog, atn); }
		@Override protected Class<? extends LexerATNSimulator> fastDfaEligibleClass() { return null; }
	}

	@Test
	public void testFastDfaEligibilityContract() {
		ATN atn = buildRichLexerATN();
		assertTrue(new LexerATNSimulator(atn).isFastDFAEligible());
		assertTrue("Helper-only subclass (like Groovy PositionAdjustingLexerATNSimulator) stays eligible",
			new HelperOnlySim(null, atn).isFastDFAEligible());
		assertFalse("Subclass overriding an ATN/DFA hook is excluded",
			new HookOverridingSim(null, atn).isFastDFAEligible());
		assertTrue("Explicit opt-in via fastDfaEligibleClass() is eligible",
			new ExplicitOptInSim(null, atn).isFastDFAEligible());
		assertFalse("Further subclass of an explicitly opted-in class is safely excluded",
			new SubOfOptInSim(null, atn).isFastDFAEligible());
		assertFalse("Explicit opt-out via returning null is excluded",
			new ExplicitOptOutSim(null, atn).isFastDFAEligible());
	}
}
