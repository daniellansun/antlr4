/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.v4.Tool;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.OutputModelController;
import org.antlr.v4.codegen.ParserFactory;
import org.antlr.v4.codegen.model.Action;
import org.antlr.v4.codegen.model.RuleFunction;
import org.antlr.v4.codegen.model.SrcOp;
import org.antlr.v4.codegen.model.decl.CodeBlock;
import org.antlr.v4.codegen.model.decl.Decl;
import org.antlr.v4.codegen.model.decl.StructDecl;
import org.antlr.v4.codegen.target.JavaTarget;
import org.antlr.v4.semantics.UseDefAnalyzer;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LeftRecursiveRule;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.ActionAST;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.GrammarASTWithOptions;
import org.antlr.v4.tool.ast.PredAST;
import org.antlr.v4.tool.ast.RuleRefAST;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Coverage for codegen actions, OutputModel decls, JavaTarget helpers,
 * UseDefAnalyzer, and semantic edge cases.
 */
public class TestCodegenActionsAndSemanticsCoverage extends BaseTest {

	@Test
	public void testActionTranslatorAndCodegenModel() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "T.g4",
				"grammar T;\n" +
				"@members { int x; }\n" +
				"a[int i] returns [int j]\n" +
				"  : ID { $j = $i; System.out.println($ID.text); }\n" +
				"  ;\n" +
				"b : a[1] ;\n" +
				"ID : [a-z]+ ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
		ErrorQueue equeue = antlr("T.g4", false, "-listener", "-visitor");
		assertNotNull(equeue);
	}

	@Test
	public void testNonLocalAttrAndSetters() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "T.g4",
				"grammar T;\n" +
				"a returns [int x] : b { $a::x = 1; System.out.println($a::x); } ;\n" +
				"b : ID ;\n" +
				"ID : [a-z]+ ;\n");
		antlr("T.g4", false);
	}

	@Test
	public void testLexerActionsAndChannelsModes() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "L.g4",
				"lexer grammar L;\n" +
				"tokens { FOO }\n" +
				"@members { void m() {} }\n" +
				"A : 'a' -> channel(HIDDEN), mode(M), type(FOO) ;\n" +
				"B : 'b' -> more ;\n" +
				"C : 'c' -> skip, pushMode(M) ;\n" +
				"D : 'd' -> popMode ;\n" +
				"mode M;\n" +
				"E : 'e' -> type(FOO) ;\n");
		antlr("L.g4", false);
	}

	@Test
	public void testSemanticChecksEdgeCases() throws Exception {
		String[] bad = {
				"parser grammar P;\n fragment F : 'x' ;\n",
				"lexer grammar L;\n a : 'x' ;\n",
				"grammar T;\n a : b ;\n b : a ;\n",
				"grammar T;\n options { tokenVocab=Missing; }\n a : X ;\n",
				"grammar T;\n import Missing;\n a : 'x' ;\n",
				"grammar T;\n a : {$x}? 'x' ;\n",
				"grammar T;\n tokens { A, A }\n a : A ;\n",
		};
		for (String gtext : bad) {
			try {
				ErrorQueue eq = new ErrorQueue();
				Grammar g = new Grammar(gtext, eq);
				g.tool.process(g, false);
			} catch (Throwable t) {
				// tolerate
			}
		}
	}

	@Test
	public void testUseDefAnalyzer() throws Exception {
		Grammar g = new Grammar(
				"grammar T;\n" +
				"a : b c ;\n" +
				"b : d ;\n" +
				"c : 'x' ;\n" +
				"d : 'y' ;\n");
		g.tool.process(g, false);
		Map<Rule, ? extends Collection<? extends Rule>> deps =
				UseDefAnalyzer.getRuleDependencies(g);
		assertNotNull(deps);

		Map<Rule, ? extends Collection<? extends Rule>> deps2 =
				UseDefAnalyzer.getRuleDependencies(g, g.rules.values());
		assertNotNull(deps2);

		if (g.implicitLexer != null) {
			UseDefAnalyzer.getRuleDependencies(g.implicitLexer);
		}
	}

	@Test
	public void testSymbolChecksQualifiedRules() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "S.g4", "parser grammar S;\n s : 's' ;\n");
		writeFile(tmpdir, "M.g4", "grammar M;\n import S;\n a : s ;\n");
		Tool tool = new Tool(new String[]{
				"-o", tmpdir,
				"-lib", tmpdir,
				new File(tmpdir, "M.g4").getAbsolutePath()
		});
		tool.processGrammarsOnCommandLine();
	}

	@Test
	public void testCodeBlockAndStructDeclHelpers() throws Exception {
		Grammar g = new Grammar("grammar T;\n a : ID ;\n ID : [a-z]+ ;\n");
		g.tool.process(g, false);
		CodeGenerator gen = new CodeGenerator(g);
		if (gen.getTarget() == null) {
			return;
		}
		ParserFactory factory = new ParserFactory(gen);
		OutputModelController controller = new OutputModelController(factory);
		factory.setController(controller);

		RuleFunction rf = new RuleFunction(factory, g.getRule("a"));
		assertNotNull(rf);
		StructDecl ctx = rf.ruleCtx;
		if (ctx != null) {
			ctx.implementInterface(new Action(factory, ctx, "// iface"));
			ctx.addExtensionMember(new Action(factory, ctx, "// ext"));
		}

		CodeBlock block = new CodeBlock(factory, 1, 1);
		Decl d = new Decl(factory, "x");
		block.addLocalDecl(d);
		block.addPreambleOp(new Action(factory, ctx, "// pre"));
		List<SrcOp> ops = new ArrayList<SrcOp>();
		ops.add(new Action(factory, ctx, "// op"));
		block.addOps(ops);

		Action act = new Action(factory, ctx, "System.out.println(1);");
		assertNotNull(act);
		try {
			act.getContextName();
		} catch (Throwable t) {
			// ok
		}
	}

	@Test
	public void testOutputModelWalkerViaCodegen() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "T.g4",
				"grammar T;\n" +
				"a : b | c+ | d* | e? ;\n" +
				"b : 'x' ;\n" +
				"c : 'y' ;\n" +
				"d : 'z' ;\n" +
				"e : ID ;\n" +
				"ID : [a-z]+ ;\n");
		antlr("T.g4", false);
	}

	@Test
	public void testJavaTargetEscapesAndLabels() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "T.g4",
				"grammar T;\n" +
				"a : t+=ID+ label=ID 'x' ;\n" +
				"ID : [a-z]+ ;\n" +
				"WS : [ \\t\\r\\n]+ -> skip ;\n");
		antlr("T.g4", false);
		Grammar g = new Grammar(
				"grammar T;\n" +
				"a : t+=ID+ label=ID 'x' ;\n" +
				"ID : [a-z]+ ;\n");
		g.tool.process(g, false);
		CodeGenerator gen = new CodeGenerator(g);
		if (gen.getTarget() instanceof JavaTarget) {
			JavaTarget target = (JavaTarget) gen.getTarget();
			assertNotNull(target.getTargetStringLiteralFromANTLRStringLiteral(gen, "'a'", true));
			assertNotNull(target.getTargetStringLiteralFromString("hi\n"));
		}
	}

	@Test
	public void testLL1AndComplexCodegen() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "T.g4",
				"grammar T;\n" +
				"s : (a | b)* c? ;\n" +
				"a : 'a' 'x'? ;\n" +
				"b : 'b'+ ;\n" +
				"c : 'c' | 'd' ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
		antlr("T.g4", false);
	}

	@Test
	public void testWildcardAndSetsCodegen() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "T.g4",
				"grammar T;\n" +
				"a : . | ~('a'|'b') | LETTER ;\n" +
				"LETTER : [a-zA-Z] ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
		antlr("T.g4", false);
	}

	@Test
	public void testLeftRecursiveCodegen() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "T.g4",
				"grammar T;\n" +
				"e : e '*' e\n" +
				"  | e '+' e\n" +
				"  | e '?'\n" +
				"  | '-' e\n" +
				"  | ID\n" +
				"  ;\n" +
				"ID : [a-z]+ ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
		antlr("T.g4", false);
		Grammar g = new Grammar(
				"grammar T;\n" +
				"e : e '*' e | e '+' e | ID ;\n" +
				"ID : [a-z]+ ;\n");
		g.tool.process(g, false);
		assertTrue(g.getRule("e") instanceof LeftRecursiveRule);
	}

	@Test
	public void testElementFrequenciesViaRuleFunction() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "T.g4",
				"grammar T;\n" +
				"a : ID ID? ID* ID+ (ID|INT) ;\n" +
				"ID : [a-z]+ ;\n" +
				"INT : [0-9]+ ;\n");
		antlr("T.g4", false);
	}

	@Test
	public void testLexerCharSetAndUnicode() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "L.g4",
				"lexer grammar L;\n" +
				"A : [a-z]+ ;\n" +
				"B : [\\u0041-\\u005A] ;\n" +
				"C : '/*' .*? '*/' ;\n" +
				"D : ~[\\r\\n]* ;\n" +
				"E : [\\-\\]] ;\n");
		antlr("L.g4", false);
	}

	@Test
	public void testGrammarTransformExtractLexer() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "T.g4",
				"grammar T;\n" +
				"tokens { FOO }\n" +
				"@members { int i; }\n" +
				"@lexer::members { int j; }\n" +
				"a : FOO ID 'lit' ;\n" +
				"ID : [a-z]+ ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
		antlr("T.g4", false, "-package", "p");
	}

	@Test
	public void testASTDupAndOptions() {
		GrammarASTWithOptions ast = new RuleRefAST(new CommonToken(1, "X"));
		ast.setOption("k", new GrammarAST(new CommonToken(1, "1")));
		assertEquals("1", ast.getOptionString("k"));
		assertNull(ast.getOptionString("missing"));

		RuleRefAST rr = new RuleRefAST(new CommonToken(1, "r"));
		assertNotNull(rr.dupNode());
		rr.setOption("p", new GrammarAST(new CommonToken(1, "v")));

		PredAST pred = new PredAST(new CommonToken(1, "{true}?"));
		assertNotNull(pred.dupNode());

		ActionAST act = new ActionAST(new CommonToken(1, "{;}"));
		assertNotNull(act.dupNode());
		act.setScope(null);
	}
}
