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
 * allocated only on a cache miss when the entry is stored. Probe fields are
 * cleared after each lookup so they do not pin otherwise-unreachable contexts.
 * {@link #join} may re-enter itself recursively via
 * {@link PredictionContext#join}; the probe key is only used for the duration
 * of each individual map lookup, and map insertions always use freshly
 * allocated immutable keys.</p>
 *
 * @author Sam Harwell
 */
public class PredictionContextCache {
	public static final PredictionContextCache UNCACHED = new PredictionContextCache(false);

	private final Map<PredictionContext, PredictionContext> contexts;
	private final Map<PredictionContextAndInt, PredictionContext> childContexts;
	private final Map<IdentityCommutativePredictionContextOperands, PredictionContext> joinContexts;

	/**
	 * Reusable probe key for {@link #getChild} lookups. Never inserted into
	 * {@link #childContexts}; only permanent keys are stored on a miss.
	 * {@code null} when caching is disabled.
	 */
	private final PredictionContextAndInt childLookupKey;

	/**
	 * Reusable probe key for {@link #join} lookups. Never inserted into
	 * {@link #joinContexts}; only permanent keys are stored on a miss.
	 * {@code null} when caching is disabled.
	 */
	private final IdentityCommutativePredictionContextOperands joinLookupKey;

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
			this.childLookupKey = new PredictionContextAndInt();
			this.joinLookupKey = new IdentityCommutativePredictionContextOperands();
		} else {
			// UNCACHED: no maps or probe keys — methods return before touching them.
			this.contexts = null;
			this.childContexts = null;
			this.joinContexts = null;
			this.childLookupKey = null;
			this.joinLookupKey = null;
		}
	}

	/**
	 * Returns whether this cache stores contexts. The shared {@link #UNCACHED}
	 * instance always returns {@code false}.
	 */
	public final boolean isEnableCache() {
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

		childLookupKey.set(context, invokingState);
		PredictionContext result = childContexts.get(childLookupKey);
		// Drop the strong reference held by the probe key as soon as the lookup
		// completes so short-lived contexts remain eligible for GC.
		childLookupKey.clear();
		if (result == null) {
			result = context.getChild(invokingState);
			result = getAsCached(result);
			// Store a permanent key; do not insert the reusable probe key.
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
		joinLookupKey.set(x, y);
		PredictionContext result = joinContexts.get(joinLookupKey);
		joinLookupKey.clear();
		if (result != null) {
			return result;
		}

		result = PredictionContext.join(x, y, this);
		result = getAsCached(result);
		// Store a permanent key; do not insert the reusable probe key.
		joinContexts.put(new IdentityCommutativePredictionContextOperands(x, y), result);
		return result;
	}

	/**
	 * Composite key for ({@link PredictionContext}, {@code int}) used by
	 * {@link #getChild}. Supports mutation for probe-only lookups via
	 * {@link #set}; map entries always use keys constructed with the
	 * two-argument constructor so their fields never change after insertion.
	 */
	protected static final class PredictionContextAndInt {
		private PredictionContext obj;
		private int value;

		/** Creates an uninitialized probe key; use {@link #set} before lookup. */
		PredictionContextAndInt() {
		}

		public PredictionContextAndInt(PredictionContext obj, int value) {
			this.obj = obj;
			this.value = value;
		}

		void set(PredictionContext obj, int value) {
			this.obj = obj;
			this.value = value;
		}

		void clear() {
			this.obj = null;
			this.value = 0;
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof PredictionContextAndInt)) {
				return false;
			} else if (obj == this) {
				return true;
			}

			PredictionContextAndInt other = (PredictionContextAndInt)obj;
			return this.value == other.value
				&& (Objects.equals(this.obj, other.obj));
		}

		@Override
		public int hashCode() {
			int hashCode = 5;
			hashCode = 7 * hashCode + (this.obj != null ? this.obj.hashCode() : 0);
			hashCode = 7 * hashCode + value;
			return hashCode;
		}
	}

	/**
	 * Identity-based commutative key for a pair of {@link PredictionContext}
	 * instances used by {@link #join} and by
	 * {@link ArrayPredictionContext} equality. Supports mutation for probe-only
	 * lookups via {@link #set}; map entries always use keys constructed with the
	 * two-argument constructor so their fields never change after insertion.
	 */
	protected static final class IdentityCommutativePredictionContextOperands {

		private PredictionContext x;
		private PredictionContext y;

		/** Creates an uninitialized probe key; use {@link #set} before lookup. */
		IdentityCommutativePredictionContextOperands() {
		}

		public IdentityCommutativePredictionContextOperands(PredictionContext x, PredictionContext y) {
			this.x = x;
			this.y = y;
		}

		void set(PredictionContext x, PredictionContext y) {
			this.x = x;
			this.y = y;
		}

		void clear() {
			this.x = null;
			this.y = null;
		}

		public PredictionContext getX() {
			return x;
		}

		public PredictionContext getY() {
			return y;
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof IdentityCommutativePredictionContextOperands)) {
				return false;
			}
			else if (this == obj) {
				return true;
			}

			IdentityCommutativePredictionContextOperands other = (IdentityCommutativePredictionContextOperands)obj;
			return (this.x == other.x && this.y == other.y) || (this.x == other.y && this.y == other.x);
		}

		@Override
		public int hashCode() {
			// Null-safe so an uninitialized probe key never throws from HashMap.
			int hx = x != null ? x.hashCode() : 0;
			int hy = y != null ? y.hashCode() : 0;
			return hx ^ hy;
		}
	}

}
