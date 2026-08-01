/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;


import org.antlr.v4.misc.EscapeSequenceParsing;
import org.antlr.v4.misc.MutableInt;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.Predicate;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.TerminalAST;
import org.antlr.runtime.CommonToken;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class TestMiscCoverage {

	@Test
	public void testMutableIntAllMethods() {
		MutableInt m1 = new MutableInt(42);
		MutableInt m2 = new MutableInt(42);
		MutableInt m3 = new MutableInt(100);

		assertEquals(42, m1.intValue());
		assertEquals(42L, m1.longValue());
		assertEquals(42.0f, m1.floatValue(), 0.001f);
		assertEquals(42.0d, m1.doubleValue(), 0.001d);
		assertEquals("42", m1.toString());

		assertTrue(m1.equals(m2));
		assertTrue(m1.equals(42));
		assertFalse(m1.equals("42"));
		assertFalse(m1.equals(null));

		assertEquals(42, m1.hashCode());
		assertEquals(0, m1.compareTo(42));
		assertTrue(m1.compareTo(m3) < 0);
		assertTrue(m3.compareTo(m1) > 0);
	}

	@Test
	public void testOrderedHashMapAllMethods() {
		org.antlr.v4.misc.OrderedHashMap<String, Integer> map = new org.antlr.v4.misc.OrderedHashMap<String, Integer>();
		map.put("first", 1);
		map.put("second", 2);

		assertEquals("first", map.getKey(0));
		assertEquals("second", map.getKey(1));
		assertEquals(Integer.valueOf(1), map.getElement(0));
		assertEquals(Integer.valueOf(2), map.getElement(1));

		Map<String, Integer> other = new HashMap<String, Integer>();
		other.put("third", 3);
		map.putAll(other);

		assertEquals(3, map.size());
		assertEquals("third", map.getKey(2));
		assertEquals(Integer.valueOf(3), map.getElement(2));

		map.remove("second");
		assertEquals(2, map.size());

		map.clear();
		assertEquals(0, map.size());
	}

	@Test
	public void testEscapeSequenceParsingEdgeCases() {
		// Non-backslash
		EscapeSequenceParsing.Result res1 = EscapeSequenceParsing.parseEscape("abc", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.INVALID, res1.type);

		// Too short
		EscapeSequenceParsing.Result res2 = EscapeSequenceParsing.parseEscape("\\", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.INVALID, res2.type);

		// Invalid unicode escape
		EscapeSequenceParsing.Result res3 = EscapeSequenceParsing.parseEscape("\\u12", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.INVALID, res3.type);

		EscapeSequenceParsing.Result res4 = EscapeSequenceParsing.parseEscape("\\u{123", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.INVALID, res4.type);

		EscapeSequenceParsing.Result res5 = EscapeSequenceParsing.parseEscape("\\uXYZW", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.INVALID, res5.type);

		// Valid unicode hex in brace
		EscapeSequenceParsing.Result res6 = EscapeSequenceParsing.parseEscape("\\u{0041}", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.CODE_POINT, res6.type);
		assertEquals('A', res6.codePoint);

		// \p and \P escape cases
		EscapeSequenceParsing.Result res7 = EscapeSequenceParsing.parseEscape("\\p", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.INVALID, res7.type);

		EscapeSequenceParsing.Result res8 = EscapeSequenceParsing.parseEscape("\\px", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.INVALID, res8.type);

		EscapeSequenceParsing.Result res9 = EscapeSequenceParsing.parseEscape("\\p{L", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.INVALID, res9.type);

		EscapeSequenceParsing.Result res10 = EscapeSequenceParsing.parseEscape("\\p{UnknownProp}", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.INVALID, res10.type);

		EscapeSequenceParsing.Result res11 = EscapeSequenceParsing.parseEscape("\\p{Lu}", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.PROPERTY, res11.type);

		EscapeSequenceParsing.Result res12 = EscapeSequenceParsing.parseEscape("\\P{Lu}", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.PROPERTY, res12.type);

		// Escaped characters like \n, \], \-
		EscapeSequenceParsing.Result res13 = EscapeSequenceParsing.parseEscape("\\]", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.CODE_POINT, res13.type);
		assertEquals(']', res13.codePoint);

		EscapeSequenceParsing.Result res14 = EscapeSequenceParsing.parseEscape("\\-", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.CODE_POINT, res14.type);
		assertEquals('-', res14.codePoint);

		EscapeSequenceParsing.Result res15 = EscapeSequenceParsing.parseEscape("\\z", 0);
		assertEquals(EscapeSequenceParsing.Result.Type.INVALID, res15.type);

		// Result methods
		assertNotNull(res11.toString());
		assertTrue(res11.equals(res11));
		assertFalse(res11.equals(null));
		assertFalse(res11.equals("other"));
		EscapeSequenceParsing.Result res11Dup = EscapeSequenceParsing.parseEscape("\\p{Lu}", 0);
		assertTrue(res11.equals(res11Dup));
		assertEquals(res11.hashCode(), res11Dup.hashCode());
	}

	@Test
	public void testUtilsAllMethods() {
		assertNull(org.antlr.v4.misc.Utils.stripFileExtension(null));
		assertEquals("file", org.antlr.v4.misc.Utils.stripFileExtension("file"));
		assertEquals("file", org.antlr.v4.misc.Utils.stripFileExtension("file.txt"));
		assertEquals("file.tar", org.antlr.v4.misc.Utils.stripFileExtension("file.tar.gz"));

		assertEquals("a, b, c", org.antlr.v4.misc.Utils.join(new String[]{"a", "b", "c"}, ", "));
		assertEquals("a\nb\n", org.antlr.v4.misc.Utils.sortLinesInString("b\na\n"));

		assertNull(org.antlr.v4.misc.Utils.nodesToStrings(null));
		List<GrammarAST> nodes = new ArrayList<GrammarAST>();
		nodes.add(new TerminalAST(new CommonToken(1, "FOO")));
		assertEquals(1, org.antlr.v4.misc.Utils.nodesToStrings(nodes).size());
		assertEquals("FOO", org.antlr.v4.misc.Utils.nodesToStrings(nodes).get(0));

		assertEquals("Abc", org.antlr.v4.misc.Utils.capitalize("abc"));
		assertEquals("abc", org.antlr.v4.misc.Utils.decapitalize("Abc"));

		assertNull(org.antlr.v4.misc.Utils.select(null, null));
		List<String> list = new ArrayList<String>();
		list.add("hello");
		List<Integer> lengths = org.antlr.v4.misc.Utils.select(list, new org.antlr.v4.runtime.misc.Func1<String, Integer>() {
			@Override
			public Integer eval(String s) {
				return s.length();
			}
		});
		assertEquals(1, lengths.size());
		assertEquals(Integer.valueOf(5), lengths.get(0));

		List<Object> mixed = new ArrayList<Object>();
		mixed.add("str");
		mixed.add(123);
		assertEquals("str", org.antlr.v4.misc.Utils.find(mixed, String.class));
		assertEquals(Integer.valueOf(123), org.antlr.v4.misc.Utils.find(mixed, Integer.class));
		assertNull(org.antlr.v4.misc.Utils.find(mixed, Double.class));

		List<Integer> nums = new ArrayList<Integer>();
		nums.add(1);
		nums.add(2);
		nums.add(3);
		nums.add(2);

		Predicate<Integer> isTwo = new Predicate<Integer>() {
			@Override
			public boolean eval(Integer integer) {
				return integer == 2;
			}
		};
		assertEquals(1, org.antlr.v4.misc.Utils.indexOf(nums, isTwo));
		assertEquals(3, org.antlr.v4.misc.Utils.lastIndexOf(nums, isTwo));

		Predicate<Integer> isFour = new Predicate<Integer>() {
			@Override
			public boolean eval(Integer integer) {
				return integer == 4;
			}
		};
		assertEquals(-1, org.antlr.v4.misc.Utils.indexOf(nums, isFour));
		assertEquals(-1, org.antlr.v4.misc.Utils.lastIndexOf(nums, isFour));

		org.antlr.v4.misc.Utils.setSize(nums, 2);
		assertEquals(2, nums.size());
		org.antlr.v4.misc.Utils.setSize(nums, 4);
		assertEquals(4, nums.size());
		assertNull(nums.get(2));
		assertNull(nums.get(3));
	}
}
