/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.misc.Tuple;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class TestCommonTokenFactory {
	@Test
	public void defaultFactoryDoesNotCopyText() {
		TokenFactory factory = CommonTokenFactory.DEFAULT;
		CharStream input = CharStreams.fromString("hello");
		TokenSource source = new MockTokenSource();
		CommonToken t = (CommonToken) factory.create(
			Tuple.create(source, input), 1, null,
			Token.DEFAULT_CHANNEL, 0, 4, 1, 0);
		assertEquals("hello", t.getText());
		assertEquals(1, t.getLine());
		assertEquals(0, t.getCharPositionInLine());
		assertEquals(1, t.getType());
		assertSame(source, t.getTokenSource());
		assertSame(input, t.getInputStream());
	}

	@Test
	public void createWithExplicitText() {
		CommonTokenFactory factory = new CommonTokenFactory();
		CommonToken t = factory.create(
			Tuple.create((TokenSource) null, (CharStream) null), 2, "explicit",
			Token.HIDDEN_CHANNEL, 0, 0, 2, 3);
		assertEquals("explicit", t.getText());
		assertEquals(Token.HIDDEN_CHANNEL, t.getChannel());
		assertEquals(2, t.getLine());
		assertEquals(3, t.getCharPositionInLine());
	}

	@Test
	public void copyTextTrueCopiesFromStream() {
		CommonTokenFactory factory = new CommonTokenFactory(true);
		CharStream input = CharStreams.fromString("abcdef");
		CommonToken t = factory.create(
			Tuple.create((TokenSource) null, input), 1, null,
			Token.DEFAULT_CHANNEL, 1, 3, 1, 1);
		assertEquals("bcd", t.getText());
		// text was explicitly set via copy
		t.setStartIndex(0);
		t.setStopIndex(0);
		assertEquals("bcd", t.getText());
	}

	@Test
	public void copyTextFalseLeavesTextNullWhenNoTextProvided() {
		CommonTokenFactory factory = new CommonTokenFactory(false);
		CharStream input = CharStreams.fromString("abc");
		CommonToken t = factory.create(
			Tuple.create((TokenSource) null, input), 1, null,
			Token.DEFAULT_CHANNEL, 0, 2, 1, 0);
		// text extracted from stream on demand
		assertEquals("abc", t.getText());
	}

	@Test
	public void createTypeAndText() {
		CommonTokenFactory factory = new CommonTokenFactory();
		CommonToken t = factory.create(7, "seven");
		assertEquals(7, t.getType());
		assertEquals("seven", t.getText());
		assertNull(t.getTokenSource());
	}

	@Test
	public void defaultInstanceIsCommonTokenFactory() {
		assertNotNull(CommonTokenFactory.DEFAULT);
		assertEquals(CommonToken.class, CommonTokenFactory.DEFAULT.create(1, "x").getClass());
	}
}
