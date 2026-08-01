/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the package-private LL(1) primitive concurrent map.
 */
public class TestConcurrentIntIntMap {

	@Test
	public void getMissingReturnsZero() {
		ConcurrentIntIntMap map = new ConcurrentIntIntMap();
		assertEquals(ConcurrentIntIntMap.MISSING, map.get(42));
		assertEquals(0, map.size());
	}

	@Test
	public void putGetAndIdempotentPut() {
		ConcurrentIntIntMap map = new ConcurrentIntIntMap();
		map.put((0 << 16) + 1, 3);
		assertEquals(3, map.get((0 << 16) + 1));
		assertEquals(1, map.size());
		// Same mapping: no growth, value unchanged.
		map.put((0 << 16) + 1, 3);
		assertEquals(3, map.get((0 << 16) + 1));
		assertEquals(1, map.size());
	}

	@Test
	public void clearEmpties() {
		ConcurrentIntIntMap map = new ConcurrentIntIntMap();
		map.put(1, 1);
		map.put(2, 2);
		map.clear();
		assertEquals(ConcurrentIntIntMap.MISSING, map.get(1));
		assertEquals(0, map.size());
	}

	@Test
	public void concurrentReadersAndWriters() throws Exception {
		final ConcurrentIntIntMap map = new ConcurrentIntIntMap();
		final int writers = 4;
		final int keysPerWriter = 200;
		ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
		try {
			List<Future<?>> futures = new ArrayList<Future<?>>();
			final AtomicInteger reads = new AtomicInteger();
			// Background readers
			for (int r = 0; r < 2; r++) {
				futures.add(pool.submit(new Callable<Void>() {
					@Override
					public Void call() {
						for (int i = 0; i < 5000; i++) {
							map.get(i % (writers * keysPerWriter));
							reads.incrementAndGet();
						}
						return null;
					}
				}));
			}
			// Writers insert disjoint key ranges
			for (int w = 0; w < writers; w++) {
				final int base = w * keysPerWriter;
				futures.add(pool.submit(new Callable<Void>() {
					@Override
					public Void call() {
						for (int i = 0; i < keysPerWriter; i++) {
							map.put(base + i, base + i + 1);
						}
						return null;
					}
				}));
			}
			for (Future<?> f : futures) {
				f.get();
			}
			assertTrue(reads.get() > 0);
			// Every written key must be visible with the expected value.
			for (int w = 0; w < writers; w++) {
				int base = w * keysPerWriter;
				for (int i = 0; i < keysPerWriter; i++) {
					assertEquals(base + i + 1, map.get(base + i));
				}
			}
			assertEquals(writers * keysPerWriter, map.size());
		}
		finally {
			pool.shutdownNow();
		}
	}
}
