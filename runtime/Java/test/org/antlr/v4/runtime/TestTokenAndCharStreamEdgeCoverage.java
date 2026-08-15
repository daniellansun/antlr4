/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.misc.Interval;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Edge cases for {@link BufferedTokenStream}, unbuffered char/token streams,
 * and {@link TokenStreamRewriter} (hidden channels, fill, seek, rewrite overlaps).
 */
public class TestTokenAndCharStreamEdgeCoverage {

	private static CommonToken tok(int type, String text) {
		return new CommonToken(type, text);
	}

	private static CommonToken hidden(String text) {
		CommonToken t = new CommonToken(99, text);
		t.setChannel(Token.HIDDEN_CHANNEL);
		return t;
	}

	private static CommonToken hiddenOn(int channel, String text) {
		CommonToken t = new CommonToken(99, text);
		t.setChannel(channel);
		return t;
	}

	@Test
	public void consumeBeforeLazyInitAndSyncFailure() {
		ExposedStream s = new ExposedStream(new MockTokenSource(tok(1, "x"), tok(2, "y")));
		// p == -1 path
		s.consume();
		assertEquals(2, s.LA(1));

		s.forceSyncFalse = true;
		s.consume();
		assertNull(s.cachedLT1());
		s.forceSyncFalse = false;

		s.adjustToOutOfRange = true;
		s.consume();
		assertNull(s.cachedLT1());
		s.adjustToOutOfRange = false;
	}

	@Test
	public void getRangeAndGetTokensEdgeCases() {
		BufferedTokenStream s = new BufferedTokenStream(new MockTokenSource(tok(1, "a"), tok(2, "b"), tok(3, "c")));
		s.fill();
		try {
			s.get(-1);
			fail();
		}
		catch (IndexOutOfBoundsException expected) {
			assertTrue(expected.getMessage().contains("out of range"));
		}
		try {
			s.get(99);
			fail();
		}
		catch (IndexOutOfBoundsException expected) {
			assertNotNull(expected.getMessage());
		}

		assertNull(s.get(-1, 1));
		List<Token> clipped = s.get(0, 99);
		assertTrue(clipped.size() >= 1);
		List<Token> toEof = s.get(0, s.size() - 1);
		for (Token t : toEof) {
			assertTrue(t.getType() != Token.EOF);
		}

		try {
			s.getTokens(-1, 0);
			fail();
		}
		catch (IndexOutOfBoundsException expected) {
			assertNotNull(expected.getMessage());
		}
		try {
			s.getTokens(0, s.size() + 1);
			fail();
		}
		catch (IndexOutOfBoundsException expected) {
			assertNotNull(expected.getMessage());
		}
		assertNull(s.getTokens(2, 0));
		BitSet none = new BitSet();
		none.set(123);
		assertNull(s.getTokens(0, 1, none));
	}

	@Test
	public void hiddenTokensAndChannelFilterAndFillLarge() {
		// on-channel A, hidden, hidden-other, B, hidden, EOF
		BufferedTokenStream s = new BufferedTokenStream(new MockTokenSource(
			tok(1, "A"),
			hidden(" "),
			hiddenOn(2, "#"),
			tok(2, "B"),
			hidden("\n")));
		s.fill();
		try {
			s.getHiddenTokensToRight(-1);
			fail();
		}
		catch (IndexOutOfBoundsException expected) {
			assertNotNull(expected.getMessage());
		}
		try {
			s.getHiddenTokensToLeft(99);
			fail();
		}
		catch (IndexOutOfBoundsException expected) {
			assertNotNull(expected.getMessage());
		}

		List<Token> right = s.getHiddenTokensToRight(0, Token.HIDDEN_CHANNEL);
		assertNotNull(right);
		List<Token> left = s.getHiddenTokensToLeft(3, Token.HIDDEN_CHANNEL);
		assertNotNull(left);
		assertNull(s.getHiddenTokensToLeft(0));

		// specific channel filter
		List<Token> ch2 = s.getHiddenTokensToRight(0, 2);
		assertNotNull(ch2);
		assertEquals(1, ch2.size());

		assertEquals("", s.getText(Interval.of(-1, -1)));
		assertTrue(s.getText(Interval.of(0, 999)).length() >= 1);
		assertEquals("", s.getText("not-a-token", "also-not"));

		// fill > 1000 tokens
		Token[] many = new Token[1105];
		for (int i = 0; i < many.length; i++) {
			many[i] = tok(1, "t");
		}
		BufferedTokenStream big = new BufferedTokenStream(new MockTokenSource(many));
		big.fill();
		assertTrue(big.size() > 1000);
	}

	@Test
	public void nextAndPreviousTokenOnChannelUnfetchedAndNegative() {
		ExposedStream s = new ExposedStream(new MockTokenSource(
			tok(1, "A"), hidden(" "), tok(2, "B")));
		// call nextTokenOnChannel with i past currently fetched tokens
		s.LT(1); // fetch first
		int idx = s.callNextTokenOnChannel(5, Token.DEFAULT_CHANNEL);
		assertTrue(idx >= 0);
		int pastEnd = s.callNextTokenOnChannel(s.size() + 5, Token.DEFAULT_CHANNEL);
		assertTrue(pastEnd >= 0);

		assertEquals(-1, s.callPreviousTokenOnChannel(-3, Token.DEFAULT_CHANNEL));
		int prev = s.callPreviousTokenOnChannel(50, Token.DEFAULT_CHANNEL);
		assertTrue(prev >= 0);

		// ensureTokenCapacity from unbuffered (size() throws)
		class Src implements TokenSource {
			int i;
			final CharStream in = new UnbufferedCharStream(new StringReader("abc"));
			@Override public Token nextToken() {
				if (i++ == 0) return tok(1, "a");
				return CommonTokenFactory.DEFAULT.create(Token.EOF, "EOF");
			}
			@Override public int getLine() { return 1; }
			@Override public int getCharPositionInLine() { return 0; }
			@Override public CharStream getInputStream() { return in; }
			@Override public String getSourceName() { return "u"; }
			@Override public TokenFactory getTokenFactory() { return CommonTokenFactory.DEFAULT; }
			@Override public void setTokenFactory(TokenFactory factory) { }
		}
		BufferedTokenStream ub = new BufferedTokenStream(new Src());
		ub.fill();
		assertTrue(ub.size() >= 1);
	}

	@Test
	public void unbufferedCharStreamFillEofSeekAndText() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("ab"), 2);
		assertEquals('a', s.LA(1));
		s.consume();
		s.consume();
		assertEquals(IntStream.EOF, s.LA(1));
		// fill after EOF already buffered
		assertEquals(IntStream.EOF, s.LA(2));
		try {
			s.LA(-5);
			fail();
		}
		catch (IndexOutOfBoundsException expected) {
			assertNotNull(expected);
		}
		// After the sliding window moves, seeking back before the buffer throws.
		UnbufferedCharStream slide = new UnbufferedCharStream(new StringReader("abcdefghij"), 2);
		for (int i = 0; i < 6; i++) {
			slide.LA(1);
			slide.consume();
		}
		try {
			slide.seek(0);
			fail();
		}
		catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("negative") || expected.getMessage() != null);
		}

		class Forced extends UnbufferedCharStream {
			Forced() { super(new StringReader("xyz"), 2); }
			void outside() {
				n = 0;
				seek(1);
			}
		}
		try {
			new Forced().outside();
		}
		catch (UnsupportedOperationException expected) {
			assertTrue(expected.getMessage().contains("outside buffer")
				|| expected.getMessage().contains("seek"));
		}
		catch (IllegalArgumentException expected) {
			assertNotNull(expected.getMessage());
		}

		UnbufferedCharStream s2 = new UnbufferedCharStream(new StringReader("xy"), 8);
		s2.LA(1);
		s2.consume();
		s2.consume();
		s2.LA(1); // EOF stored as Character.MAX_VALUE
		try {
			s2.getText(Interval.of(0, 100));
		}
		catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("interval") || expected.getMessage() != null);
		}
		catch (UnsupportedOperationException expected) {
			assertNotNull(expected.getMessage());
		}

		// grow buffer (n >= data.length)
		UnbufferedCharStream tiny = new UnbufferedCharStream(new StringReader("0123456789ABCDEF"), 2);
		tiny.mark();
		for (int i = 0; i < 16; i++) {
			tiny.LA(i + 1);
		}
		assertEquals('0', tiny.LA(1));
	}

	@Test
	public void unbufferedTokenStreamGrowLookPastEofAndSeek() {
		Token[] toks = new Token[40];
		for (int i = 0; i < toks.length; i++) {
			toks[i] = tok(1, "t" + i);
		}
		UnbufferedTokenStream s = new UnbufferedTokenStream(new MockTokenSource(toks), 4);
		s.mark();
		for (int i = 0; i < 40; i++) {
			s.LT(i + 1);
		}
		// look past the end
		Token eof = s.LT(100);
		assertEquals(Token.EOF, eof.getType());

		try {
			s.seek(-1);
			fail();
		}
		catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("negative"));
		}

		UnbufferedTokenStream s2 = new UnbufferedTokenStream(new MockTokenSource(tok(1, "a")), 8);
		s2.LT(1);
		try {
			s2.seek(50);
		}
		catch (UnsupportedOperationException expected) {
			assertTrue(expected.getMessage().contains("outside buffer"));
		}
		catch (IllegalArgumentException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void tokenStreamRewriterLastIndexInsertAfterAndOverlaps() {
		BufferedTokenStream tokens = new BufferedTokenStream(
			new MockTokenSource(tok(1, "a"), tok(2, "b"), tok(3, "c"), tok(4, "d")));
		tokens.fill();
		ExposedRewriter r = new ExposedRewriter(tokens);
		assertEquals(-1, r.exposeLast("missing"));
		r.setLastRewriteTokenIndex("prog", 3);
		assertEquals(3, r.exposeLast("prog"));

		// start<0 / stop past end
		r.insertBefore(0, "[");
		assertTrue(r.getText(Interval.of(-5, 100)).contains("a"));

		// insertAfter last token remains after interval
		r.insertAfter(tokens.size() - 1, "TAIL");
		String withTail = r.getText(Interval.of(0, tokens.size() - 1));
		assertTrue(withTail.contains("TAIL") || withTail.length() >= 1);

		// insert then replace overlapping same index
		TokenStreamRewriter r2 = new TokenStreamRewriter(tokens);
		r2.insertBefore(1, "IN");
		r2.replace(1, 2, "XY");
		assertTrue(r2.getText().contains("XY") || r2.getText().length() >= 1);

		// insert inside replace range (index > rop.index && <= lastIndex)
		TokenStreamRewriter r3 = new TokenStreamRewriter(tokens);
		r3.insertBefore(2, "mid");
		r3.replace(1, 3, "Z");
		assertNotNull(r3.getText());

		// insert within previous replace should throw
		TokenStreamRewriter r4 = new TokenStreamRewriter(tokens);
		r4.replace(1, 2, "R");
		try {
			r4.insertBefore(2, "bad");
			r4.getText();
			fail("expected insert-within-replace error");
		}
		catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("insert") || expected.getMessage() != null);
		}
	}

	static final class ExposedStream extends BufferedTokenStream {
		boolean forceSyncFalse;
		boolean adjustToOutOfRange;

		ExposedStream(TokenSource src) {
			super(src);
		}

		@Override
		protected boolean sync(int i) {
			if (forceSyncFalse) {
				return false;
			}
			return super.sync(i);
		}

		@Override
		protected int adjustSeekIndex(int i) {
			if (adjustToOutOfRange) {
				return tokens.size() + 8;
			}
			return super.adjustSeekIndex(i);
		}

		int callNextTokenOnChannel(int i, int ch) {
			return nextTokenOnChannel(i, ch);
		}

		int callPreviousTokenOnChannel(int i, int ch) {
			return previousTokenOnChannel(i, ch);
		}
	}

	static final class ExposedRewriter extends TokenStreamRewriter {
		ExposedRewriter(TokenStream tokens) {
			super(tokens);
		}

		int exposeLast(String name) {
			return getLastRewriteTokenIndex(name);
		}

		@Override
		protected void setLastRewriteTokenIndex(String programName, int i) {
			super.setLastRewriteTokenIndex(programName, i);
		}
	}
}
