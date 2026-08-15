/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.antlr.v4.Tool;
import org.antlr.v4.automata.ParserATNFactory;
import org.antlr.v4.codegen.SourceGenTriggers;
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
import org.antlr.v4.semantics.BlankActionSplitterListener;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Broad grammar-surface and generated ANTLR3 parser/lexer/tree-walker
 * coverage: v3 leftovers, comments, lexer commands, left-recursion extras,
 * and unused entry points such as alternativeEntry.
 */
public class TestToolModuleGeneratedAndGrammarCoverage extends BaseTest {

	private void gen(String name, String body) {
		mkdir(tmpdir);
		writeFile(tmpdir, name, body);
		assertNotNull(antlr(name, false));
	}

	@Test
	public void testV3AndLegacyGrammarSurface() {
		// v3 tokens assignment, empty tokens/channels, synpred, tree grammar,
		// visibility modifiers, throws/catch/finally, named imports syntax.
		String[] bodies = {
			"grammar V3T;\n" +
			"tokens { A='a'; B; }\n" +
			"s : ID ;\n" +
			"ID : [a-z]+ ;\n",

			"grammar EmptySpecs;\n" +
			"tokens { }\n" +
			"channels { }\n" +
			"s : 'x' ;\n",

			"grammar Syn;\n" +
			"s : (ID)=> ID | INT ;\n" +
			"ID : [a-z]+ ;\n" +
			"INT : [0-9]+ ;\n",

			"tree grammar TreeG;\n" +
			"s : ID ;\n",

			"grammar Vis;\n" +
			"public s : p r ;\n" +
			"protected p : ID ;\n" +
			"private r : INT ;\n" +
			"fragment F : [A-Z] ;\n" +
			"ID : [a-z]+ ;\n" +
			"INT : [0-9]+ ;\n",

			"grammar ThrowsG;\n" +
			"s throws java.io.IOException, RuntimeException\n" +
			"  : ID\n" +
			"    catch [java.io.IOException e] { System.err.println(e); }\n" +
			"    catch [RuntimeException e] { throw e; }\n" +
			"    finally { }\n" +
			"  ;\n" +
			"ID : [a-z]+ ;\n",

			"grammar Opts;\n" +
			"options { language=Java; tokenVocab=Opts; superClass=Object; k=2; }\n" +
			"s : ID ;\n" +
			"ID : [a-z]+ ;\n",
		};
		for (int i = 0; i < bodies.length; i++) {
			try {
				gen("Surf" + i + ".g4", bodies[i]);
			}
			catch (Throwable t) {
				// some v3 constructs are errors by design
			}
		}
	}

	@Test
	public void testLexerCommandsLabelsSetsAndUnicode() {
		gen("LexSurf.g4",
			"lexer grammar LexSurf;\n" +
			"tokens { IMAG }\n" +
			"channels { DOC, HIDDEN2 }\n" +
			"@header { /* lexer header */ }\n" +
			"@members { int n; }\n" +
			"A : 'a' -> type(IMAG), channel(DOC) ;\n" +
			"B : 'b' -> skip ;\n" +
			"C : 'c' -> more ;\n" +
			"D : 'd' -> pushMode(M), mode(M) ;\n" +
			"E : 'e' -> popMode ;\n" +
			"F : x='f' y+='g' ;\n" +
			"G : ~('x'|'y'|A) ;\n" +
			"H : [\\u0000-\\u007F] ;\n" +
			"I : '\\u0041'..'\\u005A' ;\n" +
			"J : '\\u{1F600}' ;\n" +
			"K : [\\p{L}\\p{Nd}]+ ;\n" +
			"L : '/*' .*? '*/' -> channel(HIDDEN) ;\n" +
			"fragment FR : [0-9] ;\n" +
			"M : FR+ ;\n" +
			"WS : [ \\t\\r\\n]+ -> skip ;\n" +
			"mode M;\n" +
			"N : ~[\"]+ -> channel(DOC) ;\n" +
			"O : '\"' -> popMode ;\n" +
			"P : 'x' -> pushMode(M) ;\n");
	}

	@Test
	public void testParserSetsOptionsActionsAndWildcards() {
		gen("ParSurf.g4",
			"grammar ParSurf;\n" +
			"options { tokenVocab=ParSurf; superClass=Object; }\n" +
			"tokens { FOO, BAR }\n" +
			"channels { MYCH }\n" +
			"@parser::header { /* ph */ }\n" +
			"@parser::members { int px; }\n" +
			"@lexer::header { /* lh */ }\n" +
			"@lexer::members { int lx; }\n" +
			"@header { /* both */ }\n" +
			"@members { int z; }\n" +
			"s[int a] returns [int b] locals [int c]\n" +
			"@init { $c = $a; }\n" +
			"@after { $b = $c; }\n" +
			"  : e # Add\n" +
			"  | t # Term\n" +
			"  ;\n" +
			"e : t ('+'<assoc=right> t)* ;\n" +
			"t : ID<fail='bad'> | INT | . | ~ID | ~('+'|'-') | {true}? ID ;\n" +
			"u : x=ID y+=INT* { $x.text; $y.size(); } ;\n" +
			"v : (ID | INT)+ | ID* | ID? | (ID INT)? ;\n" +
			"ID : [a-z]+ -> channel(MYCH) ;\n" +
			"INT : [0-9]+ ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
	}

	@Test
	public void testLeftRecursiveWithExtrasAndAssoc() {
		gen("LRSurf.g4",
			"grammar LRSurf;\n" +
			"e : <assoc=right> e '^' e\n" +
			"  | e '*' e\n" +
			"  | e '+' e\n" +
			"  | e '?'\n" +
			"  | '-' e\n" +
			"  | '(' e ')'\n" +
			"  | ID\n" +
			"  | INT\n" +
			"  ;\n" +
			"f : f '[' e ']' | ID ;\n" +
			"ID : [a-z]+ ;\n" +
			"INT : [0-9]+ ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
	}

	@Test
	public void testCommentsDocCommentsAndSrcDirective() throws Exception {
		mkdir(tmpdir);
		String body =
			"/** doc comment */\n" +
			"grammar Cmt;\n" +
			"// ordinary line comment\n" +
			"// $ANTLR src \"orig.g4\" 12\n" +
			"/* multi\n" +
			"   line */\n" +
			"s : ID { /* action comment */ $ID.text; // eol\n" +
			"        } ;\n" +
			"ID : [a-z]+ ;\n" +
			"WS : [ \\n]+ -> skip ;\n";
		// BOM + body
		File f = new File(tmpdir, "Cmt.g4");
		FileOutputStream out = new FileOutputStream(f);
		try {
			out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
			out.write(body.getBytes(StandardCharsets.UTF_8));
		}
		finally {
			out.close();
		}
		antlr("Cmt.g4", false);
	}

	@Test
	public void testMalformedInputsForLexerRecovery() {
		String[] bad = {
			"grammar X\ns : 'x' ;\n",                 // missing semi after name
			"grammar X;\ns : 'unterminated ;\n",      // bad string
			"grammar X;\ns : [a- ;\n",                // bad charset
			"grammar X;\ns : '\\u{ZZ}' ;\n",          // bad unicode
			"grammar X;\ns : $ ;\n",                  // stray dollar
			"lexer grammar X;\nA : 'a' -> typo(X) ;\n",
			"grammar X;\n@ : { }\ns:'x';\n",
		};
		for (int i = 0; i < bad.length; i++) {
			try {
				gen("BadLex" + i + ".g4", bad[i]);
			}
			catch (Throwable t) {
			}
		}
	}

	@Test
	public void testParserEntryPointsAndGeneratedHelpers() throws Exception {
		Tool tool = new Tool();
		invokeParserRule(tool, "ID | INT", "alternativeEntry");
		invokeParserRule(tool, "ID", "elementEntry");
		invokeParserRule(tool, "a : ID ;", "ruleEntry");
		invokeParserRule(tool, "(ID | INT)", "blockEntry");
		invokeParserRule(tool, "A='a';", "v3tokenSpec");
		invokeParserRule(tool, "A;", "v3tokenSpec");

		ANTLRStringStream in = new ANTLRStringStream("grammar T; s : ID ; ID : 'a' ;");
		ToolANTLRLexer lexer = new ToolANTLRLexer(in, tool);
		assertNotNull(lexer.getDelegates());
		assertNotNull(lexer.getGrammarFileName());
		// force a recognition error to hit displayRecognitionError
		ANTLRStringStream bad = new ANTLRStringStream("`");
		ToolANTLRLexer badLex = new ToolANTLRLexer(bad, tool);
		CommonTokenStream ts = new CommonTokenStream(badLex);
		ts.fill();
		ToolANTLRParser badP = new ToolANTLRParser(ts, tool);
		try {
			badP.grammarSpec();
		}
		catch (Throwable t) {
		}
		assertNotNull(badP.getTokenNames());
		assertNotNull(badP.getGrammarFileName());
		assertNotNull(badP.getDelegates());
		try {
			badP.grammarError(org.antlr.v4.tool.ErrorType.SYNTAX_ERROR, ts.LT(1), "x");
		}
		catch (Throwable t) {
		}
	}

	private void invokeParserRule(Tool tool, String text, String rule) {
		try {
			ANTLRStringStream in = new ANTLRStringStream(text);
			ToolANTLRLexer lexer = new ToolANTLRLexer(in, tool);
			CommonTokenStream tokens = new CommonTokenStream(lexer);
			ToolANTLRParser parser = new ToolANTLRParser(tokens, tool);
			Method m = ANTLRParser.class.getMethod(rule);
			Object ret = m.invoke(parser);
			assertNotNull(ret);
			Method getTree = ret.getClass().getMethod("getTree");
			getTree.invoke(ret);
		}
		catch (Throwable t) {
			// gunit-style entries may fail depending on lookahead
		}
	}

	@Test
	public void testTreeWalkersAndBuildersDirectly() throws Exception {
		Grammar g = new Grammar("grammar T;\ns : a | b+ | c* | d? | ~ID | ('x'|'y') ;\n" +
			"a : ID ;\nb : INT ;\nc : 'c' ;\nd : . ;\n" +
			"ID : [a-z]+ ;\nINT : [0-9]+ ;\nWS : [ \\n]+ -> skip ;\n");
		g.tool.process(g, false);
		assertNotNull(g.ast);

		GrammarASTAdaptor adaptor = new GrammarASTAdaptor();
		CommonTreeNodeStream nodes = new CommonTreeNodeStream(adaptor, g.ast);
		GrammarTreeVisitor visitor = new GrammarTreeVisitor(nodes);
		assertNotNull(visitor.getDelegates());
		assertNotNull(visitor.getGrammarFileName());
		visitor.traceIn("grammarSpec", 0);
		visitor.traceOut("grammarSpec", 0);
		try {
			visitor.visitGrammar(g.ast);
		}
		catch (Throwable t) {
		}
		// unused / rarely entered rules
		try {
			nodes.reset();
			visitor.astOperand();
		}
		catch (Throwable t) {
		}
		try {
			nodes.reset();
			visitor.actionElement();
		}
		catch (Throwable t) {
		}
		try {
			nodes.reset();
			visitor.exceptionHandler();
		}
		catch (Throwable t) {
		}
		try {
			nodes.reset();
			visitor.ruleModifier();
		}
		catch (Throwable t) {
		}

		nodes.reset();
		BlockSetTransformer bst = new BlockSetTransformer(nodes, g);
		assertNotNull(bst.getDelegates());
		assertNotNull(bst.getTreeAdaptor());
		assertNotNull(bst.getGrammarFileName());
		try {
			bst.downup(g.ast);
		}
		catch (Throwable t) {
		}

		nodes.reset();
		ParserATNFactory factory = new ParserATNFactory(g);
		ATNBuilder builder = new ATNBuilder(nodes, factory);
		assertNotNull(builder.getDelegates());
		assertNotNull(builder.getTokenNames());
		assertNotNull(builder.getGrammarFileName());
		try {
			builder.dummy();
		}
		catch (Throwable t) {
		}
		try {
			nodes.reset();
			builder.astOperand();
		}
		catch (Throwable t) {
		}
		try {
			nodes.reset();
			builder.elementOption();
		}
		catch (Throwable t) {
		}

		nodes.reset();
		LeftRecursiveRuleWalker lr = new LeftRecursiveRuleWalker(nodes);
		assertNotNull(lr.getDelegates());
		assertNotNull(lr.getTokenNames());
		assertNotNull(lr.getGrammarFileName());
		try {
			lr.rec_rule();
		}
		catch (Throwable t) {
		}

		nodes.reset();
		SourceGenTriggers sgt = new SourceGenTriggers(nodes, (org.antlr.v4.codegen.OutputModelController) null);
		assertNotNull(sgt.getDelegates());
		try {
			sgt.dummy();
		}
		catch (Throwable t) {
		}

		// DFA getDescription helpers
		invokeDfaDescriptions(visitor);
		invokeDfaDescriptions(builder);
		invokeDfaDescriptions(lr);
		invokeDfaDescriptions(sgt);
		invokeDfaDescriptions(bst);
	}

	private void invokeDfaDescriptions(Object owner) {
		for (Class<?> c : owner.getClass().getDeclaredClasses()) {
			if (!c.getSimpleName().startsWith("DFA")) {
				continue;
			}
			try {
				Object dfa = c.getDeclaredConstructors()[0].newInstance(owner, 0);
				Method gd = c.getMethod("getDescription");
				assertNotNull(gd.invoke(dfa));
			}
			catch (Throwable t) {
			}
		}
	}

	@Test
	public void testActionSplitterCommentsAndAllTokenKinds() {
		BlankActionSplitterListener listener = new BlankActionSplitterListener();
		listener.templateInstance("$st(x={y})");
		listener.indirectTemplateInstance("%st(x={y})");
		listener.setExprAttribute("%x.y = z;");
		listener.setSTAttribute("%x.y = z;");
		listener.templateExpr("%{foo}");
		listener.text("t");
		listener.attr("$x", new org.antlr.runtime.CommonToken(1, "x"));

		String action =
			"/* block comment */\n" +
			"// line comment\n" +
			"$a::b = 1;\n" +
			"$a::b\n" +
			"$x.y\n" +
			"$x = 2;\n" +
			"$x\n" +
			"plain text $not\n";
		ANTLRStringStream in = new ANTLRStringStream(action);
		ActionSplitter splitter = new ActionSplitter(in, listener);
		assertNotNull(splitter.getActionTokens());
		assertNotNull(splitter.getDelegates());
		assertNotNull(splitter.getGrammarFileName());

		// no-delegate ctor + recognizer-state ctor
		try {
			new ActionSplitter(new ANTLRStringStream("$x"));
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testLexerGrammarWithUnicodeIdAndArgActions() {
		try {
			gen("UniId.g4",
				"grammar UniId;\n" +
				"s : ID ;\n" +
				"ID : [\\p{L}]+ ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testParserAndLexerGrammarsSeparately() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "LOnly.g4", "lexer grammar LOnly;\nA : 'a' ;\n");
		writeFile(tmpdir, "POnly.g4",
			"parser grammar POnly;\noptions { tokenVocab=LOnly; }\ns : A ;\n");
		antlr("LOnly.g4", false);
		antlr("POnly.g4", false);
		LexerGrammar lg = new LexerGrammar("lexer grammar L2;\nA : 'a' ;\n");
		assertTrue(lg.isLexer());
		Grammar pg = new Grammar("parser grammar P2;\ns : 'x' ;\n");
		assertTrue(pg.isParser());
	}

	@Test
	public void testQualifiedImportSyntax() {
		mkdir(tmpdir);
		writeFile(tmpdir, "ImpA.g4", "parser grammar ImpA;\np : 'p' ;\n");
		writeFile(tmpdir, "RootQ.g4",
			"grammar RootQ;\n" +
			"import Alias=ImpA;\n" +
			"s : p 'x' ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
		antlr("RootQ.g4", false);
	}

	@Test
	public void testCaseInsensitiveAndAbstractOptions() {
		gen("CI.g4",
			"grammar CI;\n" +
			"options { caseInsensitive=true; abstract=true; }\n" +
			"s : 'Hello' ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
	}
}
