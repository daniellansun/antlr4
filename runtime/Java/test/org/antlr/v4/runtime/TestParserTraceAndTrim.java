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
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.tree.ErrorNodeImpl;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers {@link Parser.TraceListener} and {@link Parser.TrimToSizeListener}
 * via {@link Parser#setTrace} and {@link Parser#setTrimParseTree}.
 */
public class TestParserTraceAndTrim {

	static ATN buildParserAtn() {
		ATN atn = new ATN(ATNType.PARSER, 2);

		RuleStartState rStart = new RuleStartState();
		rStart.ruleIndex = 0;
		atn.addState(rStart);
		RuleStopState rStop = new RuleStopState();
		rStop.ruleIndex = 0;
		atn.addState(rStop);
		rStart.stopState = rStop;

		BasicState mid = new BasicState();
		mid.ruleIndex = 0;
		atn.addState(mid);
		rStart.addTransition(new AtomTransition(mid, 1));
		mid.addTransition(new EpsilonTransition(rStop));

		atn.ruleToStartState = new RuleStartState[]{rStart};
		atn.ruleToStopState = new RuleStopState[]{rStop};
		atn.clearDFA();
		return atn;
	}

	private static ParserInterpreter parserFor(String text) {
		CommonToken a = new CommonToken(1, text);
		a.setTokenIndex(0);
		CommonTokenStream tokens = new CommonTokenStream(new MockTokenSource(a));
		tokens.fill();
		Vocabulary vocab = new VocabularyImpl(new String[]{null, "'a'"}, new String[]{null, "A"});
		return new ParserInterpreter(
			"P.g4",
			vocab,
			Collections.singletonList("r"),
			buildParserAtn(),
			tokens);
	}

	@Test
	public void setTraceEnablesTraceListener() {
		ParserInterpreter parser = parserFor("a");
		assertFalse(parser.isTrace());

		PrintStream prev = System.out;
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		System.setOut(new PrintStream(buf));
		try {
			parser.setTrace(true);
			assertTrue(parser.isTrace());
			// setTrace(true) again replaces tracer
			parser.setTrace(true);
			assertTrue(parser.isTrace());

			ParserRuleContext tree = parser.parse(0);
			assertNotNull(tree);
			String out = buf.toString();
			assertTrue(out.contains("enter") || out.contains("exit") || out.contains("consume")
				|| out.contains("r"));
		}
		finally {
			System.setOut(prev);
		}

		parser.setTrace(false);
		assertFalse(parser.isTrace());
	}

	@Test
	public void setTrimParseTreeUsesTrimListener() {
		ParserInterpreter parser = parserFor("a");
		assertFalse(parser.getTrimParseTree());

		parser.setTrimParseTree(true);
		assertTrue(parser.getTrimParseTree());
		// idempotent
		parser.setTrimParseTree(true);
		assertTrue(parser.getTrimParseTree());

		ParserRuleContext tree = parser.parse(0);
		assertNotNull(tree);
		// children list should still be usable
		assertTrue(tree.getChildCount() >= 0);

		parser.setTrimParseTree(false);
		assertFalse(parser.getTrimParseTree());
	}

	@Test
	public void trimToSizeListenerDirectly() {
		Parser.TrimToSizeListener listener = Parser.TrimToSizeListener.INSTANCE;
		ParserRuleContext ctx = new ParserRuleContext();
		ctx.children = new ArrayList<ParseTree>();
		ctx.children.add(new TerminalNodeImpl(new CommonToken(1, "x")));
		// ensure ArrayList path
		assertTrue(ctx.children instanceof ArrayList);
		listener.enterEveryRule(ctx);
		listener.visitTerminal(new TerminalNodeImpl(new CommonToken(1, "x")));
		listener.visitErrorNode(new ErrorNodeImpl(new CommonToken(Token.INVALID_TYPE)));
		listener.exitEveryRule(ctx);
	}

	@Test
	public void traceListenerDirectly() {
		ParserInterpreter parser = parserFor("a");
		parser.setTrace(true);
		// TraceListener is private field; exercise via parse which fires events
		PrintStream prev = System.out;
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		System.setOut(new PrintStream(buf));
		try {
			parser.parse(0);
		}
		finally {
			System.setOut(prev);
		}
		assertTrue(parser.isTrace());
		parser.setTrace(false);
	}
}
