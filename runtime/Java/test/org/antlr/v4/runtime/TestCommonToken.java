/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.misc.Tuple;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestCommonToken {
	@Test
	public void tokenConstants() {
		assertEquals(0, Token.INVALID_TYPE);
		assertEquals(-2, Token.EPSILON);
		assertEquals(1, Token.MIN_USER_TOKEN_TYPE);
		assertEquals(IntStream.EOF, Token.EOF);
		assertEquals(0, Token.DEFAULT_CHANNEL);
		assertEquals(1, Token.HIDDEN_CHANNEL);
		assertEquals(2, Token.MIN_USER_CHANNEL_VALUE);
	}

	@Test
	public void typeOnlyConstructor() {
		CommonToken t = new CommonToken(42);
		assertEquals(42, t.getType());
		assertEquals(Token.DEFAULT_CHANNEL, t.getChannel());
		assertEquals(-1, t.getCharPositionInLine());
		assertEquals(-1, t.getTokenIndex());
		assertNull(t.getText());
		assertNull(t.getTokenSource());
		assertNull(t.getInputStream());
	}

	@Test
	public void typeAndTextConstructor() {
		CommonToken t = new CommonToken(5, "hello");
		assertEquals(5, t.getType());
		assertEquals("hello", t.getText());
		assertEquals(Token.DEFAULT_CHANNEL, t.getChannel());
	}

	@Test
	public void factoryCreateUsesExplicitLineAndColumnCtor() {
		TokenSource src = new MockTokenSource();
		CharStream input = CharStreams.fromString("abc");
		CommonToken t = new CommonTokenFactory().create(
			Tuple.create(src, input), 7, null, Token.DEFAULT_CHANNEL, 0, 2, 4, 9);
		assertEquals(7, t.getType());
		assertEquals(4, t.getLine());
		assertEquals(9, t.getCharPositionInLine());
		assertEquals(0, t.getStartIndex());
		assertEquals(2, t.getStopIndex());
		assertEquals("abc", t.getText());
	}

	@Test
	public void explicitLineColumnConstructorDoesNotQueryTokenSource() {
		final int[] lineQueries = new int[1];
		TokenSource src = new TokenSource() {
			@Override public Token nextToken() { return new CommonToken(Token.EOF); }
			@Override public int getLine() { lineQueries[0]++; return 99; }
			@Override public int getCharPositionInLine() { lineQueries[0]++; return 99; }
			@Override public CharStream getInputStream() { return null; }
			@Override public String getSourceName() { return "probe"; }
			@Override public TokenFactory getTokenFactory() { return CommonTokenFactory.DEFAULT; }
			@Override public void setTokenFactory(TokenFactory factory) { }
		};
		CharStream input = CharStreams.fromString("z");
		CommonToken t = new CommonToken(Tuple.create(src, input), 1, Token.DEFAULT_CHANNEL, 0, 0, 12, 3);
		assertEquals(12, t.getLine());
		assertEquals(3, t.getCharPositionInLine());
		assertEquals(0, lineQueries[0]);
	}

	@Test
	public void sourceConstructorCopiesLineAndColumn() {
		TokenSource src = new MockTokenSource();
		CharStream input = CharStreams.fromString("abc");
		CommonToken t = new CommonToken(Tuple.create(src, input), 1, Token.DEFAULT_CHANNEL, 0, 2);
		assertEquals(1, t.getType());
		assertEquals(1, t.getLine());
		assertEquals(0, t.getCharPositionInLine());
		assertEquals(0, t.getStartIndex());
		assertEquals(2, t.getStopIndex());
		assertEquals("abc", t.getText());
		assertSame(src, t.getTokenSource());
		assertSame(input, t.getInputStream());
	}

	@Test
	public void getTextFromInputWhenTextNotSet() {
		CharStream input = CharStreams.fromString("hello world");
		CommonToken t = new CommonToken(Tuple.create((TokenSource) null, input), 1, 0, 0, 4);
		assertEquals("hello", t.getText());
	}

	@Test
	public void getTextReturnsEofMarkerWhenIndexesPastEnd() {
		CharStream input = CharStreams.fromString("ab");
		CommonToken t = new CommonToken(Tuple.create((TokenSource) null, input), Token.EOF, 0, 10, 10);
		assertEquals("<EOF>", t.getText());
	}

	@Test
	public void setTextOverridesInputExtraction() {
		CharStream input = CharStreams.fromString("abc");
		CommonToken t = new CommonToken(Tuple.create((TokenSource) null, input), 1, 0, 0, 2);
		t.setText("xyz");
		assertEquals("xyz", t.getText());
	}

	@Test
	public void copyConstructorFromCommonTokenSharesTextAndSource() {
		CommonToken original = new CommonToken(3, "tok");
		original.setLine(2);
		original.setCharPositionInLine(4);
		original.setChannel(Token.HIDDEN_CHANNEL);
		original.setTokenIndex(7);
		original.setStartIndex(1);
		original.setStopIndex(3);

		CommonToken copy = new CommonToken(original);
		assertEquals(3, copy.getType());
		assertEquals("tok", copy.getText());
		assertEquals(2, copy.getLine());
		assertEquals(4, copy.getCharPositionInLine());
		assertEquals(Token.HIDDEN_CHANNEL, copy.getChannel());
		assertEquals(7, copy.getTokenIndex());
		assertEquals(1, copy.getStartIndex());
		assertEquals(3, copy.getStopIndex());
	}

	@Test
	public void copyConstructorFromNonCommonToken() {
		Token other = new Token() {
			@Override public String getText() { return "x"; }
			@Override public int getType() { return 9; }
			@Override public int getLine() { return 3; }
			@Override public int getCharPositionInLine() { return 1; }
			@Override public int getChannel() { return 0; }
			@Override public int getTokenIndex() { return 2; }
			@Override public int getStartIndex() { return 0; }
			@Override public int getStopIndex() { return 0; }
			@Override public TokenSource getTokenSource() { return null; }
			@Override public CharStream getInputStream() { return null; }
		};
		CommonToken copy = new CommonToken(other);
		assertEquals(9, copy.getType());
		assertEquals("x", copy.getText());
		assertEquals(3, copy.getLine());
		assertEquals(1, copy.getCharPositionInLine());
		assertEquals(2, copy.getTokenIndex());
	}

	@Test
	public void settersAndToString() {
		CommonToken t = new CommonToken(1, "a\nb\tc\rd");
		t.setType(2);
		t.setLine(5);
		t.setCharPositionInLine(8);
		t.setChannel(3);
		t.setTokenIndex(4);
		t.setStartIndex(0);
		t.setStopIndex(1);

		assertEquals(2, t.getType());
		assertEquals(5, t.getLine());
		assertEquals(8, t.getCharPositionInLine());
		assertEquals(3, t.getChannel());
		assertEquals(4, t.getTokenIndex());

		String s = t.toString();
		assertTrue(s.contains("channel=3"));
		assertTrue(s.contains("\\n"));
		assertTrue(s.contains("\\t"));
		assertTrue(s.contains("\\r"));
		assertTrue(s.startsWith("[@4,"));
	}

	@Test
	public void toStringWithNoText() {
		CommonToken t = new CommonToken(1);
		assertTrue(t.toString().contains("<no text>"));
	}
}
