/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for empty-fast-path clear on the retained precedence-filter map.
 */
public class TestClearableIntObjectHashMap {

	@Test
	public void emptyClearIsIdempotent() {
		ClearableIntObjectHashMap<String> map = new ClearableIntObjectHashMap<String>();
		map.clear();
		map.clear();
		assertTrue(map.isEmpty());
	}

	@Test
	public void putGetAndClear() {
		ClearableIntObjectHashMap<String> map = new ClearableIntObjectHashMap<String>(8);
		map.put(1, "a");
		map.put(2, "b");
		assertEquals("a", map.get(1));
		assertEquals("b", map.get(2));
		map.clear();
		assertNull(map.get(1));
		assertTrue(map.isEmpty());
		// Reuse after clear
		map.put(3, "c");
		assertEquals("c", map.get(3));
	}
}
