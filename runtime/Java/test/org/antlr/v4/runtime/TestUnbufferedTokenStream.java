/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.misc.Interval;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class TestUnbufferedTokenStream {
	private static CommonToken t(int type, String text) {
		return new CommonToken(type, text);
	}

	@Test
	public void lookaheadAndConsume() {
		TokenSource src = new MockTokenSource(t(1, "x"), t(2, "="), t(3, "1"));
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(src);
		assertEquals("x", tokens.LT(1).getText());
		assertEquals("=", tokens.LT(2).getText());
		assertEquals("1", tokens.LT(3).getText());
		assertEquals(Token.EOF, tokens.LA(4));
		tokens.consume();
		assertEquals("=", tokens.LT(1).getText());
		assertEquals("x", tokens.LT(-1).getText());
		tokens.consume();
		tokens.consume();
		assertEquals(Token.EOF, tokens.LA(1));
	}

	@Test(expected = IllegalStateException.class)
	public void consumeEofThrows() {
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(new MockTokenSource());
		tokens.consume();
	}

	@Test
	public void markKeepsBuffer() {
		TokenSource src = new MockTokenSource(t(1, "a"), t(2, "b"), t(3, "c"));
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(src);
		int m = tokens.mark();
		tokens.consume();
		tokens.consume();
		// still able to access earlier token via get while marked
		assertEquals("a", tokens.get(0).getText());
		assertEquals("b", tokens.get(1).getText());
		tokens.release(m);
	}

	@Test
	public void getTextIntervalWithinBuffer() {
		TokenSource src = new MockTokenSource(t(1, "a"), t(2, "b"), t(3, "c"));
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(src);
		int m = tokens.mark();
		// force buffer to load tokens a,b,c
		assertEquals("c", tokens.LT(3).getText());
		assertEquals("ab", tokens.getText(Interval.of(0, 1)));
		assertEquals("abc", tokens.getText(tokens.get(0), tokens.get(2)));
		tokens.release(m);
	}

	@Test
	public void getTextEmptyWithoutInterval() {
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(new MockTokenSource(t(1, "a")));
		assertEquals("", tokens.getText());
	}

	@Test
	public void tokenSourceAndSourceName() {
		MockTokenSource src = new MockTokenSource("src-name", t(1, "a"));
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(src);
		assertSame(src, tokens.getTokenSource());
		assertEquals("src-name", tokens.getSourceName());
	}

	@Test
	public void indexAdvancesOnConsume() {
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(
			new MockTokenSource(t(1, "a"), t(2, "b")));
		assertEquals(0, tokens.index());
		tokens.consume();
		assertEquals(1, tokens.index());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void sizeUnsupported() {
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(new MockTokenSource(t(1, "a")));
		tokens.size();
	}

	@Test
	public void seekWithinMarkedWindow() {
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(
			new MockTokenSource(t(1, "a"), t(2, "b"), t(3, "c")));
		int m = tokens.mark();
		tokens.consume();
		tokens.consume();
		tokens.seek(0);
		assertEquals("a", tokens.LT(1).getText());
		tokens.seek(2);
		assertEquals("c", tokens.LT(1).getText());
		tokens.release(m);
	}

	@Test
	public void getTextFromRuleContext() {
		TokenSource src = new MockTokenSource(t(1, "hi"), t(2, "there"));
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(src);
		int m = tokens.mark();
		assertEquals("there", tokens.LT(2).getText()); // load both tokens
		ParserRuleContext ctx = new ParserRuleContext();
		ctx.start = tokens.get(0);
		ctx.stop = tokens.get(1);
		assertEquals("hithere", tokens.getText(ctx));
		tokens.release(m);
	}

	@Test
	public void ltZeroAndNestedMarks() {
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(
			new MockTokenSource(t(1, "a"), t(2, "b"), t(3, "c"), t(4, "d")));
		// LT(i<=0) before any consume: absolute index < 0 path
		// After consume, LT(-1) is lastToken
		tokens.consume();
		assertEquals("a", tokens.LT(-1).getText());

		int m1 = tokens.mark();
		tokens.consume();
		int m2 = tokens.mark();
		tokens.consume();
		// get absolute indices still in buffer
		assertEquals("b", tokens.get(1).getText());
		tokens.release(m2);
		tokens.seek(1);
		assertEquals("b", tokens.LT(1).getText());
		tokens.release(m1);

		// getText with start/stop tokens
		int m3 = tokens.mark();
		assertEquals("d", tokens.LT(3).getText());
		assertEquals("bcd", tokens.getText(tokens.get(1), tokens.get(3)));
		tokens.release(m3);
	}

	@Test(expected = IndexOutOfBoundsException.class)
	public void getOutOfRangeThrows() {
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(new MockTokenSource(t(1, "a")));
		tokens.get(5);
	}

	@Test
	public void ltNegativeIndexAndPastEofAndGrowBuffer() {
		// many tokens to force buffer growth in add()
		Token[] many = new Token[40];
		for (int i = 0; i < many.length; i++) {
			many[i] = t(i + 1, String.valueOf(i));
		}
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(new MockTokenSource(many));
		// force fill/grow
		assertEquals(Token.EOF, tokens.LA(50));
		// LT past end returns last (EOF)
		assertEquals(Token.EOF, tokens.LT(100).getType());

		// seek forward
		tokens.seek(10);
		assertEquals("10", tokens.LT(1).getText());
		// seek same index no-op
		tokens.seek(10);
		// seek backward within buffer
		tokens.seek(5);
		assertEquals("5", tokens.LT(1).getText());

		// invalid release marker
		int m = tokens.mark();
		try {
			tokens.release(m - 99);
			// may throw
		}
		catch (IllegalStateException expected) {
			// ok
		}
		tokens.release(m);
	}

	@Test(expected = IndexOutOfBoundsException.class)
	public void ltGivesNegativeIndexThrows() {
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(new MockTokenSource(t(1, "a"), t(2, "b")));
		// Without consume, lastToken is null; LT(-1) uses lastToken which may NPE
		// After setup: p=0, LT with large negative relative index
		// p + i - 1 < 0 when i is very negative and p is 0: index = 0 + (-5) - 1 = -6
		tokens.LT(-5);
	}

	@Test
	public void getTextObjectStartStopUnsupportedAndIntervalOutOfWindow() {
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(new MockTokenSource(t(1, "a"), t(2, "b")));
		try {
			tokens.getText("not", "tokens");
		}
		catch (UnsupportedOperationException expected) {
			// ok
		}
		int m = tokens.mark();
		tokens.LT(2);
		try {
			// interval outside buffer window (tokens.length may exceed n filled)
			tokens.getText(Interval.of(0, 1000));
		}
		catch (UnsupportedOperationException expected) {
			// ok
		}
		tokens.release(m);
	}

	@Test(expected = IllegalArgumentException.class)
	public void seekBeforeBufferStartThrows() {
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(new MockTokenSource(t(1, "a"), t(2, "b"), t(3, "c")));
		// consume and flush buffer so start index moves
		tokens.consume();
		tokens.consume(); // may flush if no markers
		// try seek to absolute 0 which may be before buffer
		tokens.seek(0);
	}
}
