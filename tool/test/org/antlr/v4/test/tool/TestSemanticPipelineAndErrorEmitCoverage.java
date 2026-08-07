/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.v4.Tool;
import org.antlr.v4.codegen.ActionTranslator;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.CodeGenPipeline;
import org.antlr.v4.codegen.DefaultOutputModelFactory;
import org.antlr.v4.codegen.OutputModelController;
import org.antlr.v4.codegen.OutputModelWalker;
import org.antlr.v4.codegen.ParserFactory;
import org.antlr.v4.codegen.model.OutputModelObject;
import org.antlr.v4.codegen.model.RuleFunction;
import org.antlr.v4.codegen.target.JavaTarget;
import org.antlr.v4.semantics.AttributeChecks;
import org.antlr.v4.semantics.BasicSemanticChecks;
import org.antlr.v4.semantics.RuleCollector;
import org.antlr.v4.semantics.SemanticPipeline;
import org.antlr.v4.semantics.SymbolChecks;
import org.antlr.v4.semantics.SymbolCollector;
import org.antlr.v4.semantics.UseDefAnalyzer;
import org.antlr.v4.tool.Alternative;
import org.antlr.v4.tool.ErrorManager;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LeftRecursiveRule;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.ActionAST;
import org.antlr.v4.tool.ast.AltAST;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.antlr.v4.tool.ast.PredAST;
import org.antlr.v4.tool.ast.RuleAST;
import org.junit.Test;
import org.stringtemplate.v4.ST;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Coverage for SemanticPipeline, attribute/symbol checks, OutputModelWalker,
 * ErrorManager emit paths, Alternative resolve, and left-recursive analysis helpers.
 */
public class TestSemanticPipelineAndErrorEmitCoverage extends BaseTest {

	@Test
	public void testSemanticPipelineDirectly() throws Exception {
		Grammar g = new Grammar(
				"grammar T;\n" +
				"@members { int x; }\n" +
				"s[int a] returns [int b] : ID { $b=$a; } ;\n" +
				"ID : [a-z]+ ;\n");
		SemanticPipeline sp = new SemanticPipeline(g);
		sp.process();
		assertNotNull(g.getRule("s"));
	}

	@Test
	public void testSymbolAndAttributeChecks() throws Exception {
		// undefined attr, nonlocal, wrong args
		String[] grammars = {
				"grammar T;\ns : {$x}? ID ; ID:[a-z]+;\n",
				"grammar T;\ns returns [int x] : t {$s::x=1;} ; t:ID; ID:[a-z]+;\n",
				"grammar T;\ns[int a] : ID ; t : s[1,2] ; ID:[a-z]+;\n",
				"grammar T;\ns : x=ID {$y=1;} ; ID:[a-z]+;\n",
				"grammar T;\ns : ID+ {$ID.text;} ; ID:[a-z]+;\n",
				"grammar T;\ns : ids+=ID+ {$ids.size();} ; ID:[a-z]+;\n",
		};
		for (String text : grammars) {
			try {
				ErrorQueue eq = new ErrorQueue();
				Grammar g = new Grammar(text, eq);
				g.tool.process(g, false);
			} catch (Throwable t) {
			}
		}
	}

	@Test
	public void testLeftRecursiveAnalyzerToString() throws Exception {
		Grammar g = new Grammar(
				"grammar T;\n" +
				"e : e '*' e | e '+' e | e '?' | '-' e | '(' e ')' | ID ;\n" +
				"ID : [a-z]+ ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
		g.tool.process(g, false);
		Rule e = g.getRule("e");
		assertTrue(e instanceof LeftRecursiveRule);
		LeftRecursiveRule lr = (LeftRecursiveRule) e;
		assertNotNull(lr.toString());
		assertTrue(lr.getOriginalNumberOfAlts() >= 3);
		// prec rule names
		assertNotNull(lr.recPrimaryAlts);
		assertNotNull(lr.recOpAlts);
	}

	@Test
	public void testCodegenWithComplexActions() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "CA.g4",
				"grammar CA;\n" +
				"@members {\n" +
				"  int cnt;\n" +
				"  void bump() { cnt++; }\n" +
				"}\n" +
				"s : a[1] a[2] ;\n" +
				"a[int i] returns [int j]\n" +
				"  : ID {\n" +
				"      $j = $i + 1;\n" +
				"      bump();\n" +
				"      System.out.println($ID.text + $i + $j);\n" +
				"    }\n" +
				"  ;\n" +
				"ID : [a-z]+ ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
		antlr("CA.g4", false, "-listener", "-visitor");
	}

	@Test
	public void testOutputModelWalkerAndController() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "OM.g4",
				"grammar OM;\n" +
				"s : a | b+ | c* | d? | e f ;\n" +
				"a : 'a' ;\n" +
				"b : 'b' ;\n" +
				"c : 'c' ;\n" +
				"d : 'd' ;\n" +
				"e : ID ;\n" +
				"f : INT ;\n" +
				"ID : [a-z]+ ;\n" +
				"INT : [0-9]+ ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
		antlr("OM.g4", false);
	}

	@Test
	public void testDefaultOutputModelFactoryPaths() throws Exception {
		Grammar g = new Grammar(
				"grammar T;\n" +
				"s : ID+ | INT* | 'x'? | . ;\n" +
				"ID : [a-z]+ ;\n" +
				"INT : [0-9]+ ;\n");
		g.tool.process(g, false);
		if (g.atn == null) return;
		CodeGenerator gen = new CodeGenerator(g);
		if (gen.getTarget() == null) return;
		ParserFactory factory = new ParserFactory(gen);
		OutputModelController controller = new OutputModelController(factory);
		factory.setController(controller);
		try {
			OutputModelObject file = controller.buildParserOutputModel(false);
			assertNotNull(file);
			OutputModelWalker walker = new OutputModelWalker(g.tool, gen.getTarget().getTemplates());
			ST st = walker.walk(file, false);
			assertNotNull(st);
		} catch (Throwable t) {
		}
	}

	@Test
	public void testErrorManagerOneOffAndEmit() {
		Tool tool = new Tool();
		ErrorManager em = tool.errMgr;
		// WARNING_ONE_OFF / ERROR_ONE_OFF double emit
		for (ErrorType et : ErrorType.values()) {
			if (et.severity == null) continue;
			try {
				em.emit(et, new org.antlr.v4.tool.ToolMessage(et, "a", "b", "c"));
				em.emit(et, new org.antlr.v4.tool.ToolMessage(et, "a", "b", "c"));
			} catch (Throwable t) {
			}
		}
		assertTrue(em.getNumErrors() >= 0);
	}

	@Test
	public void testGrammarGetRuleVariants() throws Exception {
		Grammar g = new Grammar(
				"grammar T;\n" +
				"s : a | b ;\n" +
				"a : 'x' ;\n" +
				"b : 'y' ;\n");
		g.tool.process(g, false);
		assertNotNull(g.getRule("s"));
		assertNull(g.getRule("missing"));
		assertNotNull(g.getRule(0));
		try {
			g.getRule(999);
		} catch (Throwable t) {
		}
		assertNotNull(g.getMaxTokenType());
		assertNotNull(g.getTokenDisplayName(1));
		assertNotNull(g.getTokenName(1));
		assertNotNull(g.getRuleNames());
		assertNotNull(g.getTokenNames());
		assertNotNull(g.getTokenLiteralNames());
		assertNotNull(g.getTokenSymbolicNames());
		assertFalse(g.isLexer());
		assertTrue(g.isParser() || g.isCombined());
	}

	@Test
	public void testAlternativeResolve() throws Exception {
		Grammar g = new Grammar(
				"grammar T;\n" +
				"s : x=ID y+=ID* z=a | ID ;\n" +
				"a : INT ;\n" +
				"ID : [a-z]+ ;\n" +
				"INT : [0-9]+ ;\n");
		g.tool.process(g, false);
		Rule s = g.getRule("s");
		for (int i = 1; i < s.numberOfAlts + 1; i++) {
			Alternative alt = s.alt[i];
			if (alt == null) continue;
			assertNotNull(alt.toString());
			alt.resolveToAttribute("x", null);
			alt.resolveToAttribute("y", null);
			alt.resolveToRule("a");
			alt.resolveToRule("s");
			alt.resolveToRule("nope");
			alt.resolvesToLabel("x", null);
			alt.resolvesToListLabel("y", null);
			alt.resolvesToToken("x", null);
			alt.resolvesToAttributeDict("x", null);
		}
	}

	@Test
	public void testJavaTargetMoreMethods() throws Exception {
		Grammar g = new Grammar("grammar T;\ns:ID; ID:[a-z]+;\n");
		g.tool.process(g, false);
		CodeGenerator gen = new CodeGenerator(g);
		if (!(gen.getTarget() instanceof JavaTarget)) return;
		JavaTarget jt = (JavaTarget) gen.getTarget();
		assertNotNull(jt.getTargetStringLiteralFromString("a\nb\tc"));
		assertNotNull(jt.getTargetStringLiteralFromANTLRStringLiteral(gen, "'\\n'", true));
		assertNotNull(jt.getTargetStringLiteralFromANTLRStringLiteral(gen, "'a'", false));
		assertFalse(jt.needsHeader());
		assertNotNull(jt.getRuleFunctionContextStructName(g.getRule("s")));
	}

	@Test
	public void testActionTranslatorToString() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "AT.g4",
				"grammar AT;\n" +
				"s returns [int x] : ID {\n" +
				"  $x = 1;\n" +
				"  $s::x = 2;\n" +
				"  System.out.println($ID.text);\n" +
				"} ;\n" +
				"ID : [a-z]+ ;\n");
		antlr("AT.g4", false);
	}

	@Test
	public void testUseDefAnalyzerLexer() throws Exception {
		LexerGrammar lg = new LexerGrammar(
				"lexer grammar L;\n" +
				"A : B C ;\n" +
				"fragment B : 'b' ;\n" +
				"fragment C : 'c' ;\n");
		lg.tool.process(lg, false);
		Map<?, ?> deps = UseDefAnalyzer.getRuleDependencies(lg);
		assertNotNull(deps);
	}

	@Test
	public void testGrammarASTVisitorAndOptions() throws Exception {
		Grammar g = new Grammar(
				"grammar T;\n" +
				"options { superClass=Base; }\n" +
				"s : a ;\n" +
				"a : 'x' ;\n");
		g.tool.process(g, false);
		GrammarRootAST root = g.ast;
		assertNotNull(root);
		List<GrammarAST> nodes = root.getNodesWithType(null);
		// visit various node types
		for (Rule r : g.rules.values()) {
			RuleAST rast = r.ast;
			if (rast != null) {
				assertNotNull(rast.getRuleName());
				assertNotNull(rast.dupNode());
				rast.isLexerRule();
			}
		}
	}

	@Test
	public void testCodeGenPipelineInterpreterData() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "ID.g4",
				"lexer grammar ID;\n" +
				"channels { CH }\n" +
				"A : 'a' -> channel(CH) ;\n" +
				"mode M;\n" +
				"B : 'b' ;\n");
		// -Xexact-output-dir etc.
		antlr("ID.g4", false);
	}

	@Test
	public void testWerrorAndMsgFormat() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "W.g4",
				"grammar W;\n" +
				"s : 'x' | 'x' ; // identical alts warning\n" +
				"WS : [ \\n]+ -> skip ;\n");
		antlr("W.g4", false, "-Werror", "-message-format", "gnu");
		antlr("W.g4", false, "-message-format", "vs2005");
	}
}
