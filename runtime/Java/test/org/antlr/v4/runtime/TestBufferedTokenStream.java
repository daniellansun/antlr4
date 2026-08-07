/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.misc.Interval;
import org.junit.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
// assertTrue used by setupIsOnDemandButFillMaterializesAll

public class TestBufferedTokenStream {
	private static CommonToken tok(int type, String text) {
		return new CommonToken(type, text);
	}

	private static CommonToken hidden(String text) {
		CommonToken t = new CommonToken(99, text);
		t.setChannel(Token.HIDDEN_CHANNEL);
		return t;
	}

	private static BufferedTokenStream stream(Token... tokens) {
		return new BufferedTokenStream(new MockTokenSource(tokens));
	}

	@Test(expected = NullPointerException.class)
	public void nullTokenSourceThrows() {
		new BufferedTokenStream(null);
	}

	@Test
	public void ltAndLaAndConsume() {
		BufferedTokenStream tokens = stream(tok(1, "x"), tok(2, "="), tok(3, "1"));
		assertEquals("x", tokens.LT(1).getText());
		assertEquals(1, tokens.LA(1));
		assertEquals("=", tokens.LT(2).getText());
		tokens.consume();
		assertEquals("=", tokens.LT(1).getText());
		assertEquals("x", tokens.LT(-1).getText());
		tokens.consume();
		tokens.consume();
		assertEquals(Token.EOF, tokens.LA(1));
	}

	/**
	 * Setup remains on-demand (only first token), preserving historic lexer /
	 * parser error interleaving. Explicit {@link BufferedTokenStream#fill()}
	 * still materializes the full buffer when requested.
	 */
	@Test
	public void setupIsOnDemandButFillMaterializesAll() {
		String source = "int x = 1;";
		CharStream chars = CharStreams.fromString(source);
		TokenSource ts = new TokenSource() {
			int i;
			final TokenFactory factory = CommonTokenFactory.DEFAULT;
			final char[] data = source.toCharArray();
			@Override public Token nextToken() {
				if (i >= data.length) {
					return factory.create(Token.EOF, "EOF");
				}
				char c = data[i++];
				return new CommonToken((int)c, String.valueOf(c));
			}
			@Override public int getLine() { return 1; }
			@Override public int getCharPositionInLine() { return i; }
			@Override public CharStream getInputStream() { return chars; }
			@Override public String getSourceName() { return "ondemand"; }
			@Override public TokenFactory getTokenFactory() { return factory; }
			@Override public void setTokenFactory(TokenFactory factory) { }
		};
		BufferedTokenStream tokens = new BufferedTokenStream(ts);
		// First LT only fetches the first token (+ channel adjust)
		Token first = tokens.LT(1);
		assertEquals((int)'i', first.getType());
		assertTrue("setup must stay on-demand; full lex would reorder errors",
			tokens.size() < source.length() + 1);

		tokens.fill();
		assertEquals(source.length() + 1, tokens.size());
		assertEquals((int)'i', tokens.LA(1));
	}

	@Test
	public void fillAndSizeAndGetText() {
		BufferedTokenStream tokens = stream(tok(1, "a"), tok(2, "b"), tok(3, "c"));
		tokens.fill();
		// tokens + EOF
		assertEquals(4, tokens.size());
		assertEquals("abc", tokens.getText());
		assertEquals("bc", tokens.getText(Interval.of(1, 2)));
		assertEquals("a", tokens.get(0).getText());
	}

	@Test
	public void seekAndIndexAndReset() {
		BufferedTokenStream tokens = stream(tok(1, "a"), tok(2, "b"));
		tokens.fill();
		tokens.seek(1);
		assertEquals(1, tokens.index());
		assertEquals("b", tokens.LT(1).getText());
		tokens.reset();
		assertEquals(0, tokens.index());
		assertEquals("a", tokens.LT(1).getText());
	}

	@Test
	public void markAndReleaseAreNoOps() {
		BufferedTokenStream tokens = stream(tok(1, "a"));
		int m = tokens.mark();
		assertEquals(0, m);
		tokens.release(m);
	}

	@Test
	public void setTokenSourceResetsBuffer() {
		BufferedTokenStream tokens = stream(tok(1, "old"));
		tokens.fill();
		assertEquals(2, tokens.size());
		tokens.setTokenSource(new MockTokenSource(tok(2, "new")));
		assertEquals("new", tokens.LT(1).getText());
		assertEquals(1, tokens.size()); // only fetched LT(1) so far... actually setup fetches 0
	}

	@Test
	public void getTokensByRangeAndType() {
		BufferedTokenStream tokens = stream(tok(1, "a"), tok(2, "b"), tok(1, "c"));
		tokens.fill();
		List<Token> all = tokens.getTokens(0, 2);
		assertEquals(3, all.size());
		List<Token> type1 = tokens.getTokens(0, 2, 1);
		assertEquals(2, type1.size());
		assertEquals("a", type1.get(0).getText());
		assertEquals("c", type1.get(1).getText());

		BitSet types = new BitSet();
		types.set(2);
		List<Token> type2 = tokens.getTokens(0, 2, types);
		assertEquals(1, type2.size());
		assertEquals("b", type2.get(0).getText());
	}

	@Test
	public void getRangeOfTokensInclusive() {
		BufferedTokenStream tokens = stream(tok(1, "a"), tok(2, "b"), tok(3, "c"));
		tokens.fill();
		List<Token> subset = tokens.get(0, 1);
		assertEquals(2, subset.size());
	}

	@Test
	public void getTextByTokens() {
		BufferedTokenStream tokens = stream(tok(1, "hello"), tok(2, " "), tok(3, "world"));
		tokens.fill();
		assertEquals("hello world", tokens.getText(tokens.get(0), tokens.get(2)));
	}

	@Test
	public void getTextByRuleContextUsesSourceInterval() {
		BufferedTokenStream tokens = stream(tok(1, "ab"), tok(2, "cd"));
		tokens.fill();
		ParserRuleContext ctx = new ParserRuleContext();
		ctx.start = tokens.get(0);
		ctx.stop = tokens.get(1);
		assertEquals("abcd", tokens.getText(ctx));
	}

	@Test
	public void hiddenTokensToLeftAndRight() {
		// tokens: WS ID WS EQ WS NUM
		BufferedTokenStream tokens = stream(
			hidden(" "),
			tok(1, "x"),
			hidden(" "),
			tok(2, "="),
			hidden("\t"),
			tok(3, "34")
		);
		tokens.fill();
		assertNull(tokens.getHiddenTokensToLeft(0));
		assertEquals(1, tokens.getHiddenTokensToLeft(1).size());
		assertEquals(" ", tokens.getHiddenTokensToLeft(1).get(0).getText());
		assertEquals(1, tokens.getHiddenTokensToRight(1).size());
		List<Token> rightOfEq = tokens.getHiddenTokensToRight(3);
		assertEquals(1, rightOfEq.size());
		assertEquals("\t", rightOfEq.get(0).getText());
	}

	@Test
	public void singleEof() {
		BufferedTokenStream tokens = new BufferedTokenStream(new MockTokenSource());
		tokens.fill();
		assertEquals(1, tokens.size());
		assertEquals(Token.EOF, tokens.LA(1));
	}

	@Test(expected = IllegalStateException.class)
	public void cannotConsumeEof() {
		BufferedTokenStream tokens = new BufferedTokenStream(new MockTokenSource());
		tokens.fill();
		tokens.consume();
	}

	@Test
	public void getTokenSource() {
		MockTokenSource src = new MockTokenSource(tok(1, "a"));
		BufferedTokenStream tokens = new BufferedTokenStream(src);
		assertSame(src, tokens.getTokenSource());
		assertEquals("mock", tokens.getSourceName());
	}

	@Test
	public void ltZeroReturnsNull() {
		BufferedTokenStream tokens = stream(tok(1, "a"));
		assertNull(tokens.LT(0));
	}

	@Test
	public void lookPastEofReturnsEof() {
		BufferedTokenStream tokens = stream(tok(1, "a"));
		tokens.fill();
		assertEquals(Token.EOF, tokens.LT(100).getType());
	}

	@Test
	public void getTokensListExposed() {
		BufferedTokenStream tokens = stream(tok(1, "a"));
		tokens.fill();
		assertTrue(tokens.getTokens().size() >= 1);
	}
}
