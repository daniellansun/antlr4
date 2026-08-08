/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.v4.Tool;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.CodeGenPipeline;
import org.antlr.v4.parse.TokenVocabParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.SemanticContext;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.tool.BuildDependencyGenerator;
import org.antlr.v4.tool.DOTGenerator;
import org.antlr.v4.tool.ErrorManager;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LeftRecursiveRule;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ToolMessage;
import org.antlr.v4.tool.ast.ActionAST;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.PredAST;
import org.antlr.v4.tool.ast.RuleAST;
import org.junit.Test;
import org.stringtemplate.v4.ST;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * Coverage for Tool CLI options/help, ErrorManager, and loadGrammar paths.
 */
public class TestToolCLIAndErrorManagerCoverage extends BaseTest {

	static {
		// Keep CI/surefire free of accidental AWT init from shared JVM forks.
		System.setProperty("java.awt.headless", "true");
	}


	@Test
	public void testErrorManagerDeepPaths() {
		Tool tool = new Tool();
		ErrorManager em = tool.errMgr;
		em.info("hello");
		em.setFormat("antlr");
		em.setFormat("gnu");
		em.setFormat("vs2005");
		em.setFormat("does-not-exist-format");
		em.setFormat("antlr");

		em.syntaxError(ErrorType.SYNTAX_ERROR, "T.g4",
		new CommonToken(1, "x"), null, "bad");
		em.toolError(ErrorType.INTERNAL_ERROR, new RuntimeException("boom"), "x");
		em.grammarError(ErrorType.UNDEFINED_RULE_REF, "T.g4",
		new CommonToken(1, "r"), "r");
		em.leftRecursionCycles("T.g4", Collections.singletonList(
		Collections.singletonList(new Rule(null, "a", null, 1))));

		int before = em.getNumErrors();
		em.emit(ErrorType.ERROR_READING_IMPORTED_GRAMMAR,
		new ToolMessage(ErrorType.ERROR_READING_IMPORTED_GRAMMAR, "x"));
		em.emit(ErrorType.ERROR_READING_IMPORTED_GRAMMAR,
		new ToolMessage(ErrorType.ERROR_READING_IMPORTED_GRAMMAR, "x"));

		ErrorManager.internalError("test internal");
		ErrorManager.internalError("test with ex", new RuntimeException("x"));
		try {
			ErrorManager.fatalInternalError("fatal", new RuntimeException("x"));
			fail("expected RuntimeException");
		} catch (RuntimeException expected) {
			// expected
		}

		assertTrue(em.getNumErrors() >= before);
		assertNotNull(em.getMessageTemplate(new ToolMessage(ErrorType.INTERNAL_ERROR, "z")));
	}



	@Test
	public void testToolOptionSetAndInvalidArgs() throws Exception {
		mkdir(tmpdir);
		File asFile = new File(tmpdir, "notadir");
		asFile.createNewFile();
		Tool tool = new Tool(new String[]{
			"-Dlanguage=Java",
			"-DsuperClass=Base",
			"-DbogusOption=1",
			"-D=",
			"-DnoEquals",
			"-unknownFlag",
			"-o", tmpdir + "/",
			"-lib", tmpdir + "/",
			"-message-format", "antlr",
			"-Xlog",
			"DoesNotExist.g4"
		});
		assertNotNull(tool.grammarOptions);
		assertEquals("Java", tool.grammarOptions.get("language"));
		assertTrue(tool.errMgr.getNumErrors() > 0);

		Tool tool2 = new Tool(new String[]{"-o", asFile.getAbsolutePath()});
		assertEquals(".", tool2.outputDirectory);

		Tool tool3 = new Tool(new String[]{"-lib", "/path/that/does/not/exist/antlr4_test"});
		assertEquals(".", tool3.libDirectory);
	}



	@Test
	public void testToolHelpAndGenerateATNs() throws Exception {
		Tool tool = new Tool();
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		PrintStream oldOut = System.out;
		System.setErr(new PrintStream(bout));
		System.setOut(new PrintStream(bout));
		try {
			tool.help();
		} finally {
			System.setErr(oldErr);
			System.setOut(oldOut);
		}

		mkdir(tmpdir);
		writeFile(tmpdir, "T.g4", "grammar T;\n a : 'x' | 'y' ;\n");
		ErrorQueue equeue = antlr("T.g4", false, "-atn");
		assertNotNull(equeue);
	}



	@Test
	public void testLoadGrammar() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "Hello.g4",
		"grammar Hello;\n" +
		"r : 'hello' ID ;\n" +
		"ID : [a-z]+ ;\n" +
		"WS : [ \\n]+ -> skip ;\n");
		Tool tool = new Tool(new String[]{"-o", tmpdir, "-lib", tmpdir});
		Grammar g = tool.loadGrammar(new File(tmpdir, "Hello.g4").getAbsolutePath());
		assertNotNull(g);
		assertEquals("Hello", g.name);
	}

}
