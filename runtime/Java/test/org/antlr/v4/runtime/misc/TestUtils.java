/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TestUtils {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void joinVariants() {
		List<String> list = Arrays.asList("a", "b", "c");
		assertEquals("a, b, c", Utils.join(list, ", "));
		assertEquals("a, b, c", Utils.join(list.iterator(), ", "));
		assertEquals("a|b|c", Utils.join(new String[] { "a", "b", "c" }, "|"));
		assertEquals("", Utils.join(new ArrayList<String>(), ","));
		assertEquals("only", Utils.join(Arrays.asList("only"), ","));
	}

	@Test
	public void equalsHelper() {
		assertTrue(Utils.equals(null, null));
		assertTrue(Utils.equals("a", "a"));
		assertFalse(Utils.equals(null, "a"));
		assertFalse(Utils.equals("a", null));
		assertFalse(Utils.equals("a", "b"));
		Integer x = Integer.valueOf(1);
		assertTrue(Utils.equals(x, x));
	}

	@Test
	public void numNonnull() {
		assertEquals(0, Utils.numNonnull(null));
		assertEquals(0, Utils.numNonnull(new Object[] {}));
		assertEquals(2, Utils.numNonnull(new Object[] { "a", null, "b", null }));
	}

	@Test
	public void removeAllElements() {
		Utils.removeAllElements(null, "x");
		List<String> list = new ArrayList<String>(Arrays.asList("a", "b", "a", "c"));
		Utils.removeAllElements(list, "a");
		assertEquals(Arrays.asList("b", "c"), list);
	}

	@Test
	public void escapeWhitespace() {
		assertEquals("a·b", Utils.escapeWhitespace("a b", true));
		assertEquals("a b", Utils.escapeWhitespace("a b", false));
		assertEquals("a\\tb\\nc\\rd", Utils.escapeWhitespace("a\tb\nc\rd", false));
	}

	@Test
	public void writeAndReadFile() throws IOException {
		File f = tmp.newFile("data.txt");
		Utils.writeFile(f, "hello".getBytes(StandardCharsets.UTF_8));
		assertEquals("hello", new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));

		String path = tmp.newFile("enc.txt").getAbsolutePath();
		Utils.writeFile(path, "café");
		char[] chars = Utils.readFile(path);
		assertEquals("café", new String(chars));

		String path2 = tmp.newFile("utf8.txt").getAbsolutePath();
		Utils.writeFile(path2, "xyz", "UTF-8");
		assertEquals("xyz", new String(Utils.readFile(path2, "UTF-8")));
	}

	@Test
	public void removeAllWithPredicate() {
		List<Integer> list = new ArrayList<Integer>(Arrays.asList(1, 2, 3, 4, 5));
		Utils.removeAll(list, new Predicate<Integer>() {
			@Override
			public boolean eval(Integer arg) {
				return arg % 2 == 0;
			}
		});
		assertEquals(Arrays.asList(1, 3, 5), list);

		// remove nothing
		Utils.removeAll(list, new Predicate<Integer>() {
			@Override
			public boolean eval(Integer arg) {
				return false;
			}
		});
		assertEquals(Arrays.asList(1, 3, 5), list);

		// remove all
		Utils.removeAll(list, new Predicate<Integer>() {
			@Override
			public boolean eval(Integer arg) {
				return true;
			}
		});
		assertTrue(list.isEmpty());

		// iterable path (not List)
		Set<String> set = new LinkedHashSet<String>(Arrays.asList("a", "bb", "c"));
		Utils.removeAll((Iterable<String>) set, new Predicate<String>() {
			@Override
			public boolean eval(String arg) {
				return arg.length() > 1;
			}
		});
		assertEquals(new LinkedHashSet<String>(Arrays.asList("a", "c")), set);

		// List via Iterable overload
		List<String> list2 = new ArrayList<String>(Arrays.asList("x", "yy"));
		Utils.removeAll((Iterable<String>) list2, new Predicate<String>() {
			@Override
			public boolean eval(String arg) {
				return arg.length() == 1;
			}
		});
		assertEquals(Arrays.asList("yy"), list2);
	}

	@Test
	public void toMap() {
		Map<String, Integer> m = Utils.toMap(new String[] { "a", "b", "c" });
		assertEquals(Integer.valueOf(0), m.get("a"));
		assertEquals(Integer.valueOf(1), m.get("b"));
		assertEquals(Integer.valueOf(2), m.get("c"));
		assertTrue(Utils.toMap(new String[0]).isEmpty());
	}

	@Test
	public void toCharArray() {
		assertNull(Utils.toCharArray(null));

		IntegerList bmp = new IntegerList();
		bmp.add('a');
		bmp.add('b');
		assertArrayEquals(new char[] { 'a', 'b' }, Utils.toCharArray(bmp));

		IntegerList mixed = new IntegerList();
		mixed.add('A');
		mixed.add(0x1F600); // supplementary grinning face
		mixed.add('Z');
		char[] result = Utils.toCharArray(mixed);
		assertEquals("A" + new String(Character.toChars(0x1F600)) + "Z", new String(result));
	}

	@Test
	public void toSetFromBitSet() {
		BitSet bits = new BitSet();
		bits.set(1);
		bits.set(3);
		bits.set(5);
		IntervalSet s = Utils.toSet(bits);
		assertEquals("{1, 3, 5}", s.toString());
		assertTrue(Utils.toSet(new BitSet()).isNil());
	}
}
