/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestObjectEqualityComparator {
	@Test
	public void hashCodeNullIsZero() {
		assertEquals(0, ObjectEqualityComparator.INSTANCE.hashCode(null));
		assertEquals("abc".hashCode(), ObjectEqualityComparator.INSTANCE.hashCode("abc"));
	}

	@Test
	public void equalsHandlesNulls() {
		ObjectEqualityComparator c = ObjectEqualityComparator.INSTANCE;
		assertTrue(c.equals(null, null));
		assertFalse(c.equals(null, "a"));
		assertFalse(c.equals("a", null));
		assertTrue(c.equals("a", "a"));
		assertFalse(c.equals("a", "b"));
		assertTrue(c.equals(Integer.valueOf(1), Integer.valueOf(1)));
	}
}
