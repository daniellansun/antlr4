/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TestTuple {
	@Test
	public void createTuple2() {
		Tuple2<String, Integer> t = Tuple.create("a", 1);
		assertEquals("a", t.getItem1());
		assertEquals(Integer.valueOf(1), t.getItem2());
		assertEquals("(a, 1)", t.toString());
	}

	@Test
	public void createTuple3() {
		Tuple3<String, Integer, Boolean> t = Tuple.create("a", 1, Boolean.TRUE);
		assertEquals("a", t.getItem1());
		assertEquals(Integer.valueOf(1), t.getItem2());
		assertEquals(Boolean.TRUE, t.getItem3());
		assertEquals("(a, 1, true)", t.toString());
	}

	@Test
	public void tuple2EqualsAndHashCode() {
		Tuple2<String, Integer> a = Tuple.create("x", 2);
		Tuple2<String, Integer> b = Tuple.create("x", 2);
		Tuple2<String, Integer> c = Tuple.create("x", 3);
		Tuple2<String, Integer> d = Tuple.create(null, null);

		assertTrue(a.equals(a));
		assertTrue(a.equals(b));
		assertEquals(a.hashCode(), b.hashCode());
		assertFalse(a.equals(c));
		assertFalse(a.equals(null));
		assertFalse(a.equals("x"));
		assertTrue(d.equals(Tuple.create(null, null)));
		assertEquals(d.hashCode(), Tuple.create(null, null).hashCode());
		assertFalse(d.equals(a));
		assertNotEquals(a.hashCode(), c.hashCode());
	}

	@Test
	public void tuple3EqualsAndHashCode() {
		Tuple3<String, Integer, String> a = Tuple.create("x", 2, "z");
		Tuple3<String, Integer, String> b = Tuple.create("x", 2, "z");
		Tuple3<String, Integer, String> c = Tuple.create("x", 2, "y");
		Tuple3<String, Integer, String> d = Tuple.create(null, null, null);

		assertTrue(a.equals(a));
		assertTrue(a.equals(b));
		assertEquals(a.hashCode(), b.hashCode());
		assertFalse(a.equals(c));
		assertFalse(a.equals(null));
		assertFalse(a.equals("x"));
		assertFalse(a.equals(Tuple.create("x", 2)));
		assertTrue(d.equals(Tuple.create(null, null, null)));
		assertNull(d.getItem1());
		assertNull(d.getItem2());
		assertNull(d.getItem3());
	}

	@Test
	public void tupleEqualsPackageMethodViaNullItems() {
		// Tuple.equals is package-private; covered through Tuple2/3 with nulls
		Tuple2<String, String> t1 = new Tuple2<String, String>(null, "b");
		Tuple2<String, String> t2 = new Tuple2<String, String>(null, "b");
		Tuple2<String, String> t3 = new Tuple2<String, String>("a", null);
		assertTrue(t1.equals(t2));
		assertFalse(t1.equals(t3));
	}
}
