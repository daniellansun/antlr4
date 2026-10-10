/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.test.tool;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.tool.Grammar;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Left-recursive expressions are precedence decisions. The precedence LL(1)
 * cache must predict the same tree as a full DFA walk, including a second
 * parse that hits the warm table.
 */
public class TestPrecedenceLL1Cache extends BaseTest {

	private static final String GRAMMAR =
		"grammar T;\n" +
		"s : e EOF ;\n" +
		"e : e '*' e\n" +
		"  | e '+' e\n" +
		"  | INT\n" +
		"  ;\n" +
		"INT : [0-9]+ ;\n" +
		"WS : [ \\t\\r\\n]+ -> skip ;\n";

	@Test
	public void expressionTreesMatchWithCacheOnOffAndWarm() throws Exception {
		String[] inputs = {
			"1",
			"1+2",
			"1+2*3",
			"1*2+3",
			"8+8+8",
			"1*2*3*4",
			"1 + 2 * 3"
		};
		for (int i = 0; i < inputs.length; i++) {
			String input = inputs[i];
			String sllOff = parse(input, false, PredictionMode.SLL);
			String sllOn = parse(input, true, PredictionMode.SLL);
			String llOff = parse(input, false, PredictionMode.LL);
			String llOn = parse(input, true, PredictionMode.LL);
			assertEquals(input, sllOff, sllOn);
			assertEquals(input, llOff, llOn);
			assertEquals(input, sllOn, parseTwice(input));
		}
		// Left-recursive stop tokens must stay the last operand, not LT(-1)
		// walking onto a hidden token or EOF. "1+2*3" ends on INT 3.
		assertEquals("3", expressionStopText("1+2*3"));
		assertEquals("1", expressionStopText("1"));
		assertEquals("4", expressionStopText("1+2*3+4"));
	}

	private String expressionStopText(String input) throws Exception {
		Grammar g = grammar();
		LexerInterpreter lex = g.createLexerInterpreter(CharStreams.fromString(input));
		lex.removeErrorListeners();
		CommonTokenStream tokens = new CommonTokenStream(lex);
		ParserInterpreter parser = g.createParserInterpreter(tokens);
		parser.removeErrorListeners();
		ParserRuleContext tree = parser.parse(g.getRule("s").index);
		assertEquals(input, 0, parser.getNumberOfSyntaxErrors());
		ParserRuleContext expr = (ParserRuleContext) tree.getChild(0);
		Token stop = expr.stop;
		assertNotNull(stop);
		assertEquals(Token.DEFAULT_CHANNEL, stop.getChannel());
		return stop.getText();
	}

	private String parse(String input, boolean optimizeLl1, PredictionMode mode) throws Exception {
		Grammar g = grammar();
		LexerInterpreter lex = g.createLexerInterpreter(CharStreams.fromString(input));
		lex.removeErrorListeners();
		CommonTokenStream tokens = new CommonTokenStream(lex);
		ParserInterpreter parser = g.createParserInterpreter(tokens);
		parser.removeErrorListeners();
		parser.getInterpreter().optimize_ll1 = optimizeLl1;
		parser.getInterpreter().setPredictionMode(mode);
		ParserRuleContext tree = parser.parse(g.getRule("s").index);
		assertEquals(input + " " + mode + " ll1=" + optimizeLl1 + " " + tokens.getTokens(),
			0, parser.getNumberOfSyntaxErrors());
		return tree.toStringTree(parser);
	}

	private String parseTwice(String input) throws Exception {
		Grammar g = grammar();
		LexerInterpreter lex = g.createLexerInterpreter(CharStreams.fromString(input));
		lex.removeErrorListeners();
		CommonTokenStream tokens = new CommonTokenStream(lex);
		ParserInterpreter parser = g.createParserInterpreter(tokens);
		parser.removeErrorListeners();
		parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
		int rule = g.getRule("s").index;
		parser.parse(rule);
		assertEquals(input, 0, parser.getNumberOfSyntaxErrors());
		tokens.seek(0);
		parser.reset();
		ParserRuleContext tree = parser.parse(rule);
		assertEquals(input, 0, parser.getNumberOfSyntaxErrors());
		return tree.toStringTree(parser);
	}

	/** {@link Grammar#Grammar(String)} already runs {@code tool.process}. */
	private Grammar grammar() throws Exception {
		Grammar g = new Grammar(GRAMMAR);
		assertEquals(0, g.tool.getNumErrors());
		return g;
	}
}
