/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link PredictionContextCache}, including the probe-key
 * optimizations used by {@link #getChild} and {@link #join}.
 */
public class TestPredictionContextCache {

	@Test
	public void uncachedDisablesCaching() {
		assertFalse(PredictionContextCache.UNCACHED.isEnableCache());

		PredictionContext parent = PredictionContext.EMPTY_LOCAL;
		PredictionContext child1 = PredictionContextCache.UNCACHED.getChild(parent, 42);
		PredictionContext child2 = PredictionContextCache.UNCACHED.getChild(parent, 42);
		// Without caching, each getChild constructs a new singleton.
		assertNotSame(child1, child2);
		assertEquals(child1, child2);
	}

	@Test
	public void enabledCacheInternsChildren() {
		PredictionContextCache cache = new PredictionContextCache();
		assertTrue(cache.isEnableCache());

		PredictionContext parent = PredictionContext.EMPTY_FULL;
		PredictionContext child1 = cache.getChild(parent, 7);
		PredictionContext child2 = cache.getChild(parent, 7);
		assertSame(child1, child2);

		PredictionContext other = cache.getChild(parent, 8);
		assertNotSame(child1, other);
		assertEquals(7, child1.getReturnState(0));
		assertEquals(8, other.getReturnState(0));
	}

	@Test
	public void repeatedChildLookupsReuseProbeKeyPath() {
		// Stress the probe-key lookup path: many hits after a single miss must
		// keep returning the same interned child without corrupting the map.
		PredictionContextCache cache = new PredictionContextCache();
		PredictionContext parent = PredictionContext.EMPTY_LOCAL;
		PredictionContext first = cache.getChild(parent, 3);
		for (int i = 0; i < 100; i++) {
			assertSame(first, cache.getChild(parent, 3));
		}

		// Distinct invoking states remain distinct.
		PredictionContext other = cache.getChild(parent, 4);
		assertNotSame(first, other);
		for (int i = 0; i < 50; i++) {
			assertSame(other, cache.getChild(parent, 4));
			assertSame(first, cache.getChild(parent, 3));
		}
	}

	@Test
	public void joinSelfReturnsCachedIdentity() {
		PredictionContextCache cache = new PredictionContextCache();
		PredictionContext a = cache.getChild(PredictionContext.EMPTY_FULL, 1);
		PredictionContext joined = cache.join(a, a);
		assertSame(a, joined);
	}

	@Test
	public void joinIsCachedAndCommutative() {
		PredictionContextCache cache = new PredictionContextCache();
		PredictionContext a = cache.getChild(PredictionContext.EMPTY_LOCAL, 1);
		PredictionContext b = cache.getChild(PredictionContext.EMPTY_LOCAL, 2);

		PredictionContext ab = cache.join(a, b);
		PredictionContext abAgain = cache.join(a, b);
		PredictionContext ba = cache.join(b, a);

		assertSame(ab, abAgain);
		// Commutative key: (b,a) must resolve to the same cached join result.
		assertSame(ab, ba);
		assertEquals(2, ab.size());
	}

	@Test
	public void joinEmptyLocalWithEmptyLocal() {
		PredictionContextCache cache = new PredictionContextCache();
		PredictionContext joined = cache.join(PredictionContext.EMPTY_LOCAL, PredictionContext.EMPTY_LOCAL);
		assertSame(PredictionContext.EMPTY_LOCAL, joined);
	}

	@Test
	public void joinEmptyFullWithEmptyFull() {
		PredictionContextCache cache = new PredictionContextCache();
		PredictionContext joined = cache.join(PredictionContext.EMPTY_FULL, PredictionContext.EMPTY_FULL);
		assertSame(PredictionContext.EMPTY_FULL, joined);
	}

	@Test
	public void getAsCachedInternsEqualContexts() {
		PredictionContextCache cache = new PredictionContextCache();
		PredictionContext a1 = PredictionContext.EMPTY_FULL.getChild(11);
		PredictionContext a2 = PredictionContext.EMPTY_FULL.getChild(11);
		assertNotSame(a1, a2);
		assertEquals(a1, a2);

		PredictionContext c1 = cache.getAsCached(a1);
		PredictionContext c2 = cache.getAsCached(a2);
		assertSame(c1, c2);
	}

	@Test
	public void uncachedJoinMatchesStructuralResult() {
		PredictionContext a = PredictionContext.EMPTY_LOCAL.getChild(1);
		PredictionContext b = PredictionContext.EMPTY_LOCAL.getChild(2);

		PredictionContext cached = new PredictionContextCache().join(a, b);
		PredictionContext uncached = PredictionContextCache.UNCACHED.join(a, b);
		assertEquals(cached, uncached);
		assertNotNull(uncached);
	}

	@Test
	public void nestedChildContextsAreInterned() {
		PredictionContextCache cache = new PredictionContextCache();
		PredictionContext root = PredictionContext.EMPTY_FULL;
		PredictionContext level1 = cache.getChild(root, 100);
		PredictionContext level2a = cache.getChild(level1, 200);
		PredictionContext level2b = cache.getChild(cache.getChild(root, 100), 200);
		assertSame(level2a, level2b);
		assertEquals(200, level2a.getReturnState(0));
		assertEquals(100, level2a.getParent(0).getReturnState(0));
	}

	@Test
	public void recursiveJoinReentrancyPreservesCacheCorrectness() {
		// PredictionContext.join recursively calls cache.join for parents.
		// Probe keys must not corrupt nested lookups or permanent entries.
		PredictionContextCache cache = new PredictionContextCache();
		PredictionContext a = cache.getChild(PredictionContext.EMPTY_FULL, 1);
		PredictionContext b = cache.getChild(PredictionContext.EMPTY_FULL, 2);
		PredictionContext c = cache.getChild(PredictionContext.EMPTY_FULL, 3);
		PredictionContext d = cache.getChild(PredictionContext.EMPTY_FULL, 4);

		// Build two multi-level trees so join must recurse into parents.
		PredictionContext left = cache.getChild(cache.join(a, b), 10);
		PredictionContext right = cache.getChild(cache.join(c, d), 10);

		PredictionContext merged1 = cache.join(left, right);
		PredictionContext merged2 = cache.join(left, right);
		PredictionContext merged3 = cache.join(right, left);

		assertSame(merged1, merged2);
		assertSame(merged1, merged3);
		assertTrue(merged1.size() >= 1);
	}

	@Test
	public void repeatedJoinAfterProbeClearStillHitsCache() {
		PredictionContextCache cache = new PredictionContextCache();
		PredictionContext a = cache.getChild(PredictionContext.EMPTY_LOCAL, 5);
		PredictionContext b = cache.getChild(PredictionContext.EMPTY_LOCAL, 6);

		PredictionContext first = cache.join(a, b);
		// Interleave with other operations that reuse probe keys.
		assertSame(a, cache.getChild(PredictionContext.EMPTY_LOCAL, 5));
		assertSame(first, cache.join(b, a));
		assertSame(first, cache.join(a, b));
	}

	@Test
	public void uncachedDoesNotRetainMaps() {
		// Behavioral: UNCACHED never interns; structural equality still holds.
		assertFalse(PredictionContextCache.UNCACHED.isEnableCache());
		PredictionContext a = PredictionContext.EMPTY_LOCAL.getChild(1);
		PredictionContext b = PredictionContext.EMPTY_LOCAL.getChild(1);
		assertNotSame(a, b);
		assertEquals(
			PredictionContextCache.UNCACHED.getAsCached(a),
			a);
		assertNotSame(
			PredictionContextCache.UNCACHED.getChild(PredictionContext.EMPTY_LOCAL, 1),
			PredictionContextCache.UNCACHED.getChild(PredictionContext.EMPTY_LOCAL, 1));
	}
}
