/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestMultiMap {
	@Test
	public void mapMultipleValuesPerKey() {
		MultiMap<String, Integer> map = new MultiMap<String, Integer>();
		map.map("a", 1);
		map.map("a", 2);
		map.map("b", 3);
		assertEquals(Arrays.asList(1, 2), map.get("a"));
		assertEquals(Arrays.asList(3), map.get("b"));
	}

	@Test
	public void getPairs() {
		MultiMap<String, Integer> map = new MultiMap<String, Integer>();
		map.map("a", 1);
		map.map("a", 2);
		map.map("b", 3);
		List<Tuple2<String, Integer>> pairs = map.getPairs();
		assertEquals(3, pairs.size());
		assertEquals(Tuple.create("a", 1), pairs.get(0));
		assertEquals(Tuple.create("a", 2), pairs.get(1));
		assertEquals(Tuple.create("b", 3), pairs.get(2));
	}

	@Test
	public void emptyPairs() {
		MultiMap<String, Integer> map = new MultiMap<String, Integer>();
		assertTrue(map.getPairs().isEmpty());
	}
}
