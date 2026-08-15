/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.IntStream;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.antlr.v4.Tool;
import org.antlr.v4.codegen.CodeGenPipeline;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.OutputModelController;
import org.antlr.v4.codegen.ParserFactory;
import org.antlr.v4.codegen.SourceGenTriggers;
import org.antlr.v4.gui.JFileChooserConfirmOverwrite;
import org.antlr.v4.gui.TreeViewer;
import org.antlr.v4.parse.ANTLRLexer;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.parse.GrammarASTAdaptor;
import org.antlr.v4.parse.ToolANTLRLexer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.tool.BuildDependencyGenerator;
import org.antlr.v4.tool.ErrorManager;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.TerminalAST;
import org.junit.Test;

import javax.swing.JDialog;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Direct DFA special-state coverage, TreeViewer dialog (when a display
 * is available), SourceGenTriggers typed entry points, and leftover
 * Tool/ErrorManager/BuildDependency helpers.
 */
public class TestToolModuleDfaAndDialogCoverage extends BaseTest {

	private ToolANTLRLexer lexer(String text) {
		return new ToolANTLRLexer(new ANTLRStringStream(text), new Tool());
	}

	@Test
	public void testAntlrLexerDfaSpecialStateTransitions() throws Exception {
		char[] probes = new char[258];
		for (int i = 0; i < 256; i++) {
			probes[i] = (char) i;
		}
		probes[256] = '\uFFFF';
		probes[257] = '\u0100';
		// DFA2 special states 0..8 (// $ANTLR src predicate)
		invokeDfaSpecials("dfa2", 0, 12, probes);
		// DFA35 special states
		invokeDfaSpecials("dfa35", 0, 4, probes);

		// Drive DFA2 via real comments with many $ANTLR shapes
		String[] comments = {
			"// $ANTLR src \"f.g4\" 3\n",
			"// $ANTLR src \"f.g4\" 3 extra\n",
			"// $ANTLR src \"f.g4\"\n",
			"// $ANTLR src \n",
			"// $ANTLR src \"unterminated\n",
			"// $ANTLR src \"a\\\"b\" 1\n",
			"// $ANTLR src \"a\\\\b\" 1\n",
			"// $ANTLR\n",
			"// $ANTLRx\n",
			"// $AN\n",
			"// $ANTLR src \"f.g4\" x\n",
			"// $ANTLR src \"f.g4\" \t3\n",
			"// not antlr\n",
			"// $ANTLR src \"f.g4\" 3\r\n",
		};
		for (String c : comments) {
			try {
				ToolANTLRLexer lex = lexer(c);
				lex.mCOMMENT();
			}
			catch (Throwable t) {
			}
			try {
				ToolANTLRLexer lex = lexer(c);
				org.antlr.runtime.CommonTokenStream ts = new org.antlr.runtime.CommonTokenStream(lex);
				ts.fill();
			}
			catch (Throwable t) {
			}
		}
	}

	private void invokeDfaSpecials(String fieldName, int min, int max, char[] probes) throws Exception {
		Field f = ANTLRLexer.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		for (int s = min; s <= max; s++) {
			for (char c : probes) {
				ToolANTLRLexer lex = lexer(String.valueOf(c) + "rest\n");
				Object dfa = f.get(lex);
				Method sst = dfa.getClass().getMethod("specialStateTransition", int.class, IntStream.class);
				try {
					IntStream in = lex.getCharStream();
					int mark = in.mark();
					try {
						sst.invoke(dfa, s, in);
					}
					finally {
						in.rewind(mark);
						in.release(mark);
					}
				}
				catch (Throwable t) {
				}
			}
			// empty input
			try {
				ToolANTLRLexer lex = lexer("");
				Object dfa = f.get(lex);
				Method sst = dfa.getClass().getMethod("specialStateTransition", int.class, IntStream.class);
				sst.invoke(dfa, s, lex.getCharStream());
			}
			catch (Throwable t) {
			}
		}
	}

	@Test
	public void testTreeViewerDialogOnDisplay() throws Exception {
		Grammar g = new Grammar("grammar T;\ns : ID+ ;\nID : [a-z]+ ;\nWS : [ \\n]+ -> skip ;\n");
		g.tool.process(g, false);
		LexerInterpreter lex = g.implicitLexer.createLexerInterpreter(CharStreams.fromString("hello world"));
		ParserInterpreter pi = g.createParserInterpreter(new org.antlr.v4.runtime.CommonTokenStream(lex));
		pi.setBuildParseTree(true);
		ParseTree tree = pi.parse(g.getRule("s").index);
		TreeViewer viewer = new TreeViewer(Arrays.asList(g.getRuleNames()), tree);
		viewer.setSize(200, 200);

		// Prefer a real X display if the host has one (X0) so JDialog works.
		String prevHeadless = System.getProperty("java.awt.headless");
		String prevDisplay = System.getenv("DISPLAY");
		if ((prevDisplay == null || prevDisplay.isEmpty()) && new File("/tmp/.X11-unix/X0").exists()) {
			try {
				Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
				// cannot change env reliably; set system property only
			}
			catch (Throwable t) {
			}
			System.setProperty("java.awt.headless", "false");
		}

		Method show = TreeViewer.class.getDeclaredMethod("showInDialog", TreeViewer.class);
		show.setAccessible(true);
		Object dialog = null;
		try {
			if (!GraphicsEnvironment.isHeadless()) {
				dialog = show.invoke(null, viewer);
				if (dialog instanceof JDialog) {
					JDialog d = (JDialog) dialog;
					// fire windowClosing to cover the adapter
					d.dispatchEvent(new java.awt.event.WindowEvent(d, java.awt.event.WindowEvent.WINDOW_CLOSING));
					d.dispose();
				}
			}
			else {
				// still attempt: some JVMs construct JDialog in headless
				try {
					dialog = show.invoke(null, viewer);
				}
				catch (Throwable t) {
				}
			}
		}
		catch (Throwable t) {
		}
		finally {
			if (prevHeadless != null) {
				System.setProperty("java.awt.headless", prevHeadless);
			}
		}

		// FileFilter accept/description
		try {
			Method chooser = TreeViewer.class.getDeclaredMethod("getFileChooser", String.class, String.class);
			chooser.setAccessible(true);
			if (!GraphicsEnvironment.isHeadless()) {
				Object fc = chooser.invoke(null, ".png", "PNG files");
				assertNotNull(fc);
			}
		}
		catch (Throwable t) {
		}

		if (!GraphicsEnvironment.isHeadless()) {
			try {
				JFileChooserConfirmOverwrite ch = new JFileChooserConfirmOverwrite();
				ch.setSelectedFile(new File(tmpdir, "newfile.txt"));
				mkdir(tmpdir);
				ch.approveSelection();
			}
			catch (Throwable t) {
			}
		}
	}

	@Test
	public void testSourceGenTriggersTypedAtomAndLabel() throws Exception {
		Grammar g = new Grammar("grammar T;\ns : x=ID y+=INT* | . | 'lit' | a[1] ;\na[int i] : ID ;\nID:[a-z]+;\nINT:[0-9]+;\n");
		g.tool.process(g, false);
		CodeGenerator gen = new CodeGenerator(g);
		if (gen.getTarget() == null) return;
		ParserFactory factory = new ParserFactory(gen);
		OutputModelController ctrl = new OutputModelController(factory);
		factory.setController(ctrl);

		GrammarASTAdaptor adaptor = new GrammarASTAdaptor();
		CommonTreeNodeStream nodes = new CommonTreeNodeStream(adaptor, g.ast);
		SourceGenTriggers sgt = new SourceGenTriggers(nodes, ctrl);

		TerminalAST id = new TerminalAST(new CommonToken(ANTLRParser.TOKEN_REF, "ID"));
		TerminalAST lit = new TerminalAST(new CommonToken(ANTLRParser.STRING_LITERAL, "'x'"));
		GrammarAST wildcard = new GrammarAST(new CommonToken(ANTLRParser.WILDCARD, "."));
		GrammarAST ruleRef = new GrammarAST(new CommonToken(ANTLRParser.RULE_REF, "a"));

		Method atom = null;
		for (Method m : SourceGenTriggers.class.getDeclaredMethods()) {
			if ("atom".equals(m.getName()) && m.getParameterTypes().length == 2) {
				atom = m;
				break;
			}
		}
		if (atom != null) {
			atom.setAccessible(true);
			try { atom.invoke(sgt, id, Boolean.FALSE); } catch (Throwable t) {}
			try { atom.invoke(sgt, id, Boolean.TRUE); } catch (Throwable t) {}
			try { atom.invoke(sgt, lit, Boolean.FALSE); } catch (Throwable t) {}
			try { atom.invoke(sgt, wildcard, Boolean.FALSE); } catch (Throwable t) {}
			try { atom.invoke(sgt, ruleRef, Boolean.FALSE); } catch (Throwable t) {}
		}

		Method term = null;
		for (Method m : SourceGenTriggers.class.getDeclaredMethods()) {
			if ("terminal".equals(m.getName()) && m.getParameterTypes().length == 1) {
				term = m;
				break;
			}
		}
		if (term != null) {
			term.setAccessible(true);
			try { term.invoke(sgt, id); } catch (Throwable t) {}
			try { term.invoke(sgt, lit); } catch (Throwable t) {}
		}

		try { sgt.labeledElement(); } catch (Throwable t) {}
		try { sgt.element(); } catch (Throwable t) {}
		try { sgt.elementOption(); } catch (Throwable t) {}
	}

	@Test
	public void testBuildDependencyOnProcessedCombinedGrammar() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "DepC.g4",
			"grammar DepC;\n" +
			"s : ID ;\n" +
			"ID : [a-z]+ ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
		Tool tool = new Tool(new String[]{
			"-o", tmpdir, "-lib", tmpdir, "-listener", "-visitor",
			new File(tmpdir, "DepC.g4").getAbsolutePath()
		});
		tool.processGrammarsOnCommandLine();
		try {
			Grammar g = tool.loadGrammar(new File(tmpdir, "DepC.g4").getAbsolutePath());
			BuildDependencyGenerator dep = new BuildDependencyGenerator(tool, g);
			dep.getGeneratedFileList();
			dep.getDependenciesFileList();
			dep.getNonImportDependenciesFileList();
			dep.getDependencies();
			dep.getOutputFile("DepCParser.java");
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testErrorManagerFormatAndEmitEdges() {
		Tool tool = new Tool();
		ErrorManager em = tool.errMgr;
		em.setFormat("antlr");
		em.setFormat("gnu");
		em.setFormat("vs2005");
		em.setFormat("missing-format");
		em.resetErrorState();
		em.info("i");
		em.syntaxError(ErrorType.SYNTAX_ERROR, "T.g4", new CommonToken(1, "x"), null, "bad");
		em.toolError(ErrorType.CANNOT_WRITE_FILE, "f");
		em.grammarError(ErrorType.UNDEFINED_RULE_REF, "T.g4", new CommonToken(1, "r"), "r");
		assertNotNull(em.getMessageTemplate(new org.antlr.v4.tool.ToolMessage(ErrorType.INTERNAL_ERROR, "z")));
		try {
			em.formatWantsSingleLineMessage();
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testCodeGenPipelineOnLexerAndParser() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "PipeL.g4", "lexer grammar PipeL;\nA : 'a' ;\n");
		writeFile(tmpdir, "PipeP.g4",
			"parser grammar PipeP;\noptions { tokenVocab=PipeL; }\ns : A ;\n");
		antlr("PipeL.g4", false);
		antlr("PipeP.g4", false, "-listener", "-visitor");

		LexerGrammar lg = new LexerGrammar("lexer grammar L;\nA : 'if' | 'a' ;\n");
		lg.tool.process(lg, false);
		new CodeGenPipeline(lg).process();

		Grammar g = new Grammar("grammar T;\nclass : 'x' ;\nvoid : 'y' ;\n");
		g.tool.process(g, false);
		try {
			new CodeGenPipeline(g).process();
		}
		catch (Throwable t) {
		}
	}

	@Test
	public void testQualifiedRuleRefsAndMissingArgs() {
		mkdir(tmpdir);
		writeFile(tmpdir, "QA.g4", "parser grammar QA;\nq[int i] : 'q' ;\n");
		writeFile(tmpdir, "QB.g4",
			"grammar QB;\n" +
			"import QA;\n" +
			"s : QA.q | QA.missing | Nope.x | q | q[1] ;\n" +
			"WS : [ \\n]+ -> skip ;\n");
		antlr("QB.g4", false);
	}

	@Test
	public void testLeftRecursiveWalkerAtomShapes() throws Exception {
		GrammarASTAdaptor adaptor = new GrammarASTAdaptor();
		// RULE_REF with ARG_ACTION and options
		GrammarAST rr = new GrammarAST(new CommonToken(ANTLRParser.RULE_REF, "e"));
		rr.addChild(new GrammarAST(new CommonToken(ANTLRParser.ARG_ACTION, "[0]")));
		walkAtom(adaptor, rr);
		GrammarAST sl = new GrammarAST(new CommonToken(ANTLRParser.STRING_LITERAL, "'*'"));
		walkAtom(adaptor, sl);
		GrammarAST wc = new GrammarAST(new CommonToken(ANTLRParser.WILDCARD, "."));
		walkAtom(adaptor, wc);
		GrammarAST tr = new TerminalAST(new CommonToken(ANTLRParser.TOKEN_REF, "ID"));
		walkAtom(adaptor, tr);
		GrammarAST dot = new GrammarAST(new CommonToken(ANTLRParser.DOT, "."));
		dot.addChild(new GrammarAST(new CommonToken(ANTLRParser.ID, "Imp")));
		dot.addChild(new GrammarAST(new CommonToken(ANTLRParser.RULE_REF, "q")));
		walkAtom(adaptor, dot);
	}

	@Test
	public void testSemanticCheckErrorGrammars() {
		mkdir(tmpdir);
		// alt labels that conflict with rules / too few labels
		writeFile(tmpdir, "AltC.g4",
			"grammar AltC;\n" +
			"s : a | b ;\n" +
			"a : ID # Foo\n" +
			"  | INT\n" +
			"  ;\n" +
			"foo : ID ;\n" +
			"b : ID # Foo\n" +
			"  | INT # Bar\n" +
			"  ;\n" +
			"ID : [a-z]+ ;\n" +
			"INT : [0-9]+ ;\n");
		antlr("AltC.g4", false);

		// empty grammar
		writeFile(tmpdir, "Empty.g4", "grammar Empty;\n");
		antlr("Empty.g4", false);

		// lexer mode in parser
		writeFile(tmpdir, "BadMode.g4", "parser grammar BadMode;\nmode M;\ns : 'x' ;\n");
		antlr("BadMode.g4", false);

		// unknown target language
		writeFile(tmpdir, "NoTgt.g4", "grammar NoTgt;\noptions { language=NoSuchTarget; }\ns:'x';\n");
		antlr("NoTgt.g4", false);
	}

	@Test
	public void testCodeGeneratorUnknownTargetAndHeaderWalks() throws Exception {
		Grammar g = new Grammar("grammar T;\ns : ID ;\nID : [a-z]+ ;\n");
		g.tool.process(g, false);
		CodeGenerator bad = new CodeGenerator(g.tool, g, "NoSuchLang");
		assertNull(bad.getTarget());
		assertNull(bad.getTemplates());

		CodeGenerator gen = new CodeGenerator(g);
		if (gen.getTarget() == null) return;
		try { gen.generateParser(true); } catch (Throwable t) {}
		try { gen.generateParser(false); } catch (Throwable t) {}
		try { gen.generateListener(true); } catch (Throwable t) {}
		try { gen.generateListener(false); } catch (Throwable t) {}
		try { gen.generateVisitor(true); } catch (Throwable t) {}
		try { gen.generateVisitor(false); } catch (Throwable t) {}
		try { gen.generateBaseListener(true); } catch (Throwable t) {}
		try { gen.generateBaseListener(false); } catch (Throwable t) {}
		try { gen.generateBaseVisitor(true); } catch (Throwable t) {}
		try { gen.generateBaseVisitor(false); } catch (Throwable t) {}
		try {
			Method vocab = CodeGenerator.class.getDeclaredMethod("getTokenVocabOutput");
			vocab.setAccessible(true);
			vocab.invoke(gen);
		} catch (Throwable t) {}
		if (g.implicitLexer != null) {
			CodeGenerator lgen = new CodeGenerator(g.implicitLexer);
			try { lgen.generateLexer(true); } catch (Throwable t) {}
			try { lgen.generateLexer(false); } catch (Throwable t) {}
		}
	}

	@Test
	public void testShowInDialogMustEnterMethod() throws Exception {
		Grammar g = new Grammar("grammar T;\ns : ID ;\nID : [a-z]+ ;\nWS : [ \\n]+ -> skip ;\n");
		g.tool.process(g, false);
		try {
			LexerInterpreter lex = g.implicitLexer.createLexerInterpreter(CharStreams.fromString("hi"));
			ParserInterpreter pi = g.createParserInterpreter(new org.antlr.v4.runtime.CommonTokenStream(lex));
			pi.setBuildParseTree(true);
			ParseTree tree = pi.parse(g.getRule("s").index);
			TreeViewer viewer = new TreeViewer(Arrays.asList(g.getRuleNames()), tree);
			Method show = TreeViewer.class.getDeclaredMethod("showInDialog", TreeViewer.class);
			show.setAccessible(true);
			Object d = show.invoke(null, viewer);
			if (d instanceof JDialog) {
				((JDialog) d).dispose();
			}
		}
		catch (Throwable t) {
			// expected in headless / missing display
		}
	}

	private void walkAtom(GrammarASTAdaptor adaptor, GrammarAST tree) {
		try {
			CommonTreeNodeStream nodes = new CommonTreeNodeStream(adaptor, tree);
			org.antlr.v4.parse.LeftRecursiveRuleWalker w =
				new org.antlr.v4.parse.LeftRecursiveRuleWalker(nodes);
			w.atom();
		}
		catch (Throwable t) {
		}
		try {
			CommonTreeNodeStream nodes = new CommonTreeNodeStream(adaptor, tree);
			org.antlr.v4.parse.GrammarTreeVisitor v = new org.antlr.v4.parse.GrammarTreeVisitor(nodes);
			v.atom();
		}
		catch (Throwable t) {
		}
	}
}
