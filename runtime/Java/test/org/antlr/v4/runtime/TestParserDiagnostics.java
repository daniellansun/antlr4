/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNSerializer;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicBlockStartState;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.BlockEndState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.antlr.v4.runtime.atn.ParseInfo;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.ProfilingATNSimulator;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.TokensStartState;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.pattern.ParseTreePattern;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Full line and branch coverage for {@link ParserDiagnostics} (including subclass
 * virtual-dispatch preservation) and {@link Parser}'s {@code protected final}
 * generated-parser hooks.
 */
public class TestParserDiagnostics {

	private static ATN buildTwoRuleATN() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		// Rule 0: rule1 B
		RuleStartState r0 = new RuleStartState(); r0.ruleIndex = 0;
		BasicState r0Follow = new BasicState(); r0Follow.ruleIndex = 0;
		RuleStopState r0Stop = new RuleStopState(); r0Stop.ruleIndex = 0; r0.stopState = r0Stop;
		// Rule 1: (A | B)
		RuleStartState r1 = new RuleStartState(); r1.ruleIndex = 1;
		BasicBlockStartState dec = new BasicBlockStartState(); dec.ruleIndex = 1;
		BlockEndState end = new BlockEndState(); end.ruleIndex = 1; dec.endState = end;
		BasicState a1 = new BasicState(); a1.ruleIndex = 1;
		BasicState a2 = new BasicState(); a2.ruleIndex = 1;
		RuleStopState r1Stop = new RuleStopState(); r1Stop.ruleIndex = 1; r1.stopState = r1Stop;

		for (org.antlr.v4.runtime.atn.ATNState st : new org.antlr.v4.runtime.atn.ATNState[] {
			r0, r0Follow, r0Stop, r1, dec, end, a1, a2, r1Stop
		}) {
			atn.addState(st);
		}
		atn.defineDecisionState(dec);
		r0.addTransition(new RuleTransition(r1, 1, 0, r0Follow));
		r0Follow.addTransition(new AtomTransition(r0Stop, 2));
		r1.addTransition(new EpsilonTransition(dec));
		dec.addTransition(new EpsilonTransition(a1));
		dec.addTransition(new EpsilonTransition(a2));
		a1.addTransition(new AtomTransition(end, 1));
		a2.addTransition(new AtomTransition(end, 2));
		end.addTransition(new EpsilonTransition(r1Stop));
		atn.ruleToStartState = new RuleStartState[] { r0, r1 };
		atn.ruleToStopState = new RuleStopState[] { r0Stop, r1Stop };
		atn.clearDFA();
		return atn;
	}

	private static ATN buildMiniLexerATN() {
		ATN atn = new ATN(ATNType.LEXER, 2);
		TokensStartState start = new TokensStartState(); start.ruleIndex = -1;
		RuleStartState ra = new RuleStartState(); ra.ruleIndex = 0;
		RuleStopState ea = new RuleStopState(); ea.ruleIndex = 0; ra.stopState = ea;
		RuleStartState rb = new RuleStartState(); rb.ruleIndex = 1;
		RuleStopState eb = new RuleStopState(); eb.ruleIndex = 1; rb.stopState = eb;
		atn.addState(start); atn.addState(ra); atn.addState(ea); atn.addState(rb); atn.addState(eb);
		start.addTransition(new EpsilonTransition(ra));
		start.addTransition(new EpsilonTransition(rb));
		ra.addTransition(new AtomTransition(ea, 'a'));
		rb.addTransition(new AtomTransition(eb, 'b'));
		atn.ruleToStartState = new RuleStartState[] { ra, rb };
		atn.ruleToStopState = new RuleStopState[] { ea, eb };
		atn.ruleToTokenType = new int[] { 1, 2 };
		atn.defineMode("DEFAULT_MODE", start);
		atn.clearDFA();
		return atn;
	}

	private static class TestParser extends Parser {
		final ATN atn;
		final String serialized;
		final Vocabulary vocab = new VocabularyImpl(
			new String[] { null, "'a'", "'b'" },
			new String[] { null, "A", "B" });

		TestParser(TokenStream input, ATN atn, boolean withSerializedAtn) {
			super(input);
			this.atn = atn;
			this.serialized = withSerializedAtn
				? ATNSerializer.getSerializedAsString(atn, Arrays.asList("r0", "r1"))
				: null;
			setInterpreter(new ParserATNSimulator(this, atn));
		}

		@Override @Deprecated public String[] getTokenNames() { return new String[] { null, "A", "B" }; }
		@Override public String[] getRuleNames() { return new String[] { "r0", "r1" }; }
		@Override public Vocabulary getVocabulary() { return vocab; }
		@Override public String getGrammarFileName() { return "Diag.g4"; }
		@Override public ATN getATN() { return atn; }
		@Override public String getSerializedATN() { return serialized; }
	}

	private static CommonTokenStream streamOf(int... types) {
		List<Token> list = new ArrayList<Token>();
		for (int i = 0; i < types.length; i++) {
			CommonToken t = new CommonToken(types[i], types[i] == 1 ? "a" : "b");
			t.setTokenIndex(i);
			list.add(t);
		}
		CommonToken eof = new CommonToken(Token.EOF, "<EOF>");
		eof.setTokenIndex(types.length);
		list.add(eof);
		return new CommonTokenStream(new ListTokenSource(list));
	}

	@Test
	public void testBypassAtnExpectedTokensInvokingContextAndRuleStack() {
		ATN atn = buildTwoRuleATN();
		TestParser p = new TestParser(streamOf(1, 2), atn, true);
		p.removeErrorListeners();

		// getATNWithBypassAlts caches by serializedATN; null serializedATN throws
		ATN bypass1 = p.getATNWithBypassAlts();
		ATN bypass2 = p.getATNWithBypassAlts();
		assertSame(bypass1, bypass2);
		TestParser noSer = new TestParser(streamOf(1), atn, false);
		try { noSer.getATNWithBypassAlts(); fail(); } catch (UnsupportedOperationException expected) {}

		// getInvokingContext, getRuleIndex, getRuleInvocationStack (including ruleIndex < 0)
		ParserRuleContext ctx0 = new ParserRuleContext(null, -1) {
			@Override public int getRuleIndex() { return 0; }
		};
		ParserRuleContext ctx1 = new ParserRuleContext(ctx0, atn.ruleToStartState[0].stateNumber) {
			@Override public int getRuleIndex() { return 1; }
		};
		ParserRuleContext ctxUnknown = new ParserRuleContext(ctx1, -1); // ruleIndex == -1 -> "n/a"
		p.setContext(ctxUnknown);
		assertSame(ctx0, p.getInvokingContext(0));
		assertSame(ctx1, p.getInvokingContext(1));
		assertNull(p.getInvokingContext(99));
		assertEquals(0, p.getRuleIndex("r0"));
		assertEquals(1, p.getRuleIndex("r1"));
		assertEquals(-1, p.getRuleIndex("missing"));
		assertEquals(Arrays.asList("n/a", "r1", "r0"), p.getRuleInvocationStack());

		// isExpectedToken, getExpectedTokens, getExpectedTokensWithinCurrentRule
		p.setContext(ctx1);
		p.setState(atn.ruleToStopState[1].stateNumber); // EPSILON -> follows r0Follow expecting token 2 (B)
		assertTrue(p.isExpectedToken(2));
		assertFalse(p.isExpectedToken(1));
		assertTrue(p.getExpectedTokens().contains(2));
		assertNotNull(p.getExpectedTokensWithinCurrentRule());

		// Direct hit in current rule & EOF via top-level rule stop
		p.setState(atn.ruleToStartState[1].stateNumber);
		assertTrue(p.isExpectedToken(1));
		assertFalse(p.isExpectedToken(Token.EOF));
		p.setContext(ctx0);
		p.setState(atn.ruleToStopState[0].stateNumber);
		assertTrue(p.isExpectedToken(Token.EOF));
		assertFalse(p.isExpectedToken(1));
	}

	@Test
	public void testCompileParseTreePatternAndRuleStackPreserveSubclassVirtualDispatch() {
		final ATN atn = buildTwoRuleATN();
		final ATN lexAtn = buildMiniLexerATN();
		final Lexer lexer = new Lexer(CharStreams.fromString("ab")) {
			{ _interp = new LexerATNSimulator(this, lexAtn); }
			@Override public String[] getRuleNames() { return new String[] { "A", "B" }; }
			@Override public Vocabulary getVocabulary() {
				return new VocabularyImpl(new String[] { null, "'a'", "'b'" }, new String[] { null, "A", "B" });
			}
			@Override public String getGrammarFileName() { return "L.g4"; }
			@Override public ATN getATN() { return lexAtn; }
		};

		final boolean[] pattern3ArgCalled = new boolean[1];
		final boolean[] stack1ArgCalled = new boolean[1];
		TestParser sub = new TestParser(new CommonTokenStream(lexer), atn, true) {
			@Override
			public ParseTreePattern compileParseTreePattern(String pattern, int patternRuleIndex, Lexer l) {
				pattern3ArgCalled[0] = true;
				return super.compileParseTreePattern(pattern, patternRuleIndex, l);
			}
			@Override
			public List<String> getRuleInvocationStack(RuleContext p) {
				stack1ArgCalled[0] = true;
				return super.getRuleInvocationStack(p);
			}
		};
		sub.setContext(new ParserRuleContext());
		sub.getRuleInvocationStack();
		assertTrue("getRuleInvocationStack() must dispatch virtually to getRuleInvocationStack(RuleContext)",
			stack1ArgCalled[0]);

		// Calling compileParseTreePattern(String, int) must dispatch virtually to 3-arg overload
		try {
			sub.compileParseTreePattern("a", 0);
		}
		catch (RuntimeException ignored) {
			// ParseTreePatternMatcher may fail on minimal synthetic parser without rule methods,
			// but virtual dispatch to the 3-arg override must already have occurred.
		}
		assertTrue("compileParseTreePattern(String, int) must dispatch virtually to 3-arg overload",
			pattern3ArgCalled[0]);

		// Non-Lexer TokenSource or null input throws UnsupportedOperationException
		TestParser noLex = new TestParser(streamOf(1), atn, true);
		try { noLex.compileParseTreePattern("a", 0); fail(); } catch (UnsupportedOperationException expected) {}
		noLex.setInputStream(null);
		try { noLex.compileParseTreePattern("a", 0); fail(); } catch (UnsupportedOperationException expected) {}
	}

	@Test
	public void testNotifyErrorsDfaDumpProfileTraceAndLeftFactoredRule() {
		ATN atn = buildTwoRuleATN();
		TestParser p = new TestParser(streamOf(1, 2), atn, true);
		final List<String> errors = new ArrayList<String>();
		p.removeErrorListeners();
		p.addErrorListener(new BaseErrorListener() {
			@Override
			public <T extends Token> void syntaxError(Recognizer<T, ?> r, T sym, int line, int col, String msg, RecognitionException e) {
				errors.add(line + ":" + col + ":" + msg);
			}
		});
		p.notifyErrorListeners("boom");
		p.notifyErrorListeners(null, "nulltok", null);
		assertEquals(2, p.getNumberOfSyntaxErrors());
		assertEquals(2, errors.size());

		// DFA strings & dumpDFA (empty then non-empty across multiple decisions)
		assertEquals(1, p.getDFAStrings().size());
		p.getInterpreter().adaptivePredict(p.getInputStream(), 0, ParserRuleContext.emptyContext());
		// DFA strings & dumpDFA on populated decision state
		PrintStream origOut = System.out;
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			System.setOut(new PrintStream(baos));
			p.dumpDFA();
		}
		finally {
			System.setOut(origOut);
		}
		assertTrue(baos.toString().contains("Decision 0:"));

		// setProfile(true/false) and getParseInfo
		assertNull(p.getParseInfo());
		p.setProfile(true);
		assertTrue(p.getInterpreter() instanceof ProfilingATNSimulator);
		ParseInfo pi = p.getParseInfo();
		assertNotNull(pi);
		p.setProfile(true); // idempotent when already profiling
		p.setProfile(false);
		assertFalse(p.getInterpreter() instanceof ProfilingATNSimulator);
		p.setProfile(false); // idempotent when not profiling

		// setTrace(true/true/false) and isTrace()
		assertFalse(p.isTrace());
		p.setTrace(true);
		assertTrue(p.isTrace());
		p.setTrace(true); // re-installs existing _tracer
		assertTrue(p.isTrace());
		p.setTrace(false);
		assertFalse(p.isTrace());

		// enterLeftFactoredRule (with & without parse tree and listener)
		ParserRuleContext parent = new ParserRuleContext();
		ParserRuleContext factored = new ParserRuleContext(parent, 0);
		parent.addChild(factored);
		p.setContext(parent);
		p.setBuildParseTree(true);
		final int[] entered = new int[1];
		p.addParseListener(new ParseTreeListener() {
			@Override public void visitTerminal(TerminalNode n) {}
			@Override public void visitErrorNode(ErrorNode n) {}
			@Override public void enterEveryRule(ParserRuleContext ctx) { entered[0]++; }
			@Override public void exitEveryRule(ParserRuleContext ctx) {}
		});
		ParserRuleContext lfCtx = new ParserRuleContext(parent, 0);
		p.enterLeftFactoredRule(lfCtx, 0, 0);
		assertSame(lfCtx, p.getContext());
		assertEquals(1, entered[0]);
		assertSame(lfCtx, factored.parent);
		p.removeParseListeners();
		p.setBuildParseTree(false);
		ParserRuleContext lfCtx2 = new ParserRuleContext(lfCtx, 0);
		p.enterLeftFactoredRule(lfCtx2, 0, 0);
		assertSame(lfCtx2, p.getContext());
	}

	@Test
	public void testProtectedFinalGeneratedParserHelpers() {
		ATN atn = buildTwoRuleATN();
		TestParser p = new TestParser(streamOf(1, 1, 2), atn, true);
		p.removeErrorListeners();
		ParserRuleContext ctx = new ParserRuleContext();
		p._enterDeferred(ctx, atn.ruleToStartState[0].stateNumber, 0, atn.ruleToStartState[1].stateNumber);
		assertSame(ctx, p.getContext());
		assertEquals(atn.ruleToStartState[1].stateNumber, p.getState());

		// _prec, _sync, _sync(state), _syncPredict, _adaptivePredict
		assertTrue(p._prec(0));
		assertFalse(p._prec(-1));
		p._sync();
		p._sync(atn.decisionToState.get(0).stateNumber);
		assertEquals(1, p._syncPredict(atn.decisionToState.get(0).stateNumber, 0));
		assertEquals(1, p._adaptivePredict(0));
		p.setErrorHandler(new BailErrorStrategy());
		p._sync();
		p._sync(atn.decisionToState.get(0).stateNumber);
		assertEquals(1, p._syncPredict(atn.decisionToState.get(0).stateNumber, 0));

		// _matchStar consumes both 'A' tokens (type 1)
		p.setErrorHandler(new DefaultErrorStrategy());
		p._matchStar(0, 1, 2, 1);
		assertEquals(2, p.getInputStream().LA(1));

		// _matchSet success & mismatch (mismatch calls recoverInline WITHOUT adding extra ErrorNode)
		Token st = p.getInputStream().LT(1);
		Token matched = p._matchSet(st, true);
		assertSame(st, matched);
		assertEquals(Token.EOF, p.getInputStream().LA(1));

		// _matchSetEOF sets matchedEOF on EOF match
		Token eofTok = p.getInputStream().LT(1);
		assertFalse(p.isMatchedEOF());
		assertSame(eofTok, p._matchSetEOF(eofTok, true));
		assertTrue(p.isMatchedEOF());

		// _matchSet & _matchSetEOF mismatch do NOT add ErrorNode (matching CommonSetStuff semantics)
		TestParser mismatchParser = new TestParser(streamOf(1), atn, true);
		mismatchParser.removeErrorListeners();
		ParserRuleContext mCtx = new ParserRuleContext();
		mismatchParser.enterRule(mCtx, 0, 0);
		mismatchParser.setErrorHandler(new DefaultErrorStrategy() {
			@Override
			public Token recoverInline(Parser recognizer) {
				CommonToken conjured = new CommonToken(2, "conjured");
				conjured.setTokenIndex(-1);
				return conjured;
			}
		});
		Token setMiss = mismatchParser._matchSet(mismatchParser.getInputStream().LT(1), false);
		assertEquals(-1, setMiss.getTokenIndex());
		assertEquals("Mismatch in _matchSet must not add extra ErrorNode", 0, mCtx.getChildCount());
		Token setEofMiss = mismatchParser._matchSetEOF(mismatchParser.getInputStream().LT(1), false);
		assertEquals(-1, setEofMiss.getTokenIndex());
		assertEquals(0, mCtx.getChildCount());

		// Contrast: _match & matchWildcard mismatch DO add ErrorNode when tokenIndex == -1
		Token tokMiss = mismatchParser._match(2);
		assertEquals(-1, tokMiss.getTokenIndex());
		assertEquals(1, mCtx.getChildCount());
		assertTrue(mCtx.getChild(0) instanceof ErrorNode);

		// _matchStarPredict loop + _recover
		TestParser starP = new TestParser(streamOf(1, 2), atn, true);
		starP.removeErrorListeners();
		ParserRuleContext sCtx = new ParserRuleContext();
		starP.enterRule(sCtx, 0, 0);
		starP._matchStarPredict(0, 1, 2, 0, 1, 1);
		assertEquals(2, starP.getInputStream().LA(1));
		RecognitionException re = new InputMismatchException(starP);
		starP._recover(sCtx, re);
		assertSame(re, sCtx.exception);
	}
}
