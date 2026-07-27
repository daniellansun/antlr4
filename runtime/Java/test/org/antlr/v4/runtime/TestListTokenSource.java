/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestListTokenSource {
	@Test(expected = NullPointerException.class)
	public void nullTokensThrows() {
		new ListTokenSource(null);
	}

	@Test
	public void nextTokenFromList() {
		CommonToken a = new CommonToken(1, "a");
		a.setStartIndex(0);
		a.setStopIndex(0);
		a.setLine(1);
		a.setCharPositionInLine(0);
		CommonToken b = new CommonToken(2, "b");
		b.setStartIndex(1);
		b.setStopIndex(1);
		b.setLine(1);
		b.setCharPositionInLine(1);

		ListTokenSource source = new ListTokenSource(Arrays.asList(a, b), "src");
		assertEquals("a", source.nextToken().getText());
		assertEquals("b", source.nextToken().getText());
		Token eof = source.nextToken();
		assertEquals(Token.EOF, eof.getType());
		assertEquals("EOF", eof.getText());
		// subsequent calls return same EOF
		assertSame(eof, source.nextToken());
	}

	@Test
	public void listEndingWithEofUsesThatToken() {
		CommonToken a = new CommonToken(1, "a");
		a.setStartIndex(0);
		a.setStopIndex(0);
		CommonToken eof = new CommonToken(Token.EOF, "EOF");
		eof.setStartIndex(1);
		eof.setStopIndex(0);
		ListTokenSource source = new ListTokenSource(Arrays.asList(a, eof));
		assertEquals("a", source.nextToken().getText());
		Token end = source.nextToken();
		assertSame(eof, end);
		assertSame(eof, source.nextToken());
	}

	@Test
	public void emptyListProducesEofAtStart() {
		ListTokenSource source = new ListTokenSource(Collections.<Token>emptyList());
		Token eof = source.nextToken();
		assertEquals(Token.EOF, eof.getType());
		assertEquals(1, source.getLine());
		assertEquals(0, source.getCharPositionInLine());
	}

	@Test
	public void sourceNameExplicitAndDefault() {
		ListTokenSource named = new ListTokenSource(Collections.<Token>emptyList(), "file.g4");
		assertEquals("file.g4", named.getSourceName());

		ListTokenSource unnamed = new ListTokenSource(Collections.<Token>emptyList());
		assertEquals("List", unnamed.getSourceName());
	}

	@Test
	public void lineAndColumnAfterLastToken() {
		CommonToken t = new CommonToken(1, "ab\ncd");
		t.setLine(2);
		t.setCharPositionInLine(3);
		t.setStartIndex(0);
		t.setStopIndex(4);
		ListTokenSource source = new ListTokenSource(Collections.<Token>singletonList(t));
		source.nextToken(); // consume the only token
		// after list exhausted: line counts newlines in last token text
		assertEquals(3, source.getLine());
		assertEquals(2, source.getCharPositionInLine()); // after last \n: "cd"
	}

	@Test
	public void columnWithoutNewlineInLastToken() {
		CommonToken t = new CommonToken(1, "abc");
		t.setLine(1);
		t.setCharPositionInLine(5);
		t.setStartIndex(0);
		t.setStopIndex(2);
		ListTokenSource source = new ListTokenSource(Collections.<Token>singletonList(t));
		source.nextToken();
		assertEquals(1, source.getLine());
		assertEquals(5 + 2 - 0 + 1, source.getCharPositionInLine());
	}

	@Test
	public void tokenFactoryRoundTrip() {
		ListTokenSource source = new ListTokenSource(new ArrayList<Token>());
		assertTrue(source.getTokenFactory() instanceof CommonTokenFactory);
		CommonTokenFactory custom = new CommonTokenFactory(true);
		source.setTokenFactory(custom);
		assertSame(custom, source.getTokenFactory());
	}

	@Test
	public void getInputStreamFromTokens() {
		CharStream input = CharStreams.fromString("x");
		CommonToken t = new CommonToken(org.antlr.v4.runtime.misc.Tuple.create(null, input), 1, 0, 0, 0);
		t.setText("x");
		ListTokenSource source = new ListTokenSource(Collections.<Token>singletonList(t));
		assertSame(input, source.getInputStream());
		source.nextToken();
		assertSame(input, source.getInputStream());
	}

	@Test
	public void emptyListHasNullInputStream() {
		ListTokenSource source = new ListTokenSource(Collections.<Token>emptyList());
		assertNull(source.getInputStream());
	}
}
