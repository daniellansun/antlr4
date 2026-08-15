/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
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
		assertNotSame(range, Interval.of(1, 2));

		Interval negative = Interval.of(-1, -1);
		assertEquals(-1, negative.a);
		assertNotSame(negative, Interval.of(-1, -1));

		Interval large = Interval.of(Interval.INTERVAL_POOL_MAX_VALUE + 1,
			Interval.INTERVAL_POOL_MAX_VALUE + 1);
		assertEquals(Interval.INTERVAL_POOL_MAX_VALUE + 1, large.a);
		assertNotSame(large, Interval.of(Interval.INTERVAL_POOL_MAX_VALUE + 1,
			Interval.INTERVAL_POOL_MAX_VALUE + 1));

		Interval boundary = Interval.of(0, 0);
		assertSame(boundary, Interval.of(0, 0));

		Interval maxPooled = Interval.of(Interval.INTERVAL_POOL_MAX_VALUE, Interval.INTERVAL_POOL_MAX_VALUE);
		assertSame(maxPooled, Interval.of(Interval.INTERVAL_POOL_MAX_VALUE, Interval.INTERVAL_POOL_MAX_VALUE));
	}

	/**
	 * Upstream #4901: a lazy {@code cache[a] == null} fill could publish a
	 * half-initialized interval; {@link org.antlr.v4.runtime.CommonToken#getText()}
	 * then returned the first character or the whole stream. Eager class-init
	 * fill plus {@code final} fields must stay correct under contention.
	 */
	@Test
	public void ofReturnsCorrectBoundsUnderContention() throws Exception {
		final int threads = 8;
		final int iters = 4000;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		final AtomicInteger errors = new AtomicInteger();
		List<Callable<Void>> tasks = new ArrayList<Callable<Void>>(threads);
		for (int t = 0; t < threads; t++) {
			tasks.add(new Callable<Void>() {
				@Override
				public Void call() {
					for (int i = 0; i < iters; i++) {
						int v = i % (Interval.INTERVAL_POOL_MAX_VALUE + 3) - 1;
						Interval single = Interval.of(v, v);
						if (single.a != v || single.b != v) {
							errors.incrementAndGet();
						}
						if (v >= 0 && v <= Interval.INTERVAL_POOL_MAX_VALUE
							&& single != Interval.of(v, v)) {
							errors.incrementAndGet();
						}
						Interval range = Interval.of(v, v + 2);
						if (range.a != v || range.b != v + 2) {
							errors.incrementAndGet();
						}
					}
					return null;
				}
			});
		}
		for (Future<Void> f : pool.invokeAll(tasks)) {
			f.get();
		}
		pool.shutdown();
		assertEquals(0, errors.get());
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
