/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Package-level coverage for {@link ArrayPredictionContext} and related
 * {@link PredictionContext} graph operations.
 */
public class TestArrayPredictionContextCoverage {

	private static PredictionContext singleton(int returnState) {
		return new SingletonPredictionContext(PredictionContext.EMPTY_FULL, returnState);
	}

	@Test
	public void packageCtorAndAccessors() {
		PredictionContext p0 = PredictionContext.EMPTY_FULL;
		PredictionContext p1 = PredictionContext.EMPTY_FULL;
		ArrayPredictionContext ctx = new ArrayPredictionContext(
			new PredictionContext[] { p0, p1 },
			new int[] { 10, 20 });
		assertEquals(2, ctx.size());
		assertEquals(10, ctx.getReturnState(0));
		assertEquals(20, ctx.getReturnState(1));
		assertSame(p0, ctx.getParent(0));
		assertFalse(ctx.isEmpty());
		assertFalse(ctx.hasEmpty());
		assertEquals(0, ctx.findReturnState(10));
		assertTrue(ctx.findReturnState(99) < 0);
	}

	@Test
	public void packageCtorWithHashCode() {
		PredictionContext[] parents = new PredictionContext[] {
			PredictionContext.EMPTY_FULL, PredictionContext.EMPTY_FULL
		};
		int[] returns = new int[] { 1, 2 };
		int hash = PredictionContext.calculateHashCode(parents, returns);
		ArrayPredictionContext ctx = new ArrayPredictionContext(parents, returns, hash);
		assertEquals(hash, ctx.hashCode());
		assertEquals(2, ctx.size());
	}

	@Test
	public void addAndRemoveEmptyContext() {
		ArrayPredictionContext base = new ArrayPredictionContext(
			new PredictionContext[] { PredictionContext.EMPTY_FULL, PredictionContext.EMPTY_FULL },
			new int[] { 5, 7 });
		assertFalse(base.hasEmpty());

		PredictionContext withEmpty = base.addEmptyContext();
		assertTrue(withEmpty.hasEmpty());
		assertTrue(withEmpty instanceof ArrayPredictionContext);
		// idempotent when already has empty
		assertSame(withEmpty, withEmpty.addEmptyContext());

		PredictionContext removed = withEmpty.removeEmptyContext();
		assertFalse(removed.hasEmpty());
		// removing empty from 2-element array that is [singleton, empty] may yield singleton
		ArrayPredictionContext twoWithEmpty = new ArrayPredictionContext(
			new PredictionContext[] { PredictionContext.EMPTY_FULL, PredictionContext.EMPTY_FULL },
			new int[] { 3, PredictionContext.EMPTY_FULL_STATE_KEY });
		assertTrue(twoWithEmpty.hasEmpty());
		PredictionContext asSingleton = twoWithEmpty.removeEmptyContext();
		assertTrue(asSingleton instanceof SingletonPredictionContext
			|| asSingleton instanceof ArrayPredictionContext);
	}

	@Test
	public void appendContextWithEmptySuffixAndSingletonSuffix() {
		PredictionContext a = singleton(1);
		PredictionContext b = singleton(2);
		PredictionContext joined = PredictionContext.join(a, b);
		assertTrue(joined instanceof ArrayPredictionContext);

		PredictionContextCache cache = new PredictionContextCache();
		// empty full suffix returns context unchanged
		PredictionContext same = joined.appendContext(PredictionContext.EMPTY_FULL, cache);
		assertEquals(joined, same);

		// append a singleton suffix
		PredictionContext suffix = singleton(99);
		PredictionContext appended = joined.appendContext(suffix, cache);
		assertTrue(appended.size() >= 1);
	}

	@Test
	public void equalsAndHashCodeBranches() {
		ArrayPredictionContext c1 = new ArrayPredictionContext(
			new PredictionContext[] { PredictionContext.EMPTY_FULL, PredictionContext.EMPTY_FULL },
			new int[] { 1, 2 });
		ArrayPredictionContext c2 = new ArrayPredictionContext(
			new PredictionContext[] { PredictionContext.EMPTY_FULL, PredictionContext.EMPTY_FULL },
			new int[] { 1, 2 });
		ArrayPredictionContext c3 = new ArrayPredictionContext(
			new PredictionContext[] { PredictionContext.EMPTY_FULL, PredictionContext.EMPTY_FULL },
			new int[] { 1, 3 });

		assertEquals(c1, c1);
		assertEquals(c1, c2);
		assertEquals(c1.hashCode(), c2.hashCode());
		assertNotEquals(c1, c3);
		assertNotEquals(c1, null);
		assertNotEquals(c1, "not-a-context");
		assertNotEquals(c1, PredictionContext.EMPTY_FULL);
	}

	@Test
	public void joinCreatesArrayAndToStrings() {
		PredictionContext a = singleton(11);
		PredictionContext b = singleton(22);
		PredictionContext c = singleton(33);
		PredictionContext ab = PredictionContext.join(a, b);
		PredictionContext abc = PredictionContext.join(ab, c);
		assertTrue(abc instanceof ArrayPredictionContext);
		assertTrue(abc.size() >= 2);

		String[] strings = abc.toStrings(null, 0);
		assertTrue(strings.length >= 1);

		// getCachedContext path
		PredictionContext cached = PredictionContext.getCachedContext(
			abc,
			new ConcurrentHashMap<PredictionContext, PredictionContext>(),
			new PredictionContext.IdentityHashMap());
		assertTrue(cached != null);
	}

	@Test
	public void fromRuleContextEmptyRoot() {
		ATN atn = new ATN(ATNType.PARSER, 1);
		atn.addState(new BasicState());
		// empty parent / root context (invokingState == -1)
		org.antlr.v4.runtime.ParserRuleContext root = new org.antlr.v4.runtime.ParserRuleContext();
		assertTrue(root.isEmpty());
		PredictionContext fromRoot = PredictionContext.fromRuleContext(atn, root);
		assertEquals(PredictionContext.EMPTY_FULL, fromRoot);
		PredictionContext local = PredictionContext.fromRuleContext(atn, root, false);
		assertEquals(PredictionContext.EMPTY_LOCAL, local);
	}

	@Test
	public void equalsWorkListBranches() throws Exception {
		PredictionContext sharedLeft = PredictionContext.EMPTY_FULL.getChild(4);
		PredictionContext sharedRight = PredictionContext.EMPTY_FULL.getChild(4);
		ArrayPredictionContext repeatedLeft = new ArrayPredictionContext(
			new PredictionContext[] { sharedLeft, sharedLeft },
			new int[] { 1, 2 });
		ArrayPredictionContext repeatedRight = new ArrayPredictionContext(
			new PredictionContext[] { sharedRight, sharedRight },
			new int[] { 1, 2 });
		assertEquals(repeatedLeft, repeatedRight);

		// empty parents share a hash but are not equal
		ArrayPredictionContext fullParents = new ArrayPredictionContext(
			new PredictionContext[] { PredictionContext.EMPTY_FULL, PredictionContext.EMPTY_FULL },
			new int[] { 1, 2 });
		ArrayPredictionContext localParent = new ArrayPredictionContext(
			new PredictionContext[] { PredictionContext.EMPTY_LOCAL, PredictionContext.EMPTY_FULL },
			new int[] { 1, 2 });
		assertEquals(fullParents.hashCode(), localParent.hashCode());
		assertNotEquals(fullParents, localParent);

		ArrayPredictionContext size2 = new ArrayPredictionContext(
			new PredictionContext[] { PredictionContext.EMPTY_FULL, PredictionContext.EMPTY_FULL },
			new int[] { 1, 2 });
		ArrayPredictionContext size3 = new ArrayPredictionContext(
			new PredictionContext[] {
				PredictionContext.EMPTY_FULL, PredictionContext.EMPTY_FULL, PredictionContext.EMPTY_FULL
			},
			new int[] { 1, 2, 3 });
		forceSameHash(size2, size3);
		assertNotEquals(size2, size3);

		ArrayPredictionContext otherReturns = new ArrayPredictionContext(
			new PredictionContext[] { PredictionContext.EMPTY_FULL, PredictionContext.EMPTY_FULL },
			new int[] { 1, 9 });
		forceSameHash(size2, otherReturns);
		assertNotEquals(size2, otherReturns);

		ArrayPredictionContext parentA = new ArrayPredictionContext(
			new PredictionContext[] { PredictionContext.EMPTY_FULL.getChild(1), PredictionContext.EMPTY_FULL },
			new int[] { 4, 5 });
		ArrayPredictionContext parentB = new ArrayPredictionContext(
			new PredictionContext[] { PredictionContext.EMPTY_FULL.getChild(2), PredictionContext.EMPTY_FULL },
			new int[] { 4, 5 });
		forceSameHash(parentA, parentB);
		assertNotEquals(parentA, parentB);
	}

	/** Equalize cached hashes so {@code equals} reaches structural mismatches. */
	private static void forceSameHash(PredictionContext a, PredictionContext b) throws Exception {
		Field hash = PredictionContext.class.getDeclaredField("cachedHashCode");
		hash.setAccessible(true);
		Field modifiers = Field.class.getDeclaredField("modifiers");
		modifiers.setAccessible(true);
		modifiers.setInt(hash, hash.getModifiers() & ~Modifier.FINAL);
		hash.setInt(a, 1);
		hash.setInt(b, 1);
	}
}
