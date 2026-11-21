/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;

import java.util.BitSet;

/**
 * Package-private O(n) zero-sort conflict detector for {@link ATNConfigSet}.
 *
 * <p>
 * Implements Sam Harwell's exact conflict definition: a configuration set
 * {@code C} (with {@code |C| > 1} and no unique alternative) is conflicted iff
 * every represented {@link ATNState#getNonStopStateNumber() non-stop state}
 * {@code s} contains at least one configuration predicting {@code minAlt} and
 * for every {@code (s, a)} with {@code a != minAlt}, the joined context
 * {@code M = join(contexts(s, minAlt))} subsumes every context {@code c} of
 * {@code (s, a)} (by the join-semilattice axiom
 * {@code M join (A join B) = M <=> M join A = M && M join B = M}).</p>
 *
 * <p>
 * <strong>Ownership:</strong> One instance per {@link ParserATNSimulator}.
 * Scratch arrays are lazily allocated and sparsely cleared in {@code finally}
 * so no {@link PredictionContext} references are retained across calls. Not
 * shared across threads.</p>
 *
 * <p>Not part of any {@code public} or {@code protected} API.</p>
 */
final class ConflictDetector {

	private static final int INITIAL_CAPACITY = 32;

	private int[] stateKeys;
	private PredictionContext[] minAltCtx;
	private long[] stateAltMask;
	private int[] usedSlots;

	/**
	 * Convenience one-shot entry point for same-package tests (allocates a
	 * fresh detector instance so concurrent tests never share mutable scratch).
	 */
	@Nullable
	static ConflictInfo detectOnce(@NotNull ATNConfigSet configset,
								   @Nullable PredictionContextCache contextCache)
	{
		return new ConflictDetector().detect(configset, contextCache);
	}

	private void ensureTable(int requiredSlots) {
		int cap = INITIAL_CAPACITY;
		while (cap < requiredSlots && cap < (1 << 30)) {
			cap <<= 1;
		}
		if (stateKeys == null || stateKeys.length < cap) {
			stateKeys = new int[cap];
			minAltCtx = new PredictionContext[cap];
			stateAltMask = new long[cap];
			usedSlots = new int[cap];
		}
	}

	/**
	 * Detects whether {@code configset} represents an ATN conflict.
	 *
	 * @param configset the configuration set to inspect
	 * @param contextCache prediction-context cache (or {@code null} for uncached)
	 * @return a {@link ConflictInfo} if conflicted, or {@code null} otherwise
	 */
	@Nullable
	ConflictInfo detect(@NotNull ATNConfigSet configset,
						@Nullable PredictionContextCache contextCache)
	{
		final int configCnt = configset.size();
		if (configset.getUniqueAlt() != ATN.INVALID_ALT_NUMBER || configCnt <= 1) {
			return null;
		}

		if (contextCache == null) {
			contextCache = PredictionContextCache.UNCACHED;
		}

		// Pass 0: find global minimum alternative across all configs.
		int minAlt = Integer.MAX_VALUE;
		for (int i = 0; i < configCnt; i++) {
			int alt = configset.get(i).getAlt();
			if (alt < minAlt) {
				minAlt = alt;
				if (minAlt == 1) {
					break;
				}
			}
		}

		// Open-addressed table sized for <= 0.5 load factor (worst case: every
		// config has a distinct non-stop state).
		final int required = configCnt <= (1 << 29) ? (configCnt << 1) : configCnt;
		ensureTable(required);

		final int[] keys = stateKeys;
		final PredictionContext[] minCtxs = minAltCtx;
		final long[] altMasks = stateAltMask;
		final int[] slots = usedSlots;
		final int mask = keys.length - 1;
		final boolean minAltInMask = (minAlt & ~63) == 0;
		final long minAltBit = minAltInMask ? (1L << minAlt) : 0L;

		int stateCount = 0;
		boolean sawStopState = false;
		boolean overflowAlt = !minAltInMask;

		try {
			// Pass 1: insert ONLY alt == minAlt configs so every occupied slot
			// strictly satisfies minCtxs[slot] != null (empty-slot sentinel).
			for (int i = 0; i < configCnt; i++) {
				ATNConfig c = configset.get(i);
				if (c.getAlt() != minAlt) {
					continue;
				}
				int stateKey = c.getState().getNonStopStateNumber();
				if (stateKey == -1) {
					sawStopState = true;
				}
				PredictionContext cCtx = c.getContext();
				int slot = mix(stateKey) & mask;
				while (true) {
					PredictionContext existing = minCtxs[slot];
					if (existing == null) {
						keys[slot] = stateKey;
						minCtxs[slot] = cCtx;
						altMasks[slot] = minAltBit;
						slots[stateCount++] = slot;
						break;
					}
					if (keys[slot] == stateKey) {
						if (existing != cCtx) {
							minCtxs[slot] = contextCache.join(existing, cCtx);
						}
						break;
					}
					slot = (slot + 1) & mask;
				}
			}

			if (stateCount == 0) {
				return null;
			}

			// Production addDFAState always passes a writable base ATNConfigSet;
			// read-only sets and custom subclasses (e.g. OrderedATNConfigSet) may
			// retain duplicate (nonStopState, alt) entries outside unmerged.
			final boolean hasDuplicates = configset.isReadOnly()
				|| configset.getClass() != ATNConfigSet.class
				|| configset.hasUnmerged()
				|| sawStopState;
			boolean exact = !configset.getDipsIntoOuterContext();
			boolean needsExactVerify = false;
			boolean sawOtherAlt = false;

			// Pass 2: check all alt != minAlt configs for missing minAlt states,
			// context subsumption, per-state alt bitmasks, and exactness.
			for (int i = 0; i < configCnt; i++) {
				ATNConfig c = configset.get(i);
				int alt = c.getAlt();
				if (alt == minAlt) {
					continue;
				}
				sawOtherAlt = true;
				int stateKey = c.getState().getNonStopStateNumber();
				int slot = mix(stateKey) & mask;
				PredictionContext minCtx;
				while (true) {
					minCtx = minCtxs[slot];
					if (minCtx == null) {
						// State lacks minAlt -> cannot be conflicted.
						return null;
					}
					if (keys[slot] == stateKey) {
						break;
					}
					slot = (slot + 1) & mask;
				}

				if ((alt & ~63) == 0) {
					altMasks[slot] |= (1L << alt);
				}
				else {
					overflowAlt = true;
				}

				PredictionContext cCtx = c.getContext();
				if (cCtx != minCtx) {
					if (minCtx != PredictionContext.EMPTY_LOCAL) {
						PredictionContext joined = contextCache.join(minCtx, cCtx);
						if (joined != minCtx && !minCtx.equals(joined)) {
							return null;
						}
					}
					if (exact) {
						if (!hasDuplicates) {
							if (!minCtx.equals(cCtx)) {
								exact = false;
							}
						}
						else {
							needsExactVerify = true;
						}
					}
				}
			}

			if (!sawOtherAlt) {
				return null;
			}

			// Check represented-alternatives equality across ALL states (including
			// the last state, fixing the latent omission in prior sorting walks).
			if (exact && !overflowAlt) {
				final long expectedMask = altMasks[slots[0]];
				for (int k = 1; k < stateCount; k++) {
					if (altMasks[slots[k]] != expectedMask) {
						exact = false;
						break;
					}
				}
			}

			// Build conflicted alternatives BitSet only now that conflict is confirmed.
			BitSet alts = new BitSet();
			if (!overflowAlt) {
				long unionMask = 0L;
				for (int k = 0; k < stateCount; k++) {
					unionMask |= altMasks[slots[k]];
				}
				while (unionMask != 0L) {
					int bit = Long.numberOfTrailingZeros(unionMask);
					alts.set(bit);
					unionMask &= (unionMask - 1L);
				}
			}
			else {
				for (int i = 0; i < configCnt; i++) {
					alts.set(configset.get(i).getAlt());
				}
			}

			if (exact && (overflowAlt || needsExactVerify)) {
				exact = verifyExactSlow(configset, contextCache, minAlt, stateCount, mask);
			}

			return new ConflictInfo(alts, exact);
		}
		finally {
			for (int k = 0; k < stateCount; k++) {
				minCtxs[slots[k]] = null;
			}
		}
	}

	/**
	 * Cold exactness verifier used only when {@code alt >= 64}
	 * or when multiple configs share a {@code (nonStopState, alt)} pair (stop
	 * states, custom subclasses, or unmerged semantic-context configs). Joins all contexts per
	 * {@code (slotIndex, alt)} and verifies both identical represented alt sets
	 * across every state and exact joined-context equality with {@code minAlt}.
	 */
	private boolean verifyExactSlow(@NotNull ATNConfigSet configset,
									@NotNull PredictionContextCache contextCache,
									int minAlt,
									int stateCount,
									int mask)
	{
		final int[] keys = stateKeys;
		final PredictionContext[] minCtxs = minAltCtx;
		final int[] slots = usedSlots;
		final int configCnt = configset.size();

		ClearableLongObjectHashMap<PredictionContext> joinedBySlotAlt = new ClearableLongObjectHashMap<>(configCnt);
		BitSet[] stateAlts = stateCount > 1 ? new BitSet[stateCount] : null;
		int[] slotToIdx = new int[keys.length];
		for (int k = 0; k < stateCount; k++) {
			slotToIdx[slots[k]] = k;
			if (stateAlts != null) {
				stateAlts[k] = new BitSet();
				stateAlts[k].set(minAlt);
			}
		}

		for (int i = 0; i < configCnt; i++) {
			ATNConfig c = configset.get(i);
			int alt = c.getAlt();
			if (alt == minAlt) {
				continue;
			}
			int stateKey = c.getState().getNonStopStateNumber();
			int slot = mix(stateKey) & mask;
			while (keys[slot] != stateKey) {
				slot = (slot + 1) & mask;
			}
			int idx = slotToIdx[slot];
			if (stateAlts != null && alt >= 0) {
				stateAlts[idx].set(alt);
			}
			long pairKey = (((long) idx) << 32) | (alt & 0xFFFFFFFFL);
			int mapIdx = joinedBySlotAlt.indexOf(pairKey);
			PredictionContext cCtx = c.getContext();
			if (mapIdx < 0) {
				joinedBySlotAlt.indexInsert(mapIdx, pairKey, cCtx);
			}
			else {
				PredictionContext prev = joinedBySlotAlt.indexGet(mapIdx);
				if (prev != cCtx) {
					joinedBySlotAlt.indexReplace(mapIdx, contextCache.join(prev, cCtx));
				}
			}
		}

		if (stateAlts != null) {
			BitSet expected = stateAlts[0];
			for (int k = 1; k < stateCount; k++) {
				if (!expected.equals(stateAlts[k])) {
					return false;
				}
			}
		}

		for (int i = 0; i < configCnt; i++) {
			ATNConfig c = configset.get(i);
			int alt = c.getAlt();
			if (alt == minAlt) {
				continue;
			}
			int stateKey = c.getState().getNonStopStateNumber();
			int slot = mix(stateKey) & mask;
			while (keys[slot] != stateKey) {
				slot = (slot + 1) & mask;
			}
			int idx = slotToIdx[slot];
			long pairKey = (((long) idx) << 32) | (alt & 0xFFFFFFFFL);
			int mapIdx = joinedBySlotAlt.indexOf(pairKey);
			PredictionContext joinedAltCtx = joinedBySlotAlt.indexGet(mapIdx);
			PredictionContext minCtx = minCtxs[slot];
			if (minCtx != joinedAltCtx && !minCtx.equals(joinedAltCtx)) {
				return false;
			}
		}

		return true;
	}

	private static int mix(int h) {
		h *= 0x9E3779B9;
		return h ^ (h >>> 16);
	}
}
