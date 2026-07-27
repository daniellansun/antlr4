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
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TestIntegerList {
	@Test
	public void defaultConstructorIsEmpty() {
		IntegerList list = new IntegerList();
		assertTrue(list.isEmpty());
		assertEquals(0, list.size());
		assertArrayEquals(new int[0], list.toArray());
		assertEquals("[]", list.toString());
	}

	@Test
	public void capacityConstructor() {
		IntegerList emptyCap = new IntegerList(0);
		assertTrue(emptyCap.isEmpty());

		IntegerList list = new IntegerList(2);
		list.add(1);
		list.add(2);
		list.add(3); // force grow
		assertEquals(3, list.size());
		assertArrayEquals(new int[] { 1, 2, 3 }, list.toArray());
	}

	@Test
	public void negativeCapacityThrows() {
		assertThrows(IllegalArgumentException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				new IntegerList(-1);
			}
		});
	}

	@Test
	public void copyConstructors() {
		IntegerList src = new IntegerList();
		src.add(7);
		src.add(8);
		IntegerList copy = new IntegerList(src);
		assertEquals(src, copy);
		copy.add(9);
		assertEquals(2, src.size());

		List<Integer> coll = new ArrayList<Integer>();
		coll.add(1);
		coll.add(2);
		IntegerList fromColl = new IntegerList(coll);
		assertArrayEquals(new int[] { 1, 2 }, fromColl.toArray());
	}

	@Test
	public void addAllVariants() {
		IntegerList list = new IntegerList();
		list.addAll(new int[] { 1, 2 });
		IntegerList other = new IntegerList();
		other.add(3);
		list.addAll(other);
		list.addAll(Arrays.asList(4, 5));
		assertArrayEquals(new int[] { 1, 2, 3, 4, 5 }, list.toArray());
	}

	@Test
	public void getSetContains() {
		IntegerList list = new IntegerList();
		list.add(10);
		list.add(20);
		assertEquals(10, list.get(0));
		assertEquals(20, list.get(1));
		assertTrue(list.contains(20));
		assertFalse(list.contains(99));
		assertEquals(10, list.set(0, 11));
		assertEquals(11, list.get(0));
	}

	@Test
	public void getOutOfBounds() {
		final IntegerList list = new IntegerList();
		list.add(1);
		assertThrows(IndexOutOfBoundsException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				list.get(-1);
			}
		});
		assertThrows(IndexOutOfBoundsException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				list.get(1);
			}
		});
		assertThrows(IndexOutOfBoundsException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				list.set(2, 0);
			}
		});
	}

	@Test
	public void removeAtAndRange() {
		IntegerList list = new IntegerList();
		list.addAll(new int[] { 1, 2, 3, 4, 5 });
		assertEquals(3, list.removeAt(2));
		assertArrayEquals(new int[] { 1, 2, 4, 5 }, list.toArray());

		list.removeRange(1, 3);
		assertArrayEquals(new int[] { 1, 5 }, list.toArray());

		list.removeRange(0, 0); // empty range
		assertArrayEquals(new int[] { 1, 5 }, list.toArray());
	}

	@Test
	public void removeRangeErrors() {
		final IntegerList list = new IntegerList();
		list.addAll(new int[] { 1, 2, 3 });
		assertThrows(IndexOutOfBoundsException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				list.removeRange(-1, 1);
			}
		});
		assertThrows(IndexOutOfBoundsException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				list.removeRange(0, 4);
			}
		});
		assertThrows(IllegalArgumentException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				list.removeRange(2, 1);
			}
		});
	}

	@Test
	public void trimClearSortBinarySearch() {
		IntegerList list = new IntegerList(10);
		list.addAll(new int[] { 3, 1, 2 });
		list.trimToSize();
		list.trimToSize(); // no-op when already sized
		list.sort();
		assertArrayEquals(new int[] { 1, 2, 3 }, list.toArray());
		assertEquals(1, list.binarySearch(2));
		assertTrue(list.binarySearch(9) < 0);
		assertEquals(0, list.binarySearch(0, 2, 1));
		assertTrue(list.binarySearch(0, 2, 3) < 0);

		list.clear();
		assertTrue(list.isEmpty());
		assertArrayEquals(new int[0], list.toArray());
	}

	@Test
	public void binarySearchRangeErrors() {
		final IntegerList list = new IntegerList();
		list.addAll(new int[] { 1, 2, 3 });
		assertThrows(IndexOutOfBoundsException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				list.binarySearch(-1, 2, 1);
			}
		});
		assertThrows(IndexOutOfBoundsException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				list.binarySearch(0, 4, 1);
			}
		});
		assertThrows(IllegalArgumentException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				list.binarySearch(2, 1, 1);
			}
		});
	}

	@Test
	public void equalsHashCode() {
		IntegerList a = new IntegerList();
		a.addAll(new int[] { 1, 2 });
		IntegerList b = new IntegerList();
		b.addAll(new int[] { 1, 2 });
		IntegerList c = new IntegerList();
		c.add(1);
		IntegerList d = new IntegerList();
		d.addAll(new int[] { 1, 3 });

		assertTrue(a.equals(a));
		assertTrue(a.equals(b));
		assertEquals(a.hashCode(), b.hashCode());
		assertFalse(a.equals(c));
		assertFalse(a.equals(d));
		assertFalse(a.equals(null));
		assertFalse(a.equals(Arrays.asList(1, 2)));
	}
}
