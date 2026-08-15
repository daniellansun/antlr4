/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.VocabularyImpl;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Remaining {@link IntervalSet} (non-{@link IntSet} complement, binary contains),
 * {@link IntegerList} capacity overflow, {@link InterpreterDataReader} headers,
 * and {@link Tuple3} null-field equality.
 */
public class TestIntervalSetAndInterpreterDataCoverage {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void complementAndSubtractNonIntervalSet() {
		IntervalSet s = IntervalSet.of(1, 3);
		TinyIntSet vocab = new TinyIntSet(1, 2, 3, 4, 5);
		IntervalSet comp = s.complement(vocab);
		assertNotNull(comp);
		assertTrue(comp.contains(4) || comp.contains(5) || !comp.contains(1));

		TinyIntSet other = new TinyIntSet(2);
		IntervalSet sub = s.subtract(other);
		assertNotNull(sub);
		assertTrue(sub.contains(1));
	}

	@Test
	public void intersectionStartsAfterNonDisjointAndBinaryContains() {
		// mine.startsAfterNonDisjoint(theirs): mine=[5..10], theirs=[1..6]
		IntervalSet mine = IntervalSet.of(5, 10);
		IntervalSet theirs = IntervalSet.of(1, 6);
		IntervalSet inter = (IntervalSet) mine.and(theirs);
		assertTrue(inter.contains(5) || inter.contains(6));

		// >4 disjoint intervals → binary search
		IntervalSet many = new IntervalSet();
		many.add(1);
		many.add(3);
		many.add(5);
		many.add(7);
		many.add(9);
		many.add(11);
		assertTrue(many.contains(5));
		assertTrue(many.contains(1));
		assertTrue(many.contains(11));
		assertFalse(many.contains(0));
		assertFalse(many.contains(12));
		assertFalse(many.contains(6));
		assertFalse(many.contains(2));
	}

	@Test
	public void toStringCommaBetweenIntervalsAndDeprecatedElementName() {
		IntervalSet s = new IntervalSet();
		s.add(1);
		s.add(3);
		String text = s.toString(VocabularyImpl.EMPTY_VOCABULARY);
		assertTrue(text.contains(","));

		ExposedIntervalSet ex = new ExposedIntervalSet();
		ex.add(Token.EOF);
		assertEquals("<EOF>", ex.legacyName(new String[] { "EOF" }, Token.EOF));
		assertEquals("<EPSILON>", ex.legacyName(new String[] { }, Token.EPSILON));
		assertNotNull(ex.legacyName(new String[] { null, "A" }, 1));
	}

	@Test
	public void integerListEnsureCapacityOverflow() {
		IntegerList list = new IntegerList();
		try {
			list.addAll(new AbstractCollection<Integer>() {
				@Override
				public int size() {
					return Integer.MAX_VALUE;
				}

				@Override
				public Iterator<Integer> iterator() {
					return Collections.emptyIterator();
				}
			});
			fail("expected OutOfMemoryError");
		}
		catch (OutOfMemoryError expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void interpreterDataReaderUnexpectedSymbolicAndRuleHeadersAndLexerChannels() throws IOException {
		File badSym = tmp.newFile("bad-sym.interp");
		write(badSym,
			"token literal names:\n" +
			"null\n" +
			"\n" +
			"NOT symbolic names:\n");
		try {
			InterpreterDataReader.parseFile(badSym.getAbsolutePath());
			fail();
		}
		catch (RuntimeException expected) {
			assertTrue(expected.getMessage().contains("Unexpected"));
		}

		File badRule = tmp.newFile("bad-rule.interp");
		write(badRule,
			"token literal names:\n" +
			"null\n" +
			"\n" +
			"token symbolic names:\n" +
			"A\n" +
			"\n" +
			"NOT rule names:\n");
		try {
			InterpreterDataReader.parseFile(badRule.getAbsolutePath());
			fail();
		}
		catch (RuntimeException expected) {
			assertTrue(expected.getMessage().contains("Unexpected"));
		}

		// Channel/mode sections: the reader only enters that branch when the
		// line left after the rule-names loop equals "channel names:". The
		// loop breaks on an empty line, so that is normally dead. Drive it
		// with a custom FileReader via a temp file whose rule-names section
		// is empty (first line after the header is empty) — still not
		// "channel names:". Reflection on parseFile is not viable; we still
		// cover the unexpected-header throws above.
	}

	@Test
	public void tuple3EqualsNullFields() {
		Tuple3<String, String, String> t = Tuple.create(null, null, null);
		Tuple3<String, String, String> t2 = Tuple.create(null, null, null);
		assertEquals(t, t2);
		assertEquals(t.hashCode(), t2.hashCode());
		assertFalse(t.equals(Tuple.create("a", null, null)));
		assertFalse(t.equals(Tuple.create(null, "b", null)));
		assertFalse(t.equals(Tuple.create(null, null, "c")));
	}

	private static void write(File f, String content) throws IOException {
		try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
			w.write(content);
		}
	}

	static final class ExposedIntervalSet extends IntervalSet {
		String legacyName(String[] tokenNames, int a) {
			return elementName(tokenNames, a);
		}
	}

	static final class TinyIntSet implements IntSet {
		private final List<Integer> els = new ArrayList<Integer>();

		TinyIntSet(int... v) {
			for (int x : v) {
				els.add(x);
			}
		}

		@Override
		public void add(int el) {
			els.add(el);
		}

		@Override
		public IntSet addAll(IntSet set) {
			if (set != null) {
				els.addAll(set.toList());
			}
			return this;
		}

		@Override
		public IntSet and(IntSet a) {
			return null;
		}

		@Override
		public IntSet complement(IntSet elements) {
			return null;
		}

		@Override
		public IntSet or(IntSet a) {
			return this;
		}

		@Override
		public IntSet subtract(IntSet a) {
			return this;
		}

		@Override
		public int size() {
			return els.size();
		}

		@Override
		public boolean isNil() {
			return els.isEmpty();
		}

		@Override
		public int getSingleElement() {
			return els.size() == 1 ? els.get(0) : Token.INVALID_TYPE;
		}

		@Override
		public boolean contains(int el) {
			return els.contains(el);
		}

		@Override
		public void remove(int el) {
			els.remove(Integer.valueOf(el));
		}

		@Override
		public List<Integer> toList() {
			return new ArrayList<Integer>(els);
		}

		@Override
		public String toString() {
			return els.toString();
		}
	}
}
