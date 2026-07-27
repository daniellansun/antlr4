/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import java.util.Arrays;
import java.util.Iterator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TestOrderedHashSet {
	@Test
	public void preservesInsertionOrder() {
		OrderedHashSet<String> set = new OrderedHashSet<String>();
		assertTrue(set.add("c"));
		assertTrue(set.add("a"));
		assertTrue(set.add("b"));
		assertFalse(set.add("a"));
		assertEquals(Arrays.asList("c", "a", "b"), set.elements());
		assertEquals("c", set.get(0));
		assertEquals("a", set.get(1));
		assertEquals("b", set.get(2));
		assertArrayEquals(new Object[] { "c", "a", "b" }, set.toArray());
		assertEquals("[c, a, b]", set.toString());
	}

	@Test
	public void setReplacesElement() {
		OrderedHashSet<String> set = new OrderedHashSet<String>();
		set.add("a");
		set.add("b");
		assertEquals("a", set.set(0, "z"));
		assertEquals("z", set.get(0));
		assertTrue(set.contains("z"));
		assertFalse(set.contains("a"));
	}

	@Test
	public void removeByIndex() {
		OrderedHashSet<String> set = new OrderedHashSet<String>();
		set.add("a");
		set.add("b");
		set.add("c");
		assertTrue(set.remove(1));
		assertEquals(Arrays.asList("a", "c"), set.elements());
	}

	@Test
	public void removeObjectUnsupported() {
		final OrderedHashSet<String> set = new OrderedHashSet<String>();
		set.add("a");
		assertThrows(UnsupportedOperationException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				set.remove("a");
			}
		});
	}

	@Test
	public void clearCloneEqualsHash() {
		OrderedHashSet<String> set = new OrderedHashSet<String>();
		set.add("x");
		set.add("y");
		OrderedHashSet<String> other = new OrderedHashSet<String>();
		other.add("x");
		other.add("y");
		assertTrue(set.equals(other));
		assertEquals(set.hashCode(), other.hashCode());
		assertFalse(set.equals("x"));
		assertFalse(set.equals(null));

		@SuppressWarnings("unchecked")
		OrderedHashSet<String> clone = (OrderedHashSet<String>) set.clone();
		assertEquals(set, clone);
		clone.set(0, "z");
		assertFalse(set.equals(clone));

		set.clear();
		assertTrue(set.isEmpty());
		assertEquals("[]", set.toString());
	}

	@Test
	public void iteratorFollowsListOrder() {
		OrderedHashSet<String> set = new OrderedHashSet<String>();
		set.add("1");
		set.add("2");
		Iterator<String> it = set.iterator();
		assertEquals("1", it.next());
		assertEquals("2", it.next());
		assertFalse(it.hasNext());
	}
}
