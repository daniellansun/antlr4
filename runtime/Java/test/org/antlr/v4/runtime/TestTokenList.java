/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime;

import org.junit.Test;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.RandomAccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Full {@link java.util.List} + {@link RandomAccess} contract and chunk-boundary
 * unit tests for package-private {@link TokenList}.
 */
public class TestTokenList {

	private static CommonToken tok(int type, String text) {
		return new CommonToken(type, text);
	}

	@Test
	public void testListContractChunkBoundariesAndClearModes() {
		TokenList list = new TokenList();
		assertTrue(list instanceof RandomAccess);
		assertTrue(list.isEmpty());
		assertEquals(0, list.size());
		list.clear(); // chunks == null
		assertTrue(list.isEmpty());

		try { list.get(0); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.get(-1); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.set(0, tok(1, "x")); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.set(-1, tok(1, "x")); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.add(-1, tok(1, "x")); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.add(1, tok(1, "x")); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.remove(-1); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.remove(0); fail(); } catch (IndexOutOfBoundsException expected) {}

		// Append across >4 chunks (>1024 elements) to grow directory
		int total = 1100;
		for (int i = 0; i < total; i++) {
			list.add(tok(i + 1, "t" + i));
		}
		assertFalse(list.isEmpty());
		assertEquals(total, list.size());
		assertEquals("t0", list.get(0).getText());
		assertEquals("t255", list.get(255).getText());
		assertEquals("t256", list.get(256).getText());
		assertEquals("t1099", list.get(1099).getText());

		// set() in place
		Token old = list.set(256, tok(999, "replaced"));
		assertEquals("t256", old.getText());
		assertEquals("replaced", list.get(256).getText());

		// add(index, element) at end, start, and middle across chunk boundaries
		list.add(list.size(), tok(2000, "tail"));
		assertEquals("tail", list.get(list.size() - 1).getText());
		list.add(0, tok(2001, "head"));
		assertEquals("head", list.get(0).getText());
		assertEquals("t0", list.get(1).getText());
		list.add(257, tok(2002, "mid"));
		assertEquals("mid", list.get(257).getText());
		assertEquals("replaced", list.get(258).getText());

		// remove(index) at start, middle, and tail
		assertEquals("head", list.remove(0).getText());
		assertEquals("mid", list.remove(256).getText());
		assertEquals("tail", list.remove(list.size() - 1).getText());
		assertEquals(total, list.size());

		// clear() when directory grew past INITIAL_CHUNK_DIR releases chunks
		list.clear();
		assertTrue(list.isEmpty());
		assertEquals(0, list.size());

		// Small multi-chunk clear (<= INITIAL_CHUNK_DIR chunks, >1 chunk)
		for (int i = 0; i < 300; i++) {
			list.appendFast(tok(1, "s" + i));
		}
		assertEquals(300, list.size());
		list.clear();
		assertTrue(list.isEmpty());

		// Single non-empty chunk clear + idempotent clear when size == 0 & chunks != null
		list.appendFast(tok(1, "only"));
		list.clear();
		list.clear();
		assertTrue(list.isEmpty());

		// Remove down to empty list
		list.appendFast(tok(1, "one"));
		assertEquals("one", list.remove(0).getText());
		assertTrue(list.isEmpty());

		// Fail-fast iterator detects appendFast structural modification
		list.appendFast(tok(1, "a"));
		Iterator<Token> it = list.iterator();
		assertEquals("a", it.next().getText());
		list.appendFast(tok(2, "b"));
		try {
			it.next();
			fail("Expected ConcurrentModificationException");
		}
		catch (ConcurrentModificationException expected) {
		}
	}
}
