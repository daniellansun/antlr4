/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.ProfilingATNSimulator;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Coverage for Parser match/consume hot-path optimizations:
 * reuse of LT(1) in {@link Parser#consume(Token)}.
 */
public class TestParserHotPath {

	private static ATN simpleATN() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		RuleStartState start = new RuleStartState();
		BasicState mid = new BasicState();
		RuleStopState stop = new RuleStopState();
		start.ruleIndex = 0;
		mid.ruleIndex = 0;
		stop.ruleIndex = 0;
		start.stopState = stop;
		atn.addState(start);
		atn.addState(mid);
		atn.addState(stop);
		start.addTransition(new AtomTransition(mid, 1));
		mid.addTransition(new EpsilonTransition(stop));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}

	private static ParserInterpreter parser(int... types) {
		ATN atn = simpleATN();
		List<Token> list = new ArrayList<Token>();
		for (int i = 0; i < types.length; i++) {
			CommonToken t = new CommonToken(types[i], String.valueOf(types[i]));
			t.setTokenIndex(i);
			list.add(t);
		}
		CommonToken eof = new CommonToken(Token.EOF, "<EOF>");
		eof.setTokenIndex(types.length);
		list.add(eof);
		Vocabulary vocab = new VocabularyImpl(
			new String[] { null, "'A'", "'B'" },
			new String[] { null, "A", "B" });
		ParserInterpreter p = new ParserInterpreter("P.g4", vocab,
			Collections.singletonList("s"), atn,
			new CommonTokenStream(new ListTokenSource(list)));
		p.removeErrorListeners();
		return p;
	}

	@Test
	public void consumeReusesProvidedToken() {
		ParserInterpreter p = parser(1, 2);
		p.setBuildParseTree(false);
		p.setState(p.getATN().ruleToStartState[0].stateNumber);
		p.setContext(new InterpreterRuleContext(null, -1, 0));
		Token first = p.getInputStream().LT(1);
		assertEquals(1, first.getType());
		assertSame(first, p.consume(first));
		assertEquals(2, p.getInputStream().LA(1));
	}

	@Test
	public void matchSuccessReusesLt1WithoutSecondLookup() {
		ParserInterpreter p = parser(1);
		p.setBuildParseTree(true);
		p.setState(p.getATN().ruleToStartState[0].stateNumber);
		InterpreterRuleContext ctx = new InterpreterRuleContext(null, -1, 0);
		p.setContext(ctx);
		Token matched = p.match(1);
		assertEquals(1, matched.getType());
		assertEquals(1, ctx.getChildCount());
		assertTrue(ctx.getChild(0) instanceof TerminalNode);
		assertSame(matched, ((TerminalNode) ctx.getChild(0)).getSymbol());
	}

	@Test
	public void matchEofSetsFlagAndDoesNotAdvancePastEof() {
		ParserInterpreter p = parser();
		p.setBuildParseTree(false);
		p.setState(p.getATN().ruleToStartState[0].stateNumber);
		p.setContext(new InterpreterRuleContext(null, -1, 0));
		assertEquals(Token.EOF, p.getInputStream().LA(1));
		Token eof = p.match(Token.EOF);
		assertEquals(Token.EOF, eof.getType());
		assertTrue(p.isMatchedEOF());
		assertEquals(Token.EOF, p.getInputStream().LA(1));
	}

	@Test
	public void matchWildcardReusesToken() {
		ParserInterpreter p = parser(1);
		p.setBuildParseTree(false);
		p.setState(p.getATN().ruleToStartState[0].stateNumber);
		p.setContext(new InterpreterRuleContext(null, -1, 0));
		Token w = p.matchWildcard();
		assertEquals(1, w.getType());
		assertEquals(Token.EOF, p.getInputStream().LA(1));
	}

	@Test
	public void matchWildcardCallsReportMatchWhileRecovering() {
		ParserInterpreter p = parser(1);
		p.setBuildParseTree(false);
		p.setState(p.getATN().ruleToStartState[0].stateNumber);
		p.setContext(new InterpreterRuleContext(null, -1, 0));
		final int[] reports = new int[1];
		class ArmingStrategy extends DefaultErrorStrategy {
			void arm(Parser rec) {
				beginErrorCondition(rec);
			}

			@Override
			public void reportMatch(Parser recognizer) {
				reports[0]++;
				super.reportMatch(recognizer);
			}
		}
		ArmingStrategy strategy = new ArmingStrategy();
		p.setErrorHandler(strategy);
		strategy.arm(p);
		assertTrue(p.errorRecoveryMode);
		Token w = p.matchWildcard();
		assertEquals(1, w.getType());
		assertEquals(1, reports[0]);
		assertFalse(p.errorRecoveryMode);
	}

	@Test
	public void matchWildcardRecoversOnNonPositiveType() {
		ParserInterpreter p = parser();
		p.setBuildParseTree(true);
		p.setState(p.getATN().ruleToStartState[0].stateNumber);
		InterpreterRuleContext ctx = new InterpreterRuleContext(null, -1, 0);
		p.setContext(ctx);
		p.setErrorHandler(new DefaultErrorStrategy() {
			@Override
			public Token recoverInline(Parser recognizer) {
				CommonToken conjured = new CommonToken(1, "inserted");
				conjured.setTokenIndex(-1);
				return conjured;
			}
		});
		Token recovered = p.matchWildcard();
		assertEquals(1, recovered.getType());
		assertEquals(-1, recovered.getTokenIndex());
		assertTrue(ctx.getChildCount() >= 1);
		assertTrue(ctx.getChild(0) instanceof ErrorNode);
	}

	@Test
	public void consumeInRecoveryNotifiesErrorListeners() {
		ParserInterpreter p = parser(1);
		p.setBuildParseTree(true);
		p.setState(p.getATN().ruleToStartState[0].stateNumber);
		InterpreterRuleContext ctx = new InterpreterRuleContext(null, -1, 0);
		p.setContext(ctx);
		final List<String> events = new ArrayList<String>();
		p.addParseListener(new ParseTreeListener() {
			@Override public void visitTerminal(TerminalNode node) { events.add("t"); }
			@Override public void visitErrorNode(ErrorNode node) { events.add("e:" + node.getText()); }
			@Override public void enterEveryRule(ParserRuleContext c) { }
			@Override public void exitEveryRule(ParserRuleContext c) { }
		});
		class ArmingStrategy extends DefaultErrorStrategy {
			void arm(Parser rec) {
				beginErrorCondition(rec);
			}
		}
		ArmingStrategy armed = new ArmingStrategy();
		p.setErrorHandler(armed);
		armed.arm(p);
		p.consume();
		assertEquals(Collections.singletonList("e:1"), events);
		assertTrue(ctx.getChild(0) instanceof ErrorNode);
	}

	@Test
	public void exitRuleFallsBackToLtMinusOneWhenNothingConsumed() {
		ParserInterpreter p = parser(1, 2);
		p.setBuildParseTree(false);
		p.setState(p.getATN().ruleToStartState[0].stateNumber);
		InterpreterRuleContext ctx = new InterpreterRuleContext(null, -1, 0);
		p.setContext(ctx);
		p.getInputStream().consume();
		assertNull(p.lastConsumed);
		p.exitRule();
		assertEquals(1, ctx.stop.getType());
	}

	@Test
	public void consumeInRecoveryAddsErrorNode() {
		ParserInterpreter p = parser(1);
		p.setBuildParseTree(true);
		p.setState(p.getATN().ruleToStartState[0].stateNumber);
		InterpreterRuleContext ctx = new InterpreterRuleContext(null, -1, 0);
		p.setContext(ctx);
		class ArmingStrategy extends DefaultErrorStrategy {
			void arm(Parser rec) {
				beginErrorCondition(rec);
			}
		}
		ArmingStrategy armed = new ArmingStrategy();
		p.setErrorHandler(armed);
		armed.arm(p);
		Token t = p.getInputStream().LT(1);
		assertSame(t, p.consume(t));
		assertEquals(1, ctx.getChildCount());
		assertTrue(ctx.getChild(0) instanceof ErrorNode);
		assertSame(t, ((ErrorNode) ctx.getChild(0)).getSymbol());
	}

	@Test
	public void consumeBuildsTreeAndNotifiesListeners() {
		ParserInterpreter p = parser(1);
		p.setBuildParseTree(true);
		p.setState(p.getATN().ruleToStartState[0].stateNumber);
		InterpreterRuleContext ctx = new InterpreterRuleContext(null, -1, 0);
		p.setContext(ctx);
		final List<String> events = new ArrayList<String>();
		p.addParseListener(new ParseTreeListener() {
			@Override public void visitTerminal(TerminalNode node) { events.add("t:" + node.getText()); }
			@Override public void visitErrorNode(ErrorNode node) { events.add("e"); }
			@Override public void enterEveryRule(ParserRuleContext c) { }
			@Override public void exitEveryRule(ParserRuleContext c) { }
		});
		p.consume();
		assertEquals(Collections.singletonList("t:1"), events);
		assertEquals(1, ctx.getChildCount());
	}

	@Test
	public void enterRuleUsesSetState() {
		ParserInterpreter p = parser(1);
		InterpreterRuleContext ctx = new InterpreterRuleContext(null, -1, 0);
		int start = p.getATN().ruleToStartState[0].stateNumber;
		p.enterRule(ctx, start, 0);
		assertEquals(start, p.getState());
		assertSame(ctx, p.getContext());
		assertNotNull(ctx.start);
		assertEquals(1, ctx.start.getType());
	}

	@Test
	public void profilingReplacesInterpreter() {
		ParserInterpreter p = parser(1);
		p.setProfile(true);
		assertTrue(p.getInterpreter() instanceof ProfilingATNSimulator);
	}

	@Test
	public void bailErrorStrategyDisablesGeneratedSync() {
		ParserInterpreter p = parser(1);
		assertTrue(p.errorSyncEnabled());
		p.setErrorHandler(new BailErrorStrategy());
		assertFalse(p.errorSyncEnabled());
		p.setErrorHandler(new DefaultErrorStrategy());
		assertTrue(p.errorSyncEnabled());
	}

	@Test
	public void bailSubclassThatOverridesSyncMustReenable() {
		ParserInterpreter p = parser(1);
		p.setErrorHandler(new BailErrorStrategy() {
			@Override
			public void sync(Parser recognizer) {
				// custom work
			}

			@Override
			public boolean isSyncRequired() {
				return true;
			}
		});
		assertTrue(p.errorSyncEnabled());
	}

	@Test
	public void noConsumePredictPreservesLt1Cache() {
		ParserInterpreter p = parser(1);
		CommonTokenStream tokens = (CommonTokenStream) p.getInputStream();
		Token first = tokens.LT(1);
		assertSame(first, tokens.cachedLT1());
		// Rewind to the current index (adaptivePredict no-consume case).
		tokens.seek(tokens.index());
		assertSame(first, tokens.cachedLT1());
		assertSame(first, tokens.LT(1));
	}

	@Test
	public void consumeRecordsLastConsumedForExitRule() {
		ParserInterpreter p = parser(1, 2);
		p.setBuildParseTree(false);
		p.setState(p.getATN().ruleToStartState[0].stateNumber);
		InterpreterRuleContext ctx = new InterpreterRuleContext(null, -1, 0);
		p.setContext(ctx);
		Token first = p.getInputStream().LT(1);
		p.consume(first);
		assertSame(first, p.lastConsumed);
		p.exitRule();
		assertSame(first, ctx.stop);
	}

	@Test
	public void matchDoesNotCallReportMatchWhenNotRecovering() {
		ParserInterpreter p = parser(1);
		p.setBuildParseTree(false);
		p.setState(p.getATN().ruleToStartState[0].stateNumber);
		p.setContext(new InterpreterRuleContext(null, -1, 0));
		final int[] reports = new int[1];
		p.setErrorHandler(new DefaultErrorStrategy() {
			@Override
			public void reportMatch(Parser recognizer) {
				reports[0]++;
				super.reportMatch(recognizer);
			}
		});
		assertFalse(p.errorRecoveryMode);
		p.match(1);
		assertEquals("success path must skip reportMatch", 0, reports[0]);
	}

	@Test
	public void matchCallsReportMatchWhileRecovering() {
		ParserInterpreter p = parser(1);
		p.setBuildParseTree(false);
		p.setState(p.getATN().ruleToStartState[0].stateNumber);
		p.setContext(new InterpreterRuleContext(null, -1, 0));
		final int[] reports = new int[1];
		class ArmingStrategy extends DefaultErrorStrategy {
			void arm(Parser rec) {
				beginErrorCondition(rec);
			}

			@Override
			public void reportMatch(Parser recognizer) {
				reports[0]++;
				super.reportMatch(recognizer);
			}
		}
		ArmingStrategy strategy = new ArmingStrategy();
		p.setErrorHandler(strategy);
		strategy.arm(p);
		assertTrue(p.errorRecoveryMode);
		p.match(1);
		assertEquals(1, reports[0]);
		assertFalse("reportMatch must leave recovery", p.errorRecoveryMode);
	}

}
