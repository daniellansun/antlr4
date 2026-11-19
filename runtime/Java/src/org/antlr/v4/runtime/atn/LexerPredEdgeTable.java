/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Per-mode cache of predicate-guarded ASCII lexer DFA edges.
 *
 * <p>When {@link LexerATNSimulator#computeTargetState} traverses a
 * {@link PredicateTransition}, standard ANTLR suppresses the static DFA edge
 * so the predicate continues to be evaluated dynamically on every future
 * occurrence of {@code (state, char)}. In grammars such as Groovy where hot
 * ASCII characters ({@code '/'}, {@code '\''}, {@code 'l'}) traverse a semantic
 * predicate from a state with many active ATN configurations, recomputing full
 * ATN reach and closure on every occurrence dominates lexing CPU.</p>
 *
 * <p>Because {@code s.configs} and transition ordering are immutable, the
 * target {@link DFAState} (or {@link ATNSimulator#ERROR}) for a given
 * {@code (state, t)} with no offset-dependent {@link LexerActionExecutor}
 * mutation is a deterministic function of the sequence of
 * {@code (ruleIndex, predIndex)} boolean outcomes evaluated during
 * {@link LexerATNSimulator#getReachableConfigSet}. This table stores that
 * deterministic binary decision tree per {@code (stateNumber, asciiChar)} so
 * warm lookups evaluate the exact same predicates in the exact same order
 * without scanning ATN configurations or running epsilon closure.</p>
 *
 * <p>Package-private: never exposed in any {@code public} or {@code protected}
 * API. Bound to the mode's {@link #dfaS0} identity so even a direct
 * {@link org.antlr.v4.runtime.dfa.DFA#clear()} invalidates stale entries.</p>
 */
final class LexerPredEdgeTable {
	/** Maximum predicate decision depth cached for a single (state, char) edge. */
	static final int MAX_PRED_DEPTH = 8;

	/** ASCII slot count (0..127). */
	private static final int ASCII_SLOTS = 128;

	/**
	 * Decision tree node representing one speculative predicate evaluation.
	 * Each branch points to either the next {@link PredNode}, a leaf
	 * {@link DFAState} (including {@link ATNSimulator#ERROR}), or {@code null}
	 * if that outcome branch has not been observed yet.
	 */
	static final class PredNode {
		final int ruleIndex;
		final int predIndex;
		volatile Object onTrue;
		volatile Object onFalse;

		PredNode(int ruleIndex, int predIndex) {
			this.ruleIndex = ruleIndex;
			this.predIndex = predIndex;
		}
	}

	/** Start-state identity of the owning mode DFA when this table was created. */
	final DFAState dfaS0;

	/**
	 * Indexed by {@link DFAState#stateNumber}; each non-null row is an
	 * {@code ASCII_SLOTS}-element {@link AtomicReferenceArray} of root
	 * {@link PredNode}s. Outer table is updated copy-on-write under
	 * {@code synchronized (this)} and row slots are published via
	 * {@link AtomicReferenceArray#set} so lock-free reads always have
	 * full JMM happens-before visibility on all architectures.
	 */
	@SuppressWarnings("unchecked")
	private volatile AtomicReferenceArray<PredNode>[] byState =
		(AtomicReferenceArray<PredNode>[]) new AtomicReferenceArray<?>[16];

	LexerPredEdgeTable(@NotNull DFAState dfaS0) {
		this.dfaS0 = dfaS0;
	}

	/**
	 * Lock-free lookup of the predicate decision tree root for
	 * {@code (stateNumber, asciiChar)}. Returns {@code null} if none is cached.
	 */
	@Nullable
	PredNode getRoot(int stateNumber, int asciiChar) {
		if (stateNumber < 0 || (asciiChar & ~0x7F) != 0) {
			return null;
		}
		AtomicReferenceArray<PredNode>[] table = byState;
		if (stateNumber >= table.length) {
			return null;
		}
		AtomicReferenceArray<PredNode> row = table[stateNumber];
		if (row == null) {
			return null;
		}
		return row.get(asciiChar);
	}

	/**
	 * Installs a predicate decision path leading to {@code target} (a
	 * {@link DFAState}, including {@link ATNSimulator#ERROR}) for
	 * {@code (stateNumber, asciiChar)}.
	 */
	synchronized void put(int stateNumber,
						  int asciiChar,
						  int[] ruleIndices,
						  int[] predIndices,
						  boolean[] results,
						  int length,
						  @NotNull DFAState target) {
		if (stateNumber < 0 || length <= 0 || length > MAX_PRED_DEPTH || (asciiChar & ~0x7F) != 0) {
			return;
		}

		AtomicReferenceArray<PredNode>[] table = byState;
		AtomicReferenceArray<PredNode> row = stateNumber < table.length ? table[stateNumber] : null;
		if (row == null) {
			row = new AtomicReferenceArray<>(ASCII_SLOTS);
			PredNode root = new PredNode(ruleIndices[0], predIndices[0]);
			installChain(root, ruleIndices, predIndices, results, 0, length, target);
			row.set(asciiChar, root);
			int newLen = stateNumber >= table.length
				? Math.max(table.length * 2, stateNumber + 1)
				: table.length;
			AtomicReferenceArray<PredNode>[] nextTable = Arrays.copyOf(table, newLen);
			nextTable[stateNumber] = row;
			byState = nextTable;
			return;
		}

		PredNode cur = row.get(asciiChar);
		if (cur == null) {
			cur = new PredNode(ruleIndices[0], predIndices[0]);
			installChain(cur, ruleIndices, predIndices, results, 0, length, target);
			row.set(asciiChar, cur);
			return;
		}

		// Walk existing prefix and attach the new branch.
		for (int i = 0; i < length; i++) {
			if (cur.ruleIndex != ruleIndices[i] || cur.predIndex != predIndices[i]) {
				// Defensive: non-matching predicate order; do not corrupt tree.
				return;
			}
			boolean r = results[i];
			Object next = r ? cur.onTrue : cur.onFalse;
			if (i == length - 1) {
				if (next == null) {
					if (r) {
						cur.onTrue = target;
					}
					else {
						cur.onFalse = target;
					}
				}
				return;
			}
			if (next == null) {
				PredNode child = new PredNode(ruleIndices[i + 1], predIndices[i + 1]);
				installChain(child, ruleIndices, predIndices, results, i + 1, length, target);
				if (r) {
					cur.onTrue = child;
				}
				else {
					cur.onFalse = child;
				}
				return;
			}
			if (!(next instanceof PredNode)) {
				// Existing leaf at shorter depth; leave intact.
				return;
			}
			cur = (PredNode) next;
		}
	}

	private static void installChain(PredNode start,
									 int[] ruleIndices,
									 int[] predIndices,
									 boolean[] results,
									 int startIdx,
									 int length,
									 DFAState target) {
		PredNode cur = start;
		for (int i = startIdx; i < length; i++) {
			boolean r = results[i];
			Object next;
			if (i == length - 1) {
				next = target;
			}
			else {
				next = new PredNode(ruleIndices[i + 1], predIndices[i + 1]);
			}
			if (r) {
				cur.onTrue = next;
			}
			else {
				cur.onFalse = next;
			}
			if (next instanceof PredNode) {
				cur = (PredNode) next;
			}
		}
	}
}
