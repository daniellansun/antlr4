/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import java.util.Arrays;
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
 * safe because caching is disabled and no mutable state is retained).</p>
 *
 * <p>
 * PERF: Backed by three monomorphic open-addressed tables
 * ({@code ContextInternSet}, {@code ChildContextMap}, {@code JoinContextMap})
 * at 0.5 load factor with sparse O(size) {@link #clear()}. This eliminates
 * {@code HashMap.Node}, {@link PredictionContextAndInt}, and
 * {@link IdentityCommutativePredictionContextOperands} wrapper allocations on
 * misses, avoids megamorphic key {@code equals}/{@code hashCode} dispatch,
 * checks pointer identity and cached hash codes before structural comparison,
 * and avoids clearing oversized backing arrays after a single large full-context
 * decision.</p>
 *
 * <p>
 * {@link #join} may re-enter itself recursively via
 * {@link PredictionContext#join}; {@code JoinContextMap} performs its lookup
 * before recursing and inserts only after the recursive join returns.</p>
 *
 * @author Sam Harwell
 */
public class PredictionContextCache {
	public static final PredictionContextCache UNCACHED = new PredictionContextCache(false);

	/** Initial power-of-two slot count for each table. */
	private static final int INITIAL_CAPACITY = 64;

	/** Initial sparse-slot log capacity; grows lazily up to {@link #SPARSE_TRACK_LIMIT}. */
	private static final int INITIAL_SPARSE_TRACK = 128;

	/** Maximum occupied slots tracked for O(size) sparse clear. */
	private static final int SPARSE_TRACK_LIMIT = 4096;

	/** Shrink oversized tables back on clear above this threshold when sparsely populated. */
	private static final int MAX_RETAINED_CAPACITY = 16384;

	private final ContextInternSet contexts;
	private final ChildContextMap childContexts;
	private final JoinContextMap joinContexts;

	private final boolean enableCache;

	public PredictionContextCache() {
		this(true);
	}

	private PredictionContextCache(boolean enableCache) {
		this.enableCache = enableCache;
		if (enableCache) {
			this.contexts = new ContextInternSet();
			this.childContexts = new ChildContextMap();
			this.joinContexts = new JoinContextMap();
		}
		else {
			// UNCACHED: no tables — methods return before touching them.
			this.contexts = null;
			this.childContexts = null;
			this.joinContexts = null;
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
		if (!enableCache || context == null) {
			return context;
		}

		return contexts.getOrAdd(context);
	}

	/**
	 * Drops every cached entry so this instance can be retained across
	 * predictions on a single simulator thread. No-op for {@link #UNCACHED}.
	 *
	 * <p>Package-private: only the ATN simulators recycle a retained cache.</p>
	 */
	final void clear() {
		if (!enableCache) {
			return;
		}
		contexts.clear();
		childContexts.clear();
		joinContexts.clear();
	}

	public PredictionContext getChild(PredictionContext context, int invokingState) {
		if (!enableCache) {
			return context.getChild(invokingState);
		}

		return childContexts.getOrCompute(context, invokingState, contexts);
	}

	public PredictionContext join(PredictionContext x, PredictionContext y) {
		if (!enableCache) {
			return PredictionContext.join(x, y, this);
		}

		// Fast path: joining a context with itself is a no-op.
		if (x == y) {
			return getAsCached(x);
		}

		PredictionContext result = joinContexts.get(x, y);
		if (result != null) {
			return result;
		}

		result = PredictionContext.join(x, y, this);
		result = getAsCached(result);
		joinContexts.put(x, y, result);
		return result;
	}

	// -------------------------------------------------------------------------
	// Monomorphic zero-key-allocation open-addressed tables with sparse clear.
	// -------------------------------------------------------------------------

	private static int mix(int h) {
		h ^= (h >>> 16);
		return h;
	}

	/**
	 * Monomorphic intern set for {@link PredictionContext} (single array,
	 * load factor 0.5, O(size) sparse clear).
	 */
	private static final class ContextInternSet {
		private PredictionContext[] table = new PredictionContext[INITIAL_CAPACITY];
		private int size;
		private int resizeAt = INITIAL_CAPACITY >>> 1;
		private int[] usedSlots = new int[INITIAL_SPARSE_TRACK];
		private int usedCount;

		PredictionContext getOrAdd(PredictionContext ctx) {
			final PredictionContext[] t = table;
			final int mask = t.length - 1;
			final int h = ctx.hashCode();
			int slot = mix(h) & mask;
			while (true) {
				PredictionContext existing = t[slot];
				if (existing == null) {
					t[slot] = ctx;
					int uc = usedCount;
					if (uc >= 0) {
						if (uc < usedSlots.length) {
							usedSlots[uc] = slot;
							usedCount = uc + 1;
						}
						else if (uc < SPARSE_TRACK_LIMIT) {
							usedSlots = Arrays.copyOf(usedSlots, Math.min(SPARSE_TRACK_LIMIT, usedSlots.length << 1));
							usedSlots[uc] = slot;
							usedCount = uc + 1;
						}
						else {
							usedCount = -1;
						}
					}
					if (++size >= resizeAt) {
						rehash();
					}
					return ctx;
				}
				if (existing == ctx || (existing.hashCode() == h && existing.equals(ctx))) {
					return existing;
				}
				slot = (slot + 1) & mask;
			}
		}

		private void rehash() {
			final PredictionContext[] old = table;
			final int newLen = old.length << 1;
			final int mask = newLen - 1;
			final PredictionContext[] next = new PredictionContext[newLen];
			for (int i = 0; i < old.length; i++) {
				PredictionContext ctx = old[i];
				if (ctx != null) {
					int slot = mix(ctx.hashCode()) & mask;
					while (next[slot] != null) {
						slot = (slot + 1) & mask;
					}
					next[slot] = ctx;
				}
			}
			table = next;
			resizeAt = newLen >>> 1;
			usedCount = -1;
		}

		void clear() {
			if (size == 0) {
				return;
			}
			final PredictionContext[] t = table;
			if (t.length > MAX_RETAINED_CAPACITY && size <= (t.length >>> 2)) {
				table = new PredictionContext[INITIAL_CAPACITY];
				resizeAt = INITIAL_CAPACITY >>> 1;
			}
			else if (usedCount >= 0 && usedCount <= (t.length >>> 2)) {
				final int[] slots = usedSlots;
				for (int i = 0, n = usedCount; i < n; i++) {
					t[slots[i]] = null;
				}
			}
			else {
				Arrays.fill(t, null);
			}
			size = 0;
			usedCount = 0;
		}
	}

	/**
	 * Monomorphic zero-wrapper map for {@code (PredictionContext, int) -> PredictionContext}.
	 */
	private static final class ChildContextMap {
		private PredictionContext[] keyCtx = new PredictionContext[INITIAL_CAPACITY];
		private int[] keyState = new int[INITIAL_CAPACITY];
		private PredictionContext[] vals = new PredictionContext[INITIAL_CAPACITY];
		private int size;
		private int resizeAt = INITIAL_CAPACITY >>> 1;
		private int[] usedSlots = new int[INITIAL_SPARSE_TRACK];
		private int usedCount;

		private static int childHash(int ctxHash, int invokingState) {
			int h = (ctxHash * 31 + invokingState) * 0x9E3779B9;
			return h ^ (h >>> 16);
		}

		PredictionContext getOrCompute(PredictionContext context, int invokingState, ContextInternSet internSet) {
			final PredictionContext[] kCtx = keyCtx;
			final int[] kState = keyState;
			final int mask = kCtx.length - 1;
			final int ctxHash = context.hashCode();
			int slot = childHash(ctxHash, invokingState) & mask;
			while (true) {
				PredictionContext k = kCtx[slot];
				if (k == null) {
					PredictionContext result = context.getChild(invokingState);
					result = internSet.getOrAdd(result);
					// Neither getChild nor internSet mutates this map, so slot is still empty.
					kCtx[slot] = context;
					kState[slot] = invokingState;
					vals[slot] = result;
					int uc = usedCount;
					if (uc >= 0) {
						if (uc < usedSlots.length) {
							usedSlots[uc] = slot;
							usedCount = uc + 1;
						}
						else if (uc < SPARSE_TRACK_LIMIT) {
							usedSlots = Arrays.copyOf(usedSlots, Math.min(SPARSE_TRACK_LIMIT, usedSlots.length << 1));
							usedSlots[uc] = slot;
							usedCount = uc + 1;
						}
						else {
							usedCount = -1;
						}
					}
					if (++size >= resizeAt) {
						rehash();
					}
					return result;
				}
				if (kState[slot] == invokingState
					&& (k == context || (k.hashCode() == ctxHash && k.equals(context)))) {
					return vals[slot];
				}
				slot = (slot + 1) & mask;
			}
		}

		private void rehash() {
			final PredictionContext[] oldCtx = keyCtx;
			final int[] oldState = keyState;
			final PredictionContext[] oldVals = vals;
			final int newLen = oldCtx.length << 1;
			final int mask = newLen - 1;
			final PredictionContext[] nextCtx = new PredictionContext[newLen];
			final int[] nextState = new int[newLen];
			final PredictionContext[] nextVals = new PredictionContext[newLen];
			for (int i = 0; i < oldCtx.length; i++) {
				PredictionContext k = oldCtx[i];
				if (k != null) {
					int st = oldState[i];
					int slot = childHash(k.hashCode(), st) & mask;
					while (nextCtx[slot] != null) {
						slot = (slot + 1) & mask;
					}
					nextCtx[slot] = k;
					nextState[slot] = st;
					nextVals[slot] = oldVals[i];
				}
			}
			keyCtx = nextCtx;
			keyState = nextState;
			vals = nextVals;
			resizeAt = newLen >>> 1;
			usedCount = -1;
		}

		void clear() {
			if (size == 0) {
				return;
			}
			final PredictionContext[] kCtx = keyCtx;
			final PredictionContext[] v = vals;
			if (kCtx.length > MAX_RETAINED_CAPACITY && size <= (kCtx.length >>> 2)) {
				keyCtx = new PredictionContext[INITIAL_CAPACITY];
				keyState = new int[INITIAL_CAPACITY];
				vals = new PredictionContext[INITIAL_CAPACITY];
				resizeAt = INITIAL_CAPACITY >>> 1;
			}
			else if (usedCount >= 0 && usedCount <= (kCtx.length >>> 2)) {
				final int[] slots = usedSlots;
				for (int i = 0, n = usedCount; i < n; i++) {
					int s = slots[i];
					kCtx[s] = null;
					v[s] = null;
				}
			}
			else {
				Arrays.fill(kCtx, null);
				Arrays.fill(v, null);
			}
			size = 0;
			usedCount = 0;
		}
	}

	/**
	 * Monomorphic zero-wrapper commutative identity map for
	 * {@code (PredictionContext x, PredictionContext y) -> PredictionContext}.
	 * Uses pointer identity exclusively (zero {@code equals()} dispatch).
	 */
	private static final class JoinContextMap {
		private PredictionContext[] keyLeft = new PredictionContext[INITIAL_CAPACITY];
		private PredictionContext[] keyRight = new PredictionContext[INITIAL_CAPACITY];
		private PredictionContext[] vals = new PredictionContext[INITIAL_CAPACITY];
		private int size;
		private int resizeAt = INITIAL_CAPACITY >>> 1;
		private int[] usedSlots = new int[INITIAL_SPARSE_TRACK];
		private int usedCount;

		private static int pairHash(PredictionContext x, PredictionContext y) {
			int hx = x != null ? x.hashCode() : 0;
			int hy = y != null ? y.hashCode() : 0;
			return mix((hx ^ hy) + ((hx + hy) * 0x9E3779B9));
		}

		PredictionContext get(PredictionContext x, PredictionContext y) {
			final PredictionContext[] lefts = keyLeft;
			final PredictionContext[] rights = keyRight;
			final PredictionContext[] v = vals;
			final int mask = lefts.length - 1;
			int slot = pairHash(x, y) & mask;
			while (true) {
				PredictionContext val = v[slot];
				if (val == null) {
					return null;
				}
				PredictionContext lx = lefts[slot];
				PredictionContext rx = rights[slot];
				if ((lx == x && rx == y) || (lx == y && rx == x)) {
					return val;
				}
				slot = (slot + 1) & mask;
			}
		}

		void put(PredictionContext x, PredictionContext y, PredictionContext result) {
			final PredictionContext[] lefts = keyLeft;
			final PredictionContext[] rights = keyRight;
			final PredictionContext[] v = vals;
			final int mask = lefts.length - 1;
			int slot = pairHash(x, y) & mask;
			while (true) {
				PredictionContext val = v[slot];
				if (val == null) {
					lefts[slot] = x;
					rights[slot] = y;
					v[slot] = result;
					int uc = usedCount;
					if (uc >= 0) {
						if (uc < usedSlots.length) {
							usedSlots[uc] = slot;
							usedCount = uc + 1;
						}
						else if (uc < SPARSE_TRACK_LIMIT) {
							usedSlots = Arrays.copyOf(usedSlots, Math.min(SPARSE_TRACK_LIMIT, usedSlots.length << 1));
							usedSlots[uc] = slot;
							usedCount = uc + 1;
						}
						else {
							usedCount = -1;
						}
					}
					if (++size >= resizeAt) {
						rehash();
					}
					return;
				}
				PredictionContext lx = lefts[slot];
				PredictionContext rx = rights[slot];
				if ((lx == x && rx == y) || (lx == y && rx == x)) {
					v[slot] = result;
					return;
				}
				slot = (slot + 1) & mask;
			}
		}

		private void rehash() {
			final PredictionContext[] oldLeft = keyLeft;
			final PredictionContext[] oldRight = keyRight;
			final PredictionContext[] oldVals = vals;
			final int newLen = oldLeft.length << 1;
			final int mask = newLen - 1;
			final PredictionContext[] nextLeft = new PredictionContext[newLen];
			final PredictionContext[] nextRight = new PredictionContext[newLen];
			final PredictionContext[] nextVals = new PredictionContext[newLen];
			for (int i = 0; i < oldVals.length; i++) {
				PredictionContext val = oldVals[i];
				if (val != null) {
					PredictionContext lx = oldLeft[i];
					PredictionContext rx = oldRight[i];
					int slot = pairHash(lx, rx) & mask;
					while (nextVals[slot] != null) {
						slot = (slot + 1) & mask;
					}
					nextLeft[slot] = lx;
					nextRight[slot] = rx;
					nextVals[slot] = val;
				}
			}
			keyLeft = nextLeft;
			keyRight = nextRight;
			vals = nextVals;
			resizeAt = newLen >>> 1;
			usedCount = -1;
		}

		void clear() {
			if (size == 0) {
				return;
			}
			final PredictionContext[] lefts = keyLeft;
			final PredictionContext[] rights = keyRight;
			final PredictionContext[] v = vals;
			if (v.length > MAX_RETAINED_CAPACITY && size <= (v.length >>> 2)) {
				keyLeft = new PredictionContext[INITIAL_CAPACITY];
				keyRight = new PredictionContext[INITIAL_CAPACITY];
				vals = new PredictionContext[INITIAL_CAPACITY];
				resizeAt = INITIAL_CAPACITY >>> 1;
			}
			else if (usedCount >= 0 && usedCount <= (v.length >>> 2)) {
				final int[] slots = usedSlots;
				for (int i = 0, n = usedCount; i < n; i++) {
					int s = slots[i];
					lefts[s] = null;
					rights[s] = null;
					v[s] = null;
				}
			}
			else {
				Arrays.fill(lefts, null);
				Arrays.fill(rights, null);
				Arrays.fill(v, null);
			}
			size = 0;
			usedCount = 0;
		}
	}

	// -------------------------------------------------------------------------
	// Protected key classes preserved for backward API compatibility.
	// -------------------------------------------------------------------------

	/**
	 * Shared equality/hash contract for ({@link PredictionContext}, {@code int})
	 * keys.
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
			PredictionContext ctx = context();
			int ctxHash = ctx != null ? ctx.hashCode() : 0;
			return (ctxHash * 0x9E3779B9) ^ invokingState();
		}
	}

	/**
	 * Immutable map key for ({@link PredictionContext}, {@code int}).
	 * Preserved as a {@code protected} API type for subclasses and tests.
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
	 * Shared equality/hash contract for identity-based commutative pairs of
	 * {@link PredictionContext} used by {@link ArrayPredictionContext} equality.
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
			// Commutative in (hx, hy) and non-zero when hx == hy != 0,
			// avoiding bucket-0 clustering on equal-hash operand pairs.
			return (hx ^ hy) + ((hx + hy) * 0x9E3779B9);
		}
	}

	/**
	 * Immutable identity-based commutative key for a pair of
	 * {@link PredictionContext} instances. Used as visited-set elements in
	 * {@link ArrayPredictionContext} and preserved for backward compatibility.
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
}
