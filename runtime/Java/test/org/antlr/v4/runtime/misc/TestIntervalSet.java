/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.VocabularyImpl;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TestIntervalSet {
	@Test
	public void singleElement() {
		IntervalSet s = IntervalSet.of(99);
		assertEquals("99", s.toString());
		assertEquals(1, s.size());
		assertEquals(99, s.getSingleElement());
		assertEquals(99, s.getMinElement());
		assertEquals(99, s.getMaxElement());
	}

	@Test
	public void minMaxEmptyAndEof() {
		assertEquals(0, IntervalSet.COMPLETE_CHAR_SET.getMinElement());
		assertEquals(Token.EPSILON, IntervalSet.COMPLETE_CHAR_SET.or(IntervalSet.of(Token.EPSILON)).getMinElement());
		assertEquals(Token.EOF, IntervalSet.COMPLETE_CHAR_SET.or(IntervalSet.of(Token.EOF)).getMinElement());
		assertEquals(Token.INVALID_TYPE, new IntervalSet().getMinElement());
		assertEquals(Token.INVALID_TYPE, new IntervalSet().getMaxElement());
		assertEquals(Token.INVALID_TYPE, IntervalSet.of(1, 3).getSingleElement());
	}

	@Test
	public void constructorsAndClear() {
		IntervalSet empty = new IntervalSet();
		assertTrue(empty.isNil());
		assertEquals("{}", empty.toString());

		IntervalSet fromEls = new IntervalSet(1, 3, 2);
		assertEquals("{1..3}", fromEls.toString());

		IntervalSet nullEls = new IntervalSet((int[]) null);
		assertTrue(nullEls.isNil());

		List<Interval> intervals = new ArrayList<Interval>();
		intervals.add(Interval.of(5, 6));
		IntervalSet fromList = new IntervalSet(intervals);
		assertEquals("{5..6}", fromList.toString());

		IntervalSet copy = new IntervalSet(fromEls);
		assertEquals(fromEls, copy);

		copy.clear();
		assertTrue(copy.isNil());
		assertFalse(fromEls.isNil());
	}

	@Test
	public void isolatedAndMixedElements() {
		IntervalSet s = new IntervalSet();
		s.add(1);
		s.add('z');
		s.add('\uFFF0');
		assertEquals("{1, 122, 65520}", s.toString());

		IntervalSet mixed = new IntervalSet();
		mixed.add(1);
		mixed.add('a', 'z');
		mixed.add('0', '9');
		assertEquals("{1, 48..57, 97..122}", mixed.toString());
	}

	@Test
	public void addInvalidRangeIsNoOp() {
		IntervalSet s = new IntervalSet();
		s.add(5, 3);
		assertTrue(s.isNil());
		s.add(1, 2);
		s.add(1, 2); // duplicate
		assertEquals("{1..2}", s.toString());
	}

	@Test
	public void andOperations() {
		assertEquals("{13..15}", IntervalSet.of(10, 20).and(IntervalSet.of(13, 15)).toString());
		assertEquals("100", IntervalSet.of('a', 'z').and(IntervalSet.of('d')).toString());
		assertEquals("{}", IntervalSet.of('a', 'z').and(IntervalSet.of('0', '9')).toString());
		assertEquals("{}", IntervalSet.of('a').and(IntervalSet.of('d')).toString());
		assertNull(IntervalSet.of(1, 2).and(null));

		IntervalSet s = IntervalSet.of(10, 20);
		IntervalSet s2 = IntervalSet.of(2);
		s2.add(15);
		s2.add(18);
		assertEquals("{15, 18}", s.and(s2).toString());
		assertEquals("{15, 18}", s2.and(s).toString());
	}

	@Test
	public void complementOperations() {
		IntervalSet vocabulary = IntervalSet.of(1, 1000);
		vocabulary.add(2000, 3000);
		assertEquals("{1..49, 51..1000, 2000..3000}", IntervalSet.of(50, 50).complement(vocabulary).toString());

		IntervalSet s = IntervalSet.of(50, 60);
		s.add(5);
		s.add(250, 300);
		assertEquals("{1..4, 6..49, 61..249, 301..1000}", s.complement(IntervalSet.of(1, 1000)).toString());
		assertEquals("{}", IntervalSet.of(1, 1000).complement(IntervalSet.of(1, 1000)).toString());
		assertEquals("2", IntervalSet.of(1).complement(IntervalSet.of(1, 2)).toString());
		assertEquals("{97..98}", IntervalSet.of(1, 96).or(IntervalSet.of(99, Lexer.MAX_CHAR_VALUE))
			.complement(1, Lexer.MAX_CHAR_VALUE).toString());

		assertNull(IntervalSet.of(1).complement((IntSet) null));
		assertNull(IntervalSet.of(1).complement(new IntervalSet()));
	}

	@Test
	public void subtractOperations() {
		assertEquals("{10..11, 16..20}", IntervalSet.of(10, 20).subtract(IntervalSet.of(12, 15)).toString());

		IntervalSet withEof = IntervalSet.of(10, 20);
		withEof.add(Token.EOF);
		assertEquals("{<EOF>, 10..11, 16..20}", withEof.subtract(IntervalSet.of(12, 15)).toString());

		assertEquals("{12..20}", IntervalSet.of(10, 20).subtract(IntervalSet.of(5, 11)).toString());
		assertEquals("{11..20}", IntervalSet.of(10, 20).subtract(IntervalSet.of(5, 10)).toString());
		assertEquals("{10..14}", IntervalSet.of(10, 20).subtract(IntervalSet.of(15, 25)).toString());
		assertEquals("{10..19}", IntervalSet.of(10, 20).subtract(IntervalSet.of(20, 25)).toString());
		assertEquals("{}", IntervalSet.of(10, 20).subtract(IntervalSet.of(1, 25)).toString());

		IntervalSet multi = IntervalSet.of(10, 20);
		multi.add(30, 40);
		multi.add(50, 60);
		assertEquals("{56..60}", multi.subtract(IntervalSet.of(5, 55)).toString());
		assertEquals("{10..14, 56..60}", multi.subtract(IntervalSet.of(15, 55)).toString());

		IntervalSet s = IntervalSet.of(0, 113);
		s.add(115, 200);
		IntervalSet s2 = IntervalSet.of(0, 115);
		s2.add(117, 200);
		assertEquals("116", s.subtract(s2).toString());

		assertEquals("{}", IntervalSet.of(15).subtract(IntervalSet.of(1, 5).or(IntervalSet.of(10, 20))).toString());
		assertEquals(IntervalSet.of(10, 20), IntervalSet.of(10, 20).subtract(null));
		assertEquals(IntervalSet.of(10, 20), IntervalSet.of(10, 20).subtract(new IntervalSet()));
		assertTrue(IntervalSet.subtract(null, IntervalSet.of(1)).isNil());
		assertEquals(IntervalSet.of(1, 2), IntervalSet.subtract(IntervalSet.of(1, 2), null));
	}

	@Test
	public void orOperations() {
		IntervalSet a = IntervalSet.of(1, 2);
		IntervalSet b = IntervalSet.of(4, 5);
		assertEquals("{1..2, 4..5}", a.or(b).toString());
		assertEquals("{1..2, 4..5}", IntervalSet.or(new IntervalSet[] { a, b }).toString());
	}

	@Test
	public void equalsHashMembership() {
		IntervalSet s = IntervalSet.of(10, 20);
		s.add(2);
		s.add(499, 501);
		IntervalSet s2 = IntervalSet.of(10, 20);
		s2.add(2);
		s2.add(499, 501);
		assertEquals(s, s2);
		assertEquals(s.hashCode(), s2.hashCode());
		assertFalse(s.equals(IntervalSet.of(10, 20)));
		assertFalse(s.equals(null));
		assertFalse(s.equals("x"));

		assertFalse(s.contains(0));
		assertTrue(s.contains(2));
		assertTrue(s.contains(15));
		assertTrue(s.contains(499));
		assertFalse(s.contains(25));
	}

	@Test
	public void mergeCases() {
		IntervalSet s = IntervalSet.of(0, 41);
		s.add(42);
		s.add(43, 65534);
		assertEquals("{0..65534}", s.toString());

		IntervalSet s2 = IntervalSet.of(43, 65534);
		s2.add(42);
		s2.add(0, 41);
		assertEquals("{0..65534}", s2.toString());

		IntervalSet s3 = IntervalSet.of(42);
		s3.add(10);
		s3.add(0, 9);
		s3.add(43, 65534);
		s3.add(11, 41);
		assertEquals("{0..65534}", s3.toString());

		IntervalSet s4 = new IntervalSet();
		s4.add(0);
		s4.add(3);
		s4.add(5);
		s4.add(0, 7);
		assertEquals("{0..7}", s4.toString());

		IntervalSet s5 = IntervalSet.of(1, 10);
		s5.add(20, 30);
		s5.add(5, 25);
		assertEquals("{1..30}", s5.toString());
	}

	@Test
	public void sizeListSetArray() {
		IntervalSet s = IntervalSet.of(20, 30);
		s.add(50, 55);
		s.add(5, 19);
		assertEquals(32, s.size());

		IntervalSet s2 = IntervalSet.of(20, 25);
		s2.add(50, 55);
		s2.add(5, 5);
		assertEquals(Arrays.asList(5, 20, 21, 22, 23, 24, 25, 50, 51, 52, 53, 54, 55), s2.toList());
		assertArrayEquals(new int[] { 5, 20, 21, 22, 23, 24, 25, 50, 51, 52, 53, 54, 55 }, s2.toArray());
		Set<Integer> expected = new HashSet<Integer>(s2.toList());
		assertEquals(expected, s2.toSet());

		IntegerList il = s2.toIntegerList();
		assertEquals(s2.size(), il.size());
		assertEquals(5, il.get(0));
		assertArrayEquals(s2.toArray(), il.toArray());
	}

	@Test
	public void removeElements() {
		IntervalSet s = IntervalSet.of(1, 10);
		s.add(-3, -3);
		s.remove(-3);
		assertEquals("{1..10}", s.toString());

		s = IntervalSet.of(1, 10);
		s.add(-3, -3);
		s.remove(1);
		assertEquals("{-3, 2..10}", s.toString());

		s = IntervalSet.of(1, 10);
		s.add(-3, -3);
		s.remove(10);
		assertEquals("{-3, 1..9}", s.toString());

		s = IntervalSet.of(1, 10);
		s.add(-3, -3);
		s.remove(5);
		assertEquals("{-3, 1..4, 6..10}", s.toString());

		s.remove(100); // not present
		assertEquals("{-3, 1..4, 6..10}", s.toString());
		s.remove(0); // before first
		assertEquals("{-3, 1..4, 6..10}", s.toString());
	}

	@Test
	public void toStringVariants() {
		assertEquals("{}", new IntervalSet().toString());
		assertEquals("{}", new IntervalSet().toString(true));
		assertEquals("'a'", IntervalSet.of('a').toString(true));
		assertEquals("{'a'..'c'}", IntervalSet.of('a', 'c').toString(true));
		assertEquals("<EOF>", IntervalSet.of(Token.EOF).toString());
		assertEquals("<EOF>", IntervalSet.of(Token.EOF).toString(false));

		Vocabulary vocab = VocabularyImpl.fromTokenNames(new String[] { null, "A", "B", "C" });
		IntervalSet named = new IntervalSet(1, 2, 3);
		assertEquals("{A, B, C}", named.toString(vocab));
		assertEquals("{}", new IntervalSet().toString(vocab));
		assertEquals("A", IntervalSet.of(1).toString(vocab));
		assertEquals("<EOF>", IntervalSet.of(Token.EOF).toString(vocab));
		assertEquals("<EPSILON>", IntervalSet.of(Token.EPSILON).toString(vocab));

		@SuppressWarnings("deprecation")
		String deprecated = IntervalSet.of(1).toString(new String[] { null, "A" });
		assertEquals("A", deprecated);
	}

	@Test
	public void readonly() {
		IntervalSet s = IntervalSet.of(1, 2);
		assertFalse(s.isReadonly());
		s.setReadonly(true);
		assertTrue(s.isReadonly());

		assertThrows(IllegalStateException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				s.add(3);
			}
		});
		assertThrows(IllegalStateException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				s.add(3, 4);
			}
		});
		assertThrows(IllegalStateException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				s.clear();
			}
		});
		assertThrows(IllegalStateException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				s.remove(1);
			}
		});
		assertThrows(IllegalStateException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				s.setReadonly(false);
			}
		});

		assertTrue(IntervalSet.EMPTY_SET.isReadonly());
		assertTrue(IntervalSet.COMPLETE_CHAR_SET.isReadonly());
	}

	@Test
	public void addAllAndGetIntervals() {
		IntervalSet s = new IntervalSet();
		assertSameLike(s, s.addAll(null));
		s.addAll(IntervalSet.of(1, 3));
		assertEquals("{1..3}", s.toString());
		assertNotNull(s.getIntervals());
		assertEquals(1, s.getIntervals().size());

		// non-IntervalSet IntSet path via a tiny wrapper using toList
		final IntervalSet source = IntervalSet.of(5, 6);
		IntSet other = new IntSet() {
			@Override public void add(int el) { throw new UnsupportedOperationException(); }
			@Override public IntSet addAll(IntSet set) { throw new UnsupportedOperationException(); }
			@Override public IntSet complement(IntSet elements) { throw new UnsupportedOperationException(); }
			@Override public IntSet subtract(IntSet a) { throw new UnsupportedOperationException(); }
			@Override public IntSet or(IntSet a) { throw new UnsupportedOperationException(); }
			@Override public IntSet and(IntSet a) { throw new UnsupportedOperationException(); }
			@Override public boolean contains(int el) { return source.contains(el); }
			@Override public boolean isNil() { return source.isNil(); }
			@Override public int getSingleElement() { return source.getSingleElement(); }
			@Override public int size() { return source.size(); }
			@Override public void remove(int el) { throw new UnsupportedOperationException(); }
			@Override public List<Integer> toList() { return source.toList(); }
		};
		s.addAll(other);
		assertEquals("{1..3, 5..6}", s.toString());

		// subtract/complement with non-IntervalSet
		assertEquals("1", IntervalSet.of(1, 2).subtract(other).or(IntervalSet.of(1)).and(IntervalSet.of(1)).toString());
	}

	private static void assertSameLike(IntervalSet expected, IntervalSet actual) {
		assertTrue(expected == actual);
	}

	@Test
	public void andPartialOverlap() {
		// cover startsAfterNonDisjoint branch in and()
		IntervalSet a = IntervalSet.of(0, 115);
		IntervalSet b = IntervalSet.of(115, 200);
		assertEquals("115", a.and(b).toString());
	}

	@Test
	public void notRIntersectionNotT() {
		IntervalSet s = IntervalSet.of(0, 's');
		s.add('u', 200);
		IntervalSet s2 = IntervalSet.of(0, 'q');
		s2.add('s', 200);
		assertEquals("{0..113, 115, 117..200}", s.and(s2).toString());
	}
}
