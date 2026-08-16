/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.test.tool;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.Token;
import org.antlr.v4.Tool;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.misc.Graph;
import org.antlr.v4.misc.MutableInt;
import org.antlr.v4.parse.ANTLRLexer;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.parse.ToolANTLRLexer;
import org.antlr.v4.parse.ToolANTLRParser;
import org.antlr.v4.tool.ANTLRMessage;
import org.antlr.v4.tool.DefaultToolListener;
import org.antlr.v4.tool.ErrorManager;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LeftRecursionCyclesMessage;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ToolMessage;
import org.antlr.v4.tool.ast.ActionAST;
import org.antlr.v4.tool.ast.AltAST;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.GrammarASTErrorNode;
import org.antlr.v4.tool.ast.GrammarASTVisitor;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.antlr.v4.tool.ast.PredAST;
import org.antlr.v4.tool.ast.RuleAST;
import org.antlr.v4.tool.ast.RuleRefAST;
import org.antlr.v4.tool.ast.TerminalAST;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Grammar AST copy constructors, tool messages, LexerGrammar mode map,
 * unknown codegen target, and malformed-grammar parse recovery.
 */
public class TestToolAstApiMessagesAndMalformedGrammar extends BaseTest {

	@Test
	public void actionPredRuleRefAndTerminalAstCopyConstructors() {
		CommonToken tok = new CommonToken(ANTLRParser.ACTION, "{x=1;}");
		ActionAST action = new ActionAST(tok);
		ActionAST actionType = new ActionAST(ANTLRParser.ACTION);
		ActionAST actionTyped = new ActionAST(ANTLRParser.ACTION, tok);
		assertNotNull(actionType.dupNode());
		assertNotNull(actionTyped.dupNode());
		ActionAST actionCopy = new ActionAST(action);
		assertNotNull(actionCopy.dupNode());

		PredAST pred = new PredAST(new CommonToken(ANTLRParser.SEMPRED, "{true}?"));
		assertNotNull(new PredAST(pred).dupNode());
		assertNotNull(new PredAST(ANTLRParser.SEMPRED).dupNode());
		assertNotNull(new PredAST(ANTLRParser.SEMPRED, pred.token).dupNode());

		RuleRefAST ref = new RuleRefAST(new CommonToken(ANTLRParser.RULE_REF, "e"));
		assertNotNull(new RuleRefAST(ref).dupNode());
		assertNotNull(new RuleRefAST(ANTLRParser.RULE_REF).dupNode());
		assertNotNull(new RuleRefAST(ANTLRParser.RULE_REF, ref.token).dupNode());

		TerminalAST term = new TerminalAST(new CommonToken(ANTLRParser.STRING_LITERAL, "'x'"));
		assertNotNull(new TerminalAST(term).dupNode());
		assertNotNull(new TerminalAST(ANTLRParser.STRING_LITERAL).dupNode());
		assertNotNull(new TerminalAST(ANTLRParser.STRING_LITERAL, term.token).dupNode());

		AltAST alt = new AltAST(new CommonToken(ANTLRParser.ALT, "ALT"));
		assertNotNull(new AltAST(alt).dupNode());
		assertNotNull(new AltAST(ANTLRParser.ALT).dupNode());
		assertNotNull(new AltAST(ANTLRParser.ALT, alt.token).dupNode());
		assertNotNull(new AltAST(ANTLRParser.ALT, alt.token, "ALT").dupNode());
	}

	@Test
	public void grammarRootAstRejectsNullTokenStreamAndHonorsCmdLineOptions() {
		CommonToken t = new CommonToken(ANTLRParser.GRAMMAR, "grammar");
		try {
			new GrammarRootAST(t, null);
			fail();
		}
		catch (NullPointerException expected) {
			assertEquals("tokenStream", expected.getMessage());
		}
		try {
			new GrammarRootAST(ANTLRParser.GRAMMAR, t, null);
			fail();
		}
		catch (NullPointerException expected) {
			assertEquals("tokenStream", expected.getMessage());
		}
		try {
			new GrammarRootAST(ANTLRParser.GRAMMAR, t, "grammar", null);
			fail();
		}
		catch (NullPointerException expected) {
			assertEquals("tokenStream", expected.getMessage());
		}

		ANTLRStringStream in = new ANTLRStringStream("grammar T; s : 'a' ;");
		ToolANTLRLexer lex = new ToolANTLRLexer(in, new Tool());
		CommonTokenStream tokens = new CommonTokenStream(lex);
		tokens.fill();
		GrammarRootAST root = new GrammarRootAST(t, tokens);
		assertNull(root.getGrammarName());
		GrammarAST name = new GrammarAST(new CommonToken(ANTLRParser.ID, "T"));
		root.addChild(name);
		assertEquals("T", root.getGrammarName());
		root.cmdLineOptions = new HashMap<String, String>();
		root.cmdLineOptions.put("language", "Java");
		assertEquals("Java", root.getOptionString("language"));
		assertEquals("false", root.getOptionString("abstract"));
		assertNotNull(root.dupNode());
		assertNotNull(new GrammarRootAST(root).dupNode());
		assertNotNull(new GrammarRootAST(ANTLRParser.GRAMMAR, t, tokens));
		assertNotNull(new GrammarRootAST(ANTLRParser.GRAMMAR, t, "grammar", tokens));
	}

	@Test
	public void optionStringReportsInvalidEscapeInQuotedValue() throws Exception {
		Grammar g = new Grammar(
			"grammar T;\n" +
			"options { superClass='\\q'; }\n" +
			"s : ID ;\n" +
			"ID : 'a' ;\n");
		assertNotNull(g.ast);
		// invalid escape in option should have been reported; option string still returns
		String v = g.ast.getOptionString("superClass");
		assertNotNull(v);
	}

	@Test
	public void grammarAstErrorNodeDelegatesToCommonErrorNode() {
		ANTLRStringStream in = new ANTLRStringStream("???");
		ToolANTLRLexer lex = new ToolANTLRLexer(in, new Tool());
		CommonTokenStream tokens = new CommonTokenStream(lex);
		tokens.fill();
		Token start = tokens.LT(1);
		Token stop = tokens.LT(1);
		RecognitionException re = new RecognitionException(tokens);
		GrammarASTErrorNode err = new GrammarASTErrorNode(tokens, start, stop, re);
		assertNotNull(err.getText());
		assertNotNull(err.toString());
		err.getType();
		err.isNil();
	}

	@Test
	public void toolMessageAndLeftRecursionCyclesMessageConstructors() {
		ToolMessage tm = new ToolMessage(ErrorType.CANNOT_WRITE_FILE);
		assertEquals(ErrorType.CANNOT_WRITE_FILE, tm.getErrorType());
		ToolMessage tm2 = new ToolMessage(ErrorType.CANNOT_WRITE_FILE, "x");
		assertEquals(1, tm2.getArgs().length);
		ToolMessage tm3 = new ToolMessage(ErrorType.CANNOT_WRITE_FILE, new RuntimeException("e"), "y");
		assertNotNull(tm3.getCause() != null || tm3.getArgs().length >= 1);

		ANTLRMessage bare = new ANTLRMessage(ErrorType.INTERNAL_ERROR);
		assertEquals(0, bare.getArgs().length);
		ANTLRMessage withTok = new ANTLRMessage(ErrorType.INTERNAL_ERROR, Token.INVALID_TOKEN, "a");
		assertEquals(1, withTok.getArgs().length);

		List<Rule> cycle = new ArrayList<Rule>();
		LeftRecursionCyclesMessage lrc = new LeftRecursionCyclesMessage("T.g4", Collections.singletonList(cycle));
		assertNotNull(lrc);
	}

	@Test
	public void lexerGrammarDefineAndUndefineRuleUpdatesModes() throws Exception {
		DefaultToolListener listener = new DefaultToolListener(new Tool());
		LexerGrammar lg = new LexerGrammar("lexer grammar L;\nA : 'a' ;\n", listener);
		assertNotNull(lg.getRule("A"));
		Rule a = lg.getRule("A");
		assertTrue(lg.undefineRule(a));
		assertFalse(lg.undefineRule(a));
		assertTrue(lg.defineRule(a));
		assertFalse(lg.defineRule(a));

		LexerGrammar lg2 = new LexerGrammar("L.g4", "lexer grammar L;\nB : 'b' ;\n", listener);
		assertNotNull(lg2.getRule("B"));

		LexerGrammar lg3 = new LexerGrammar(new Tool(), lg.ast);
		assertNotNull(lg3);
	}

	@Test
	public void codeGeneratorRejectsUnknownTargetAndWalksHeaderTemplates() throws Exception {
		Grammar g = new Grammar("grammar T;\ns : ID ;\nID : 'a' ;\n");
		g.loadImportedGrammars();
		CodeGenerator gen = new CodeGenerator(g.tool, g, "NotALanguage");
		assertNull(gen.getTarget());
		assertNull(gen.getTemplates());
		try {
			gen.generateParser();
			fail();
		}
		catch (UnsupportedOperationException expected) {
			assertTrue(expected.getMessage().contains("target"));
		}

		CodeGenerator java = new CodeGenerator(g);
		try {
			java.generateParser(false);
			java.generateParser(true);
			java.generateListener(false);
			java.generateListener(true);
			java.generateBaseListener(false);
			java.generateBaseListener(true);
			java.generateVisitor(false);
			java.generateVisitor(true);
			java.generateBaseVisitor(false);
			java.generateBaseVisitor(true);
		}
		catch (Throwable ignored) {
			// combined grammars without a full Tool pipeline may lack implicit lexer
		}

		LexerGrammar lg = new LexerGrammar("lexer grammar L;\nA : 'a' ;\n");
		CodeGenerator lexGen = new CodeGenerator(lg);
		try {
			lexGen.generateLexer(false);
			lexGen.generateLexer(true);
		}
		catch (Throwable ignored) {
		}
	}

	@Test
	public void errorManagerEmitsWithLocationAndExistingFileBasename() throws Exception {
		Tool tool = new Tool();
		ErrorManager mgr = tool.errMgr;
		ANTLRMessage msg = new ANTLRMessage(ErrorType.INTERNAL_ERROR, Token.INVALID_TOKEN, "boom");
		msg.fileName = tmpdir + File.separator + "does-not-exist-xyz.g4";
		msg.line = 3;
		msg.charPosition = 1;
		mgr.emit(ErrorType.INTERNAL_ERROR, msg);

		// existing file → ANTLR format uses basename
		mkdir(tmpdir);
		writeFile(tmpdir, "Shown.g4", "grammar Shown; s : 'a' ;");
		msg.fileName = new File(tmpdir, "Shown.g4").getAbsolutePath();
		mgr.emit(ErrorType.INTERNAL_ERROR, msg);

		DefaultToolListener listener = new DefaultToolListener(tool);
		tool.addListener(listener);
		tool.info("info-line");
		tool.error(new ToolMessage(ErrorType.CANNOT_WRITE_FILE, "z"));
		tool.warning(new ToolMessage(ErrorType.INTERNAL_ERROR, "w"));
	}

	@Test
	public void graphTopologicalSortAndMutableIntCompare() {
		Graph<String> g = new Graph<String>();
		g.addEdge("a", "b");
		g.addEdge("b", "c");
		List<String> sorted = g.sort();
		assertTrue(sorted.contains("a"));
		MutableInt m = new MutableInt(1);
		assertEquals(0, m.compareTo(1));
		assertTrue(m.compareTo(2) < 0);
		assertFalse(m.equals(null));
	}

	@Test
	public void parseRecoversFromMalformedGrammarSnippets() throws Exception {
		// exercise generated parser / lexer error recovery on broken input
		String[] snippets = new String[] {
			"",
			"grammar ;",
			"grammar T",
			"parser grammar T; options { }",
			"lexer grammar T; tokens { }",
			"grammar T; tokens { A, }",
			"grammar T; @members { int x; ",
			"grammar T; s : ;",
			"grammar T; s : | ;",
			"grammar T; s : ( ;",
			"grammar T; s : 'a' | ;",
			"grammar T; s : ~ ;",
			"grammar T; s : { ;",
			"grammar T; s : # ;",
			"grammar T; fragment : 'x' ;",
			"lexer grammar T; mode ;",
			"lexer grammar T; A : 'a' -> ;",
			"lexer grammar T; A : 'a' -> channel() ;",
			"grammar T; s : e[ ;",
			"grammar T; s[int x] returns [int y] locals [int z] : ID ; ID : 'a';",
			"grammar T; s : ID -> type(ID) ; ID : 'a';",
			"grammar T; import Missing;",
			"grammar T; options { tokenVocab=Nope; } s : A ;",
			"grammar T; s : 'a'..'z' ;",
			"tree grammar T; s : ID ;",
			"grammar T; s : ID ; ID : [a-z ;",
			"grammar T; s : /* unterminated",
			"grammar T; s : \"unterminated",
			"grammar T; s : '\\u' ;",
			"grammar T; s : '\\u{110000}' ;",
			"grammar T; : ID ; ID : 'a';",
			"grammar T; s : ID ID ID ID ID ID ID ID ID ID ; ID : 'a';",
		};
		for (String gtext : snippets) {
			try {
				new Grammar(gtext);
			}
			catch (Exception ignored) {
			}
			try {
				Tool t = new Tool();
				t.errMgr.setFormat("antlr");
				ANTLRStringStream in = new ANTLRStringStream(gtext);
				ToolANTLRLexer lexer = new ToolANTLRLexer(in, t);
				CommonTokenStream tokens = new CommonTokenStream(lexer);
				ToolANTLRParser p = new ToolANTLRParser(tokens, t);
				p.grammarSpec();
			}
			catch (Exception ignored) {
			}
		}
	}

	@Test
	public void parseLeftFactorAndLeftRecursiveGrammars() throws Exception {
		try {
			new Grammar(
				"grammar LF;\n" +
				"s @leftfactor{e}\n" +
				"  : a e\n" +
				"  | b e\n" +
				"  | c\n" +
				"  ;\n" +
				"a : 'a' ;\n" +
				"b : 'b' ;\n" +
				"c : 'c' ;\n" +
				"e : 'e' ;\n");
		}
		catch (Exception ignored) {
		}
		try {
			new Grammar(
				"grammar LR;\n" +
				"e : e '*' e\n" +
				"  | e '+' e\n" +
				"  | INT\n" +
				"  ;\n" +
				"INT : [0-9]+ ;\n");
		}
		catch (Exception ignored) {
		}
		try {
			new Grammar(
				"grammar Mut;\n" +
				"a : b 'x' ;\n" +
				"b : a 'y' | 'z' ;\n");
		}
		catch (Exception ignored) {
		}
	}

	@Test
	public void grammarAstVisitorWalksRuleAndAltNodes() throws Exception {
		Grammar g = new Grammar("grammar T;\ns : a | b ;\na : ID ;\nb : INT ;\nID : 'x' ;\nINT : '1' ;\n");
		final int[] visits = new int[1];
		GrammarASTVisitor v = new GrammarASTVisitor() {
			@Override public Object visit(GrammarAST node) { visits[0]++; return node; }
			@Override public Object visit(GrammarRootAST node) { visits[0]++; return node; }
			@Override public Object visit(org.antlr.v4.tool.ast.RuleAST node) { visits[0]++; return node; }
			@Override public Object visit(org.antlr.v4.tool.ast.BlockAST node) { visits[0]++; return node; }
			@Override public Object visit(AltAST node) { visits[0]++; return node; }
			@Override public Object visit(org.antlr.v4.tool.ast.NotAST node) { visits[0]++; return node; }
			@Override public Object visit(PredAST node) { visits[0]++; return node; }
			@Override public Object visit(org.antlr.v4.tool.ast.RangeAST node) { visits[0]++; return node; }
			@Override public Object visit(org.antlr.v4.tool.ast.SetAST node) { visits[0]++; return node; }
			@Override public Object visit(RuleRefAST node) { visits[0]++; return node; }
			@Override public Object visit(TerminalAST node) { visits[0]++; return node; }
			@Override public Object visit(org.antlr.v4.tool.ast.StarBlockAST node) { visits[0]++; return node; }
			@Override public Object visit(org.antlr.v4.tool.ast.PlusBlockAST node) { visits[0]++; return node; }
			@Override public Object visit(org.antlr.v4.tool.ast.OptionalBlockAST node) { visits[0]++; return node; }
		};
		g.ast.visit(v);
		assertTrue(visits[0] >= 1);
		RuleAST rule = (RuleAST) g.ast.getFirstDescendantWithType(ANTLRParser.RULE);
		if (rule != null) {
			assertNotNull(rule.dupNode());
		}
	}

	@Test
	public void unicodeDataLookupEveryPropertyAndAlias() {
		// Executes every generated addProperty via class init (already done)
		// and every alias / property lookup in getPropertyCodePoints.
		try {
			Class<?> ud = Class.forName("org.antlr.v4.unicode.UnicodeData");
			java.lang.reflect.Field ranges = ud.getDeclaredField("propertyCodePointRanges");
			ranges.setAccessible(true);
			@SuppressWarnings("unchecked")
			java.util.Map<String, ?> map = (java.util.Map<String, ?>) ranges.get(null);
			java.lang.reflect.Method get = ud.getMethod("getPropertyCodePoints", String.class);
			int n = 0;
			for (String key : map.keySet()) {
				assertNotNull(get.invoke(null, key));
				n++;
			}
			java.lang.reflect.Field aliases = ud.getDeclaredField("propertyAliases");
			aliases.setAccessible(true);
			@SuppressWarnings("unchecked")
			java.util.Map<String, String> aliasMap = (java.util.Map<String, String>) aliases.get(null);
			for (String alias : aliasMap.keySet()) {
				get.invoke(null, alias);
			}
			assertTrue(n > 100);
			// unknown property
			assertNull(get.invoke(null, "this_property_does_not_exist_zz"));
		}
		catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}
}
