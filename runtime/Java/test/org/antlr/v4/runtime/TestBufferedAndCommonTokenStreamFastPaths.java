/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link BufferedTokenStream} ({@code tokenCount}, {@code tokenAt},
 * {@code laOneSlow}/{@code ltOneSlow}, bounds guards, custom backing lists) and
 * {@link CommonTokenStream} ({@code nextOnChannel}, {@code fetchNextOnChannel},
 * {@code ltForwardFast}, {@code seek}, {@code consume}, {@code LB} override guard).
 */
public class TestBufferedAndCommonTokenStreamFastPaths {

	private static CommonToken tok(int type, int channel, String text) {
		CommonToken t = new CommonToken(type, text);
		t.setChannel(channel);
		return t;
	}

	private static TokenSource sourceOf(final Token... toks) {
		return new TokenSource() {
			int i = 0;
			@Override
			public Token nextToken() {
				return i < toks.length ? toks[i++] : tok(Token.EOF, 0, "<EOF>");
			}
			@Override public int getLine() { return 1; }
			@Override public int getCharPositionInLine() { return 0; }
			@Override public CharStream getInputStream() { return null; }
			@Override public String getSourceName() { return "mock"; }
			@Override public void setTokenFactory(TokenFactory factory) {}
			@Override public TokenFactory getTokenFactory() { return CommonTokenFactory.DEFAULT; }
		};
	}

	private static final class ReadOnlyToken implements Token {
		final int type;
		final int channel;
		final String text;
		ReadOnlyToken(int type, int channel, String text) {
			this.type = type; this.channel = channel; this.text = text;
		}
		@Override public String getText() { return text; }
		@Override public int getType() { return type; }
		@Override public int getLine() { return 1; }
		@Override public int getCharPositionInLine() { return 0; }
		@Override public int getChannel() { return channel; }
		@Override public int getTokenIndex() { return -1; }
		@Override public int getStartIndex() { return 0; }
		@Override public int getStopIndex() { return 0; }
		@Override public TokenSource getTokenSource() { return null; }
		@Override public CharStream getInputStream() { return null; }
	}

	private static final class CustomWritableToken extends CommonToken {
		CustomWritableToken(int type, int channel, String text) {
			super(type, text);
			setChannel(channel);
		}
	}

	@Test
	public void testBufferedTokenStreamAccessorsBoundsAndCustomList() {
		BufferedTokenStream bts = new BufferedTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 1, "ws"), tok(3, 0, "b"), tok(Token.EOF, 0, "<EOF>")
		));
		assertEquals(1, bts.LA(1));
		bts.clearCachedLT1();
		assertEquals(1, bts.LA(1));
		bts.clearCachedLT1();
		assertEquals("a", bts.LT(1).getText());
		assertNull(bts.LT(0));
		bts.consume();
		assertEquals("a", bts.LT(-1).getText());
		assertEquals(Token.EOF, bts.LT(Integer.MAX_VALUE).getType());

		bts.seek(99);
		bts.clearCachedLT1();
		assertEquals(Token.EOF, bts.LT(1).getType());
		try { bts.consume(); fail("Expected IllegalStateException"); } catch (IllegalStateException expected) {}

		// Subclass overriding LB(1) must not be bypassed by cachedLTMinus1
		final boolean[] lbCalled = new boolean[1];
		BufferedTokenStream subBts = new BufferedTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 0, "b"), tok(Token.EOF, 0, "<EOF>")
		)) {
			@Override protected Token LB(int k) { lbCalled[0] = true; return super.LB(k); }
		};
		subBts.LT(1);
		subBts.consume();
		assertEquals("a", subBts.LT(-1).getText());
		assertTrue(lbCalled[0]);

		// Subclass replacing tokens with custom ArrayList exercises tokenCount()/tokenAt() fallback
		BufferedTokenStream customListStream = new BufferedTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 1, "h"), tok(3, 0, "b"), tok(Token.EOF, 0, "<EOF>")
		)) {
			{ this.tokens = new ArrayList<Token>(); }
		};
		assertEquals(1, customListStream.LA(1));
		customListStream.consume();
		assertEquals(2, customListStream.nextTokenOnChannel(1, 0));
		assertEquals(3, customListStream.LA(2));
		customListStream.seek(50);
		customListStream.clearCachedLT1();
		assertEquals(Token.EOF, customListStream.LA(1));
		customListStream.clearCachedLT1();
		assertEquals(Token.EOF, customListStream.LT(1).getType());
		assertEquals(customListStream.size() - 1, customListStream.nextTokenOnChannel(999, 0));

		// Negative index rejected in nextTokenOnChannel
		try {
			bts.nextTokenOnChannel(-1, 0);
			fail("Expected AssertionError or IndexOutOfBoundsException");
		}
		catch (AssertionError expected) {}
		catch (IndexOutOfBoundsException expected) {}

		// LB when tokens externally shortened throws IOOBE
		BufferedTokenStream shortStream = new BufferedTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 0, "b"), tok(3, 0, "c"), tok(Token.EOF, 0, "<EOF>")
		));
		shortStream.fill();
		shortStream.seek(2);
		shortStream.getTokens().clear();
		try { shortStream.LB(1); fail(); } catch (IndexOutOfBoundsException expected) {}
	}

	@Test
	public void testCommonTokenStreamSeekConsumeLtForwardFastAndLbGuard() {
		CommonTokenStream cts = new CommonTokenStream(sourceOf(
			tok(1, 1, "h0"),
			new CustomWritableToken(2, 0, "w1"),
			new ReadOnlyToken(3, 1, "rh2"),
			new ReadOnlyToken(4, 0, "r3"),
			tok(5, 0, "c4"),
			new ReadOnlyToken(Token.EOF, 0, "<EOF>")
		));
		assertEquals("w1", cts.LT(1).getText());
		assertEquals("r3", cts.LT(2).getText());
		assertEquals("c4", cts.LT(3).getText());
		assertEquals(Token.EOF, cts.LT(4).getType());
		assertEquals(Token.EOF, cts.LT(10).getType());

		cts.clearCachedLT1();
		cts.seek(cts.index());
		assertEquals("w1", cts.LT(1).getText());
		cts.seek(4);
		assertEquals("c4", cts.LT(1).getText());
		cts.seek(0);
		assertEquals(1, cts.index());

		// Streaming consume() fused frontier fetch across custom token types
		CommonTokenStream stream2 = new CommonTokenStream(sourceOf(
			tok(1, 0, "a"),
			new CustomWritableToken(2, 1, "h"),
			new ReadOnlyToken(3, 0, "b"),
			new CustomWritableToken(Token.EOF, 0, "<EOF>")
		));
		assertEquals("a", stream2.LT(1).getText());
		stream2.consume();
		assertEquals("b", stream2.LT(1).getText());
		assertEquals("a", stream2.LT(-1).getText());
		stream2.consume();
		assertEquals(Token.EOF, stream2.LT(1).getType());
		try { stream2.consume(); fail(); } catch (IllegalStateException expected) {}

		// Subclass overriding LB(1)
		final boolean[] subLb = new boolean[1];
		CommonTokenStream subCts = new CommonTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 0, "b"), tok(Token.EOF, 0, "<EOF>")
		)) {
			@Override protected Token LB(int k) { subLb[0] = true; return super.LB(k); }
		};
		subCts.LT(1);
		subCts.consume();
		assertEquals("a", subCts.LT(-1).getText());
		assertTrue(subLb[0]);

		// Truncated stream seek throws
		CommonTokenStream trunc = new CommonTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 0, "b"), tok(Token.EOF, 0, "<EOF>")
		));
		trunc.fill();
		trunc.seek(1);
		trunc.getTokens().clear();
		try { trunc.seek(1); fail(); } catch (IllegalStateException expected) {}
	}

	@Test
	public void testCommonTokenStreamNextOnChannelAllEdgeCases() {
		// 1. Uninitialized stream: afterIndex == -1 fetches first on-channel token without mutating p/cachedLT1
		CommonTokenStream cts = new CommonTokenStream(sourceOf(
			tok(1, 1, "h0"),
			tok(2, 0, "a1"),
			new CustomWritableToken(3, 1, "h2"),
			new ReadOnlyToken(4, 0, "b3"),
			tok(5, 0, "c4"),
			tok(Token.EOF, 0, "<EOF>")
		));
		assertEquals(-1, cts.index());
		assertNull(cts.cachedLT1);

		Token first = cts.nextOnChannel(-1);
		assertEquals("a1", first.getText());
		assertEquals(-1, cts.index());
		assertNull(cts.cachedLT1);

		// Frontier fetch from afterIndex == 1 skips hidden h2 -> returns b3 (index 3)
		Token second = cts.nextOnChannel(1);
		assertEquals("b3", second.getText());
		assertEquals(-1, cts.index());

		// Frontier fetch with afterIndex > current buffer size (afterIndex == 3 -> c4; afterIndex == 4 -> EOF)
		assertEquals("c4", cts.nextOnChannel(3).getText());
		// Skip ahead past unbuffered frontier (e.g. afterIndex == 10 before EOF fetched -> returns EOF!)
		CommonTokenStream farAhead = new CommonTokenStream(sourceOf(
			tok(1, 0, "x0"), tok(2, 0, "x1"), tok(Token.EOF, 0, "<EOF>")
		));
		assertEquals(Token.EOF, farAhead.nextOnChannel(10).getType());
		assertEquals(-1, farAhead.index());

		// Buffered hidden-skip & buffered adjacent hit
		assertEquals("a1", cts.nextOnChannel(-1).getText());
		assertEquals("b3", cts.nextOnChannel(1).getText());
		assertEquals("c4", cts.nextOnChannel(3).getText());
		assertEquals(Token.EOF, cts.nextOnChannel(4).getType());

		// Already fetchedEOF: index at or past EOF clamps to EOF, including Integer.MAX_VALUE
		assertEquals(Token.EOF, cts.nextOnChannel(5).getType());
		assertEquals(Token.EOF, cts.nextOnChannel(999).getType());
		assertEquals(Token.EOF, cts.nextOnChannel(Integer.MAX_VALUE).getType());

		// Invalid negative index (< -1) throws IllegalArgumentException
		try {
			cts.nextOnChannel(-2);
			fail("Expected IllegalArgumentException");
		}
		catch (IllegalArgumentException expected) {
		}
		try {
			cts.nextOnChannel(Integer.MIN_VALUE);
			fail("Expected IllegalArgumentException");
		}
		catch (IllegalArgumentException expected) {
		}

		// Verify nextOnChannel preserves p, cachedLT1, and cachedLTMinus1 across lookahead
		cts.LT(1);
		cts.consume(); // p now at b3, cachedLTMinus1 == a1
		Token savedLt1 = cts.cachedLT1;
		Token savedPrev = cts.cachedLTMinus1;
		int savedP = cts.index();
		assertEquals("c4", cts.nextOnChannel(savedP).getText());
		assertEquals(savedP, cts.index());
		assertSame(savedLt1, cts.cachedLT1);
		assertSame(savedPrev, cts.cachedLTMinus1);

		// Subclass / custom backing list fallback preserves p & cachedLT1 (does NOT call lazyInit())
		CommonTokenStream subStream = new CommonTokenStream(sourceOf(
			tok(1, 1, "h0"), tok(2, 0, "a1"), tok(3, 0, "b2"), tok(Token.EOF, 0, "<EOF>")
		)) {
			{ this.tokens = new ArrayList<Token>(); }
		};
		assertEquals(-1, subStream.index());
		assertEquals("a1", subStream.nextOnChannel(-1).getText());
		assertEquals(-1, subStream.index());
		assertNull(subStream.cachedLT1);
		assertEquals("b2", subStream.nextOnChannel(1).getText());
		assertEquals(Token.EOF, subStream.nextOnChannel(Integer.MAX_VALUE).getType());

		// Cover buffered multi-hidden-skip inside CommonTokenStream.nextOnChannel (lines 312-317) + LB(2) (line 216)
		CommonTokenStream bufferedHidden = new CommonTokenStream(sourceOf(
			tok(1, 0, "a0"),
			tok(2, 1, "h1"),
			tok(3, 1, "h2"),
			tok(4, 0, "b3"),
			tok(5, 0, "c4"),
			tok(Token.EOF, 0, "<EOF>")
		));
		bufferedHidden.fill();
		// Already filled -> nextOnChannel(0) scans buffered h1, h2 -> returns b3!
		assertEquals("b3", bufferedHidden.nextOnChannel(0).getText());
		bufferedHidden.seek(4); // c4
		assertEquals("a0", bufferedHidden.LT(-2).getText()); // LB(2) -> line 216!

		// Cover BufferedTokenStream lines 205 (same-index seek with cachedLT1==null), 436 (ltOneSlow p>=sz),
		// 448 (ltOneSlow fallback when sz==0 / subclass), 646 (nextTokenOnChannel when !sync(i)), and CommonTokenStream:216
		BufferedTokenStream btsEdge = new BufferedTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 0, "b"), tok(Token.EOF, 0, "<EOF>")
		));
		btsEdge.fill();
		btsEdge.seek(1);
		btsEdge.clearCachedLT1();
		btsEdge.seek(1); // p == adjusted && cachedLT1 == null -> populates cachedLT1 (line 205)
		assertEquals("b", btsEdge.LT(1).getText());
		btsEdge.p = 50;
		btsEdge.clearCachedLT1();
		assertEquals(Token.EOF, btsEdge.LT(1).getType()); // line 436: ltOneSlow clamps p >= sz
		assertEquals(2, btsEdge.nextTokenOnChannel(50, 0)); // line 646: !sync(50) -> tokenCount() - 1

		// Subclass CommonTokenStream calling LT(2) bypasses ltForwardFast and hits CommonTokenStream line 216
		CommonTokenStream subLt2 = new CommonTokenStream(sourceOf(
			tok(1, 0, "a0"), tok(2, 1, "h1"), tok(3, 0, "b2"), tok(Token.EOF, 0, "<EOF>")
		)) {
			// anonymous subclass -> getClass() != CommonTokenStream.class
		};
		assertEquals("b2", subLt2.LT(2).getText());

		// Regression: nextOnChannel(Integer.MAX_VALUE) and nextOnChannel(Integer.MAX_VALUE - 1) on
		// uninitialized base and subclass streams must NOT overflow sync() and must NOT mutate p/cachedLT1!
		CommonTokenStream uninitBase = new CommonTokenStream(sourceOf(
			tok(1, 1, "h0"), tok(2, 0, "a1"), tok(Token.EOF, 0, "<EOF>")
		));
		assertEquals(Token.EOF, uninitBase.nextOnChannel(Integer.MAX_VALUE).getType());
		assertEquals(-1, uninitBase.index());
		assertNull(uninitBase.cachedLT1);

		CommonTokenStream uninitSub = new CommonTokenStream(sourceOf(
			tok(1, 1, "h0"), tok(2, 0, "a1"), tok(Token.EOF, 0, "<EOF>")
		)) {
			{ this.tokens = new ArrayList<Token>(); }
		};
		assertEquals(Token.EOF, uninitSub.nextOnChannel(Integer.MAX_VALUE - 1).getType());
		assertEquals(-1, uninitSub.index());
		assertNull(uninitSub.cachedLT1);
		assertEquals(Token.EOF, uninitSub.nextOnChannel(Integer.MAX_VALUE).getType());
		assertEquals(-1, uninitSub.index());
		assertNull(uninitSub.cachedLT1);
	}
}
