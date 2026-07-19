/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Used to cache {@link PredictionContext} objects. It is used for the shared
 * context cache associated with contexts in DFA states. This cache can be used
 * for both lexers and parsers.
 *
 * <p>
 * Cache instances created with {@link #PredictionContextCache()} are intended
 * for single-threaded use during a prediction operation. Concurrent access from
 * multiple threads is not supported (the shared {@link #UNCACHED} instance is
 * safe because caching is disabled and no mutable probe state is retained).</p>
 *
 * <p>
 * PERF: {@link #getChild} and {@link #join} use mutable probe keys for map
 * lookups so a cache hit does not allocate a key object. Permanent keys are
 * allocated only on a cache miss when the entry is stored. Probe types are
 * distinct from permanent key types so a probe can never be inserted into the
 * map: the maps are typed as {@code Map<PredictionContextAndInt, …>} and
 * {@code Map<IdentityCommutativePredictionContextOperands, …>}, while
 * {@link Map#get(Object)} still accepts probes because equality is defined on
 * the shared {@code ChildKey}/{@code JoinKey} bases.</p>
 *
 * <p>
 * {@link #join} may re-enter itself recursively via
 * {@link PredictionContext#join}; the probe key is only used for the duration
 * of each individual map lookup, and map insertions always use freshly
 * allocated immutable keys.</p>
 *
 * @author Sam Harwell
 */
public class PredictionContextCache {
	public static final PredictionContextCache UNCACHED = new PredictionContextCache(false);

	private final Map<PredictionContextAndInt, PredictionContext> childContexts;
	private final Map<IdentityCommutativePredictionContextOperands, PredictionContext> joinContexts;
	private final Map<PredictionContext, PredictionContext> contexts;

	/**
	 * Reusable probe for {@link #getChild}. Never passed to {@link Map#put}.
	 */
	private final ChildKeyProbe childProbe;

	/**
	 * Reusable probe for {@link #join}. Never passed to {@link Map#put}.
	 */
	private final JoinKeyProbe joinProbe;

	private final boolean enableCache;

	public PredictionContextCache() {
		this(true);
	}

	private PredictionContextCache(boolean enableCache) {
		this.enableCache = enableCache;
		if (enableCache) {
			this.contexts = new HashMap<PredictionContext, PredictionContext>();
			this.childContexts = new HashMap<PredictionContextAndInt, PredictionContext>();
			this.joinContexts = new HashMap<IdentityCommutativePredictionContextOperands, PredictionContext>();
			this.childProbe = new ChildKeyProbe();
			this.joinProbe = new JoinKeyProbe();
		}
		else {
			// UNCACHED: no maps or probes — methods return before touching them.
			this.contexts = null;
			this.childContexts = null;
			this.joinContexts = null;
			this.childProbe = null;
			this.joinProbe = null;
		}
	}

	/**
	 * Returns whether this cache stores contexts. The shared {@link #UNCACHED}
	 * instance always returns {@code false}.
	 *
	 * <p>Package-private: production code branches on {@link #UNCACHED}
	 * identity or simply calls the cache methods; tests may inspect this
	 * flag.</p>
	 */
	final boolean isEnableCache() {
		return enableCache;
	}

	public PredictionContext getAsCached(PredictionContext context) {
		if (!enableCache) {
			return context;
		}

		PredictionContext result = contexts.get(context);
		if (result == null) {
			result = context;
			contexts.put(context, context);
		}

		return result;
	}

	public PredictionContext getChild(PredictionContext context, int invokingState) {
		if (!enableCache) {
			return context.getChild(invokingState);
		}

		childProbe.set(context, invokingState);
		PredictionContext result = childContexts.get(childProbe);
		// Drop the strong reference held by the probe as soon as the lookup
		// completes so short-lived contexts remain eligible for GC.
		childProbe.clear();
		if (result == null) {
			result = context.getChild(invokingState);
			result = getAsCached(result);
			// Permanent immutable key only — probes are a different type and
			// cannot be passed here without a compile error.
			childContexts.put(new PredictionContextAndInt(context, invokingState), result);
		}

		return result;
	}

	public PredictionContext join(PredictionContext x, PredictionContext y) {
		if (!enableCache) {
			return PredictionContext.join(x, y, this);
		}

		// Fast path: joining a context with itself is a no-op.
		if (x == y) {
			return getAsCached(x);
		}

		// Probe-key lookup is not re-entrant: PredictionContext.join may call
		// back into this method for parent contexts. Only the get() uses the
		// probe; the recursive work below uses permanent keys exclusively.
		joinProbe.set(x, y);
		PredictionContext result = joinContexts.get(joinProbe);
		joinProbe.clear();
		if (result != null) {
			return result;
		}

		result = PredictionContext.join(x, y, this);
		result = getAsCached(result);
		joinContexts.put(new IdentityCommutativePredictionContextOperands(x, y), result);
		return result;
	}

	// -------------------------------------------------------------------------
	// Child keys: immutable permanent entry vs mutable probe (distinct types).
	// -------------------------------------------------------------------------

	/**
	 * Shared equality/hash contract for ({@link PredictionContext}, {@code int})
	 * keys used by {@link #getChild}. Permanent entries use
	 * {@link PredictionContextAndInt}; lookups use {@link ChildKeyProbe}.
	 */
	abstract static class ChildKey {
		abstract PredictionContext context();
		abstract int invokingState();

		@Override
		public final boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			if (!(o instanceof ChildKey)) {
				return false;
			}
			ChildKey other = (ChildKey)o;
			return invokingState() == other.invokingState()
				&& Objects.equals(context(), other.context());
		}

		@Override
		public final int hashCode() {
			int hashCode = 5;
			PredictionContext ctx = context();
			hashCode = 7 * hashCode + (ctx != null ? ctx.hashCode() : 0);
			hashCode = 7 * hashCode + invokingState();
			return hashCode;
		}
	}

	/**
	 * Immutable map key for ({@link PredictionContext}, {@code int}) stored in
	 * {@link #childContexts}. Fields are final so entries cannot be mutated
	 * after insertion.
	 */
	protected static final class PredictionContextAndInt extends ChildKey {
		private final PredictionContext obj;
		private final int value;

		public PredictionContextAndInt(PredictionContext obj, int value) {
			this.obj = obj;
			this.value = value;
		}

		@Override
		PredictionContext context() {
			return obj;
		}

		@Override
		int invokingState() {
			return value;
		}
	}

	/**
	 * Mutable probe for {@link #getChild} lookups. Package-private and not a
	 * {@link PredictionContextAndInt}, so it cannot be passed to
	 * {@link Map#put} on {@code Map<ChildKey, …>} only through an erroneous
	 * cast — and the cache never exposes the map.
	 */
	private static final class ChildKeyProbe extends ChildKey {
		private PredictionContext obj;
		private int value;

		void set(PredictionContext obj, int value) {
			this.obj = obj;
			this.value = value;
		}

		void clear() {
			this.obj = null;
			this.value = 0;
		}

		@Override
		PredictionContext context() {
			return obj;
		}

		@Override
		int invokingState() {
			return value;
		}
	}

	// -------------------------------------------------------------------------
	// Join keys: immutable permanent entry vs mutable probe (distinct types).
	// -------------------------------------------------------------------------

	/**
	 * Shared equality/hash contract for identity-based commutative pairs of
	 * {@link PredictionContext} used by {@link #join} and by
	 * {@link ArrayPredictionContext} equality.
	 */
	abstract static class JoinKey {
		abstract PredictionContext left();
		abstract PredictionContext right();

		@Override
		public final boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			if (!(o instanceof JoinKey)) {
				return false;
			}
			JoinKey other = (JoinKey)o;
			PredictionContext x = left();
			PredictionContext y = right();
			PredictionContext ox = other.left();
			PredictionContext oy = other.right();
			return (x == ox && y == oy) || (x == oy && y == ox);
		}

		@Override
		public final int hashCode() {
			PredictionContext x = left();
			PredictionContext y = right();
			int hx = x != null ? x.hashCode() : 0;
			int hy = y != null ? y.hashCode() : 0;
			return hx ^ hy;
		}
	}

	/**
	 * Immutable identity-based commutative key for a pair of
	 * {@link PredictionContext} instances. Used as map entries in
	 * {@link #joinContexts} and as visited-set elements in
	 * {@link ArrayPredictionContext}.
	 */
	protected static final class IdentityCommutativePredictionContextOperands extends JoinKey {
		private final PredictionContext x;
		private final PredictionContext y;

		public IdentityCommutativePredictionContextOperands(PredictionContext x, PredictionContext y) {
			this.x = x;
			this.y = y;
		}

		public PredictionContext getX() {
			return x;
		}

		public PredictionContext getY() {
			return y;
		}

		@Override
		PredictionContext left() {
			return x;
		}

		@Override
		PredictionContext right() {
			return y;
		}
	}

	/**
	 * Mutable probe for {@link #join} lookups. Distinct from
	 * {@link IdentityCommutativePredictionContextOperands} so it cannot be
	 * stored as a permanent map key through the cache API.
	 */
	private static final class JoinKeyProbe extends JoinKey {
		private PredictionContext x;
		private PredictionContext y;

		void set(PredictionContext x, PredictionContext y) {
			this.x = x;
			this.y = y;
		}

		void clear() {
			this.x = null;
			this.y = null;
		}

		@Override
		PredictionContext left() {
			return x;
		}

		@Override
		PredictionContext right() {
			return y;
		}
	}
}
