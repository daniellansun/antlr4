/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;

/**
 * Lazily allocated, capacity-preserving scratch {@link ATNConfigSet} for
 * prediction and lexing hot paths.
 *
 * <p>
 * <strong>Ownership contract (single rule):</strong> {@link #obtain(int)}
 * always returns an <em>empty</em> writable set (clears on reuse). Callers must
 * {@link #release()} in a {@code finally} after the edge / closure work so
 * config-graph references are dropped between uses while list/map capacity is
 * retained. DFA storage must {@link ATNConfigSet#clone(boolean) clone} before
 * {@link #release()}.</p>
 *
 * <p>Package-private: shared by {@link ParserATNSimulator},
 * {@link LexerATNSimulator}, and tests in this package.</p>
 */
final class RetainedConfigSet {

	private final boolean ordered;

	@Nullable
	private ATNConfigSet buffer;

	/**
	 * @param ordered {@code true} for {@link OrderedATNConfigSet} (lexer),
	 * {@code false} for plain {@link ATNConfigSet} (parser)
	 */
	RetainedConfigSet(boolean ordered) {
		this.ordered = ordered;
	}

	/**
	 * Returns the retained buffer, allocating on first use. Always empty on
	 * return (clears when reusing an existing buffer).
	 *
	 * @param sourceSize hint for initial capacity ({@link ATNConfigSet#scratchCapacity})
	 */
	@NotNull
	ATNConfigSet obtain(int sourceSize) {
		ATNConfigSet set = buffer;
		if (set == null) {
			int capacity = ATNConfigSet.scratchCapacity(sourceSize);
			set = ordered ? new OrderedATNConfigSet(capacity) : new ATNConfigSet(capacity);
			buffer = set;
		}
		else {
			set.clear();
		}
		return set;
	}

	/**
	 * Drops config-graph references held by the buffer; keeps capacity.
	 * No-op if {@link #obtain(int)} has never been called.
	 */
	void release() {
		if (buffer != null) {
			buffer.clear();
		}
	}

	/**
	 * Underlying buffer after first {@link #obtain(int)}, else {@code null}.
	 * Same-package tests may observe identity and emptiness.
	 */
	@Nullable
	ATNConfigSet buffer() {
		return buffer;
	}
}
