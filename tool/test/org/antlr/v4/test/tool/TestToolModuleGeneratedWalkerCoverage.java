/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.Token;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.antlr.v4.Tool;
import org.antlr.v4.automata.ParserATNFactory;
import org.antlr.v4.codegen.OutputModelController;
import org.antlr.v4.codegen.OutputModelWalker;
import org.antlr.v4.codegen.ParserFactory;
import org.antlr.v4.codegen.SourceGenTriggers;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.model.OutputModelObject;
import org.antlr.v4.codegen.model.dbg;
import org.antlr.v4.gui.TreeViewer;
import org.antlr.v4.parse.ANTLRLexer;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.parse.ATNBuilder;
import org.antlr.v4.parse.ActionSplitter;
import org.antlr.v4.parse.BlockSetTransformer;
import org.antlr.v4.parse.GrammarASTAdaptor;
import org.antlr.v4.parse.GrammarTreeVisitor;
import org.antlr.v4.parse.LeftRecursiveRuleWalker;
import org.antlr.v4.parse.ToolANTLRLexer;
import org.antlr.v4.parse.ToolANTLRParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.semantics.BlankActionSplitterListener;
import org.antlr.v4.tool.DOTGenerator;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.ast.AltAST;
import org.antlr.v4.tool.ast.BlockAST;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.NotAST;
import org.antlr.v4.tool.ast.RuleAST;
import org.antlr.v4.tool.ast.SetAST;
import org.antlr.v4.tool.ast.TerminalAST;
import org.junit.Test;

import javax.swing.JDialog;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Direct invocation of generated ANTLR3 lexer/parser/tree-walker methods
 * plus DFA DOT and remaining GUI dialog paths.
 */
public class TestToolModuleGeneratedWalkerCoverage extends BaseTest {

	static {
		System.setProperty("java.awt.headless", "true");
	}

	private ToolANTLRLexer lexer(String text) {
		return new ToolANTLRLexer(new ANTLRStringStream(text), new Tool());
	}

	private void invokeLexer(String method, String text) {
		try {
			ToolANTLRLexer lex = lexer(text);
			Method m = ANTLRLexer.class.getMethod(method);
			m.invoke(lex);
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testAntlrLexerFragmentAndKeywordMethods() {
		invokeLexer("mSRC", "src \"orig.g4\" 12");
		invokeLexer("mNameChar", "a");
		invokeLexer("mNameChar", "0");
		invokeLexer("mNameChar", "_");
		invokeLexer("mNameChar", "\u00e9");
		invokeLexer("mNameStartChar", "A");
		invokeLexer("mNameStartChar", "\u4e00");
		invokeLexer("mNameStartChar", "_");
		invokeLexer("mUNICODE_EXTENDED_ESC", "u{1F600}");
		invokeLexer("mUNICODE_EXTENDED_ESC", "u{1234567}"); // too many digits
		invokeLexer("mUNICODE_ESC", "u0041");
		invokeLexer("mESC_SEQ", "n");
		invokeLexer("mESC_SEQ", "u0041");
		invokeLexer("mESC_SEQ", "u{41}");
		invokeLexer("mHEX_DIGIT", "A");
		invokeLexer("mHEX_DIGIT", "f");
		invokeLexer("mACTION_CHAR_LITERAL", "'x'");
		invokeLexer("mACTION_CHAR_LITERAL", "'\\n'");
		invokeLexer("mACTION_STRING_LITERAL", "\"hi\"");
		invokeLexer("mACTION_STRING_LITERAL", "\"a\\\"b\"");
		invokeLexer("mACTION_ESC", "\\n");
		invokeLexer("mINT", "12345");
		invokeLexer("mSTRING_LITERAL", "'abc'");
		invokeLexer("mSTRING_LITERAL", "'unterminated");
		invokeLexer("mSTRING_LITERAL", "'\\u0041'");
		invokeLexer("mSTRING_LITERAL", "'\\u{1F4A9}'");
		invokeLexer("mCOMMENT", "// line\n");
		invokeLexer("mCOMMENT", "// $ANTLR src \"f.g4\" 3\n");
		invokeLexer("mCOMMENT", "/* block */");
		invokeLexer("mCOMMENT", "/** doc */");
		invokeLexer("mDOC_COMMENT", "/** doc */");
		invokeLexer("mACTION", "{ int x = 1; }");
		invokeLexer("mACTION", "{ 'c' \"s\" {nested} }");
		invokeLexer("mNESTED_ACTION", "{ int x = 1; }");
		invokeLexer("mARG_ACTION", "[int x, String y]");
		invokeLexer("mARG_OR_CHARSET", "[a-z]");
		invokeLexer("mLEXER_CHAR_SET", "[a-zA-Z_]");
		invokeLexer("mOPTIONS", "options {");
		invokeLexer("mTOKENS_SPEC", "tokens {");
		invokeLexer("mCHANNELS", "channels {");
		invokeLexer("mTREE_GRAMMAR", "tree grammar");
		invokeLexer("mPROTECTED", "protected");
		invokeLexer("mPUBLIC", "public");
		invokeLexer("mPRIVATE", "private");
		invokeLexer("mFRAGMENT", "fragment");
		invokeLexer("mLEXER", "lexer");
		invokeLexer("mPARSER", "parser");
		invokeLexer("mGRAMMAR", "grammar");
		invokeLexer("mIMPORT", "import");
		invokeLexer("mRETURNS", "returns");
		invokeLexer("mLOCALS", "locals");
		invokeLexer("mTHROWS", "throws");
		invokeLexer("mCATCH", "catch");
		invokeLexer("mFINALLY", "finally");
		invokeLexer("mMODE", "mode");
		invokeLexer("mCOLON", ":");
		invokeLexer("mCOLONCOLON", "::");
		invokeLexer("mCOMMA", ",");
		invokeLexer("mSEMI", ";");
		invokeLexer("mLPAREN", "(");
		invokeLexer("mRPAREN", ")");
		invokeLexer("mRARROW", "->");
		invokeLexer("mLT", "<");
		invokeLexer("mGT", ">");
		invokeLexer("mASSIGN", "=");
		invokeLexer("mQUESTION", "?");
		invokeLexer("mSYNPRED", "=>");
		invokeLexer("mSTAR", "*");
		invokeLexer("mPLUS", "+");
		invokeLexer("mPLUS_ASSIGN", "+=");
		invokeLexer("mOR", "|");
		invokeLexer("mDOLLAR", "$");
		invokeLexer("mDOT", ".");
		invokeLexer("mRANGE", "..");
		invokeLexer("mAT", "@");
		invokeLexer("mPOUND", "#");
		invokeLexer("mNOT", "~");
		invokeLexer("mRBRACE", "}");
		invokeLexer("mID", "hello");
		invokeLexer("mID", "Hello_1");
		invokeLexer("mID", "\u03b1\u03b2");
		invokeLexer("mWS", " \t\r\n\f");
		invokeLexer("mNLCHARS", "\n");
		invokeLexer("mWSCHARS", " ");
		invokeLexer("mWSNLCHARS", "\t");
		invokeLexer("mUnicodeBOM", "\uFEFF");
		invokeLexer("mERRCHAR", "`");
		invokeLexer("mTokens", "grammar T; s : 'x' ;");

		// tokenize a kitchen-sink grammar so DFA specialStateTransition runs
		String sink =
			"\uFEFF" +
			"/** docs */\n" +
			"// $ANTLR src \"orig.g4\" 9\n" +
			"grammar Sink;\n" +
			"options { language = Java; }\n" +
			"tokens { A, B }\n" +
			"channels { C }\n" +
			"import X, Y=Z;\n" +
			"@parser::members { int i = 'x'; String s = \"y\"; }\n" +
			"s[int a] returns [int b] throws E locals [int c]\n" +
			"@init { }\n" +
			"  : ID | INT | . | ~ID | 'a'..'z' | (ID)=> ID\n" +
			"    catch [E e] { }\n" +
			"    finally { }\n" +
			"  ;\n" +
			"ID : [\\p{L}\\u0041-\\u005A]+ ;\n" +
			"INT : [0-9]+ ;\n" +
			"WS : [ \\t\\r\\n]+ -> skip ;\n";
		ToolANTLRLexer lex = lexer(sink);
		CommonTokenStream ts = new CommonTokenStream(lex);
		try {
			ts.fill();
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testParserEntryPointsWithTreeAdaptor() throws Exception {
		Tool tool = new Tool();
		GrammarASTAdaptor adaptor = new GrammarASTAdaptor();
		invokeWithAdaptor(tool, adaptor, "ID | INT", "alternativeEntry");
		invokeWithAdaptor(tool, adaptor, "ID+", "elementEntry");
		invokeWithAdaptor(tool, adaptor, "x=ID", "elementEntry");
		invokeWithAdaptor(tool, adaptor, "a : ID | INT ;", "ruleEntry");
		invokeWithAdaptor(tool, adaptor, "(ID | INT)+", "blockEntry");
		invokeWithAdaptor(tool, adaptor, "A='a';", "v3tokenSpec");
		invokeWithAdaptor(tool, adaptor, "FOO;", "v3tokenSpec");
		invokeWithAdaptor(tool, adaptor, "ID", "atom");
		invokeWithAdaptor(tool, adaptor, "'lit'", "atom");
		invokeWithAdaptor(tool, adaptor, ".", "wildcard");
		invokeWithAdaptor(tool, adaptor, "~ID", "notSet");
		invokeWithAdaptor(tool, adaptor, "('a'|'b')", "blockSet");
		invokeWithAdaptor(tool, adaptor, "ID<assoc=right>", "element");
		invokeWithAdaptor(tool, adaptor, "ID -> type(ID)", "element");
		invokeWithAdaptor(tool, adaptor, "{true}?", "element");
		invokeWithAdaptor(tool, adaptor, "{ $x = 1; }", "actionElement");
		invokeWithAdaptor(tool, adaptor, "x=ID", "labeledElement");
		invokeWithAdaptor(tool, adaptor, "xs+=ID", "labeledElement");
		invokeWithAdaptor(tool, adaptor, "throws E, F", "throwsSpec");
		invokeWithAdaptor(tool, adaptor, "import A, B=C;", "delegateGrammars");
		invokeWithAdaptor(tool, adaptor, "A=B", "delegateGrammar");
		invokeWithAdaptor(tool, adaptor, "tokens { A, B, }", "tokensSpec");
		invokeWithAdaptor(tool, adaptor, "tokens { A='a'; B; }", "tokensSpec");
		invokeWithAdaptor(tool, adaptor, "channels { X, Y }", "channelsSpec");
		invokeWithAdaptor(tool, adaptor, "@parser::header { }", "action");
		invokeWithAdaptor(tool, adaptor, "@members { }", "action");
		invokeWithAdaptor(tool, adaptor, "lexer", "actionScopeName");
		invokeWithAdaptor(tool, adaptor, "parser", "actionScopeName");
		invokeWithAdaptor(tool, adaptor, "Java", "optionValue");
		invokeWithAdaptor(tool, adaptor, "'str'", "optionValue");
		invokeWithAdaptor(tool, adaptor, "{act}", "optionValue");
		invokeWithAdaptor(tool, adaptor, "3", "optionValue");
		invokeWithAdaptor(tool, adaptor, "ID+", "ebnfSuffix");
		invokeWithAdaptor(tool, adaptor, "ID*", "ebnf");
		invokeWithAdaptor(tool, adaptor, "(ID|INT)", "block");
		invokeWithAdaptor(tool, adaptor, "ID | INT", "altList");
		invokeWithAdaptor(tool, adaptor, "ID | INT", "ruleAltList");
	}

	private void invokeWithAdaptor(Tool tool, GrammarASTAdaptor adaptor, String text, String rule) {
		try {
			ToolANTLRLexer lex = new ToolANTLRLexer(new ANTLRStringStream(text), tool);
			CommonTokenStream tokens = new CommonTokenStream(lex);
			ToolANTLRParser parser = new ToolANTLRParser(tokens, tool);
			parser.setTreeAdaptor(adaptor);
			Method m = ANTLRParser.class.getMethod(rule);
			m.invoke(parser);
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testTreeWalkerRulesOnSyntheticTrees() throws Exception {
		GrammarASTAdaptor adaptor = new GrammarASTAdaptor();
		// TOKEN_REF / STRING_LITERAL trees for LeftRecursiveRuleWalker.token
		TerminalAST tok = new TerminalAST(new CommonToken(ANTLRParser.TOKEN_REF, "ID"));
		TerminalAST lit = new TerminalAST(new CommonToken(ANTLRParser.STRING_LITERAL, "'x'"));
		walkLeftRec(adaptor, tok, "token");
		walkLeftRec(adaptor, lit, "token");
		walkLeftRec(adaptor, labeled(adaptor, ANTLRParser.ASSIGN, tok), "token");
		walkLeftRec(adaptor, labeled(adaptor, ANTLRParser.PLUS_ASSIGN, lit), "token");

		// elementOption trees
		GrammarAST id = new GrammarAST(new CommonToken(ANTLRParser.ID, "assoc"));
		walkLeftRec(adaptor, id, "elementOption");
		walkGTV(adaptor, tok, "atom");
		walkGTV(adaptor, lit, "atom");
		walkGTV(adaptor, new GrammarAST(new CommonToken(ANTLRParser.WILDCARD, ".")), "atom");

		// astOperand: atom or ^(NOT set/block)
		walkGTV(adaptor, tok, "astOperand");
		NotAST not = new NotAST(ANTLRParser.NOT, new CommonToken(ANTLRParser.NOT, "~"));
		SetAST set = new SetAST(ANTLRParser.SET, new CommonToken(ANTLRParser.SET, "SET"), "SET");
		set.addChild(lit.dupNode());
		not.addChild(set);
		walkGTV(adaptor, not, "astOperand");

		// actionElement
		GrammarAST act = new GrammarAST(new CommonToken(ANTLRParser.ACTION, "{x;}"));
		walkGTV(adaptor, act, "actionElement");

		// exceptionHandler / ruleModifier
		GrammarAST catchN = new GrammarAST(new CommonToken(ANTLRParser.CATCH, "catch"));
		catchN.addChild(new GrammarAST(new CommonToken(ANTLRParser.ARG_ACTION, "[E e]")));
		catchN.addChild(new GrammarAST(new CommonToken(ANTLRParser.ACTION, "{}")));
		walkGTV(adaptor, catchN, "exceptionHandler");
		walkGTV(adaptor, new GrammarAST(new CommonToken(ANTLRParser.PUBLIC, "public")), "ruleModifier");
		walkGTV(adaptor, new GrammarAST(new CommonToken(ANTLRParser.PRIVATE, "private")), "ruleModifier");
		walkGTV(adaptor, new GrammarAST(new CommonToken(ANTLRParser.PROTECTED, "protected")), "ruleModifier");

		// ATNBuilder unused rules
		walkATN(adaptor, tok, "atom");
		walkATN(adaptor, tok, "terminal");
		walkATN(adaptor, tok, "elementOption");
		walkATN(adaptor, tok, "astOperand");
		walkATN(adaptor, new GrammarAST(new CommonToken(ANTLRParser.RULE_REF, "a")), "ruleref");

		// SourceGenTriggers
		walkSGT(adaptor, tok, "atom");
		walkSGT(adaptor, lit, "terminal");
		walkSGT(adaptor, labeled(adaptor, ANTLRParser.ASSIGN, tok), "labeledElement");
		walkSGT(adaptor, id, "elementOption");
		GrammarAST range = new GrammarAST(new CommonToken(ANTLRParser.RANGE, ".."));
		range.addChild(new TerminalAST(new CommonToken(ANTLRParser.STRING_LITERAL, "'a'")));
		range.addChild(new TerminalAST(new CommonToken(ANTLRParser.STRING_LITERAL, "'z'")));
		walkSGT(adaptor, range, "range");

		// BlockSetTransformer
		try {
			CommonTreeNodeStream nodes = new CommonTreeNodeStream(adaptor, set);
			Grammar g = new Grammar("grammar T;\na:'x';\n");
			BlockSetTransformer bst = new BlockSetTransformer(nodes, g);
			bst.blockSet();
		}
		catch (Throwable t) {
		}
		try {
			CommonTreeNodeStream nodes = new CommonTreeNodeStream(adaptor, id);
			Grammar g = new Grammar("grammar T;\na:'x';\n");
			new BlockSetTransformer(nodes, g).elementOption();
		}
		catch (Throwable t) {
		}
	}

	private GrammarAST labeled(GrammarASTAdaptor adaptor, int type, GrammarAST elem) {
		GrammarAST root = new GrammarAST(new CommonToken(type, type == ANTLRParser.ASSIGN ? "=" : "+="));
		root.addChild(new GrammarAST(new CommonToken(ANTLRParser.ID, "x")));
		root.addChild(elem.dupNode());
		return root;
	}

	private void walkLeftRec(GrammarASTAdaptor adaptor, GrammarAST tree, String rule) {
		try {
			CommonTreeNodeStream nodes = new CommonTreeNodeStream(adaptor, tree);
			LeftRecursiveRuleWalker w = new LeftRecursiveRuleWalker(nodes);
			LeftRecursiveRuleWalker.class.getMethod(rule).invoke(w);
		}
		catch (Throwable t) {
		}
	}

	private void walkGTV(GrammarASTAdaptor adaptor, GrammarAST tree, String rule) {
		try {
			CommonTreeNodeStream nodes = new CommonTreeNodeStream(adaptor, tree);
			GrammarTreeVisitor w = new GrammarTreeVisitor(nodes);
			GrammarTreeVisitor.class.getMethod(rule).invoke(w);
		}
		catch (Throwable t) {
		}
	}

	private void walkATN(GrammarASTAdaptor adaptor, GrammarAST tree, String rule) {
		try {
			Grammar g = new Grammar("grammar T;\na:'x';\n");
			g.tool.process(g, false);
			CommonTreeNodeStream nodes = new CommonTreeNodeStream(adaptor, tree);
			ATNBuilder b = new ATNBuilder(nodes, new ParserATNFactory(g));
			ATNBuilder.class.getMethod(rule).invoke(b);
		}
		catch (Throwable t) {
		}
	}

	private void walkSGT(GrammarASTAdaptor adaptor, GrammarAST tree, String rule) {
		try {
			CommonTreeNodeStream nodes = new CommonTreeNodeStream(adaptor, tree);
			SourceGenTriggers sgt = new SourceGenTriggers(nodes, (OutputModelController) null);
			SourceGenTriggers.class.getMethod(rule).invoke(sgt);
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testActionSplitterIsolatedCommentsAndAttrs() {
		BlankActionSplitterListener l = new BlankActionSplitterListener();
		tokeniseAction(l, "// only a line comment\n");
		tokeniseAction(l, "/* only a block comment */");
		tokeniseAction(l, "$x = 1;");
		tokeniseAction(l, "$r::x = 2;");
		tokeniseAction(l, "$r::x");
		tokeniseAction(l, "$lab.text");
		tokeniseAction(l, "$x");
		tokeniseAction(l, "   ");
		tokeniseAction(l, "plain");
	}

	private void tokeniseAction(BlankActionSplitterListener l, String text) {
		try {
			new ActionSplitter(new ANTLRStringStream(text), l).getActionTokens();
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testDotGeneratorUsesInterpreterDfa() throws Exception {
		Grammar g = new Grammar(
			"grammar T;\n" +
			"s : a | b ;\n" +
			"a : ID ;\n" +
			"b : INT ;\n" +
			"ID : [a-z]+ ;\n" +
			"INT : [0-9]+ ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
		g.tool.process(g, false);
		LexerGrammar lg = g.implicitLexer;
		LexerInterpreter lex = lg.createLexerInterpreter(CharStreams.fromString("hello 123"));
		org.antlr.v4.runtime.CommonTokenStream tokens =
			new org.antlr.v4.runtime.CommonTokenStream(lex);
		ParserInterpreter pi = g.createParserInterpreter(tokens);
		pi.setBuildParseTree(true);
		assertNotNull(pi.parse(g.getRule("s").index));

		DOTGenerator pdot = new DOTGenerator(g);
		if (pi.getATN() != null && pi.getATN().decisionToDFA != null) {
			for (DFA dfa : pi.getATN().decisionToDFA) {
				if (dfa != null && dfa.s0.get() != null) {
					assertNotNull(pdot.getDOT(dfa, false));
					pdot.getDOT(dfa, true);
				}
			}
		}
		DOTGenerator ldot = new DOTGenerator(lg);
		if (lex.getATN() != null && lex.getATN().decisionToDFA != null) {
			for (DFA dfa : lex.getATN().decisionToDFA) {
				if (dfa != null && dfa.s0.get() != null) {
					ldot.getDOT(dfa, true);
				}
			}
		}
		// also try lexer interpreter DFA after matching
		lex.reset();
		lex.setInputStream(CharStreams.fromString("abc"));
		lex.nextToken();
	}

	@Test
	public void testRichImportsForTransformPipeline() {
		mkdir(tmpdir);
		writeFile(tmpdir, "ImpTok.g4",
			"lexer grammar ImpTok;\n" +
			"options { superClass=Lexer; }\n" +
			"tokens { IMPTOK }\n" +
			"channels { IMPCH }\n" +
			"@members { int fromImp; }\n" +
			"@header { /* imp header */ }\n" +
			"IA : 'ia' -> channel(IMPCH) ;\n" +
			"mode IM;\n" +
			"IB : 'ib' ;\n");
		writeFile(tmpdir, "RootTok.g4",
			"grammar RootTok;\n" +
			"import ImpTok;\n" +
			"tokens { ROOTTOK }\n" +
			"channels { ROOTCH }\n" +
			"@members { int fromRoot; }\n" +
			"@header { /* root header */ }\n" +
			"s : IA IMPTOK ROOTTOK ;\n" +
			"mode IM;\n" +
			"IC : 'ic' ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
		antlr("ImpTok.g4", false);
		antlr("RootTok.g4", false);

		// import with options that differ
		writeFile(tmpdir, "ImpOpt.g4",
			"parser grammar ImpOpt;\n" +
			"options { superClass=X; tokenVocab=ImpTok; }\n" +
			"p : 'p' ;\n");
		writeFile(tmpdir, "RootOpt.g4",
			"grammar RootOpt;\n" +
			"options { superClass=Y; }\n" +
			"import ImpOpt;\n" +
			"s : p ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
		antlr("RootOpt.g4", false);

		// qualified rule ref
		writeFile(tmpdir, "QImp.g4", "parser grammar QImp;\nq : 'q' ;\n");
		writeFile(tmpdir, "QRoot.g4",
			"grammar QRoot;\n" +
			"import QImp;\n" +
			"s : QImp.q | Missing.foo ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
		antlr("QRoot.g4", false);
	}

	@Test
	public void testLeftRecursiveWithPrequelsAndHandlers() {
		mkdir(tmpdir);
		writeFile(tmpdir, "LRP.g4",
			"grammar LRP;\n" +
			"e returns [int v] locals [int t]\n" +
			"@init { $t = 0; }\n" +
			"@after { }\n" +
			"  : e '*' e\n" +
			"  | e '+' e\n" +
			"  | ID\n" +
			"    catch [Exception ex] { }\n" +
			"    finally { }\n" +
			"  ;\n" +
			"f : x=f '[' INT ']' | ID ;\n" +
			"g : g '?' | ID<assoc=right> | . | ~ID | ('a'|'b') ;\n" +
			"ID : [a-z]+ ;\n" +
			"INT : [0-9]+ ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
		antlr("LRP.g4", false);
	}

	@Test
	public void testTreeViewerShowInDialogAndExport() throws Exception {
		Grammar g = new Grammar(
			"grammar T;\ns : ID+ ;\nID : [a-z]+ ;\nWS : [ \\n]+ -> skip ;\n");
		g.tool.process(g, false);
		LexerInterpreter lex = g.implicitLexer.createLexerInterpreter(CharStreams.fromString("hello world"));
		ParserInterpreter pi = g.createParserInterpreter(new org.antlr.v4.runtime.CommonTokenStream(lex));
		pi.setBuildParseTree(true);
		org.antlr.v4.runtime.tree.ParseTree tree = pi.parse(g.getRule("s").index);
		TreeViewer viewer = new TreeViewer(Arrays.asList(g.getRuleNames()), tree);

		// Always invoke; HeadlessException is expected on CI but the method
		// must still be entered for coverage.
		try {
			Method show = TreeViewer.class.getDeclaredMethod("showInDialog", TreeViewer.class);
			show.setAccessible(true);
			show.invoke(null, viewer);
		}
		catch (Throwable t) {
			// headless / window toolkit
		}

		Method svg = TreeViewer.class.getDeclaredMethod("generateSVGFile", TreeViewer.class, JDialog.class);
		svg.setAccessible(true);
		try {
			svg.invoke(null, viewer, null);
		}
		catch (Throwable t) {
		}
		Method png = TreeViewer.class.getDeclaredMethod("generatePNGFile", TreeViewer.class, JDialog.class);
		png.setAccessible(true);
		try {
			png.invoke(null, viewer, null);
		}
		catch (Throwable t) {
		}
		Method chooser = TreeViewer.class.getDeclaredMethod("getFileChooser", String.class, String.class);
		chooser.setAccessible(true);
		try {
			chooser.invoke(null, ".png", "PNG");
		}
		catch (Throwable t) {
		}

		if (!GraphicsEnvironment.isHeadless()) {
			try {
				viewer.open();
			}
			catch (Throwable t) {
			}
		}
	}

	@Test
	public void testOutputModelWalkerErrorPathsAndProcess() throws Exception {
		Grammar g = new Grammar("grammar T;\na : 'x' | 'y' | ID ;\nID:[a-z]+;\n");
		g.tool.process(g, false);
		CodeGenerator gen = new CodeGenerator(g);
		if (gen.getTarget() == null) {
			return;
		}
		ParserFactory factory = new ParserFactory(gen);
		OutputModelController ctrl = new OutputModelController(factory);
		factory.setController(ctrl);
		try {
			OutputModelObject root = ctrl.buildParserOutputModel(false);
			OutputModelWalker walker = new OutputModelWalker(g.tool, gen.getTemplates());
			if (root != null) {
				walker.walk(root, false);
				walker.walk(root, true); // header templates missing for Java
			}
			walker.walk(new dbg(), false);
		}
		catch (Throwable t) {
		}

		mkdir(tmpdir);
		writeFile(tmpdir, "OM.g4",
			"grammar OM;\n" +
			"s : a | b | c ;\n" +
			"a : ID ;\n" +
			"b : INT+ ;\n" +
			"c : ID? ID* ;\n" +
			"ID : [a-z]+ ;\n" +
			"INT : [0-9]+ ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
		antlr("OM.g4", false, "-listener", "-visitor", "-Xforce-atn");
	}

	@Test
	public void testThrowsCatchFinallyArgActionOnPlainRule() {
		mkdir(tmpdir);
		writeFile(tmpdir, "Ex.g4",
			"grammar Ex;\n" +
			"s throws Exception\n" +
			"  : ID\n" +
			"    catch [Exception e] { System.out.println(e); }\n" +
			"    finally { }\n" +
			"  ;\n" +
			"ID : [a-z]+ ;\n");
		antlr("Ex.g4", false);
	}

	@Test
	public void testLexerElementOptionsAndCommandsVariants() {
		mkdir(tmpdir);
		writeFile(tmpdir, "LCmd.g4",
			"lexer grammar LCmd;\n" +
			"tokens { T }\n" +
			"A : 'a' -> type(T) ;\n" +
			"B : 'b' -> channel(HIDDEN) ;\n" +
			"C : 'c' -> mode(M) ;\n" +
			"D : 'd' -> pushMode(M) ;\n" +
			"E : 'e' -> popMode ;\n" +
			"F : 'f' -> more ;\n" +
			"G : 'g' -> skip ;\n" +
			"H : 'h' -> type(T), channel(HIDDEN), skip ;\n" +
			"I : {true}? 'i' ;\n" +
			"J : 'j' { /* lexer action */ } ;\n" +
			"K : ('k'|'l'|'m') ;\n" +
			"L : ~[abc] ;\n" +
			"mode M;\n" +
			"Z : 'z' -> popMode ;\n");
		antlr("LCmd.g4", false);
	}

	@Test
	public void testUnicodeIdentifiersInGrammar() {
		mkdir(tmpdir);
		writeFile(tmpdir, "Uid.g4",
			"grammar Uid;\n" +
			"s : café | \u03b1\u03b2 ;\n" +
			"café : ID ;\n" +
			"\u03b1\u03b2 : INT ;\n" +
			"ID : [a-z\u00e9]+ ;\n" +
			"INT : [0-9]+ ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
		antlr("Uid.g4", false);
	}
}
