/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestInterval {
	@Test
	public void ofPoolsSinglePointIntervals() {
		Interval a = Interval.of(5, 5);
		Interval b = Interval.of(5, 5);
		assertSame(a, b);
		assertEquals(5, a.a);
		assertEquals(5, a.b);
	}

	@Test
	public void ofDoesNotPoolRangesOrOutOfRangeSingles() {
		Interval range = Interval.of(1, 2);
		assertEquals(1, range.a);
		assertEquals(2, range.b);

		Interval negative = Interval.of(-1, -1);
		assertEquals(-1, negative.a);

		Interval large = Interval.of(Interval.INTERVAL_POOL_MAX_VALUE + 1,
			Interval.INTERVAL_POOL_MAX_VALUE + 1);
		assertEquals(Interval.INTERVAL_POOL_MAX_VALUE + 1, large.a);

		Interval boundary = Interval.of(0, 0);
		assertSame(boundary, Interval.of(0, 0));

		Interval maxPooled = Interval.of(Interval.INTERVAL_POOL_MAX_VALUE, Interval.INTERVAL_POOL_MAX_VALUE);
		assertSame(maxPooled, Interval.of(Interval.INTERVAL_POOL_MAX_VALUE, Interval.INTERVAL_POOL_MAX_VALUE));
	}

	@Test
	public void length() {
		assertEquals(1, Interval.of(3, 3).length());
		assertEquals(2, Interval.of(9, 10).length());
		assertEquals(0, Interval.of(5, 4).length());
		assertEquals(0, Interval.INVALID.length());
	}

	@Test
	public void equalsAndHashCode() {
		Interval a = new Interval(1, 3);
		Interval b = Interval.of(1, 3);
		Interval c = Interval.of(1, 4);
		assertTrue(a.equals(a));
		assertTrue(a.equals(b));
		assertEquals(a.hashCode(), b.hashCode());
		assertFalse(a.equals(c));
		assertFalse(a.equals(null));
		assertFalse(a.equals("1..3"));
	}

	@Test
	public void startsBeforeAndAfterRelations() {
		Interval left = Interval.of(1, 3);
		Interval right = Interval.of(5, 7);
		Interval overlap = Interval.of(3, 6);
		Interval inside = Interval.of(2, 2);

		assertTrue(left.startsBeforeDisjoint(right));
		assertFalse(left.startsBeforeDisjoint(overlap));
		assertTrue(left.startsBeforeNonDisjoint(overlap));
		assertTrue(left.startsBeforeNonDisjoint(inside));

		assertTrue(right.startsAfter(left));
		assertTrue(right.startsAfterDisjoint(left));
		assertFalse(overlap.startsAfterDisjoint(left));
		assertTrue(overlap.startsAfterNonDisjoint(left));
		assertFalse(left.startsAfterNonDisjoint(right));
	}

	@Test
	public void disjointAdjacentContains() {
		Interval a = Interval.of(1, 3);
		Interval b = Interval.of(5, 7);
		Interval c = Interval.of(4, 4);
		Interval d = Interval.of(2, 2);

		assertTrue(a.disjoint(b));
		assertFalse(a.disjoint(Interval.of(3, 5)));
		assertTrue(a.adjacent(c));
		assertTrue(c.adjacent(a));
		assertFalse(a.adjacent(b));
		assertTrue(a.properlyContains(d));
		assertTrue(a.properlyContains(a));
		assertFalse(a.properlyContains(b));
	}

	@Test
	public void unionIntersectionDifference() {
		Interval a = Interval.of(1, 5);
		Interval b = Interval.of(4, 8);
		assertEquals(Interval.of(1, 8), a.union(b));
		assertEquals(Interval.of(4, 5), a.intersection(b));

		// other starts before / overlaps left
		Interval diffLeft = a.differenceNotProperlyContained(Interval.of(0, 2));
		assertNotNull(diffLeft);
		assertEquals(3, diffLeft.a);
		assertEquals(5, diffLeft.b);

		// other starts after / overlaps right
		Interval diffRight = a.differenceNotProperlyContained(Interval.of(4, 6));
		assertNotNull(diffRight);
		assertEquals(1, diffRight.a);
		assertEquals(3, diffRight.b);

		// totally before - startsBeforeNonDisjoint false, startsAfterNonDisjoint false
		assertNull(a.differenceNotProperlyContained(Interval.of(10, 12)));
	}

	@Test
	public void toStringFormat() {
		assertEquals("1..3", Interval.of(1, 3).toString());
		assertEquals("-1..-2", Interval.INVALID.toString());
	}
}
