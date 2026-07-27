/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class TestMurmurHash {
	@Test
	public void initializeUsesDefaultSeed() {
		assertEquals(0, MurmurHash.initialize());
	}

	@Test
	public void initializeWithSeed() {
		assertEquals(42, MurmurHash.initialize(42));
		assertEquals(-1, MurmurHash.initialize(-1));
	}

	@Test
	public void updateWithIntIsDeterministic() {
		int h1 = MurmurHash.update(MurmurHash.initialize(), 123);
		int h2 = MurmurHash.update(MurmurHash.initialize(), 123);
		assertEquals(h1, h2);
		assertNotEquals(h1, MurmurHash.update(MurmurHash.initialize(), 124));
	}

	@Test
	public void updateWithObjectUsesHashCodeOrZeroForNull() {
		int withNull = MurmurHash.update(MurmurHash.initialize(), null);
		int withZero = MurmurHash.update(MurmurHash.initialize(), 0);
		assertEquals(withZero, withNull);

		String s = "hello";
		int withObj = MurmurHash.update(MurmurHash.initialize(), s);
		int withHash = MurmurHash.update(MurmurHash.initialize(), s.hashCode());
		assertEquals(withHash, withObj);
	}

	@Test
	public void finishIsDeterministic() {
		int hash = MurmurHash.update(MurmurHash.initialize(1), 99);
		assertEquals(MurmurHash.finish(hash, 1), MurmurHash.finish(hash, 1));
		assertNotEquals(MurmurHash.finish(hash, 1), MurmurHash.finish(hash, 2));
	}

	@Test
	public void hashCodeArray() {
		String[] data = new String[] { "a", "b", null };
		int h1 = MurmurHash.hashCode(data, 7);
		int h2 = MurmurHash.hashCode(data, 7);
		assertEquals(h1, h2);
		assertNotEquals(h1, MurmurHash.hashCode(data, 8));
		assertNotEquals(h1, MurmurHash.hashCode(new String[] { "a", "c", null }, 7));
	}

	@Test
	public void hashCodeEmptyArray() {
		assertEquals(MurmurHash.finish(MurmurHash.initialize(0), 0),
			MurmurHash.hashCode(new Object[0], 0));
	}
}
