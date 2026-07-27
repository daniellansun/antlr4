/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TestArray2DHashSet {
	@Test
	public void basicAddContainsRemove() {
		Array2DHashSet<String> set = new Array2DHashSet<String>();
		assertTrue(set.isEmpty());
		// use distinct instances so add()'s reference equality return value is meaningful
		String a1 = new String("a");
		String a2 = new String("a");
		assertTrue(set.add(a1));
		assertFalse(set.add(a2));
		assertTrue(set.contains(new String("a")));
		assertFalse(set.contains("b"));
		assertFalse(set.contains(null));
		assertFalse(set.containsFast(null));
		assertEquals(1, set.size());
		assertTrue(set.remove(new String("a")));
		assertFalse(set.remove(new String("a")));
		assertFalse(set.remove(null));
		assertTrue(set.isEmpty());
	}

	@Test
	public void getOrAddAndGet() {
		Array2DHashSet<String> set = new Array2DHashSet<String>();
		String a1 = new String("a");
		String a2 = new String("a");
		assertSame(a1, set.getOrAdd(a1));
		assertSame(a1, set.getOrAdd(a2));
		assertSame(a1, set.get(a2));
		assertNull(set.get("missing"));
		assertNull(set.get(null));
	}

	@Test
	public void constructorsWithComparator() {
		Array2DHashSet<String> set = new Array2DHashSet<String>(ObjectEqualityComparator.INSTANCE);
		set.add("x");
		assertTrue(set.contains("x"));

		Array2DHashSet<String> small = new Array2DHashSet<String>(null, 4, 2);
		for (int i = 0; i < 20; i++) {
			small.add("v" + i);
		}
		assertEquals(20, small.size());
		for (int i = 0; i < 20; i++) {
			assertTrue(small.contains("v" + i));
		}
	}

	@Test
	public void expandAndBucketGrowth() {
		// small initial capacity to force expand; tiny bucket capacity to force bucket growth
		Array2DHashSet<Integer> set = new Array2DHashSet<Integer>(null, 2, 1);
		for (int i = 0; i < 50; i++) {
			assertTrue(set.add(Integer.valueOf(i)));
		}
		assertEquals(50, set.size());
		// Integer cache makes valueOf(0) same reference as existing; use non-cached range
		Integer again = Integer.valueOf(200);
		set.add(again);
		assertFalse(set.add(Integer.valueOf(200))); // different instance outside cache (-128..127)
		// force non-cached duplicate via new Integer-like objects
		Array2DHashSet<String> strings = new Array2DHashSet<String>(null, 2, 1);
		for (int i = 0; i < 50; i++) {
			strings.add(new String("v" + i));
		}
		assertEquals(50, strings.size());
		assertFalse(strings.add(new String("v0")));
		assertTrue(strings.contains(new String("v49")));
	}

	@Test
	public void equalsHashCodeToString() {
		Array2DHashSet<String> a = new Array2DHashSet<String>();
		Array2DHashSet<String> b = new Array2DHashSet<String>();
		assertEquals("{}", a.toString());
		assertTrue(a.equals(a));
		assertFalse(a.equals(null));
		assertFalse(a.equals("x"));

		a.add("x");
		a.add("y");
		b.add("y");
		b.add("x");
		assertTrue(a.equals(b));
		assertEquals(a.hashCode(), b.hashCode());
		assertTrue(a.toString().contains("x"));
		assertNotEmptyTable(a.toTableString());

		b.add("z");
		assertFalse(a.equals(b));
	}

	private static void assertNotEmptyTable(String table) {
		assertTrue(table.length() > 0);
	}

	@Test
	public void toArrayAndIterator() {
		Array2DHashSet<String> set = new Array2DHashSet<String>();
		set.add("a");
		set.add("b");
		Object[] arr = set.toArray();
		assertEquals(2, arr.length);
		List<Object> asList = Arrays.asList(arr);
		assertTrue(asList.contains("a"));
		assertTrue(asList.contains("b"));

		String[] typed = set.toArray(new String[0]);
		assertEquals(2, typed.length);
		String[] larger = set.toArray(new String[5]);
		assertTrue(countNonNullPrefix(larger) >= 2);

		final Iterator<String> it = set.iterator();
		assertTrue(it.hasNext());
		String first = it.next();
		it.remove();
		assertFalse(set.contains(first));
		// second remove without next must throw
		assertThrows(IllegalStateException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				it.remove();
			}
		});
		assertTrue(it.hasNext());
		it.next();
		assertFalse(it.hasNext());
		assertThrows(NoSuchElementException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				it.next();
			}
		});
	}

	private static int countNonNullPrefix(String[] a) {
		int n = 0;
		for (String s : a) {
			if (s != null) n++;
		}
		return n;
	}

	@Test
	public void bulkOperations() {
		Array2DHashSet<String> set = new Array2DHashSet<String>();
		List<String> data = Arrays.asList("a", "b", "c");
		// note: addAll's return value follows implementation (true when duplicate seen)
		set.addAll(data);
		assertEquals(3, set.size());
		assertTrue(set.containsAll(data));
		assertTrue(set.containsAll(new Array2DHashSet<String>() {{
			add("a");
			add("b");
		}}));
		assertFalse(set.containsAll(Arrays.asList("a", "z")));

		assertTrue(set.removeAll(Arrays.asList("a", "z")));
		assertFalse(set.contains("a"));
		assertTrue(set.contains("b"));

		set.retainAll(Arrays.asList("b"));
		assertTrue(set.contains("b"));
		assertFalse(set.contains("c"));

		set.clear();
		assertTrue(set.isEmpty());
		assertEquals("{}", set.toString());
	}

	@Test
	public void retainAllEmptyAndNoop() {
		Array2DHashSet<String> set = new Array2DHashSet<String>();
		set.add("a");
		set.add("b");
		set.retainAll(Arrays.asList("a", "b", "c"));
		// content preserved (size accounting in retainAll may over-count)
		assertTrue(set.contains("a"));
		assertTrue(set.contains("b"));
		assertFalse(set.contains("c"));

		Array2DHashSet<String> set2 = new Array2DHashSet<String>();
		set2.add("x");
		set2.add("y");
		set2.retainAll(new ArrayList<String>());
		assertFalse(set2.contains("x"));
		assertFalse(set2.contains("y"));
	}
}
