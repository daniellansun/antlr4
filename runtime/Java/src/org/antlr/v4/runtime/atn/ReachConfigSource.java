/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;

import java.util.ArrayList;

/**
 * Random-access view of ATN configurations for a single
 * {@link ParserATNSimulator#computeTargetState} invocation.
 *
 * <p>
 * Starts as a zero-allocation view over the source {@link ATNConfigSet}. When
 * full-context prediction must rewrite every config with an appended return
 * state, the view materializes a mutable list once and all subsequent reads go
 * through that list. Callers iterate with {@link #size()} / {@link #get(int)}
 * only — no dual-null checks in the reach loop.</p>
 *
 * <p>
 * PERF: A single instance is retained on {@link ReachComputation} (one per
 * {@link ParserATNSimulator}) and rebound via
 * {@link #reset(ATNConfigSet)}/{@link #release()} around each edge so the
 * view object and the full-context rewrite {@link ArrayList} keep capacity
 * across predictions. SLL paths never touch the list.</p>
 *
 * <p>Package-private: owned by the ATN prediction hot path.</p>
 */
final class ReachConfigSource {

	@Nullable
	private ATNConfigSet set;

	/**
	 * Active read source after the first {@link #appendContext}; {@code null}
	 * while still viewing {@link #set} directly.
	 */
	@Nullable
	private ArrayList<ATNConfig> list;

	/**
	 * Capacity-preserving rewrite buffer. Assigned to {@link #list} on first
	 * materialization; cleared by {@link #release()}.
	 */
	@Nullable
	private ArrayList<ATNConfig> rewriteScratch;

	/**
	 * Bind this source to {@code set} for a new edge computation. Always
	 * starts as a zero-copy view (no materialization).
	 */
	void reset(@NotNull ATNConfigSet set) {
		this.set = set;
		this.list = null;
	}

	/**
	 * Drop config-graph references held by the rewrite buffer; keep list
	 * capacity. Safe to call when {@link #reset} was never invoked.
	 *
	 * <p>After {@code release()}, this view is invalid until the next
	 * {@link #reset(ATNConfigSet)}. {@link #size()} / {@link #get(int)} throw
	 * {@link IllegalStateException} if used while unbound.</p>
	 */
	void release() {
		this.set = null;
		this.list = null;
		if (rewriteScratch != null) {
			rewriteScratch.clear();
		}
	}

	int size() {
		if (list != null) {
			return list.size();
		}
		if (set == null) {
			throw new IllegalStateException("ReachConfigSource is not bound; call reset first");
		}
		return set.size();
	}

	@NotNull
	ATNConfig get(int index) {
		if (list != null) {
			return list.get(index);
		}
		if (set == null) {
			throw new IllegalStateException("ReachConfigSource is not bound; call reset first");
		}
		return set.get(index);
	}

	/**
	 * Appends {@code returnState} to every configuration's prediction context.
	 * Materializes a mutable copy of the source set on first call, reusing the
	 * retained rewrite scratch list when available.
	 */
	void appendContext(int returnState, @NotNull PredictionContextCache contextCache) {
		if (list == null) {
			int n = set.size();
			ArrayList<ATNConfig> scratch = rewriteScratch;
			if (scratch == null) {
				scratch = new ArrayList<ATNConfig>(n);
				rewriteScratch = scratch;
			}
			else {
				scratch.clear();
				scratch.ensureCapacity(n);
			}
			for (int i = 0; i < n; i++) {
				scratch.add(set.get(i));
			}
			list = scratch;
		}
		for (int i = 0, n = list.size(); i < n; i++) {
			list.set(i, list.get(i).appendContext(returnState, contextCache));
		}
	}

	/**
	 * Whether this source has materialized a mutable list (full-context
	 * rewrite path). Package-private for tests.
	 */
	boolean isMaterialized() {
		return list != null;
	}

	/**
	 * Retained rewrite scratch after first materialization, else {@code null}.
	 * Same-package tests may observe capacity-preserving reuse.
	 */
	@Nullable
	ArrayList<ATNConfig> rewriteScratch() {
		return rewriteScratch;
	}
}
