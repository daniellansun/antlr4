/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.v4.Tool;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.CodeGenPipeline;
import org.antlr.v4.parse.TokenVocabParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.SemanticContext;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.tool.BuildDependencyGenerator;
import org.antlr.v4.tool.DOTGenerator;
import org.antlr.v4.tool.ErrorManager;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LeftRecursiveRule;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ToolMessage;
import org.antlr.v4.tool.ast.ActionAST;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.PredAST;
import org.antlr.v4.tool.ast.RuleAST;
import org.junit.Test;
import org.stringtemplate.v4.ST;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * Coverage for {@link org.antlr.v4.tool.DOTGenerator} ATN/DFA serialization to DOT.
 */
public class TestDOTGeneratorCoverage extends BaseTest {

	static {
		// Keep CI/surefire free of accidental AWT init from shared JVM forks.
		System.setProperty("java.awt.headless", "true");
	}


	@Test
	public void testDOTGeneratorATNAndDFA() throws Exception {
		Grammar g = new Grammar("grammar T;\n a : 'x' | 'y' ;\n WS : [ \\n]+ -> skip ;\n");
		g.tool.process(g, false);
		assertNotNull(g.atn);
		DOTGenerator dotGen = new DOTGenerator(g);
		Rule r = g.getRule("a");
		String atnDot = dotGen.getDOT(g.atn.ruleToStartState[r.index]);
		assertNotNull(atnDot);

		LexerGrammar lg = g.implicitLexer;
		if (lg != null && lg.atn != null) {
			LexerInterpreter lex = lg.createLexerInterpreter(CharStreams.fromString("x"));
			CommonTokenStream tokens = new CommonTokenStream(lex);
			ParserInterpreter parser = g.createParserInterpreter(tokens);
			ParseTree tree = parser.parse(r.index);
			assertNotNull(tree);
			if (g.atn.decisionToDFA != null) {
				for (DFA dfa : g.atn.decisionToDFA) {
					if (dfa != null && dfa.s0.get() != null) {
						String dfaDot = dotGen.getDOT(dfa, false);
						assertNotNull(dfaDot);
						break;
					}
				}
			}
		}
	}



	@Test
	public void testDOTGeneratorLexerDFA() throws Exception {
		LexerGrammar lg = new LexerGrammar(
		"lexer grammar L;\n A : 'a' | 'b' ;\n");
		lg.tool.process(lg, false);
		assertNotNull(lg.atn);
		DOTGenerator dotGen = new DOTGenerator(lg);
		LexerInterpreter lex = lg.createLexerInterpreter(CharStreams.fromString("a"));
		lex.nextToken();
		if (lg.atn.decisionToDFA != null) {
			for (DFA dfa : lg.atn.decisionToDFA) {
				if (dfa != null && dfa.s0.get() != null) {
					String dfaDot = dotGen.getDOT(dfa, true);
					assertNotNull(dfaDot);
					break;
				}
			}
		}
		String atnDot = dotGen.getDOT(lg.atn.ruleToStartState[lg.getRule("A").index], true);
		assertNotNull(atnDot);
	}

}
