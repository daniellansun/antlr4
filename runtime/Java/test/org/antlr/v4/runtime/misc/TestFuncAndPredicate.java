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

public class TestFuncAndPredicate {
	@Test
	public void func0() {
		Func0<Integer> f = new Func0<Integer>() {
			@Override
			public Integer eval() {
				return Integer.valueOf(42);
			}
		};
		assertEquals(Integer.valueOf(42), f.eval());
	}

	@Test
	public void func1() {
		Func1<String, Integer> f = new Func1<String, Integer>() {
			@Override
			public Integer eval(String arg1) {
				return Integer.valueOf(arg1.length());
			}
		};
		assertEquals(Integer.valueOf(3), f.eval("abc"));
	}

	@Test
	public void predicate() {
		Predicate<String> p = new Predicate<String>() {
			@Override
			public boolean eval(String arg) {
				return arg != null && arg.startsWith("a");
			}
		};
		assertTrue(p.eval("abc"));
		assertFalse(p.eval("xyz"));
		assertFalse(p.eval(null));
	}
}
