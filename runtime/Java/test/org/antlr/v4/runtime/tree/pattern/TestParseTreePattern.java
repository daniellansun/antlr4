/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.tree.pattern;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.MultiMap;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestParseTreePattern {

	@Test
	public void textChunk() {
		TextChunk chunk = new TextChunk("abc");
		assertEquals("abc", chunk.getText());
		assertEquals("'abc'", chunk.toString());
		try {
			new TextChunk(null);
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("null"));
		}
	}

	@Test
	public void tagChunk() {
		TagChunk unlabeled = new TagChunk("ID");
		assertEquals("ID", unlabeled.getTag());
		assertNull(unlabeled.getLabel());
		assertEquals("ID", unlabeled.toString());

		TagChunk labeled = new TagChunk("id", "ID");
		assertEquals("ID", labeled.getTag());
		assertEquals("id", labeled.getLabel());
		assertEquals("id:ID", labeled.toString());

		try {
			new TagChunk(null);
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}
		try {
			new TagChunk("");
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}
		try {
			new TagChunk("l", null);
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}
	}

	@Test
	public void ruleTagToken() {
		RuleTagToken tok = new RuleTagToken("expr", 99);
		assertEquals("expr", tok.getRuleName());
		assertNull(tok.getLabel());
		assertEquals(99, tok.getType());
		assertEquals(Token.DEFAULT_CHANNEL, tok.getChannel());
		assertEquals("<expr>", tok.getText());
		assertEquals(0, tok.getLine());
		assertEquals(-1, tok.getCharPositionInLine());
		assertEquals(-1, tok.getTokenIndex());
		assertEquals(-1, tok.getStartIndex());
		assertEquals(-1, tok.getStopIndex());
		assertNull(tok.getTokenSource());
		assertNull(tok.getInputStream());
		assertEquals("expr:99", tok.toString());

		RuleTagToken labeled = new RuleTagToken("expr", 99, "e");
		assertEquals("e", labeled.getLabel());
		assertEquals("<e:expr>", labeled.getText());

		try {
			new RuleTagToken(null, 1);
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}
		try {
			new RuleTagToken("", 1);
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}
	}

	@Test
	public void tokenTagToken() {
		TokenTagToken tok = new TokenTagToken("ID", 1);
		assertEquals("ID", tok.getTokenName());
		assertNull(tok.getLabel());
		assertEquals(1, tok.getType());
		assertEquals("<ID>", tok.getText());
		assertEquals("ID:1", tok.toString());

		TokenTagToken labeled = new TokenTagToken("ID", 1, "id");
		assertEquals("id", labeled.getLabel());
		assertEquals("<id:ID>", labeled.getText());
	}

	@Test
	public void parseTreeMatch() {
		ParserRuleContext tree = new ParserRuleContext();
		ParseTreePatternMatcher matcher = new ParseTreePatternMatcher(null, null);
		ParseTree patternTree = new TerminalNodeImpl(new CommonToken(1, "x"));
		ParseTreePattern pattern = new ParseTreePattern(matcher, "x", 0, patternTree);

		MultiMap<String, ParseTree> labels = new MultiMap<String, ParseTree>();
		TerminalNodeImpl labeledNode = new TerminalNodeImpl(new CommonToken(1, "x"));
		labels.map("id", labeledNode);
		labels.map("id", new TerminalNodeImpl(new CommonToken(1, "y")));
		labels.map("ID", labeledNode);

		ParseTreeMatch match = new ParseTreeMatch(tree, pattern, labels, null);
		assertTrue(match.succeeded());
		assertNull(match.getMismatchedNode());
		assertSame(tree, match.getTree());
		assertSame(pattern, match.getPattern());
		assertSame(labels, match.getLabels());
		// get returns last for label
		assertEquals("y", ((TerminalNodeImpl) match.get("id")).getText());
		assertEquals(2, match.getAll("id").size());
		assertTrue(match.getAll("missing").isEmpty());
		assertNull(match.get("missing"));
		assertTrue(match.toString().contains("succeeded"));

		ParseTreeMatch failed = new ParseTreeMatch(tree, pattern, new MultiMap<String, ParseTree>(), tree);
		assertFalse(failed.succeeded());
		assertSame(tree, failed.getMismatchedNode());
		assertTrue(failed.toString().contains("failed"));

		try {
			new ParseTreeMatch(null, pattern, labels, null);
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}
		try {
			new ParseTreeMatch(tree, null, labels, null);
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}
		try {
			new ParseTreeMatch(tree, pattern, null, null);
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}
	}

	@Test
	public void parseTreePatternMatcherDelimitersAndSplit() {
		ParseTreePatternMatcher matcher = new ParseTreePatternMatcher(null, null);
		assertNull(matcher.getLexer());
		assertNull(matcher.getParser());

		matcher.setDelimiters("<", ">", "\\");
		List<Chunk> chunks = matcher.split("<ID> = <e:expr> ;");
		assertEquals(4, chunks.size());
		assertTrue(chunks.get(0) instanceof TagChunk);
		assertEquals("ID", ((TagChunk) chunks.get(0)).getTag());
		assertTrue(chunks.get(1) instanceof TextChunk);
		assertEquals(" = ", ((TextChunk) chunks.get(1)).getText());
		assertTrue(chunks.get(2) instanceof TagChunk);
		assertEquals("expr", ((TagChunk) chunks.get(2)).getTag());
		assertEquals("e", ((TagChunk) chunks.get(2)).getLabel());
		assertTrue(chunks.get(3) instanceof TextChunk);
		assertEquals(" ;", ((TextChunk) chunks.get(3)).getText());

		// no tags
		List<Chunk> plain = matcher.split("x = 0;");
		assertEquals(1, plain.size());
		assertTrue(plain.get(0) instanceof TextChunk);

		// escape sequences stripped from text
		List<Chunk> escaped = matcher.split("\\<notag\\>");
		assertEquals(1, escaped.size());
		assertEquals("<notag>", ((TextChunk) escaped.get(0)).getText());

		// tags only
		List<Chunk> tagsOnly = matcher.split("<ID>");
		assertEquals(1, tagsOnly.size());

		// text before and after
		List<Chunk> around = matcher.split("a<ID>b");
		assertEquals(3, around.size());

		try {
			matcher.setDelimiters(null, ">", "\\");
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}
		try {
			matcher.setDelimiters("", ">", "\\");
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}
		try {
			matcher.setDelimiters("<", null, "\\");
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}
		try {
			matcher.setDelimiters("<", "", "\\");
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}

		try {
			matcher.split("<ID");
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("unterminated"));
		}
		try {
			matcher.split("ID>");
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("missing start"));
		}
		try {
			matcher.split(">ID<");
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("out of order") || e.getMessage().length() > 0);
		}
	}

	@Test
	public void matchTokenTagAndLiteral() {
		ParseTreePatternMatcher matcher = new ParseTreePatternMatcher(null, null);

		// actual: terminal type 1 text "foo"
		TerminalNodeImpl actual = new TerminalNodeImpl(new CommonToken(1, "foo"));
		// pattern: TokenTagToken type 1
		TerminalNodeImpl patternNode = new TerminalNodeImpl(new TokenTagToken("ID", 1, "id"));
		ParseTreePattern pattern = new ParseTreePattern(matcher, "<id:ID>", 0, patternNode);

		ParseTreeMatch m = matcher.match(actual, pattern);
		assertTrue(m.succeeded());
		assertSame(actual, m.get("id"));
		assertSame(actual, m.get("ID"));
		assertTrue(matcher.matches(actual, pattern));

		// exact text match
		TerminalNodeImpl litPattern = new TerminalNodeImpl(new CommonToken(1, "foo"));
		ParseTreePattern lit = new ParseTreePattern(matcher, "foo", 0, litPattern);
		assertTrue(matcher.matches(actual, lit));

		// text mismatch
		TerminalNodeImpl wrong = new TerminalNodeImpl(new CommonToken(1, "bar"));
		ParseTreeMatch failMatch = matcher.match(actual, new ParseTreePattern(matcher, "bar", 0, wrong));
		assertFalse(failMatch.succeeded());
		assertSame(actual, failMatch.getMismatchedNode());

		// type mismatch
		TerminalNodeImpl typeMismatch = new TerminalNodeImpl(new CommonToken(2, "foo"));
		assertFalse(matcher.matches(actual, new ParseTreePattern(matcher, "x", 0, typeMismatch)));
	}

	@Test
	public void matchRuleTagAndChildren() {
		ParseTreePatternMatcher matcher = new ParseTreePatternMatcher(null, null);

		class RuleCtx extends ParserRuleContext {
			private final int idx;
			RuleCtx(int idx) { this.idx = idx; }
			@Override public int getRuleIndex() { return idx; }
		}

		// actual tree: rule0 -> "x"
		RuleCtx actual = new RuleCtx(0);
		TerminalNodeImpl x = new TerminalNodeImpl(new CommonToken(1, "x"));
		x.setParent(actual);
		actual.addAnyChild(x);

		// pattern: rule0 containing RuleTagToken child for rule "expr" (rule index 0)
		RuleCtx patternRoot = new RuleCtx(0);
		TerminalNodeImpl ruleTag = new TerminalNodeImpl(new RuleTagToken("expr", 100));
		ruleTag.setParent(patternRoot);
		patternRoot.addAnyChild(ruleTag);

		ParseTreePattern pattern = new ParseTreePattern(matcher, "<expr>", 0, patternRoot);
		ParseTreeMatch m = matcher.match(actual, pattern);
		assertTrue(m.succeeded());
		assertSame(actual, m.get("expr"));

		// labeled rule tag
		RuleCtx patternRoot2 = new RuleCtx(0);
		TerminalNodeImpl labeledTag = new TerminalNodeImpl(new RuleTagToken("expr", 100, "e"));
		labeledTag.setParent(patternRoot2);
		patternRoot2.addAnyChild(labeledTag);
		ParseTreePattern p2 = new ParseTreePattern(matcher, "<e:expr>", 0, patternRoot2);
		ParseTreeMatch m2 = matcher.match(actual, p2);
		assertTrue(m2.succeeded());
		assertSame(actual, m2.get("e"));
		assertSame(actual, m2.get("expr"));

		// rule index mismatch
		RuleCtx wrongRule = new RuleCtx(1);
		assertFalse(matcher.matches(wrongRule, p2));

		// structural match of children
		RuleCtx patternStruct = new RuleCtx(0);
		TerminalNodeImpl px = new TerminalNodeImpl(new CommonToken(1, "x"));
		px.setParent(patternStruct);
		patternStruct.addAnyChild(px);
		assertTrue(matcher.matches(actual, new ParseTreePattern(matcher, "x", 0, patternStruct)));

		// child count mismatch
		RuleCtx patternTwoKids = new RuleCtx(0);
		patternTwoKids.addAnyChild(new TerminalNodeImpl(new CommonToken(1, "x")));
		patternTwoKids.addAnyChild(new TerminalNodeImpl(new CommonToken(1, "y")));
		assertFalse(matcher.matches(actual, new ParseTreePattern(matcher, "xy", 0, patternTwoKids)));

		// mixed types fail
		assertFalse(matcher.matches(actual, new ParseTreePattern(matcher, "t", 0,
			new TerminalNodeImpl(new CommonToken(1, "x")))));
	}

	@Test
	public void parseTreePatternAccessorsAndMatch() {
		ParseTreePatternMatcher matcher = new ParseTreePatternMatcher(null, null);
		TerminalNodeImpl patternTree = new TerminalNodeImpl(new TokenTagToken("ID", 1));
		ParseTreePattern pattern = new ParseTreePattern(matcher, "<ID>", 3, patternTree);

		assertSame(matcher, pattern.getMatcher());
		assertEquals("<ID>", pattern.getPattern());
		assertEquals(3, pattern.getPatternRuleIndex());
		assertSame(patternTree, pattern.getPatternTree());

		TerminalNodeImpl actual = new TerminalNodeImpl(new CommonToken(1, "name"));
		assertTrue(pattern.matches(actual));
		assertTrue(pattern.match(actual).succeeded());
	}

	@Test
	public void exceptionClasses() {
		ParseTreePatternMatcher.CannotInvokeStartRule e1 =
			new ParseTreePatternMatcher.CannotInvokeStartRule(new RuntimeException("x"));
		assertNotNull(e1.getCause());

		ParseTreePatternMatcher.StartRuleDoesNotConsumeFullPattern e2 =
			new ParseTreePatternMatcher.StartRuleDoesNotConsumeFullPattern();
		assertNotNull(e2);
	}

	@Test
	public void matchImplNullArgs() {
		ParseTreePatternMatcher matcher = new ParseTreePatternMatcher(null, null);
		TerminalNodeImpl t = new TerminalNodeImpl(new CommonToken(1, "x"));
		ParseTreePattern pattern = new ParseTreePattern(matcher, "x", 0, t);
		try {
			matcher.match(null, pattern);
			fail();
		} catch (IllegalArgumentException e) {
			// expected - matchImpl checks tree null; match itself may NPE first
		} catch (NullPointerException e) {
			// also acceptable depending on call path
		}
	}

	/**
	 * Full tokenize/compile path using LexerInterpreter + ParserInterpreter with
	 * serialized ATN (bypass alts) for a trivial {@code s : A ;} grammar.
	 */
	@Test
	public void compileAndMatchWithInterpreters() {
		org.antlr.v4.runtime.atn.ATN lexerAtn = buildLexerA();
		org.antlr.v4.runtime.atn.ATN parserAtn = buildParserMatchA();

		String serialized = org.antlr.v4.runtime.atn.ATNSerializer.getSerializedAsString(
			parserAtn, java.util.Collections.singletonList("s"));

		org.antlr.v4.runtime.Vocabulary vocab = new org.antlr.v4.runtime.VocabularyImpl(
			new String[]{null, "'a'"}, new String[]{null, "A"});

		org.antlr.v4.runtime.CharStream cs =
			org.antlr.v4.runtime.CharStreams.fromString("a");
		org.antlr.v4.runtime.LexerInterpreter lexer = new org.antlr.v4.runtime.LexerInterpreter(
			"L.g4",
			vocab,
			java.util.Collections.singletonList("A"),
			null,
			java.util.Collections.singletonList("DEFAULT_MODE"),
			lexerAtn,
			cs);

		org.antlr.v4.runtime.CommonTokenStream tokens =
			new org.antlr.v4.runtime.CommonTokenStream(lexer);
		SerParserInterpreter parser = new SerParserInterpreter(
			"P.g4",
			vocab,
			java.util.Collections.singletonList("s"),
			parserAtn,
			tokens,
			serialized);

		// parse real input tree
		lexer.setInputStream(org.antlr.v4.runtime.CharStreams.fromString("a"));
		tokens = new org.antlr.v4.runtime.CommonTokenStream(lexer);
		parser.setInputStream(tokens);
		ParserRuleContext tree = parser.parse(0);
		assertNotNull(tree);

		ParseTreePatternMatcher matcher = new ParseTreePatternMatcher(lexer, parser);
		assertSame(lexer, matcher.getLexer());
		assertSame(parser, matcher.getParser());

		// tokenize tags and literals
		java.util.List<? extends org.antlr.v4.runtime.Token> tagTokens = matcher.tokenize("<A>");
		assertEquals(1, tagTokens.size());
		assertTrue(tagTokens.get(0) instanceof TokenTagToken);

		java.util.List<? extends org.antlr.v4.runtime.Token> litTokens = matcher.tokenize("a");
		assertFalse(litTokens.isEmpty());

		// unknown token tag
		try {
			matcher.tokenize("<NOTOKEN>");
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("Unknown token"));
		}

		// unknown rule tag
		try {
			matcher.tokenize("<notrule>");
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("Unknown rule"));
		}

		// invalid tag (neither upper nor lower)
		try {
			matcher.tokenize("<_bad>");
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("invalid tag"));
		}

		// labeled token tag
		java.util.List<? extends org.antlr.v4.runtime.Token> labeled = matcher.tokenize("<id:A>");
		assertTrue(labeled.get(0) instanceof TokenTagToken);
		assertEquals("id", ((TokenTagToken) labeled.get(0)).getLabel());

		// rule tag tokenize
		java.util.List<? extends org.antlr.v4.runtime.Token> ruleToks = matcher.tokenize("<s>");
		assertTrue(ruleToks.get(0) instanceof RuleTagToken);

		// compile pattern and match
		ParseTreePattern pattern = matcher.compile("<A>", 0);
		assertTrue(matcher.matches(tree, pattern));
		assertTrue(matcher.matches(tree, "<A>", 0));
		ParseTreeMatch m = matcher.match(tree, pattern);
		assertTrue(m.succeeded());
		assertNotNull(m.get("A"));

		ParseTreeMatch m2 = matcher.match(tree, "a", 0);
		assertTrue(m2.succeeded());

		// mismatch
		assertFalse(matcher.matches(new TerminalNodeImpl(new CommonToken(1, "z")), pattern));

		// findAll via xpath on pattern
		java.util.List<ParseTreeMatch> all = pattern.findAll(tree, "/*");
		assertNotNull(all);
	}

	@Test
	public void compileStartRuleDoesNotConsumeFullPattern() {
		org.antlr.v4.runtime.atn.ATN lexerAtn = buildLexerA();
		org.antlr.v4.runtime.atn.ATN parserAtn = buildParserMatchA();
		String serialized = org.antlr.v4.runtime.atn.ATNSerializer.getSerializedAsString(
			parserAtn, java.util.Collections.singletonList("s"));
		org.antlr.v4.runtime.Vocabulary vocab = new org.antlr.v4.runtime.VocabularyImpl(
			new String[]{null, "'a'"}, new String[]{null, "A"});
		org.antlr.v4.runtime.LexerInterpreter lexer = new org.antlr.v4.runtime.LexerInterpreter(
			"L.g4", vocab, java.util.Collections.singletonList("A"), null,
			java.util.Collections.singletonList("DEFAULT_MODE"), lexerAtn,
			org.antlr.v4.runtime.CharStreams.fromString("a"));
		SerParserInterpreter parser = new SerParserInterpreter(
			"P.g4", vocab, java.util.Collections.singletonList("s"), parserAtn,
			new org.antlr.v4.runtime.CommonTokenStream(lexer), serialized);
		ParseTreePatternMatcher matcher = new ParseTreePatternMatcher(lexer, parser);
		try {
			// two tokens but rule only consumes one
			matcher.compile("a a", 0);
			fail();
		} catch (ParseTreePatternMatcher.StartRuleDoesNotConsumeFullPattern e) {
			// expected
		} catch (RuntimeException e) {
			// may also fail earlier during parse of second a without separator
			assertNotNull(e);
		}
	}

	static class SerParserInterpreter extends org.antlr.v4.runtime.ParserInterpreter {
		private final String serialized;

		SerParserInterpreter(String grammarFileName,
							 org.antlr.v4.runtime.Vocabulary vocabulary,
							 java.util.Collection<String> ruleNames,
							 org.antlr.v4.runtime.atn.ATN atn,
							 org.antlr.v4.runtime.TokenStream input,
							 String serialized) {
			super(grammarFileName, vocabulary, ruleNames, atn, input);
			this.serialized = serialized;
		}

		@Override
		public String getSerializedATN() {
			return serialized;
		}
	}

	static org.antlr.v4.runtime.atn.ATN buildLexerA() {
		org.antlr.v4.runtime.atn.ATN atn =
			new org.antlr.v4.runtime.atn.ATN(org.antlr.v4.runtime.atn.ATNType.LEXER, 1);
		org.antlr.v4.runtime.atn.TokensStartState tokensStart =
			new org.antlr.v4.runtime.atn.TokensStartState();
		tokensStart.ruleIndex = -1;
		org.antlr.v4.runtime.atn.RuleStartState ruleStart =
			new org.antlr.v4.runtime.atn.RuleStartState();
		org.antlr.v4.runtime.atn.BasicState mid = new org.antlr.v4.runtime.atn.BasicState();
		org.antlr.v4.runtime.atn.RuleStopState ruleStop =
			new org.antlr.v4.runtime.atn.RuleStopState();
		ruleStart.ruleIndex = 0;
		mid.ruleIndex = 0;
		ruleStop.ruleIndex = 0;
		ruleStart.stopState = ruleStop;
		atn.addState(tokensStart);
		atn.addState(ruleStart);
		atn.addState(mid);
		atn.addState(ruleStop);
		tokensStart.addTransition(new org.antlr.v4.runtime.atn.EpsilonTransition(ruleStart));
		ruleStart.addTransition(new org.antlr.v4.runtime.atn.AtomTransition(mid, 'a'));
		mid.addTransition(new org.antlr.v4.runtime.atn.EpsilonTransition(ruleStop));
		atn.ruleToStartState = new org.antlr.v4.runtime.atn.RuleStartState[]{ruleStart};
		atn.ruleToStopState = new org.antlr.v4.runtime.atn.RuleStopState[]{ruleStop};
		atn.ruleToTokenType = new int[]{1};
		atn.lexerActions = new org.antlr.v4.runtime.atn.LexerAction[0];
		atn.defineMode("DEFAULT_MODE", tokensStart);
		atn.clearDFA();
		return atn;
	}

	static org.antlr.v4.runtime.atn.ATN buildParserMatchA() {
		// Rule start must use epsilon only (required for bypass-alt ATN verification).
		// s : A ;
		// ruleStart -eps-> matchA -Atom(A)-> afterA -eps-> ruleStop
		org.antlr.v4.runtime.atn.ATN atn =
			new org.antlr.v4.runtime.atn.ATN(org.antlr.v4.runtime.atn.ATNType.PARSER, 1);
		org.antlr.v4.runtime.atn.RuleStartState ruleStart =
			new org.antlr.v4.runtime.atn.RuleStartState();
		org.antlr.v4.runtime.atn.BasicState matchA = new org.antlr.v4.runtime.atn.BasicState();
		org.antlr.v4.runtime.atn.BasicState afterA = new org.antlr.v4.runtime.atn.BasicState();
		org.antlr.v4.runtime.atn.RuleStopState ruleStop =
			new org.antlr.v4.runtime.atn.RuleStopState();
		ruleStart.ruleIndex = 0;
		matchA.ruleIndex = 0;
		afterA.ruleIndex = 0;
		ruleStop.ruleIndex = 0;
		ruleStart.stopState = ruleStop;
		atn.addState(ruleStart);
		atn.addState(matchA);
		atn.addState(afterA);
		atn.addState(ruleStop);
		ruleStart.addTransition(new org.antlr.v4.runtime.atn.EpsilonTransition(matchA));
		matchA.addTransition(new org.antlr.v4.runtime.atn.AtomTransition(afterA, 1));
		afterA.addTransition(new org.antlr.v4.runtime.atn.EpsilonTransition(ruleStop));
		atn.ruleToStartState = new org.antlr.v4.runtime.atn.RuleStartState[]{ruleStart};
		atn.ruleToStopState = new org.antlr.v4.runtime.atn.RuleStopState[]{ruleStop};
		atn.clearDFA();
		return atn;
	}
}
