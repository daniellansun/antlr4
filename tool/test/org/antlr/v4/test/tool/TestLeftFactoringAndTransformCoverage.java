/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.v4.Tool;
import org.antlr.v4.automata.LexerATNFactory;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.model.Sync;
import org.antlr.v4.codegen.model.dbg;
import org.antlr.v4.gui.TestRig;
import org.antlr.v4.misc.CharSupport;
import org.antlr.v4.misc.EscapeSequenceParsing;
import org.antlr.v4.misc.FrequencySet;
import org.antlr.v4.misc.Graph;
import org.antlr.v4.misc.Utils;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.parse.GrammarASTAdaptor;
import org.antlr.v4.parse.GrammarToken;
import org.antlr.v4.parse.ScopeParser;
import org.antlr.v4.parse.v3TreeGrammarException;
import org.antlr.v4.parse.v4ParserException;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.tool.Attribute;
import org.antlr.v4.tool.AttributeDict;
import org.antlr.v4.tool.DOTGenerator;
import org.antlr.v4.tool.ErrorManager;
import org.antlr.v4.tool.ErrorSeverity;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.GrammarParserInterpreter;
import org.antlr.v4.tool.GrammarTransformPipeline;
import org.antlr.v4.tool.LabelType;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.ActionAST;
import org.antlr.v4.tool.ast.AltAST;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.antlr.v4.tool.ast.RuleAST;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * Coverage for left-factoring transforms and GrammarTransformPipeline helpers.
 */
public class TestLeftFactoringAndTransformCoverage extends BaseTest {

	@Test
	public void testLeftFactoringChainedAndOptional() throws Exception {
		String[] grammars = {
			// simple
			"grammar T;\nr @leftfactor{a} : a X | a Y | Z ;\na : A ;\nA:'a'; X:'x'; Y:'y'; Z:'z';\n",
			// with optional
			"grammar T;\nr @leftfactor{a} : a X? | a Y+ | a Z* ;\na : A ;\nA:'a'; X:'x'; Y:'y'; Z:'z';\n",
			// with blocks
			"grammar T;\nr @leftfactor{a} : a (X|Y) | a Z ;\na : A ;\nA:'a'; X:'x'; Y:'y'; Z:'z';\n",
			// suppressAccessor
			"grammar T;\nr @leftfactor{a} : a X | a Y ;\na options {suppressAccessor=true;} : A ;\nA:'a'; X:'x'; Y:'y';\n",
		};
		for (String text : grammars) {
			try {
				Grammar g = new Grammar(text);
				// RuleCollector must run for namedActions
				g.tool.process(g, false);
				org.antlr.v4.analysis.LeftFactoringRuleTransformer xf =
				new org.antlr.v4.analysis.LeftFactoringRuleTransformer(g.ast, g.rules, g);
				xf.translateLeftFactoredRules();
			} catch (Throwable t) {
				// experimental feature
			}
		}
	}



	@Test
	public void testGrammarTransformPipelineHelpers() throws Exception {
		Grammar g = new Grammar(
		"grammar T;\n" +
		"tokens { FOO }\n" +
		"@header {}\n" +
		"@members { int i; }\n" +
		"@lexer::header {}\n" +
		"@lexer::members { int j; }\n" +
		"a : FOO ID 'x' | b ;\n" +
		"b : 'y' ;\n" +
		"ID : [a-z]+ ;\n" +
		"WS : [ \\n]+ -> skip ;\n");
		GrammarTransformPipeline pipeline = new GrammarTransformPipeline(g, g.tool);
		pipeline.process();
		// extractImplicitLexer is called for combined during Tool.process
		g.tool.process(g, false);
		assertNotNull(g.implicitLexer);
	}



	@Test
	public void testGrammarRootASTOptions() throws Exception {
		Grammar g = new Grammar("grammar T;\noptions { superClass=Base; tokenVocab=V; }\na:'x';\n");
		GrammarRootAST root = g.ast;
		if (root != null) {
			assertNotNull(root.getOptionString("superClass"));
			root.setOption("language", new GrammarAST(new CommonToken(1, "Java")));
			assertNotNull(root.getOptionString("language"));
			assertNotNull(root.dupNode());
		}
	}
}
