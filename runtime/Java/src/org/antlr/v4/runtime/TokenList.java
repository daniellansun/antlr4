/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.RandomAccess;

/**
 * Package-private chunked {@link Token} list backing {@link BufferedTokenStream}.
 *
 * <p>Stores tokens in fixed 256-slot {@code Token[]} chunks (1 KB per chunk on
 * 64-bit CompressedOops). Appending never copies existing token references,
 * eliminating {@link java.util.ArrayList} resize copies, throwaway initial
 * backing arrays, and G1GC remembered-set copy barriers during lazy token
 * fetching and {@link BufferedTokenStream#fill()}.</p>
 *
 * <p>Implements {@link java.util.List} and {@link RandomAccess} so
 * {@link BufferedTokenStream#tokens} and {@link BufferedTokenStream#getTokens()}
 * retain full JDK {@link java.util.List} semantics. Package-private hot-path
 * methods ({@link #appendFast}, {@link #getFast}) avoid bounds checks and
 * {@code modCount} updates when the stream is in its standard mode.</p>
 */
final class TokenList extends AbstractList<Token> implements RandomAccess {
	static final int CHUNK_SHIFT = 8;
	static final int CHUNK_SIZE = 1 << CHUNK_SHIFT; // 256
	static final int CHUNK_MASK = CHUNK_SIZE - 1;
	private static final int INITIAL_CHUNK_DIR = 4;

	private Token[][] chunks;
	private Token[] curChunk;
	private int curChunkIndex;
	private int curOffset = CHUNK_SIZE;
	int size;

	TokenList() {
	}

	/**
	 * Monomorphic append fast path for {@link BufferedTokenStream} /
	 * {@link CommonTokenStream}. Never copies prior token arrays.
	 */
	void appendFast(Token t) {
		modCount++;
		int off = curOffset;
		Token[] chunk = curChunk;
		if (off == CHUNK_SIZE) {
			chunk = advanceChunk();
			off = 0;
		}
		chunk[off] = t;
		curOffset = off + 1;
		size++;
	}

	private Token[] advanceChunk() {
		if (chunks == null) {
			chunks = new Token[INITIAL_CHUNK_DIR][];
			Token[] first = new Token[CHUNK_SIZE];
			chunks[0] = first;
			curChunk = first;
			curChunkIndex = 0;
			curOffset = 0;
			return first;
		}
		int nextIdx = curChunkIndex + 1;
		if (nextIdx >= chunks.length) {
			chunks = Arrays.copyOf(chunks, chunks.length * 2);
		}
		Token[] next = chunks[nextIdx];
		if (next == null) {
			next = new Token[CHUNK_SIZE];
			chunks[nextIdx] = next;
		}
		curChunk = next;
		curChunkIndex = nextIdx;
		curOffset = 0;
		return next;
	}

	/**
	 * Unchecked random access for stream hot paths where {@code 0 <= index < size}
	 * has already been established.
	 */
	Token getFast(int index) {
		return chunks[index >>> CHUNK_SHIFT][index & CHUNK_MASK];
	}

	@Override
	public int size() {
		return size;
	}

	@Override
	public boolean isEmpty() {
		return size == 0;
	}

	@Override
	public Token get(int index) {
		if (index < 0 || index >= size) {
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
		}
		return chunks[index >>> CHUNK_SHIFT][index & CHUNK_MASK];
	}

	@Override
	public Token set(int index, Token element) {
		if (index < 0 || index >= size) {
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
		}
		Token[] chunk = chunks[index >>> CHUNK_SHIFT];
		int slot = index & CHUNK_MASK;
		Token old = chunk[slot];
		chunk[slot] = element;
		return old;
	}

	@Override
	public boolean add(Token element) {
		appendFast(element);
		return true;
	}

	@Override
	public void add(int index, Token element) {
		if (index < 0 || index > size) {
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
		}
		modCount++;
		if (index == size) {
			appendFast(element);
			return;
		}
		// Append dummy slot at end, then shift right from index..size-2
		appendFast(null);
		for (int i = size - 1; i > index; i--) {
			chunks[i >>> CHUNK_SHIFT][i & CHUNK_MASK] =
				chunks[(i - 1) >>> CHUNK_SHIFT][(i - 1) & CHUNK_MASK];
		}
		chunks[index >>> CHUNK_SHIFT][index & CHUNK_MASK] = element;
	}

	@Override
	public Token remove(int index) {
		if (index < 0 || index >= size) {
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
		}
		modCount++;
		Token old = chunks[index >>> CHUNK_SHIFT][index & CHUNK_MASK];
		int last = size - 1;
		for (int i = index; i < last; i++) {
			chunks[i >>> CHUNK_SHIFT][i & CHUNK_MASK] =
				chunks[(i + 1) >>> CHUNK_SHIFT][(i + 1) & CHUNK_MASK];
		}
		chunks[last >>> CHUNK_SHIFT][last & CHUNK_MASK] = null;
		size = last;
		if (size == 0) {
			curChunkIndex = 0;
			curChunk = chunks[0];
			curOffset = 0;
		}
		else {
			int tailIndex = (size - 1) >>> CHUNK_SHIFT;
			curChunkIndex = tailIndex;
			curChunk = chunks[tailIndex];
			curOffset = ((size - 1) & CHUNK_MASK) + 1;
		}
		return old;
	}

	@Override
	public void clear() {
		modCount++;
		if (chunks == null) {
			size = 0;
			return;
		}
		if (chunks.length > INITIAL_CHUNK_DIR) {
			chunks = null;
			curChunk = null;
			curChunkIndex = 0;
			curOffset = CHUNK_SIZE;
			size = 0;
			return;
		}
		if (size > 0) {
			int firstClear = Math.min(size, CHUNK_SIZE);
			Arrays.fill(chunks[0], 0, firstClear, null);
			int usedChunks = (size + CHUNK_SIZE - 1) >>> CHUNK_SHIFT;
			if (usedChunks > 1) {
				Arrays.fill(chunks, 1, usedChunks, null);
			}
		}
		size = 0;
		curChunkIndex = 0;
		curChunk = chunks[0];
		curOffset = 0;
	}
}
