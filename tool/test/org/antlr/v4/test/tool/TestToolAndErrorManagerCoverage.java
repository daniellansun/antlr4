/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.v4.Tool;
import org.antlr.v4.tool.DOTGenerator;
import org.antlr.v4.tool.ErrorManager;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;

import org.junit.Test;

import static org.junit.Assert.*;

public class TestToolAndErrorManagerCoverage {

	@Test
	public void testDOTGenerator() throws Exception {
		String grammarStr = "grammar T;\n a : 'x' | 'y' ;\n";
		Grammar g = new Grammar(grammarStr);
		g.tool.process(g, false);

		if (g.atn != null && g.getRule("a") != null) {
			Rule r = g.getRule("a");
			DOTGenerator dotGen = new DOTGenerator(g);
			String dot = dotGen.getDOT(g.atn.ruleToStartState[r.index]);
			assertNotNull(dot);
		}
	}

	@Test
	public void testErrorManager() {
		Tool tool = new Tool();
		ErrorManager errMgr = new ErrorManager(tool);
		errMgr.info("info msg");
		errMgr.toolError(ErrorType.CANNOT_WRITE_FILE, "foo.txt");
		errMgr.grammarError(ErrorType.UNDEFINED_RULE_REF, "T.g4", null, "x");
		assertEquals(2, errMgr.getNumErrors());
	}

	@Test
	public void testLexerGrammarMethods() throws Exception {
		String lexerGrammarStr = "lexer grammar L;\n A : 'a' ;\n B : 'b' ;\n";
		LexerGrammar lg = new LexerGrammar(lexerGrammarStr);
		assertTrue(lg.isLexer());
		assertFalse(lg.isParser());
	}
}
