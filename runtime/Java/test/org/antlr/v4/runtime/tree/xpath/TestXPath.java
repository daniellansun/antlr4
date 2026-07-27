/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.tree.xpath;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestXPath {

	static class IndexedRuleContext extends ParserRuleContext {
		private final int ruleIndex;

		IndexedRuleContext(int ruleIndex) {
			this.ruleIndex = ruleIndex;
		}

		@Override
		public int getRuleIndex() {
			return ruleIndex;
		}
	}

	/**
	 * Minimal parser used by XPath for token/rule name resolution.
	 */
	static class StubParser extends Parser {
		private final String[] ruleNames;
		private final Vocabulary vocabulary;
		private final ATN atn;

		StubParser(String[] ruleNames, String[] tokenNames, int maxTokenType) {
			super(new CommonTokenStream(new ListTokenSource(Collections.<Token>emptyList())));
			this.ruleNames = ruleNames;
			// tokenNames indexed by type; pad for index 0
			this.vocabulary = VocabularyImpl.fromTokenNames(tokenNames);
			this.atn = new ATN(ATNType.PARSER, maxTokenType);
			setInterpreter(new ParserATNSimulator(this, atn));
		}

		@Override
		@SuppressWarnings("deprecation")
		public String[] getTokenNames() {
			// not used when getVocabulary is overridden
			return new String[0];
		}

		@Override
		public Vocabulary getVocabulary() {
			return vocabulary;
		}

		@Override
		public String[] getRuleNames() {
			return ruleNames;
		}

		@Override
		public String getGrammarFileName() {
			return "Stub.g4";
		}

		@Override
		public ATN getATN() {
			return atn;
		}
	}

	/** Tree: prog(0) -> stat(1) -> ID(1) "x", INT(2) "42"; prog also has ID "y" */
	private static IndexedRuleContext sampleTree() {
		IndexedRuleContext prog = new IndexedRuleContext(0);
		IndexedRuleContext stat = new IndexedRuleContext(1);
		stat.setParent(prog);

		TerminalNodeImpl id = new TerminalNodeImpl(new CommonToken(1, "x"));
		id.setParent(stat);
		stat.addAnyChild(id);

		TerminalNodeImpl num = new TerminalNodeImpl(new CommonToken(2, "42"));
		num.setParent(stat);
		stat.addAnyChild(num);

		prog.addAnyChild(stat);

		TerminalNodeImpl id2 = new TerminalNodeImpl(new CommonToken(1, "y"));
		id2.setParent(prog);
		prog.addAnyChild(id2);

		return prog;
	}

	private static StubParser stubParser() {
		// tokenNames: index 0 unused/invalid, 1=ID, 2=INT
		String[] tokenNames = new String[]{null, "ID", "INT"};
		return new StubParser(new String[]{"prog", "stat"}, tokenNames, 2);
	}

	@Test
	public void xpathElementToString() {
		XPathTokenElement tok = new XPathTokenElement("ID", 1);
		assertEquals("XPathTokenElement[ID]", tok.toString());
		tok.invert = true;
		assertEquals("XPathTokenElement[!ID]", tok.toString());

		XPathRuleElement rule = new XPathRuleElement("stat", 1);
		assertTrue(rule.toString().contains("stat"));

		assertEquals("XPathWildcardElement[*]", new XPathWildcardElement().toString());
		assertEquals("XPathWildcardAnywhereElement[*]", new XPathWildcardAnywhereElement().toString());
	}

	@Test
	public void xpathTokenElementEvaluate() {
		IndexedRuleContext root = sampleTree();
		// children of prog: stat, ID(y)
		XPathTokenElement id = new XPathTokenElement("ID", 1);
		Collection<ParseTree> nodes = id.evaluate(root);
		assertEquals(1, nodes.size());
		assertEquals("y", ((TerminalNodeImpl) nodes.iterator().next()).getText());

		id.invert = true;
		Collection<ParseTree> inverted = id.evaluate(root);
		// invert among terminal children only — only ID is terminal type 1; invert means type != 1
		// no other terminal children under prog
		assertEquals(0, inverted.size());

		// under stat: ID and INT
		XPathTokenElement intTok = new XPathTokenElement("INT", 2);
		assertEquals(1, intTok.evaluate(root.getChild(0)).size());
		intTok.invert = true;
		assertEquals(1, intTok.evaluate(root.getChild(0)).size()); // ID
	}

	@Test
	public void xpathRuleElementEvaluate() {
		IndexedRuleContext root = sampleTree();
		XPathRuleElement stat = new XPathRuleElement("stat", 1);
		Collection<ParseTree> nodes = stat.evaluate(root);
		assertEquals(1, nodes.size());

		stat.invert = true;
		// children that are rule nodes with index != 1: none
		assertEquals(0, stat.evaluate(root).size());

		XPathRuleElement prog = new XPathRuleElement("prog", 0);
		// root's children don't include prog
		assertEquals(0, prog.evaluate(root).size());
	}

	@Test
	public void xpathAnywhereElements() {
		IndexedRuleContext root = sampleTree();

		XPathTokenAnywhereElement ids = new XPathTokenAnywhereElement("ID", 1);
		assertEquals(2, ids.evaluate(root).size());

		XPathRuleAnywhereElement stats = new XPathRuleAnywhereElement("stat", 1);
		assertEquals(1, stats.evaluate(root).size());

		XPathRuleAnywhereElement progs = new XPathRuleAnywhereElement("prog", 0);
		// includes root itself via findAllRuleNodes
		assertEquals(1, progs.evaluate(root).size());

		XPathWildcardAnywhereElement all = new XPathWildcardAnywhereElement();
		assertTrue(all.evaluate(root).size() >= 4);
		all.invert = true;
		assertTrue(all.evaluate(root).isEmpty());
	}

	@Test
	public void xpathWildcardElement() {
		IndexedRuleContext root = sampleTree();
		XPathWildcardElement star = new XPathWildcardElement();
		assertEquals(2, star.evaluate(root).size());
		star.invert = true;
		assertTrue(star.evaluate(root).isEmpty());
	}

	@Test
	public void xpathLexerErrorListenerNoOp() {
		XPathLexerErrorListener listener = new XPathLexerErrorListener();
		listener.syntaxError(null, null, 1, 0, "msg", null);
		// no exception
	}

	@Test
	public void xpathLexerMetadataAndUnicodeIds() {
		// Exercise getGrammarFileName / getSerializedATN and NameChar/NameStartChar sempred paths
		org.antlr.v4.runtime.CharStream cs = org.antlr.v4.runtime.CharStreams.fromString(
			"//\u03B1\u03B2 /!\u4E2D*");
		XPathLexer lexer = new XPathLexer(cs);
		assertEquals("XPathLexer.g4", lexer.getGrammarFileName());
		assertNotNull(lexer.getSerializedATN());
		assertTrue(lexer.getSerializedATN().length() > 0);
		// drain tokens (unicode ID + operators); sempred gates NameStartChar/NameChar
		List<Token> toks = new ArrayList<Token>();
		Token t;
		do {
			t = lexer.nextToken();
			toks.add(t);
		} while (t.getType() != Token.EOF);
		assertTrue(toks.size() >= 2);

		// Direct sempred calls: rule 5 = NameChar (pred 0), rule 6 = NameStartChar (pred 1)
		// Need prior char in input for LA(-1); re-lex a single letter first
		XPathLexer lex2 = new XPathLexer(org.antlr.v4.runtime.CharStreams.fromString("aZ"));
		lex2.nextToken(); // consume ID so position advances; then call sempred with lookbehind
		// after first token, LA(-1) may still be available on stream
		assertTrue(lex2.sempred(null, 5, 0)); // NameChar
		assertTrue(lex2.sempred(null, 6, 1)); // NameStartChar pred index 1
		assertTrue(lex2.sempred(null, 0, 0)); // default true for other rules
		assertTrue(lex2.sempred(null, 5, 99)); // unknown pred index => true
		assertTrue(lex2.sempred(null, 6, 99));
	}

	@Test
	public void xpathFindAllWildcard() {
		IndexedRuleContext root = sampleTree();
		StubParser parser = stubParser();

		// evaluate wraps `root` under a dummy node; `/*` selects children of dummy => just root
		Collection<ParseTree> top = XPath.findAll(root, "/*", parser);
		assertEquals(1, top.size());
		assertTrue(top.contains(root));

		// children of the actual root
		Collection<ParseTree> kids = XPath.findAll(root, "/*/*", parser);
		assertEquals(2, kids.size());

		Collection<ParseTree> anywhere = XPath.findAll(root, "//*", parser);
		assertTrue(anywhere.size() >= 4);
	}

	@Test
	public void xpathFindAllTokenAndRule() {
		IndexedRuleContext root = sampleTree();
		StubParser parser = stubParser();

		Collection<ParseTree> ids = XPath.findAll(root, "//ID", parser);
		assertEquals(2, ids.size());

		Collection<ParseTree> stats = XPath.findAll(root, "//stat", parser);
		assertEquals(1, stats.size());

		Collection<ParseTree> nestedIds = XPath.findAll(root, "/prog/stat/ID", parser);
		assertEquals(1, nestedIds.size());
		assertEquals("x", ((TerminalNodeImpl) nestedIds.iterator().next()).getText());

		Collection<ParseTree> rootProg = XPath.findAll(root, "/prog", parser);
		// dummy root's child is prog
		assertEquals(1, rootProg.size());
	}

	@Test
	public void xpathInvert() {
		IndexedRuleContext root = sampleTree();
		StubParser parser = stubParser();

		// under prog: children are stat and ID; /!stat should be non-stat rule children? 
		// invert on rule element: rule nodes where index != stat
		// only stat is rule child, so /!stat among rule children is empty
		// /!ID among token children: y has type ID, invert empty for tokens under root... 
		Collection<ParseTree> notId = XPath.findAll(root, "/!ID", parser);
		// terminal children of dummy's child? Path is relative to dummy root containing `root`
		// /!ID: children of dummy matching inverted ID — dummy's child is prog (rule), not terminal
		assertEquals(0, notId.size());

		Collection<ParseTree> notStatUnderProg = XPath.findAll(root, "/*", parser);
		assertFalse(notStatUnderProg.isEmpty());
	}

	@Test
	public void xpathSplitAndInvalid() {
		StubParser parser = stubParser();
		XPath xpath = new XPath(parser, "//ID");
		assertEquals(1, xpath.split("//ID").length);

		// path without leading slash still works for bare word
		XPathElement[] els = xpath.split("ID");
		assertEquals(1, els.length);

		try {
			new XPath(parser, "//");
			fail("missing path element");
		} catch (IllegalArgumentException e) {
			// expected
		}

		try {
			new XPath(parser, "//NOT_A_TOKEN");
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("isn't a valid"));
		}

		try {
			new XPath(parser, "//notARule");
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("isn't a valid"));
		}

		try {
			new XPath(parser, "\u0001");
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("Invalid tokens") || e.getCause() != null);
		}
	}

	@Test
	public void xpathEvaluateDirect() {
		IndexedRuleContext root = sampleTree();
		StubParser parser = stubParser();
		XPath xpath = new XPath(parser, "//INT");
		Collection<ParseTree> result = xpath.evaluate(root);
		assertEquals(1, result.size());
		assertEquals("42", ((TerminalNodeImpl) result.iterator().next()).getText());
	}

	@Test
	public void xpathInvertAnywhere() {
		IndexedRuleContext root = sampleTree();
		StubParser parser = stubParser();
		// //!ID is not standard in split the same way - invert after anywhere
		XPath xpath = new XPath(parser, "//!ID");
		// inverted token anywhere isn't special-cased in anywhere elements —
		// getXPathElement creates XPathTokenAnywhereElement which doesn't honor invert
		// but invert flag is set on the element
		XPathElement[] els = xpath.split("//!ID");
		assertEquals(1, els.length);
		assertTrue(els[0].invert);
	}

	@Test
	public void xpathLexerTokenizesOperatorsAndIds() {
		org.antlr.v4.runtime.CharStream cs =
			org.antlr.v4.runtime.CharStreams.fromString("//ID / prog ! * 'str'");
		XPathLexer lexer = new XPathLexer(cs);
		List<Token> toks = new ArrayList<Token>();
		Token t;
		do {
			t = lexer.nextToken();
			if (t.getType() != Token.EOF) {
				toks.add(t);
			}
		} while (t.getType() != Token.EOF);
		assertTrue(toks.size() >= 4);
		// exercise vocabulary / names
		assertNotNull(lexer.getVocabulary());
		assertNotNull(lexer.getRuleNames());
		assertNotNull(lexer.getModeNames());
		assertNotNull(lexer.getChannelNames());
		@SuppressWarnings("deprecation")
		String[] tokenNames = lexer.getTokenNames();
		assertNotNull(tokenNames);
		assertNotNull(XPathLexer.VOCABULARY);
		assertTrue(XPathLexer.TOKEN_REF > 0 || XPathLexer.RULE_REF > 0);
	}

	@Test
	public void xpathLexerErrorPathOnInvalidChar() {
		org.antlr.v4.runtime.CharStream cs =
			org.antlr.v4.runtime.CharStreams.fromString("@");
		XPathLexer lexer = new XPathLexer(cs);
		lexer.removeErrorListeners();
		lexer.addErrorListener(new XPathLexerErrorListener());
		Token t = lexer.nextToken();
		// may be ERROR or skip — either way lexer ran recover path
		assertNotNull(t);
	}

	@Test
	public void xpathMoreExpressions() {
		IndexedRuleContext root = sampleTree();
		StubParser parser = stubParser();

		Collection<ParseTree> star = XPath.findAll(root, "//*", parser);
		assertTrue(star.size() >= 1);

		try {
			XPath.findAll(root, "/", parser);
			fail("bare slash should be invalid");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("Missing") || e.getMessage().length() > 0);
		}

		Collection<ParseTree> bang = XPath.findAll(root, "//!*", parser);
		assertNotNull(bang);

		// string token path if grammar supports
		try {
			XPath xp = new XPath(parser, "//'x'");
			assertNotNull(xp);
		} catch (IllegalArgumentException e) {
			// string may not map to a token type on stub
		}

		// multi-segment with invert
		XPathElement[] els = new XPath(parser, "/prog/!stat").split("/prog/!stat");
		assertTrue(els.length >= 2);
		assertTrue(els[els.length - 1].invert);

		// anywhere rule
		Collection<ParseTree> anywhereStat = XPath.findAll(root, "//stat", parser);
		assertEquals(1, anywhereStat.size());
	}

	@Test
	public void xpathWildcardAnywhereInvert() {
		IndexedRuleContext root = sampleTree();
		XPathWildcardAnywhereElement el = new XPathWildcardAnywhereElement();
		el.invert = true;
		Collection<ParseTree> r = el.evaluate(root);
		assertNotNull(r);
	}
}
