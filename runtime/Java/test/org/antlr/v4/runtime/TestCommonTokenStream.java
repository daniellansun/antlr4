/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TestCommonTokenStream {
	private static CommonToken on(int type, String text) {
		return new CommonToken(type, text);
	}

	private static CommonToken off(String text) {
		CommonToken t = new CommonToken(99, text);
		t.setChannel(Lexer.HIDDEN);
		return t;
	}

	@Test
	public void skipsOffChannelTokens() {
		// simulate " x =34  ;\n"
		TokenSource lexer = new MockTokenSource(
			off(" "),
			on(1, "x"),
			off(" "),
			on(1, "="),
			on(1, "34"),
			off(" "),
			off(" "),
			on(1, ";"),
			off("\n")
		);

		CommonTokenStream tokens = new CommonTokenStream(lexer);
		assertEquals("x", tokens.LT(1).getText());
		tokens.consume();
		assertEquals("=", tokens.LT(1).getText());
		assertEquals("x", tokens.LT(-1).getText());
		tokens.consume();
		assertEquals("34", tokens.LT(1).getText());
		assertEquals("=", tokens.LT(-1).getText());
		tokens.consume();
		assertEquals(";", tokens.LT(1).getText());
		assertEquals("34", tokens.LT(-1).getText());
		tokens.consume();
		assertEquals(Token.EOF, tokens.LA(1));
		assertEquals(";", tokens.LT(-1).getText());
		assertEquals("34", tokens.LT(-2).getText());
		assertEquals("=", tokens.LT(-3).getText());
		assertEquals("x", tokens.LT(-4).getText());
	}

	/**
	 * LT(1)/LA(1) cache must never surface hidden-channel tokens after consume
	 * or seek (p is always adjustSeekIndex'd onto the default channel).
	 */
	@Test
	public void lt1CacheRespectsChannelAfterConsumeAndSeek() {
		TokenSource lexer = new MockTokenSource(
			off(" "),
			on(1, "a"),
			off(" "),
			off("\t"),
			on(2, "b"),
			off(" "),
			on(3, "c")
		);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		// Multiple LT(1)/LA(1) hits exercise the cache
		assertEquals("a", tokens.LT(1).getText());
		assertEquals(1, tokens.LA(1));
		assertEquals("a", tokens.LT(1).getText());
		assertEquals(1, tokens.LA(1));

		tokens.consume();
		assertEquals("b", tokens.LT(1).getText());
		assertEquals(2, tokens.LA(1));
		// k>1 still skips hidden
		assertEquals("c", tokens.LT(2).getText());

		tokens.seek(0);
		assertEquals("a", tokens.LT(1).getText());
		assertEquals(1, tokens.LA(1));

		// Seek to a raw buffer index that is hidden; adjustSeekIndex should
		// advance to the next on-channel token before caching.
		tokens.seek(2); // off-channel " "
		assertEquals(Token.DEFAULT_CHANNEL, tokens.LT(1).getChannel());
		assertEquals("b", tokens.LT(1).getText());
		assertEquals(2, tokens.LA(1));
	}

	@Test
	public void channelConstructorFiltersToChannel() {
		TokenSource lexer = new MockTokenSource(
			on(1, "a"),
			off(" "),
			on(1, "b")
		);
		CommonTokenStream defaultChannel = new CommonTokenStream(lexer);
		defaultChannel.fill();
		assertEquals("a", defaultChannel.LT(1).getText());

		TokenSource lexer2 = new MockTokenSource(
			on(1, "a"),
			off(" "),
			on(1, "b")
		);
		CommonTokenStream hiddenOnly = new CommonTokenStream(lexer2, Token.HIDDEN_CHANNEL);
		assertEquals(" ", hiddenOnly.LT(1).getText());
	}

	@Test
	public void getNumberOfOnChannelTokens() {
		TokenSource lexer = new MockTokenSource(
			off(" "),
			on(1, "x"),
			off(" "),
			on(1, "="),
			on(1, "1")
		);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		// on-channel: x, =, 1, EOF
		assertEquals(4, tokens.getNumberOfOnChannelTokens());
	}

	@Test
	public void fetchOffChannelHiddenTokens() {
		// " x =34  ; \n"
		TokenSource lexer = new MockTokenSource(
			off(" "), // 0
			on(1, "x"), // 1
			off(" "), // 2
			on(1, "="), // 3
			on(1, "34"), // 4
			off(" "), // 5
			off(" "), // 6
			on(1, ";"), // 7
			off(" "), // 8
			off("\n") // 9
		);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		tokens.fill();

		assertNull(tokens.getHiddenTokensToLeft(0));
		assertNull(tokens.getHiddenTokensToRight(0));

		assertEquals(1, tokens.getHiddenTokensToLeft(1).size());
		assertEquals(1, tokens.getHiddenTokensToRight(1).size());
		assertEquals(1, tokens.getHiddenTokensToLeft(3).size());
		assertNull(tokens.getHiddenTokensToRight(3));
		assertEquals(2, tokens.getHiddenTokensToRight(4).size());
		assertEquals(2, tokens.getHiddenTokensToLeft(7).size());
		assertEquals(2, tokens.getHiddenTokensToRight(7).size());
	}

	@Test
	public void singleEof() {
		CommonTokenStream tokens = new CommonTokenStream(new MockTokenSource());
		tokens.fill();
		assertEquals(Token.EOF, tokens.LA(1));
		assertEquals(0, tokens.index());
		assertEquals(1, tokens.size());
	}

	@Test(expected = IllegalStateException.class)
	public void cannotConsumeEof() {
		CommonTokenStream tokens = new CommonTokenStream(new MockTokenSource());
		tokens.fill();
		tokens.consume();
	}

	@Test
	public void getTextIncludesOffChannel() {
		TokenSource lexer = new MockTokenSource(
			off(" "),
			on(1, "x"),
			off(" "),
			on(1, "y")
		);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		tokens.fill();
		assertEquals(" x y", tokens.getText());
	}

	@Test
	public void lookbackBeyondStartIsNull() {
		CommonTokenStream tokens = new CommonTokenStream(new MockTokenSource(on(1, "a")));
		assertNull(tokens.LT(-1));
	}
}
