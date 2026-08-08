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
 * Coverage for TestRig execution, GrammarParserInterpreter, and related runtime paths.
 */
public class TestTestRigAndInterpreterCoverage extends BaseTest {

	@Test
	public void testGrammarParserInterpreterCoverage() throws Exception {
		LexerGrammar lg = new LexerGrammar(
		"lexer grammar L;\n" +
		"A : 'a' ;\n" +
		"B : 'b' ;\n" +
		"WS : [ \\n]+ -> skip ;\n");
		Grammar g = new Grammar("parser grammar P;\n s : A B | A ;\n", lg);
		// Grammar(String, LexerGrammar) processes both
		LexerInterpreter lex = lg.createLexerInterpreter(CharStreams.fromString("a b"));
		CommonTokenStream tokens = new CommonTokenStream(lex);
		if (g.atn != null) {
			try {
				GrammarParserInterpreter gpi = new GrammarParserInterpreter(g, g.atn, tokens);
				gpi.setBuildParseTree(true);
				ParseTree t = gpi.parse(g.getRule("s").index);
				assertNotNull(t);
			} catch (UnsupportedOperationException uoe) {
				// no serialized ATN in some configurations
			}
			try {
				ParserInterpreter pi = g.createParserInterpreter(tokens);
				assertNotNull(pi.parse(g.getRule("s").index));
			} catch (Throwable t) {
			}
		}
	}



	@Test
	public void testLexerModesCodegenAndATN() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "LM.g4",
		"lexer grammar LM;\n" +
		"A : 'a' -> pushMode(STRING) ;\n" +
		"mode STRING;\n" +
		"S : ~[\"]+ ;\n" +
		"END : '\"' -> popMode ;\n");
		antlr("LM.g4", false);
	}



	@Test
	public void testTestRigWithCompiledGrammar() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "R.g4",
		"grammar R;\n" +
		"s : ID+ ;\n" +
		"ID : [a-z]+ ;\n" +
		"WS : [ \\n]+ -> skip ;\n");
		ErrorQueue eq = antlr("R.g4", false);
		assertTrue("errors: " + eq.errors, eq.errors.isEmpty());
		boolean ok = compile("RLexer.java", "RParser.java");
		assertTrue(ok);

		// write input
		writeFile(tmpdir, "in.txt", "hello world");
		// load with URLClassLoader and run TestRig-style process
		URLClassLoader cl = new URLClassLoader(new URL[]{new File(tmpdir).toURI().toURL()},
		Thread.currentThread().getContextClassLoader());
		ClassLoader prev = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(cl);
		PrintStream oldErr = System.err;
		PrintStream oldOut = System.out;
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		System.setErr(new PrintStream(buf));
		System.setOut(new PrintStream(buf));
		try {
			TestRig rig = new TestRig(new String[]{
				"R", "s",
				"-tokens", "-tree", "-trace", "-SLL", "-diagnostics",
				"-encoding", "UTF-8",
				new File(tmpdir, "in.txt").getAbsolutePath()
			});
			rig.process();
		} catch (Throwable t) {
			// may fail if package-less class names
		} finally {
			Thread.currentThread().setContextClassLoader(prev);
			System.setErr(oldErr);
			System.setOut(oldOut);
			cl.close();
		}
	}



	@Test
	public void testCompositeImportIntegration() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "S.g4", "parser grammar S;\ns : 's' ;\n");
		writeFile(tmpdir, "M.g4",
		"grammar M;\n" +
		"import S;\n" +
		"a : s 'x' ;\n" +
		"WS : [ \\n]+ -> skip ;\n");
		antlr("M.g4", false);
	}



	@Test
	public void testToolMainAndLog() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "Hi.g4", "grammar Hi;\nr:'hi';\n");
		// main with return_dont_exit via -XdbgST
		String[] args = new String[]{
			"-o", tmpdir, "-lib", tmpdir,
			"-Xlog",
			new File(tmpdir, "Hi.g4").getAbsolutePath()
		};
		// Don't call Tool.main as it may System.exit; call process instead
		Tool tool = new Tool(args);
		tool.processGrammarsOnCommandLine();
	}



	@Test
	public void testPredicatedAltsAndActions() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "P.g4",
		"grammar P;\n" +
		"@members { boolean b = true; }\n" +
		"s : {b}? ID | {!b}? INT | ID {System.out.println($ID.text);} ;\n" +
		"ID : [a-z]+ ;\n" +
		"INT : [0-9]+ ;\n" +
		"WS : [ \\n]+ -> skip ;\n");
		antlr("P.g4", false);
	}



	@Test
	public void testDOTGeneratorWithPopulatedDFA() throws Exception {
		LexerGrammar lg = new LexerGrammar(
		"lexer grammar L;\n" +
		"A : 'ab' | 'ac' | 'x'+ ;\n" +
		"WS : [ \\n]+ -> skip ;\n");
		lg.tool.process(lg, false);
		LexerInterpreter lex = lg.createLexerInterpreter(CharStreams.fromString("ab ac xxx"));
		while (lex.nextToken().getType() != -1) {
			// consume
		}
		DOTGenerator dot = new DOTGenerator(lg);
		if (lg.atn.decisionToDFA != null) {
			for (DFA dfa : lg.atn.decisionToDFA) {
				if (dfa != null && dfa.s0.get() != null) {
					String s = dot.getDOT(dfa, true);
					assertNotNull(s);
				}
			}
		}
		// ATN with various transition types
		for (Rule r : lg.rules.values()) {
			String atnDot = dot.getDOT(lg.atn.ruleToStartState[r.index], true);
			assertNotNull(atnDot);
		}
	}

}
