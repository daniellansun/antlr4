/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.Token;
import org.antlr.v4.Tool;
import org.antlr.v4.parse.ActionSplitter;
import org.antlr.v4.parse.ActionSplitterListener;
import org.antlr.v4.parse.GrammarASTAdaptor;
import org.antlr.v4.parse.TokenVocabParser;
import org.antlr.v4.parse.ToolANTLRLexer;
import org.antlr.v4.parse.v4ParserException;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.ast.GrammarAST;

import org.junit.Test;

import java.io.File;
import java.io.PrintWriter;
import java.util.Map;

import static org.junit.Assert.*;

public class TestParsePackageCoverage {

	@Test
	public void testTokenVocabParser() throws Exception {
		File vocabFile = File.createTempFile("TestVocab", ".tokens");
		vocabFile.deleteOnExit();
		try (PrintWriter pw = new PrintWriter(vocabFile)) {
			pw.println("A=1");
			pw.println("'b'=2");
			pw.println("C=3");
		}

		String vocabName = vocabFile.getName().substring(0, vocabFile.getName().lastIndexOf('.'));
		Grammar g = new Grammar("grammar T;\noptions { tokenVocab=" + vocabName + "; }\na : A;\n");
		g.tool.libDirectory = vocabFile.getParent();

		TokenVocabParser parser = new TokenVocabParser(g);
		Map<String, Integer> vocab = parser.load();
		assertNotNull(vocab);
		assertEquals(Integer.valueOf(1), vocab.get("A"));
	}

	@Test
	public void testToolANTLRLexer() {
		ANTLRStringStream input = new ANTLRStringStream("grammar T;");
		ToolANTLRLexer lexer = new ToolANTLRLexer(input, new Tool());
		assertNotNull(lexer);
	}

	@Test
	public void testActionSplitter() throws Exception {
		ActionSplitterListener dummyListener = new ActionSplitterListener() {
			@Override public void qualifiedAttr(String expr, Token x, Token y) {}
			@Override public void setAttr(String expr, Token x, Token rhs) {}
			@Override public void attr(String expr, Token x) {}
			@Override public void setNonLocalAttr(String expr, Token x, Token y, Token rhs) {}
			@Override public void nonLocalAttr(String expr, Token x, Token y) {}
			@Override public void text(String text) {}
		};

		ANTLRStringStream input = new ANTLRStringStream("$x.y = $z;");
		ActionSplitter splitter = new ActionSplitter(input, dummyListener);
		splitter.getActionTokens();
	}

	@Test
	public void testV4ParserException() {
		v4ParserException ex = new v4ParserException("msg", new ANTLRStringStream("x"));
		assertEquals("msg", ex.msg);
	}

	@Test
	public void testGrammarASTAdaptor() {
		GrammarASTAdaptor adaptor = new GrammarASTAdaptor();
		Token tok = new CommonToken(1, "TEST");
		GrammarAST node = (GrammarAST) adaptor.create(tok);
		assertNotNull(node);
		assertEquals("TEST", node.getText());

		GrammarAST dupNode = (GrammarAST) adaptor.dupNode(node);
		assertNotNull(dupNode);
		assertEquals("TEST", dupNode.getText());
	}
}
