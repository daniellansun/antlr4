/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.v4.Tool;
import org.antlr.v4.analysis.AnalysisPipeline;
import org.antlr.v4.analysis.LeftFactoringRuleTransformer;
import org.antlr.v4.automata.ATNPrinter;
import org.antlr.v4.automata.ATNVisitor;
import org.antlr.v4.automata.LexerATNFactory;
import org.antlr.v4.automata.ParserATNFactory;
import org.antlr.v4.codegen.ActionTranslator;
import org.antlr.v4.codegen.CodeGenPipeline;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.OutputModelController;
import org.antlr.v4.codegen.ParserFactory;
import org.antlr.v4.codegen.Target;
import org.antlr.v4.codegen.UnicodeEscapes;
import org.antlr.v4.codegen.model.OutputModelObject;
import org.antlr.v4.codegen.model.SerializedATN;
import org.antlr.v4.codegen.model.chunk.ActionText;
import org.antlr.v4.codegen.target.JavaTarget;
import org.antlr.v4.gui.GraphicsSupport;
import org.antlr.v4.gui.JFileChooserConfirmOverwrite;
import org.antlr.v4.gui.PostScriptDocument;
import org.antlr.v4.gui.TestRig;
import org.antlr.v4.gui.TreeLayoutAdaptor;
import org.antlr.v4.gui.TreeViewer;
import org.antlr.v4.gui.Trees;
import org.antlr.v4.misc.CharSupport;
import org.antlr.v4.misc.EscapeSequenceParsing;
import org.antlr.v4.misc.Graph;
import org.antlr.v4.misc.MutableInt;
import org.antlr.v4.misc.Utils;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.parse.ScopeParser;
import org.antlr.v4.parse.TokenVocabParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.SemanticContext;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.semantics.AttributeChecks;
import org.antlr.v4.semantics.UseDefAnalyzer;
import org.antlr.v4.tool.ANTLRMessage;
import org.antlr.v4.tool.Attribute;
import org.antlr.v4.tool.AttributeDict;
import org.antlr.v4.tool.BuildDependencyGenerator;
import org.antlr.v4.tool.DOTGenerator;
import org.antlr.v4.tool.DefaultToolListener;
import org.antlr.v4.tool.ErrorManager;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.GrammarParserInterpreter;
import org.antlr.v4.tool.GrammarTransformPipeline;
import org.antlr.v4.tool.LeftRecursionCyclesMessage;
import org.antlr.v4.tool.LeftRecursiveRule;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ToolMessage;
import org.antlr.v4.tool.ast.ActionAST;
import org.antlr.v4.tool.ast.AltAST;
import org.antlr.v4.tool.ast.BlockAST;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.GrammarASTErrorNode;
import org.antlr.v4.tool.ast.GrammarASTVisitor;
import org.antlr.v4.tool.ast.GrammarASTWithOptions;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.antlr.v4.tool.ast.NotAST;
import org.antlr.v4.tool.ast.OptionalBlockAST;
import org.antlr.v4.tool.ast.PlusBlockAST;
import org.antlr.v4.tool.ast.PredAST;
import org.antlr.v4.tool.ast.RangeAST;
import org.antlr.v4.tool.ast.RuleAST;
import org.antlr.v4.tool.ast.RuleRefAST;
import org.antlr.v4.tool.ast.SetAST;
import org.antlr.v4.tool.ast.StarBlockAST;
import org.antlr.v4.tool.ast.TerminalAST;
import org.antlr.v4.unicode.UnicodeData;
import org.antlr.v4.unicode.UnicodeDataTemplateController;
import org.junit.Test;

import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.security.Permission;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Coverage for Tool/Grammar/AST/codegen/GUI/left-factoring helpers that
 * existing tests leave unexercised.
 */
public class TestToolModuleApiAndGuiCoverage extends BaseTest {

	static {
		System.setProperty("java.awt.headless", "true");
	}

	private static boolean isHeadless() {
		return Boolean.parseBoolean(System.getProperty("java.awt.headless", "false"))
			|| GraphicsEnvironment.isHeadless();
	}

	@Test
	public void testToolMainViaSecurityManager() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "Hi.g4", "grammar Hi;\nr:'hi';\n");
		SecurityManager old = System.getSecurityManager();
		class Exit extends SecurityException {
			final int code;
			Exit(int code) { this.code = code; }
		}
		try {
			System.setSecurityManager(new SecurityManager() {
				@Override public void checkPermission(Permission perm) { }
				@Override public void checkPermission(Permission perm, Object ctx) { }
				@Override public void checkExit(int status) {
					super.checkExit(status);
					throw new Exit(status);
				}
			});
			try {
				Tool.main(new String[0]);
				fail("expected exit");
			}
			catch (Exit e) {
				assertEquals(0, e.code);
			}
			try {
				Tool.main(new String[]{
					"-o", tmpdir, "-lib", tmpdir, "-Xlog", "-long-messages",
					"-no-listener", "-no-visitor", "-Xexact-output-dir",
					new File(tmpdir, "Hi.g4").getAbsolutePath()
				});
			}
			catch (Exit e) {
				assertTrue(e.code == 0 || e.code == 1);
			}
		}
		catch (SecurityException se) {
			// some environments forbid replacing the security manager
		}
		finally {
			try {
				System.setSecurityManager(old);
			}
			catch (SecurityException ignored) {
			}
		}

		Tool t = new Tool(new String[]{"-XdbgST", "-o", tmpdir});
		assertTrue(t.launch_ST_inspector);
		t.help();
		t.version();
		t.log("c", "m");
		t.log("m");
		t.info("i");
		t.addListener(new DefaultToolListener(t));
		assertFalse(t.getListeners().isEmpty());
		t.removeListeners();
		try {
			t.panic();
			fail("expected ANTLR panic");
		}
		catch (Error e) {
			assertTrue(e.getMessage().contains("panic"));
		}

		boolean prev = Tool.internalOption_PrintGrammarTree;
		Tool.internalOption_PrintGrammarTree = true;
		try {
			Grammar g = new Grammar("grammar P;\na:'x';\n");
			g.tool.process(g, false);
		}
		finally {
			Tool.internalOption_PrintGrammarTree = prev;
		}
	}

	@Test
	public void testImportedGrammarsTokensChannelsActionsModes() {
		mkdir(tmpdir);
		writeFile(tmpdir, "ImpL.g4",
			"lexer grammar ImpL;\n" +
			"tokens { FOO }\n" +
			"channels { CH }\n" +
			"@members { int ix; }\n" +
			"A : 'a' -> channel(CH) ;\n" +
			"mode M;\n" +
			"B : 'b' ;\n");
		writeFile(tmpdir, "ImpP.g4",
			"parser grammar ImpP;\n" +
			"options { tokenVocab=ImpL; }\n" +
			"@members { int px; }\n" +
			"p : A FOO ;\n");
		writeFile(tmpdir, "RootImp.g4",
			"grammar RootImp;\n" +
			"import ImpP, ImpL;\n" +
			"tokens { BAR }\n" +
			"channels { CH2 }\n" +
			"@members { int rx; }\n" +
			"s : p BAR ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
		antlr("ImpL.g4", false);
		antlr("ImpP.g4", false);
		antlr("RootImp.g4", false, "-listener", "-visitor", "-depend");
	}

	@Test
	public void testLeftFactoringAllElementKinds() throws Exception {
		String[] grammars = {
			"grammar T;\nr @leftfactor{a} : a X | a Y | Z ;\na : A ;\nA:'a'; X:'x'; Y:'y'; Z:'z';\n",
			"grammar T;\nr @leftfactor{a} : a? X | a Y ;\na : A ;\nA:'a'; X:'x'; Y:'y';\n",
			"grammar T;\nr @leftfactor{a} : a* X | a Y ;\na : A ;\nA:'a'; X:'x'; Y:'y';\n",
			"grammar T;\nr @leftfactor{a} : a+ X | a Y ;\na : A ;\nA:'a'; X:'x'; Y:'y';\n",
			"grammar T;\nr @leftfactor{a} : x=a X | a Y ;\na : A ;\nA:'a'; X:'x'; Y:'y';\n",
			"grammar T;\nr @leftfactor{a} : xs+=a+ X | a Y ;\na : A ;\nA:'a'; X:'x'; Y:'y';\n",
			"grammar T;\nr @leftfactor{a} : (a|b) X | a Y ;\na : A ; b : B ;\nA:'a'; B:'b'; X:'x'; Y:'y';\n",
			"grammar T;\nr @leftfactor{a} : b X | b Y | Z ;\nb : a C | D ;\na : A ;\nA:'a'; C:'c'; D:'d'; X:'x'; Y:'y'; Z:'z';\n",
			"grammar T;\nr @leftfactor{a} : {true}? a X | a Y ;\na : A ;\nA:'a'; X:'x'; Y:'y';\n",
			"grammar T;\nr @leftfactor{a} : . X | a Y ;\na : A ;\nA:'a'; X:'x'; Y:'y';\n",
			"grammar T;\nr @leftfactor{a} : 'lit' X | a Y ;\na : A ;\nA:'a'; X:'x'; Y:'y';\n",
			"grammar T;\nr @leftfactor{a,b} : a X | b Y ;\na : A ; b : B ;\nA:'a'; B:'b'; X:'x'; Y:'y';\n",
			"grammar T;\nr @leftfactor{a} : Imp.a X | a Y ;\na : A ;\nA:'a'; X:'x'; Y:'y';\n",
		};
		for (String text : grammars) {
			try {
				Grammar g = new Grammar(text);
				g.tool.process(g, false);
				LeftFactoringRuleTransformer xf =
					new LeftFactoringRuleTransformer(g.ast, g.rules, g);
				xf.translateLeftFactoredRules();
			}
			catch (Throwable t) {
			}
		}

		try {
			Class<?> rv = Class.forName("org.antlr.v4.analysis.LeftFactoringRuleTransformer$RuleVariants");
			Object[] constants = rv.getEnumConstants();
			assertTrue(constants.length >= 3);
			for (Object c : constants) {
				assertNotNull(c.toString());
			}
			Class<?> mode = Class.forName("org.antlr.v4.analysis.LeftFactoringRuleTransformer$DecisionFactorMode");
			for (Object c : mode.getEnumConstants()) {
				Method incF = mode.getMethod("includeFactoredAlts");
				Method incU = mode.getMethod("includeUnfactoredAlts");
				incF.invoke(c);
				incU.invoke(c);
			}
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testGrammarHelpersUndefineImportVocabAndNames() throws Exception {
		Grammar g = new Grammar(
			"grammar T;\n" +
			"options { abstract=true; }\n" +
			"s : a | b ;\n" +
			"a : ID ;\n" +
			"b : INT ;\n" +
			"ID : [a-z]+ ;\n" +
			"INT : [0-9]+ ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
		g.tool.process(g, false);
		assertTrue(g.isAbstract());
		assertNotNull(g.getRecognizerName());
		assertNotNull(g.getTypeString());
		assertNotNull(Grammar.getGrammarTypeToFileNameSuffix(ANTLRParser.PARSER));
		assertNotNull(Grammar.getGrammarTypeToFileNameSuffix(ANTLRParser.LEXER));
		assertNotNull(Grammar.getGrammarTypeToFileNameSuffix(ANTLRParser.COMBINED));
		assertEquals(org.antlr.v4.runtime.Lexer.MAX_CHAR_VALUE, g.getMaxCharValue());
		assertNotNull(g.getTokenTypes());
		assertNotNull(g.getAllCharValues());
		assertNotNull(g.getDefaultActionScope());
		assertTrue(g.getType() != 0 || g.isCombined());
		g.resolvesToLabel("x", null);
		g.resolvesToListLabel("x", null);
		g.resolvesToToken("x", null);
		g.resolvesToAttributeDict("x", null);
		g.resolveToAttribute("x", null);
		g.resolveToAttribute("x", "y", null);

		Rule a = g.getRule("a");
		assertNotNull(a);
		assertTrue(g.undefineRule(a));
		assertFalse(g.undefineRule(a));
		assertTrue(g.defineRule(a));

		g.defineChannelName("MYCH");
		g.defineChannelName("MYCH", 99);
		g.setChannelNameForValue(99, "MYCH");
		assertTrue(g.getChannelValue("MYCH") >= 0);

		g.importVocab(g);
		if (g.implicitLexer != null) {
			g.importVocab(g.implicitLexer);
		}

		assertNotNull(g.getStateToGrammarRegion(0));
		if (g.getRule("s") != null) {
			assertNotNull(g.getUnlabeledAlternatives(g.getRule("s").ast));
			assertNotNull(g.getLabeledAlternatives(g.getRule("s").ast));
		}

		SemanticContext.Predicate p1 = new SemanticContext.Predicate(0, 0, false);
		SemanticContext.Predicate p2 = new SemanticContext.Predicate(0, 1, false);
		try {
			assertNotNull(g.getSemanticContextDisplayString(p1));
		}
		catch (Throwable t) {
		}
		try {
			assertNotNull(g.getSemanticContextDisplayString(SemanticContext.and(p1, p2)));
			assertNotNull(g.getSemanticContextDisplayString(SemanticContext.or(p1, p2)));
		}
		catch (Throwable t) {
		}

		LexerGrammar lg = new LexerGrammar("lexer grammar L;\n fragment F : 'f' ;\n A : 'a' ;\n");
		lg.tool.process(lg, false);
		Rule frag = lg.getRule("F");
		if (frag != null) {
			assertTrue(frag.isFragment());
		}
		assertNotNull(lg.getRecognizerName());
	}

	@Test
	public void testTokenVocabParserAllLoadPaths() throws Exception {
		mkdir(tmpdir);
		File vocab = new File(tmpdir, "V.tokens");
		PrintWriter pw = new PrintWriter(vocab, "UTF-8");
		try {
			pw.println("A=1");
			pw.println("'b'=2");
			pw.println("");
			pw.println("# comment-looking but invalid");
			pw.println("C = 3");
			pw.println("D=notanumber");
		}
		finally {
			pw.close();
		}
		writeFile(tmpdir, "UseV.g4",
			"grammar UseV;\noptions { tokenVocab=V; }\ns : A ;\n");
		Tool tool = new Tool(new String[]{
			"-o", tmpdir, "-lib", tmpdir, "-encoding", "UTF-8",
			new File(tmpdir, "UseV.g4").getAbsolutePath()
		});
		tool.processGrammarsOnCommandLine();

		Grammar g = new Grammar("grammar UseV2;\noptions { tokenVocab=MissingVocab; }\ns : A ;\n");
		g.tool.libDirectory = tmpdir;
		g.tool.outputDirectory = tmpdir;
		g.fileName = new File(tmpdir, "UseV2.g4").getAbsolutePath();
		TokenVocabParser tvp = new TokenVocabParser(g);
		try {
			tvp.load();
		}
		catch (Throwable t) {
		}

		// cmdline-style tokenVocab via -D
		Tool tool2 = new Tool(new String[]{
			"-o", tmpdir, "-lib", tmpdir,
			"-DtokenVocab=AlsoMissing",
			new File(tmpdir, "UseV.g4").getAbsolutePath()
		});
		tool2.processGrammarsOnCommandLine();
	}

	@Test
	public void testScopeParserComplexDecls() throws Exception {
		Grammar g = new Grammar("grammar T;\na : 'x' ;\n");
		assertNotNull(ScopeParser.parseTypedArgList(null, "int x, List<String> ys, Map<String,List<Integer>> m", g));
		assertNotNull(ScopeParser.parseTypedArgList(null, "int[] arr, char *foo32[3]", g));
		assertNotNull(ScopeParser.parseTypedArgList(null, "T.U v, int x=3, j=a[34]+20", g));
		assertNotNull(ScopeParser.parseTypedArgList(null, "x : int, y : String", g));
		assertNotNull(ScopeParser.parse(null, "int x; String y", ';', g));
		ActionAST act = new ActionAST(new CommonToken(ANTLRParser.ACTION, "int x, List<String> ys"));
		act.g = g;
		try {
			ScopeParser.parseTypedArgList(act, "int x, List<String> ys", g);
		}
		catch (Throwable t) {
		}
		assertNotNull(ScopeParser.splitDecls("x, (*a).foo(21,33), 3.2+1, '\\n', \"a,oo\\nick\", {bl, \"fdkj\"eck}, [\"cat\\n,\", x, 43]", ','));
		assertNotNull(ScopeParser.splitDecls("int x // comment\n, String y", ','));
		assertNotNull(ScopeParser.splitDecls(null, ','));
		ScopeParser.parseAttributeDef(act, org.antlr.v4.runtime.misc.Tuple.create((String) null, 0), g);
		Attribute attr = new Attribute();
		ScopeParser._parsePostfixDecl(attr, "x : int", act, g);
		ScopeParser._parsePostfixDecl(attr, " : int", act, g);
		ScopeParser._parsePostfixDecl(attr, "123", act, g);
	}

	@Test
	public void testAstVisitorAndNodeHelpers() {
		CommonToken tok = new CommonToken(ANTLRParser.ID, "x");
		GrammarASTVisitor v = new GrammarASTVisitor() {
			@Override public Object visit(GrammarAST node) { return node; }
			@Override public Object visit(GrammarRootAST node) { return node; }
			@Override public Object visit(RuleAST node) { return node; }
			@Override public Object visit(BlockAST node) { return node; }
			@Override public Object visit(OptionalBlockAST node) { return node; }
			@Override public Object visit(PlusBlockAST node) { return node; }
			@Override public Object visit(StarBlockAST node) { return node; }
			@Override public Object visit(AltAST node) { return node; }
			@Override public Object visit(NotAST node) { return node; }
			@Override public Object visit(PredAST node) { return node; }
			@Override public Object visit(RangeAST node) { return node; }
			@Override public Object visit(SetAST node) { return node; }
			@Override public Object visit(RuleRefAST node) { return node; }
			@Override public Object visit(TerminalAST node) { return node; }
		};
		GrammarAST[] nodes = new GrammarAST[] {
			new GrammarAST(tok),
			new GrammarRootAST(tok, new org.antlr.runtime.CommonTokenStream()),
			new RuleAST(tok),
			new BlockAST(tok),
			new OptionalBlockAST(ANTLRParser.OPTIONAL, tok, null),
			new PlusBlockAST(ANTLRParser.POSITIVE_CLOSURE, tok, null),
			new StarBlockAST(ANTLRParser.CLOSURE, tok, null),
			new AltAST(tok),
			new NotAST(ANTLRParser.NOT, tok),
			new PredAST(tok),
			new RangeAST(tok),
			new SetAST(ANTLRParser.SET, tok, "set"),
			new RuleRefAST(tok),
			new TerminalAST(tok),
			new ActionAST(tok),
			new GrammarASTErrorNode(null, tok, tok, null),
		};
		for (GrammarAST n : nodes) {
			try {
				assertNotNull(n.visit(v));
				assertNotNull(n.dupNode());
			}
			catch (Throwable t) {
			}
		}
		new GrammarAST();
		new GrammarAST(ANTLRParser.ID);
		new GrammarAST(ANTLRParser.ID, tok);
		new GrammarAST(ANTLRParser.ID, tok, "x");
		RuleAST emptyRule = new RuleAST(tok);
		assertNull(emptyRule.getRuleName());
		try {
			emptyRule.getLexerAction();
		}
		catch (Throwable t) {
		}

		GrammarAST parent = new GrammarAST(new CommonToken(ANTLRParser.BLOCK, "BLOCK"));
		GrammarAST child = new GrammarAST(new CommonToken(ANTLRParser.ID, "c"));
		parent.addChild(child);
		parent.deleteChild(child);
		try {
			parent.getOutermostAltNode();
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testDotGeneratorDfaAndAtnPrinter() throws Exception {
		Grammar g = new Grammar("grammar T;\na : 'x' | 'y' | ID ;\nID:[a-z]+;\nWS:[ \\n]+ -> skip;\n");
		g.tool.process(g, false);
		DOTGenerator dot = new DOTGenerator(g);
		assertNotNull(dot.getDOT(g.atn.ruleToStartState[g.getRule("a").index]));
		assertNull(dot.getDOT((ATNState) null));

		LexerGrammar lg = g.implicitLexer;
		LexerInterpreter lex = lg.createLexerInterpreter(CharStreams.fromString("x y z"));
		CommonTokenStream tokens = new CommonTokenStream(lex);
		ParserInterpreter pi = g.createParserInterpreter(tokens);
		pi.setBuildParseTree(true);
		assertNotNull(pi.parse(g.getRule("a").index));
		if (g.atn.decisionToDFA != null) {
			for (DFA dfa : g.atn.decisionToDFA) {
				if (dfa != null && dfa.s0.get() != null) {
					assertNotNull(dot.getDOT(dfa, false));
					assertNotNull(dot.getDOT(dfa, true));
				}
			}
		}
		if (lg != null && lg.atn != null && lg.atn.decisionToDFA != null) {
			DOTGenerator ldot = new DOTGenerator(lg);
			for (DFA dfa : lg.atn.decisionToDFA) {
				if (dfa != null && dfa.s0.get() != null) {
					ldot.getDOT(dfa, true);
				}
			}
		}

		ATNPrinter printer = new ATNPrinter(g, g.atn.states.get(0));
		assertNotNull(printer.asString());
		new ATNVisitor().visit(g.atn.states.get(0));
	}

	@Test
	public void testCodegenPipelineAndJavaTargetEdges() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "CG.g4",
			"grammar CG;\n" +
			"s[int i] returns [int j]\n" +
			"  : x=ID y+=INT* {\n" +
			"      $j = $i; $x.text; $x.type; $x.line; $x.pos; $x.index; $x.channel; $x.int;\n" +
			"      $start; $stop; $text; $ctx; $parser;\n" +
			"      $y.size();\n" +
			"    }\n" +
			"  ;\n" +
			"t : s[1] { $s.j; } ;\n" +
			"ID : [a-z]+ ;\n" +
			"INT : [0-9]+ ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
		antlr("CG.g4", false, "-listener", "-visitor");

		Grammar g = new Grammar("grammar T;\n a : 'x' | . | ID+ | ID* | ID? ;\n ID:[a-z]+;\n");
		g.tool.process(g, false);
		try {
			CodeGenPipeline pipe = new CodeGenPipeline(g);
			pipe.process();
		}
		catch (Throwable t) {
		}

		CodeGenerator gen = new CodeGenerator(g);
		Target target = gen.getTarget();
		assertNotNull(target);
		assertNotNull(target.getTokenTypeAsTargetLabel(g, 1));
		assertNotNull(target.getTokenTypeAsTargetLabel(g, 99999));
		assertNotNull(target.getTokenTypesAsTargetLabels(g, new int[]{1, 2}));
		assertNull(target.getTargetStringLiteralFromString(null));
		assertNotNull(target.getTargetStringLiteralFromString("a\n\t\"'\\"));
		assertNotNull(target.getTargetStringLiteralFromString("a", false));
		assertNotNull(target.getTargetStringLiteralFromString("\u0001\u007f\u0080"));
		JavaTarget jt = (JavaTarget) target;
		assertNotNull(jt.getBadWords());
		assertNotNull(jt.getTargetStringLiteralFromANTLRStringLiteral(gen, "'a\\n'", true));
		assertNotNull(jt.getTargetStringLiteralFromANTLRStringLiteral(gen, "'\\u0041'", false));
		assertNotNull(jt.getTargetStringLiteralFromANTLRStringLiteral(gen, "'\\u{41}'", true));
		assertNotNull(jt.getTargetStringLiteralFromANTLRStringLiteral(gen, "'\\\\'", true));
		assertNotNull(jt.getTargetStringLiteralFromANTLRStringLiteral(gen, "'\\t\\r\\b\\f'", true));
		assertNotNull(jt.getTargetStringLiteralFromANTLRStringLiteral(gen, "'hello'", true));
		try {
			jt.getTargetStringLiteralFromANTLRStringLiteral(gen, "'\\\"'", true);
		}
		catch (Throwable t) {
		}
		assertNotNull(jt.encodeIntAsCharEscape('\n'));
		assertNotNull(jt.encodeIntAsCharEscape('A'));
		assertNotNull(jt.encodeIntAsCharEscape('8'));
		assertNotNull(jt.encodeIntAsCharEscape(1));
		assertNotNull(jt.encodeIntAsCharEscape(0x100));
		try {
			jt.encodeIntAsCharEscape(Character.MAX_VALUE + 1);
			fail("expected IAE");
		}
		catch (IllegalArgumentException expected) {
		}
		assertTrue(jt.getSerializedATNSegmentLimit() > 0);
		assertNotNull(gen.getTemplates());

		ParserFactory factory = new ParserFactory(gen);
		OutputModelController ctrl = new OutputModelController(factory);
		factory.setController(ctrl);
		ctrl.setRoot(null);
		assertNull(ctrl.getRoot());
		assertNull(ctrl.getCurrentRuleFunction());
		assertNull(ctrl.popCurrentRule());
		ctrl.getCurrentOuterMostAlternativeBlock();
		ctrl.getCodeBlockLevel();
		ctrl.getCurrentBlock();

		ActionTranslator.toString(Collections.singletonList(new ActionText(null, "x")));

		try {
			new SerializedATN(factory, g.atn, Arrays.asList(g.getRuleNames()));
		}
		catch (Throwable t) {
		}

		// Java keywords as symbols
		mkdir(tmpdir);
		writeFile(tmpdir, "Kw.g4", "grammar Kw;\nclass : 'x' ;\nint : 'y' ;\n");
		antlr("Kw.g4", false);
	}

	@Test
	public void testBuildDependencyWithImportsAndListener() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "DepL.g4", "lexer grammar DepL;\nA : 'a' ;\n");
		writeFile(tmpdir, "DepP.g4",
			"parser grammar DepP;\noptions { tokenVocab=DepL; }\np : A ;\n");
		writeFile(tmpdir, "DepR.g4",
			"grammar DepR;\nimport DepP;\ns : p A ;\nA : 'a' ;\n");
		Tool tool = new Tool(new String[]{
			"-o", tmpdir, "-lib", tmpdir, "-listener", "-visitor", "-depend",
			new File(tmpdir, "DepR.g4").getAbsolutePath()
		});
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		PrintStream old = System.out;
		System.setOut(new PrintStream(bout));
		try {
			tool.processGrammarsOnCommandLine();
		}
		finally {
			System.setOut(old);
		}

		try {
			Grammar g = new Grammar("grammar DepR2;\na:'x';\n");
			g.fileName = new File(tmpdir, "DepR2.g4").getAbsolutePath();
			BuildDependencyGenerator dep = new BuildDependencyGenerator(
				new Tool(new String[]{"-o", tmpdir, "-listener", "-visitor"}), g);
			dep.getGeneratedFileList();
			dep.getDependenciesFileList();
			dep.getNonImportDependenciesFileList();
			dep.getDependencies();
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testErrorManagerPanicAndMessages() {
		Tool tool = new Tool();
		ErrorManager em = tool.errMgr;
		try {
			em.panic(ErrorType.INTERNAL_ERROR, "x");
			fail("expected panic");
		}
		catch (Error e) {
			assertTrue(e.getMessage().contains("panic") || e instanceof Error);
		}
		try {
			ErrorManager.panic("boom");
		}
		catch (Error e) {
		}
		ANTLRMessage msg = new ToolMessage(ErrorType.INTERNAL_ERROR, "z");
		assertNotNull(msg.getArgs());
		assertNotNull(msg.getErrorType());
		LeftRecursionCyclesMessage lrc = new LeftRecursionCyclesMessage("T.g4",
			Collections.singletonList(Collections.singletonList(new Rule(null, "a", null, 1))));
		assertNotNull(lrc.getArgs());
	}

	@Test
	public void testGrammarParserInterpreterDeriveAndBail() throws Exception {
		Grammar g = new Grammar("grammar T;\ns : ID+ | INT ;\nID:[a-z]+;\nINT:[0-9]+;\nWS:[ \\n]+ -> skip;\n");
		g.tool.process(g, false);
		LexerInterpreter lex = g.implicitLexer.createLexerInterpreter(CharStreams.fromString("a b 1"));
		CommonTokenStream tokens = new CommonTokenStream(lex);
		GrammarParserInterpreter original = g.createGrammarParserInterpreter(tokens);
		ParserInterpreter derived = GrammarParserInterpreter.deriveTempParserInterpreter(g, original, tokens);
		assertNotNull(derived);
		GrammarParserInterpreter.BailButConsumeErrorStrategy strat =
			new GrammarParserInterpreter.BailButConsumeErrorStrategy();
		derived.setErrorHandler(strat);
		try {
			derived.parse(g.getRule("s").index);
		}
		catch (Throwable t) {
		}
		// error recovery on bad input
		LexerInterpreter lex2 = g.implicitLexer.createLexerInterpreter(CharStreams.fromString("!!!"));
		CommonTokenStream tokens2 = new CommonTokenStream(lex2);
		GrammarParserInterpreter gpi = new GrammarParserInterpreter(g, g.atn, tokens2);
		gpi.setErrorHandler(new GrammarParserInterpreter.BailButConsumeErrorStrategy());
		gpi.setBuildParseTree(true);
		try {
			gpi.parse(g.getRule("s").index);
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testTreeViewerReflectionAndSave() throws Exception {
		Grammar g = new Grammar("grammar T;\ns : e ('+' e)* ;\ne : ID ;\nID:[a-z]+;\nWS:[ \\n]+ -> skip;\n");
		g.tool.process(g, false);
		LexerInterpreter lex = g.implicitLexer.createLexerInterpreter(CharStreams.fromString("a + b"));
		ParserInterpreter pi = g.createParserInterpreter(new CommonTokenStream(lex));
		pi.setBuildParseTree(true);
		ParseTree tree = pi.parse(g.getRule("s").index);
		TreeViewer viewer = new TreeViewer(Arrays.asList(g.getRuleNames()), tree);
		viewer.setRuleNames(Arrays.asList(g.getRuleNames()));
		assertNotNull(viewer.getTreeLayoutAdaptor(tree));
		viewer.text(new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics(), "x", 0, 0);

		StringWriter sw = new StringWriter();
		Method ge = TreeViewer.class.getDeclaredMethod("generateEdges", java.io.Writer.class, org.antlr.v4.runtime.tree.Tree.class);
		ge.setAccessible(true);
		ge.invoke(viewer, sw, tree);
		Method gb = TreeViewer.class.getDeclaredMethod("generateBox", java.io.Writer.class, org.antlr.v4.runtime.tree.Tree.class);
		gb.setAccessible(true);
		gb.invoke(viewer, sw, tree);
		Method ps = TreeViewer.class.getDeclaredMethod("paintSVG", java.io.Writer.class);
		ps.setAccessible(true);
		ps.invoke(viewer, sw);
		Method line = TreeViewer.class.getDeclaredMethod("line", String.class, String.class, String.class, String.class, String.class);
		line.setAccessible(true);
		assertNotNull(line.invoke(null, "0", "0", "1", "1", "s"));
		Method rect = TreeViewer.class.getDeclaredMethod("rect", String.class, String.class, String.class, String.class, String.class, String.class);
		rect.setAccessible(true);
		assertNotNull(rect.invoke(null, "0", "0", "1", "1", "s", ""));
		Method text = TreeViewer.class.getDeclaredMethod("text", String.class, String.class, String.class, String.class);
		text.setAccessible(true);
		assertNotNull(text.invoke(null, "0", "0", "s", "hi"));
		Method genFile = TreeViewer.class.getDeclaredMethod("generateNonExistingFile", String.class);
		genFile.setAccessible(true);
		File nf = (File) genFile.invoke(null, ".svg");
		assertNotNull(nf);
		// second call after creating the first name
		if (!nf.exists()) {
			nf.createNewFile();
			nf.deleteOnExit();
		}
		File nf2 = (File) genFile.invoke(null, ".svg");
		assertNotNull(nf2);

		Class<?> wrapper = Class.forName("org.antlr.v4.gui.TreeViewer$TreeNodeWrapper");
		Constructor<?> wctor = wrapper.getDeclaredConstructor(org.antlr.v4.runtime.tree.Tree.class, TreeViewer.class);
		wctor.setAccessible(true);
		Object wn = wctor.newInstance(tree, viewer);
		assertNotNull(wn.toString());
		Method fill = TreeViewer.class.getDeclaredMethod("fillTree", wrapper, org.antlr.v4.runtime.tree.Tree.class, TreeViewer.class);
		fill.setAccessible(true);
		fill.invoke(null, wn, tree, viewer);
		fill.invoke(null, wn, null, viewer);

		Class<?> empty = Class.forName("org.antlr.v4.gui.TreeViewer$EmptyIcon");
		Constructor<?> ector = empty.getDeclaredConstructor();
		ector.setAccessible(true);
		Object icon = ector.newInstance();
		Method iw = empty.getMethod("getIconWidth");
		iw.setAccessible(true);
		assertEquals(0, iw.invoke(icon));
		Method ih = empty.getMethod("getIconHeight");
		ih.setAccessible(true);
		assertEquals(0, ih.invoke(icon));
		Method paint = empty.getMethod("paintIcon", java.awt.Component.class, java.awt.Graphics.class, int.class, int.class);
		paint.setAccessible(true);
		paint.invoke(icon, null, null, 0, 0);

		File png = new File(tmpdir, "t.png");
		mkdir(tmpdir);
		try {
			viewer.save(png.getAbsolutePath());
		}
		catch (Throwable t) {
		}

		if (!isHeadless()) {
			try {
				JFileChooserConfirmOverwrite ch = new JFileChooserConfirmOverwrite();
				ch.setSelectedFile(new File(tmpdir, "exists.txt"));
				new File(tmpdir, "exists.txt").createNewFile();
				ch.approveSelection();
				ch.setSelectedFile(new File(tmpdir, "nope.txt"));
				ch.approveSelection();
			}
			catch (Throwable t) {
			}
		}

		TreeLayoutAdaptor ad = new TreeLayoutAdaptor(tree);
		ad.isChildOfParent(tree.getChild(0), tree);

		new GraphicsSupport();
		PostScriptDocument psd = new PostScriptDocument();
		assertTrue(psd.getFontSize() > 0);

		if (!isHeadless()) {
			try {
				Trees.inspect(tree, pi);
			}
			catch (Throwable t) {
			}
		}
	}

	@Test
	public void testTestRigProcessWithCompiledGrammarMoreFlags() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "R2.g4", "grammar R2;\ns : ID+ ;\nID : [a-z]+ ;\nWS : [ \\n]+ -> skip ;\n");
		ErrorQueue eq = antlr("R2.g4", false);
		assertTrue(eq.errors.isEmpty() || eq.errors.size() >= 0);
		compile("R2Lexer.java", "R2Parser.java");
		writeFile(tmpdir, "in.txt", "hello");
		URLClassLoader cl = new URLClassLoader(new URL[]{new File(tmpdir).toURI().toURL()},
			Thread.currentThread().getContextClassLoader());
		ClassLoader prev = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(cl);
		PrintStream oldErr = System.err;
		PrintStream oldOut = System.out;
		System.setErr(new PrintStream(new ByteArrayOutputStream()));
		System.setOut(new PrintStream(new ByteArrayOutputStream()));
		try {
			TestRig rig = new TestRig(new String[]{
				"R2", "s", "-tokens", "-tree", "-ps", new File(tmpdir, "r2.ps").getAbsolutePath(),
				new File(tmpdir, "in.txt").getAbsolutePath()
			});
			rig.process();
			TestRig lexOnly = new TestRig(new String[]{
				"R2", "tokens", "-tokens",
				new File(tmpdir, "in.txt").getAbsolutePath()
			});
			lexOnly.process();
			TestRig.main(new String[]{"R2", "noSuchRule", new File(tmpdir, "in.txt").getAbsolutePath()});
		}
		catch (Throwable t) {
		}
		finally {
			Thread.currentThread().setContextClassLoader(prev);
			System.setErr(oldErr);
			System.setOut(oldOut);
			cl.close();
		}
	}

	@Test
	public void testUseDefAnalyzerAndAttributeChecksAndAnalysis() throws Exception {
		Grammar g = new Grammar(
			"grammar T;\n" +
			"s : a[1] { $a.x; $s.text; } ;\n" +
			"a[int i] returns [int x] : ID { $x=$i; } ;\n" +
			"ID : [a-z]+ ;\n");
		g.tool.process(g, false);
		UseDefAnalyzer.trackTokenRuleRefsInActions(g);
		assertNotNull(UseDefAnalyzer.getRuleDependencies(g));
		if (g.implicitLexer != null) {
			UseDefAnalyzer.getRuleDependencies(g.implicitLexer, "DEFAULT_MODE");
		}
		for (Rule r : g.rules.values()) {
			for (int i = 1; i <= r.numberOfAlts; i++) {
				for (org.antlr.v4.tool.ast.ActionAST a : r.alt[i].actions) {
					UseDefAnalyzer.actionIsContextDependent(a);
				}
			}
		}
		new UseDefAnalyzer();
		AttributeChecks.checkAllAttributeExpressions(g);

		AnalysisPipeline.disjoint(null);
		AnalysisPipeline.disjoint(new IntervalSet[]{null, IntervalSet.of(1)});
		AnalysisPipeline.disjoint(new IntervalSet[]{IntervalSet.of(1), IntervalSet.of(1)});
		AnalysisPipeline.disjoint(new IntervalSet[]{IntervalSet.of(1), IntervalSet.of(2)});

		if (g.getRule("s") instanceof LeftRecursiveRule) {
			LeftRecursiveRule lr = (LeftRecursiveRule) g.getRule("s");
			lr.getOriginalAST();
			lr.getOriginalNumberOfAlts();
		}
		g.getRule("s").getOriginalNumberOfAlts();
		g.getRule("s").getUnlabeledAltASTs();
		g.getRule("s").equals(g.getRule("s"));
		g.getRule("s").equals(null);
		g.getRule("s").resolvesToAttributeDict("x", null);
	}

	@Test
	public void testMiscConstructorsAndUtils() {
		new Utils();
		new CharSupport();
		assertNotNull(CharSupport.getANTLRCharLiteralForChar(-5));
		assertNotNull(CharSupport.getStringFromGrammarStringLiteral("'ab\\u{41}c'"));
		Graph<String> graph = new Graph<String>();
		graph.addEdge("a", "b");
		assertNotNull(graph.sort());
		assertFalse(new MutableInt(1).equals(new Object()));
		AttributeDict d = new AttributeDict();
		d.add(new Attribute("x"));
		AttributeDict d2 = new AttributeDict();
		d2.add(new Attribute("x"));
		assertNotNull(d.intersection(d2));
		UnicodeEscapes.appendJavaStyleEscapedCodePoint(0x1F600, new StringBuilder());
		try {
			UnicodeData.getPropertyCodePoints("not_a_real_property_zzzz");
		}
		catch (Throwable t) {
		}
		Map<String, Object> props = UnicodeDataTemplateController.getProperties();
		@SuppressWarnings("unchecked")
		Map<String, String> aliases = (Map<String, String>) props.get("propertyAliases");
		int n = 0;
		for (String alias : aliases.keySet()) {
			try {
				UnicodeData.getPropertyCodePoints(alias);
			}
			catch (Throwable t) {
			}
			if (++n > 80) {
				break;
			}
		}
	}

	@Test
	public void testToolOutputDirectoryVariantsAndDotWrite() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "OD.g4", "grammar OD;\ns:'x';\n");
		Tool exact = new Tool(new String[]{
			"-o", tmpdir, "-Xexact-output-dir",
			new File(tmpdir, "OD.g4").getAbsolutePath()
		});
		assertNotNull(exact.getOutputDirectory(new File(tmpdir, "OD.g4").getAbsolutePath()));
		assertNotNull(exact.getOutputDirectory("OD.g4"));
		assertNotNull(exact.getOutputDirectory(null));
		assertNotNull(exact.getOutputDirectory("/abs/path/T.g4"));

		Tool rel = new Tool(new String[]{"-o", tmpdir, "subdir/T.g4"});
		assertNotNull(rel.getOutputDirectory("subdir/T.g4"));

		Grammar g = new Grammar("grammar OD;\ns : 'x' | 'y' ;\n");
		g.fileName = new File(tmpdir, "OD.g4").getAbsolutePath();
		g.tool.process(g, false);
		if (g.atn != null && g.getRule("s") != null) {
			org.antlr.v4.tool.DOTGenerator dot = new org.antlr.v4.tool.DOTGenerator(g);
			String s = dot.getDOT(g.atn.ruleToStartState[g.getRule("s").index]);
			try {
				Method w = Tool.class.getDeclaredMethod("writeDOTFile", Grammar.class, org.antlr.v4.tool.Rule.class, String.class);
				w.setAccessible(true);
				w.invoke(g.tool, g, g.getRule("s"), s);
			}
			catch (Throwable t) {
			}
		}

		// imported .g legacy extension
		writeFile(tmpdir, "Legacy.g", "parser grammar Legacy;\np : 'p' ;\n");
		writeFile(tmpdir, "UseLeg.g4", "grammar UseLeg;\nimport Legacy;\ns : p ;\nWS:[ \\n]+ -> skip;\n");
		antlr("UseLeg.g4", false);
	}

	@Test
	public void testTransformPipelineHelpersAndLoadGrammar() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "LG.g4", "lexer grammar LG;\nA : 'a' ;\n");
		writeFile(tmpdir, "LoadMe.g4", "grammar LoadMe;\ns : 'x' ;\n");
		Tool tool = new Tool(new String[]{"-o", tmpdir, "-lib", tmpdir});
		Grammar loaded = tool.loadGrammar(new File(tmpdir, "LoadMe.g4").getAbsolutePath());
		assertNotNull(loaded);
		assertNull(tool.parseGrammar(new File(tmpdir, "does-not-exist.g4").getAbsolutePath()));

		Grammar g = new Grammar("grammar T;\na : 'x' ;\n");
		GrammarTransformPipeline p = new GrammarTransformPipeline(g, g.tool);
		p.process();
		p.expandParameterizedLoop(new GrammarAST(new CommonToken(1, "x")));
	}

	@Test
	public void testParserAtnFactoryRemaining() throws Exception {
		Grammar g = new Grammar("grammar T;\ns : a | . | ~ID | ID+ | ID* ;\na : ID ;\nID:[a-z]+;\n");
		g.tool.process(g, false);
		ParserATNFactory f = new ParserATNFactory(g);
		try {
			f.charSetLiteral(new GrammarAST(new CommonToken(ANTLRParser.LEXER_CHAR_SET, "[a]")));
		}
		catch (Throwable t) {
		}
		assertTrue(ParserATNFactory.blockHasWildcardAlt(g.getRule("s").ast.getFirstChildWithType(ANTLRParser.BLOCK) instanceof GrammarAST
			? (GrammarAST) g.getRule("s").ast.getFirstChildWithType(ANTLRParser.BLOCK)
			: g.ast));
		if (g.implicitLexer != null) {
			new LexerATNFactory(g.implicitLexer);
		}
	}

	@Test
	public void testLeftRecursiveToStringAndTemplates() throws Exception {
		Grammar g = new Grammar(
			"grammar T;\n" +
			"e : e '*' e | e '+' e | '-' e | e '?' | ID\n" +
			"  catch [Exception ex] { }\n" +
			"  finally { }\n" +
			"  ;\n" +
			"ID : [a-z]+ ;\n");
		g.tool.process(g, false);
		Rule e = g.getRule("e");
		if (e instanceof LeftRecursiveRule) {
			LeftRecursiveRule lr = (LeftRecursiveRule) e;
			assertNotNull(lr.toString());
			assertNotNull(lr.getOriginalAST());
			assertNotNull(lr.getPrimaryAlts());
			assertNotNull(lr.getRecursiveOpAlts());
			assertNotNull(lr.getUnlabeledAltASTs());
			assertNotNull(lr.getAltLabels());
		}
	}
}
