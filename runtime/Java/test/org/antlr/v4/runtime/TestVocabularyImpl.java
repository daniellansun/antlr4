/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class TestVocabularyImpl {
	@Test
	public void emptyVocabulary() {
		Vocabulary v = VocabularyImpl.EMPTY_VOCABULARY;
		assertNotNull(v);
		assertEquals("EOF", v.getSymbolicName(Token.EOF));
		assertEquals("0", v.getDisplayName(Token.INVALID_TYPE));
		assertNull(v.getLiteralName(1));
		assertNull(v.getSymbolicName(1));
		assertEquals("EOF", v.getDisplayName(Token.EOF));
		assertEquals(-1, v.getMaxTokenType());
	}

	@Test
	public void fromLiteralAndSymbolicNames() {
		String[] literals = { null, "'+'", "'-'" };
		String[] symbols = { null, "PLUS", "MINUS" };
		VocabularyImpl v = new VocabularyImpl(literals, symbols);
		assertEquals(2, v.getMaxTokenType());
		assertEquals("'+'", v.getLiteralName(1));
		assertEquals("PLUS", v.getSymbolicName(1));
		// display prefers literal name when present
		assertEquals("'+'", v.getDisplayName(1));
		assertEquals("'-'", v.getDisplayName(2));
		assertEquals("MINUS", v.getSymbolicName(2));
		assertEquals("EOF", v.getSymbolicName(Token.EOF));
	}

	@Test
	public void displayNamesOverride() {
		String[] literals = { null, "'x'" };
		String[] symbols = { null, "X" };
		String[] display = { null, "X-token" };
		VocabularyImpl v = new VocabularyImpl(literals, symbols, display);
		assertEquals("X-token", v.getDisplayName(1));
	}

	@Test
	public void nullNameArraysBecomeEmpty() {
		VocabularyImpl v = new VocabularyImpl(null, null, null);
		assertEquals(-1, v.getMaxTokenType());
		assertNull(v.getLiteralName(0));
		assertNull(v.getSymbolicName(0));
		assertEquals("0", v.getDisplayName(0));
	}

	@Test
	public void fromTokenNamesClassifiesNames() {
		String[] tokenNames = {
			"<INVALID>",
			"ID",
			"'//'",
			"rule_ref",
			null
		};
		Vocabulary vocabulary = VocabularyImpl.fromTokenNames(tokenNames);
		assertNotNull(vocabulary);
		assertEquals("EOF", vocabulary.getSymbolicName(Token.EOF));
		assertEquals("ID", vocabulary.getSymbolicName(1));
		assertNull(vocabulary.getLiteralName(1));
		assertEquals("'//'", vocabulary.getLiteralName(2));
		assertNull(vocabulary.getSymbolicName(2));
		assertNull(vocabulary.getLiteralName(3));
		assertNull(vocabulary.getSymbolicName(3));
		assertEquals("rule_ref", vocabulary.getDisplayName(3));
		assertEquals("<INVALID>", vocabulary.getDisplayName(0));
	}

	@Test
	public void fromTokenNamesNullOrEmptyReturnsEmptyVocabulary() {
		assertSame(VocabularyImpl.EMPTY_VOCABULARY, VocabularyImpl.fromTokenNames(null));
		assertSame(VocabularyImpl.EMPTY_VOCABULARY, VocabularyImpl.fromTokenNames(new String[0]));
	}

	@Test
	public void displayNameFallsBackToTypeNumber() {
		VocabularyImpl v = new VocabularyImpl(new String[]{null}, new String[]{null});
		assertEquals("99", v.getDisplayName(99));
	}
}
