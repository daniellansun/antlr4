/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.Token;

import org.antlr.v4.Tool;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.tool.BuildDependencyGenerator;
import org.antlr.v4.tool.DefaultToolListener;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.GrammarInterpreterRuleContext;
import org.antlr.v4.tool.LabelElementPair;
import org.antlr.v4.tool.LeftRecursionCyclesMessage;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ToolMessage;
import org.antlr.v4.tool.ast.*;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;

public class TestToolASTAndMiscCoverage {

	@Test
	public void testBuildDependencyGenerator() throws Exception {
		Tool tool = new Tool(new String[]{"-o", "outdir"});
		Grammar g = new Grammar("T.g4", "grammar T;\n a : 'x';\n");

		BuildDependencyGenerator depGen = new BuildDependencyGenerator(tool, g);
		assertNotNull(depGen.getGenerator());
		assertEquals("T.g4", depGen.groomQualifiedFileName(".", "T.g4"));
		assertEquals("outdir" + File.separator + "T.g4", depGen.groomQualifiedFileName("outdir", "T.g4"));
		assertEquals("out\\ dir" + File.separator + "T.g4", depGen.groomQualifiedFileName("out dir", "T.g4"));
	}

	@Test
	public void testLeftRecursionCyclesMessage() {
		Rule r1 = new Rule(null, "a", null, 1);
		Rule r2 = new Rule(null, "b", null, 1);
		Collection<? extends Collection<Rule>> cycles = Arrays.asList(
				Arrays.asList(r1, r2)
		);
		LeftRecursionCyclesMessage msg = new LeftRecursionCyclesMessage("T.g4", cycles);
		assertNotNull(msg);
		assertNotNull(msg.getArgs());
	}

	@Test
	public void testToolMessageAndDefaultListener() {
		ToolMessage msg = new ToolMessage(org.antlr.v4.tool.ErrorType.CANNOT_WRITE_FILE, "foo.txt");
		assertEquals(org.antlr.v4.tool.ErrorType.CANNOT_WRITE_FILE, msg.getErrorType());

		DefaultToolListener listener = new DefaultToolListener(new Tool());
		listener.info("info msg");
		listener.error(msg);
		listener.warning(msg);
	}

	@Test
	public void testLabelElementPair() throws Exception {
		LexerGrammar g = new LexerGrammar("lexer grammar L;\n A: 'a';\n");
		GrammarAST labelAST = new GrammarAST(new CommonToken(1, "x"));
		GrammarAST elemAST = new GrammarAST(new CommonToken(2, "A"));
		elemAST.addChild(new GrammarAST(new CommonToken(ANTLRParser.TOKEN_REF, "A")));

		LabelElementPair pair = new LabelElementPair(g, labelAST, elemAST, ANTLRParser.ASSIGN);
		assertNotNull(pair.toString());
	}

	@Test
	public void testGrammarInterpreterRuleContext() {
		GrammarInterpreterRuleContext ctx = new GrammarInterpreterRuleContext(null, 0, 10);
		ctx.setOuterAltNum(2);
		assertEquals(2, ctx.getOuterAltNum());
		assertEquals(2, ctx.getAltNumber());

		ctx.setAltNumber(3);
		assertEquals(3, ctx.getOuterAltNum());
	}

	@Test
	public void testGrammarASTClasses() {
		Token tok = new CommonToken(100, "TEST");
		CommonTokenStream stream = new CommonTokenStream();

		AltAST altAST = new AltAST(tok);
		assertNotNull(altAST.dupNode());

		RuleAST ruleAST = new RuleAST(tok);
		assertNotNull(ruleAST.dupNode());

		PredAST predAST = new PredAST(tok);
		assertNotNull(predAST.dupNode());

		GrammarRootAST rootAST = new GrammarRootAST(tok, stream);
		assertNotNull(rootAST.dupNode());

		RuleRefAST ruleRefAST = new RuleRefAST(tok);
		assertNotNull(ruleRefAST.dupNode());

		TerminalAST termAST = new TerminalAST(tok);
		assertNotNull(termAST.dupNode());

		RuleRefAST optionsAST = new RuleRefAST(tok);
		assertNotNull(optionsAST.dupNode());
		optionsAST.setOption("foo", new GrammarAST(new CommonToken(1, "bar")));
		assertEquals(1, optionsAST.getNumberOfOptions());

		GrammarASTErrorNode errNode = new GrammarASTErrorNode(null, tok, tok, null);
		assertNotNull(errNode);

		RangeAST rangeAST = new RangeAST(tok);
		assertNotNull(rangeAST.dupNode());

		NotAST notAST = new NotAST(100, tok);
		assertNotNull(notAST.dupNode());

		SetAST setAST = new SetAST(100, tok, "set");
		assertNotNull(setAST.dupNode());

		ActionAST actionAST = new ActionAST(tok);
		assertNotNull(actionAST.dupNode());
		assertNull(actionAST.getScope());
	}
}
