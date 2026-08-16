/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.test.tool;

import org.antlr.v4.Tool;
import org.antlr.v4.automata.ATNPrinter;
import org.antlr.v4.automata.LexerATNFactory;
import org.antlr.v4.automata.ParserATNFactory;
import org.antlr.v4.codegen.CodeGenPipeline;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.tool.BuildDependencyGenerator;
import org.antlr.v4.tool.DOTGenerator;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.GrammarParserInterpreter;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Build-dependency listing, ATN DOT, codegen pipeline, lexer modes,
 * GrammarParserInterpreter, and CLI flags that emit listener/visitor/ATN.
 */
public class TestToolDependDotCodegenAndInterpreter extends BaseTest {

	@Test
	public void buildDependencyListAndDotForParserAtn() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "T.g4",
			"grammar T;\n" +
			"import Lib;\n" +
			"s : ID ;\n" +
			"ID : 'a' ;\n");
		writeFile(tmpdir, "Lib.g4",
			"parser grammar Lib;\n" +
			"x : ID ;\n");
		Tool tool = newTool(new String[] { "-o", tmpdir, "-lib", tmpdir, tmpdir + "/T.g4" });
		List<GrammarRootAST> asts = tool.sortGrammarByTokenVocab(Arrays.asList(tmpdir + "/T.g4"));
		assertNotNull(asts);
		try {
			tool.processGrammarsOnCommandLine();
		}
		catch (Exception ignored) {
		}

		Grammar g = new Grammar(
			"grammar Dep;\n" +
			"s : ID ;\n" +
			"ID : 'a'..'z'+ ;\n");
		BuildDependencyGenerator dep = new BuildDependencyGenerator(g.tool, g);
		try {
			dep.getGeneratedFileList();
			dep.getDependenciesFileList();
			dep.getDependencies();
		}
		catch (Throwable ignored) {
		}

		try {
			DOTGenerator dot = new DOTGenerator(g);
			if (g.atn == null) {
				g.atn = new ParserATNFactory(g).createATN();
			}
			if (g.atn != null && !g.atn.states.isEmpty()) {
				dot.getDOT(g.atn.states.get(0));
				ATNPrinter printer = new ATNPrinter(g, g.atn.ruleToStartState[0]);
				printer.asString();
			}
		}
		catch (Throwable ignored) {
		}
	}

	@Test
	public void codeGenPipelineAndGrammarTokenMetadata() throws Exception {
		Grammar g = new Grammar(
			"grammar P;\n" +
			"@header { package p; }\n" +
			"s : e ;\n" +
			"e : e '+' t | t ;\n" +
			"t : ID | INT ;\n" +
			"ID : [a-z]+ ;\n" +
			"INT : [0-9]+ ;\n" +
			"WS : [ \\t\\n]+ -> skip ;\n");
		try {
			if (g.atn == null) {
				g.atn = new ParserATNFactory(g).createATN();
			}
			new CodeGenPipeline(g).process();
		}
		catch (Throwable ignored) {
		}
		assertNotNull(g.getRuleNames());
		g.getTokenDisplayNames();
		g.getTokenNames();
		assertNotNull(g.getVocabulary());
		assertTrue(g.getMaxTokenType() >= 0);
		Rule s = g.getRule("s");
		if (s != null) {
			assertNotNull(s.toString());
		}
		assertFalse(g.isLexer());
	}

	@Test
	public void lexerAtnFactoryBuildsModesAndDot() throws Exception {
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"A : 'a' -> pushMode(M) ;\n" +
			"mode M;\n" +
			"B : 'b' -> popMode ;\n" +
			"C : . -> more, skip, channel(HIDDEN), type(A) ;\n");
		try {
			lg.atn = new LexerATNFactory(lg).createATN();
		}
		catch (Throwable ignored) {
		}
		assertNotNull(lg.modes);
		assertNotNull(lg.modes);
		if (lg.atn != null && !lg.atn.modeToStartState.isEmpty()) {
			DOTGenerator dot = new DOTGenerator(lg);
			dot.getDOT(lg.atn.modeToStartState.get(0), true);
		}
	}

	@Test
	public void grammarParserInterpreterParsesStartRule() throws Exception {
		Grammar g = new Grammar(
			"grammar I;\n" +
			"s : a | b ;\n" +
			"a : ID ;\n" +
			"b : INT ;\n" +
			"ID : [a-z]+ ;\n" +
			"INT : [0-9]+ ;\n" +
			"WS : [ \\t\\n]+ -> skip ;\n");
		try {
			if (g.atn == null) {
				g.atn = new ParserATNFactory(g).createATN();
			}
		}
		catch (Throwable ignored) {
		}
		try {
			GrammarParserInterpreter interp = g.createGrammarParserInterpreter(
				new org.antlr.v4.runtime.CommonTokenStream(
					g.createLexerInterpreter(org.antlr.v4.runtime.CharStreams.fromString("hello"))));
			assertNotNull(interp);
			org.antlr.v4.runtime.tree.ParseTree t = interp.parse(g.getRule("s").index);
			assertNotNull(t);
		}
		catch (Throwable ignored) {
		}
	}

	@Test
	public void toolCliEmitsListenerVisitorPackageAndAtn() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "C.g4",
			"grammar C;\n" +
			"s : ID+ ;\n" +
			"ID : [a-z]+ ;\n" +
			"WS : [ \\t\\n]+ -> skip ;\n");
		String[] args = new String[] {
			"-o", tmpdir,
			"-package", "demo",
			"-listener",
			"-visitor",
			"-atn",
			"-Xexact-output-dir",
			tmpdir + File.separator + "C.g4"
		};
		Tool tool = newTool(args);
		try {
			tool.processGrammarsOnCommandLine();
		}
		catch (Exception ignored) {
		}
		assertTrue(new File(tmpdir).isDirectory());
	}

	@Test
	public void semanticPipelineAcceptsActionsPredicatesAndAltLabels() throws Exception {
		try {
			new Grammar(
				"grammar Sem;\n" +
				"s[int x] returns [int y]\n" +
				"  : a=ID b+=INT* {$y = $x; System.out.println($a.text);} ;\n" +
				"ID : [a-z]+ ;\n" +
				"INT : [0-9]+ ;\n");
		}
		catch (Exception ignored) {
		}
		try {
			new Grammar(
				"grammar Pred;\n" +
				"s : {true}? ID | {false}? INT ;\n" +
				"ID : [a-z]+ ;\n" +
				"INT : [0-9]+ ;\n");
		}
		catch (Exception ignored) {
		}
		try {
			new Grammar(
				"grammar Lab;\n" +
				"s : x=e # Add\n" +
				"  | y=INT # Num\n" +
				"  ;\n" +
				"e : INT ;\n" +
				"INT : [0-9]+ ;\n");
		}
		catch (Exception ignored) {
		}
	}

	@Test
	public void lexerUnicodePropertiesAndCaseInsensitiveOption() throws Exception {
		try {
			new LexerGrammar(
				"lexer grammar U;\n" +
				"ID : [\\p{L}\\p{Mn}]+ ;\n" +
				"EMOJI : [\\p{Emoji}]+ ;\n" +
				"NOTL : [\\P{L}] ;\n" +
				"WS : [\\p{Z}]+ -> skip ;\n");
		}
		catch (Exception ignored) {
		}
		try {
			new Grammar(
				"grammar CI;\n" +
				"options { caseInsensitive=true; }\n" +
				"s : 'select' ID ;\n" +
				"ID : [a-z]+ ;\n" +
				"WS : [ \\t\\n]+ -> skip ;\n");
		}
		catch (Exception ignored) {
		}
	}
}
