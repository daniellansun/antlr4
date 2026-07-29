/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for package-private {@link OpenAddressedHashSet}: the JDK
 * {@link Set} adapter that hides HPPC on the epsilon-closure busy-set path.
 * Style matches other runtime ATN unit tests.
 */
public class TestOpenAddressedHashSet {

	@Test
	public void emptySetBasics() {
		OpenAddressedHashSet<String> set = new OpenAddressedHashSet<String>(4);
		assertTrue(set.isEmpty());
		assertEquals(0, set.size());
		assertFalse(set.contains("a"));
		assertFalse(set.remove("a"));
	}

	@Test
	public void addContainsRemoveAndClear() {
		OpenAddressedHashSet<String> set = new OpenAddressedHashSet<String>(8);
		assertTrue(set.add("x"));
		assertFalse(set.add("x"));
		assertTrue(set.contains("x"));
		assertEquals(1, set.size());
		assertFalse(set.isEmpty());

		assertTrue(set.add("y"));
		assertEquals(2, set.size());
		assertTrue(set.remove("x"));
		assertFalse(set.contains("x"));
		assertEquals(1, set.size());
		assertFalse(set.remove("x"));

		set.clear();
		assertTrue(set.isEmpty());
		assertEquals(0, set.size());
		assertFalse(set.contains("y"));
	}

	@Test
	public void valueEqualityForAtnConfigs() {
		// Same contract the busy set relies on: equal configs collapse.
		BasicState state = new BasicState();
		state.stateNumber = 7;
		ATNConfig a = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		ATNConfig b = ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL);
		assertTrue(a.equals(b));

		OpenAddressedHashSet<ATNConfig> set = new OpenAddressedHashSet<ATNConfig>(4);
		assertTrue(set.add(a));
		assertFalse(set.add(b));
		assertEquals(1, set.size());
		assertTrue(set.contains(b));
		assertTrue(set.remove(b));
		assertTrue(set.isEmpty());
	}

	@Test
	public void addAllAndBulkMembership() {
		OpenAddressedHashSet<Integer> set = new OpenAddressedHashSet<Integer>(4);
		assertTrue(set.addAll(Arrays.asList(1, 2, 3)));
		assertFalse(set.addAll(Arrays.asList(1, 2)));
		assertTrue(set.containsAll(Arrays.asList(1, 3)));
		assertFalse(set.containsAll(Arrays.asList(1, 9)));
		assertEquals(3, set.size());
	}

	@Test
	public void iteratorAndRemove() {
		OpenAddressedHashSet<String> set = new OpenAddressedHashSet<String>(4);
		set.add("a");
		set.add("b");
		set.add("c");

		List<String> seen = new ArrayList<String>();
		Iterator<String> it = set.iterator();
		while (it.hasNext()) {
			String next = it.next();
			seen.add(next);
			if ("b".equals(next)) {
				it.remove();
			}
		}
		assertEquals(3, seen.size());
		assertTrue(seen.containsAll(Arrays.asList("a", "b", "c")));
		assertEquals(2, set.size());
		assertFalse(set.contains("b"));
		assertTrue(set.contains("a"));
		assertTrue(set.contains("c"));
	}

	@Test
	public void iteratorRemoveWithoutNextThrows() {
		OpenAddressedHashSet<String> set = new OpenAddressedHashSet<String>(2);
		set.add("a");
		Iterator<String> it = set.iterator();
		try {
			it.remove();
			fail("expected IllegalStateException");
		}
		catch (IllegalStateException expected) {
			// ok
		}
		assertTrue(it.hasNext());
		it.next();
		it.remove();
		assertTrue(set.isEmpty());
		try {
			it.remove();
			fail("expected IllegalStateException on double remove");
		}
		catch (IllegalStateException expected) {
			// ok
		}
	}

	@Test
	public void iteratorNextPastEndThrows() {
		OpenAddressedHashSet<String> set = new OpenAddressedHashSet<String>(2);
		Iterator<String> it = set.iterator();
		assertFalse(it.hasNext());
		try {
			it.next();
			fail("expected NoSuchElementException");
		}
		catch (NoSuchElementException expected) {
			// ok
		}
	}

	@Test
	public void toArrayMatchesMembers() {
		OpenAddressedHashSet<String> set = new OpenAddressedHashSet<String>(4);
		set.add("p");
		set.add("q");
		Object[] array = set.toArray();
		assertEquals(2, array.length);
		Set<Object> asSet = new HashSet<Object>(Arrays.asList(array));
		assertTrue(asSet.contains("p"));
		assertTrue(asSet.contains("q"));
	}

	@Test
	public void setEqualsAndHashCodeContract() {
		OpenAddressedHashSet<String> a = new OpenAddressedHashSet<String>(4);
		a.add("x");
		a.add("y");
		OpenAddressedHashSet<String> b = new OpenAddressedHashSet<String>(4);
		b.add("y");
		b.add("x");
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());

		HashSet<String> jdk = new HashSet<String>(Arrays.asList("x", "y"));
		assertEquals(a, jdk);
		assertEquals(jdk, a);
	}

	@Test
	public void removeAllAndRetainAll() {
		OpenAddressedHashSet<String> set = new OpenAddressedHashSet<String>(8);
		set.addAll(Arrays.asList("a", "b", "c", "d"));
		assertTrue(set.removeAll(Arrays.asList("b", "d", "z")));
		assertEquals(new HashSet<String>(Arrays.asList("a", "c")), set);

		assertTrue(set.retainAll(Collections.singleton("a")));
		assertEquals(Collections.singleton("a"), set);
		assertFalse(set.retainAll(Collections.singleton("a")));
	}

	@Test
	public void delegateIsObjectHashSet() {
		// Same-package diagnostic: storage is HPPC; type is not on the Set API.
		OpenAddressedHashSet<String> set = new OpenAddressedHashSet<String>(4);
		assertNotNull(set.delegate());
		assertTrue(set.delegate() instanceof ClearableObjectHashSet);
		set.add("z");
		assertEquals(1, set.delegate().size());
		assertTrue(set.delegate().contains("z"));
	}

	@Test
	public void clearEmptyIsIdempotentOnBusySetPath() {
		// Models EpsilonClosure finally-block clear when busy set was unused
		// or already emptied; must remain empty and reusable.
		OpenAddressedHashSet<String> set = new OpenAddressedHashSet<String>(8);
		set.clear();
		set.clear();
		assertTrue(set.isEmpty());
		assertTrue(set.add("a"));
		set.clear();
		set.clear();
		assertTrue(set.isEmpty());
		assertFalse(set.contains("a"));
		assertTrue(set.add("b"));
		assertEquals(1, set.size());
	}

	@Test
	public void nullElementSupportedLikeHashSet() {
		// HPPC empty-key path; ensure the Set facade still works for null.
		OpenAddressedHashSet<String> set = new OpenAddressedHashSet<String>(2);
		assertTrue(set.add(null));
		assertFalse(set.add(null));
		assertTrue(set.contains(null));
		assertEquals(1, set.size());
		assertArrayEquals(new Object[] { null }, set.toArray());
		assertTrue(set.remove(null));
		assertTrue(set.isEmpty());
	}

	@Test
	public void growthBeyondInitialCapacity() {
		OpenAddressedHashSet<Integer> set = new OpenAddressedHashSet<Integer>(2);
		for (int i = 0; i < 64; i++) {
			assertTrue(set.add(i));
		}
		assertEquals(64, set.size());
		for (int i = 0; i < 64; i++) {
			assertTrue(set.contains(i));
		}
	}

	@Test
	public void iteratorIsSnapshotIsolatedFromLaterAdds() {
		OpenAddressedHashSet<String> set = new OpenAddressedHashSet<String>(4);
		set.add("a");
		Iterator<String> it = set.iterator();
		// Structural add after iterator() must not appear in the snapshot.
		set.add("b");
		List<String> seen = new ArrayList<String>();
		while (it.hasNext()) {
			seen.add(it.next());
		}
		assertEquals(Collections.singletonList("a"), seen);
		assertEquals(2, set.size());
		assertTrue(set.contains("b"));
	}

	@Test
	public void foreignTypeContainsAndRemoveReturnFalse() {
		// Unchecked cast to E plus HPPC equality: wrong-type probes must not throw.
		OpenAddressedHashSet<Integer> set = new OpenAddressedHashSet<Integer>(4);
		set.add(1);
		assertFalse(set.contains("x"));
		assertFalse(set.remove("x"));
		assertEquals(1, set.size());
		assertTrue(set.contains(1));
	}
}
