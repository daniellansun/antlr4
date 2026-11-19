/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.misc.Interval;

/**
 * Alternative to {@link ANTLRInputStream} which treats the input
 * as a series of Unicode code points, instead of a series of UTF-16
 * code units.
 *
 * Use this if you need to parse input which potentially contains
 * Unicode values > U+FFFF.
 */
public abstract class CodePointCharStream implements UnicodeCharStream, CharStream {
	protected final int size;
	protected final String name;

	// To avoid lots of virtual method calls, we directly access
	// the state of the underlying code points in the
	// CodePointBuffer.
	protected int position;

	// Use the factory method {@link #fromBuffer(CodePointBuffer)} to
	// construct instances of this type.
	private CodePointCharStream(int position, int remaining, String name) {
		// TODO
		assert position == 0;
		this.size = remaining;
		this.name = name;
		this.position = 0;
	}

	// Visible for testing.
	abstract Object getInternalStorage();

	private static final String[] ASCII_STRINGS = new String[128];
	private static final String CRLF = "\r\n";

	static {
		for (int i = 0; i < ASCII_STRINGS.length; i++) {
			ASCII_STRINGS[i] = String.valueOf((char) i);
		}
	}

	/**
	 * Returns the UTF-16 encoded text spanning the inclusive code-point range
	 * {@code [start..stop]}, avoiding an intermediate {@link Interval}
	 * allocation on hot token and lexer paths.
	 *
	 * @since 4.13.2.19
	 */
	public abstract String getText(int start, int stop);

	/**
	 * Overflow-safe slice length for {@link #getText(int, int)}: computes
	 * {@code min((long) stop - start + 1, size - startIdx)} without 32-bit
	 * wraparound when {@code stop} is near {@link Integer#MIN_VALUE} or
	 * {@link Integer#MAX_VALUE}.
	 */
	static int computeTextLength(int start, int stop, int startIdx, int size) {
		long rawLen = (long) stop - (long) start + 1L;
		long avail = (long) size - (long) startIdx;
		long clamped = rawLen < avail ? rawLen : avail;
		if (clamped < 0L || clamped > Integer.MAX_VALUE) {
			return -1;
		}
		return (int) clamped;
	}

	/**
	 * Direct {@link String} factory avoiding intermediate
	 * {@link CodePointBuffer.Builder} / {@link java.nio.ByteBuffer} wrappers
	 * on the dominant Latin-1 path while preserving exact Unicode widening
	 * semantics when code points above {@code U+00FF} are encountered.
	 */
	static CodePointCharStream fromString(String s, String sourceName) {
		final int len = s.length();
		final byte[] out = new byte[len];
		int i = 0;
		final int limit = len - 3;
		for (; i < limit; i += 4) {
			char c0 = s.charAt(i);
			char c1 = s.charAt(i + 1);
			char c2 = s.charAt(i + 2);
			char c3 = s.charAt(i + 3);
			if (((c0 | c1 | c2 | c3) & 0xFF00) != 0) {
				break;
			}
			out[i] = (byte) c0;
			out[i + 1] = (byte) c1;
			out[i + 2] = (byte) c2;
			out[i + 3] = (byte) c3;
		}
		for (; i < len; i++) {
			char c = s.charAt(i);
			if ((c & 0xFF00) != 0) {
				return fromStringNonLatin1(s, sourceName, out, i, len);
			}
			out[i] = (byte) c;
		}
		return new CodePoint8BitCharStream(0, len, sourceName, out, 0);
	}

	private static CodePointCharStream fromStringNonLatin1(String s, String sourceName, byte[] latinPrefix, int splitIndex, int len) {
		char firstWide = s.charAt(splitIndex);
		if (!Character.isHighSurrogate(firstWide)) {
			final char[] out = new char[len];
			s.getChars(0, len, out, 0);
			for (int i = splitIndex + 1; i < len; i++) {
				if (Character.isHighSurrogate(out[i])) {
					return fromStringSurrogates(s, sourceName, out, i, len);
				}
			}
			return new CodePoint16BitCharStream(0, len, sourceName, out, 0);
		}
		return fromStringSurrogatesFromBytes(s, sourceName, latinPrefix, splitIndex, len);
	}

	private static CodePointCharStream fromStringSurrogates(String s, String sourceName, char[] bmpPrefix, int splitIndex, int len) {
		final int[] out = new int[len];
		for (int i = 0; i < splitIndex; i++) {
			out[i] = bmpPrefix[i];
		}
		int outIdx = appendCodePoints(s, splitIndex, len, out, splitIndex);
		return new CodePoint32BitCharStream(0, outIdx, sourceName, out, 0);
	}

	private static CodePointCharStream fromStringSurrogatesFromBytes(String s, String sourceName, byte[] latinPrefix, int splitIndex, int len) {
		final int[] out = new int[len];
		for (int i = 0; i < splitIndex; i++) {
			out[i] = latinPrefix[i] & 0xFF;
		}
		int outIdx = appendCodePoints(s, splitIndex, len, out, splitIndex);
		return new CodePoint32BitCharStream(0, outIdx, sourceName, out, 0);
	}

	private static int appendCodePoints(String s, int start, int len, int[] out, int outIdx) {
		int prevHigh = -1;
		for (int i = start; i < len; i++) {
			char c = s.charAt(i);
			if (prevHigh != -1) {
				if (Character.isLowSurrogate(c)) {
					out[outIdx++] = Character.toCodePoint((char) prevHigh, c);
					prevHigh = -1;
				}
				else {
					out[outIdx++] = prevHigh;
					if (Character.isHighSurrogate(c)) {
						prevHigh = c;
					}
					else {
						out[outIdx++] = c;
						prevHigh = -1;
					}
				}
			}
			else if (Character.isHighSurrogate(c)) {
				prevHigh = c;
			}
			else {
				out[outIdx++] = c;
			}
		}
		if (prevHigh != -1) {
			out[outIdx++] = prevHigh;
		}
		return outIdx;
	}

	/**
	 * Constructs a {@link CodePointCharStream} which provides access
	 * to the Unicode code points stored in {@code codePointBuffer}.
	 */
	public static CodePointCharStream fromBuffer(CodePointBuffer codePointBuffer) {
		return fromBuffer(codePointBuffer, UNKNOWN_SOURCE_NAME);
	}

	/**
	 * Constructs a named {@link CodePointCharStream} which provides access
	 * to the Unicode code points stored in {@code codePointBuffer}.
	 */
	public static CodePointCharStream fromBuffer(CodePointBuffer codePointBuffer, String name) {
		// Java lacks generics on primitive types.
		//
		// To avoid lots of calls to virtual methods in the
		// very hot codepath of LA() below, we construct one
		// of three concrete subclasses.
		//
		// The concrete subclasses directly access the code
		// points stored in the underlying array (byte[],
		// char[], or int[]), so we can avoid lots of virtual
		// method calls to ByteBuffer.get(offset).
		switch (codePointBuffer.getType()) {
			case BYTE:
				return new CodePoint8BitCharStream(
						codePointBuffer.position(),
						codePointBuffer.remaining(),
						name,
						codePointBuffer.byteArray(),
						codePointBuffer.arrayOffset());
			case CHAR:
				return new CodePoint16BitCharStream(
						codePointBuffer.position(),
						codePointBuffer.remaining(),
						name,
						codePointBuffer.charArray(),
						codePointBuffer.arrayOffset());
			case INT:
				return new CodePoint32BitCharStream(
						codePointBuffer.position(),
						codePointBuffer.remaining(),
						name,
						codePointBuffer.intArray(),
						codePointBuffer.arrayOffset());
		}
		throw new UnsupportedOperationException("Not reached");
	}

	@Override
	public final void consume() {
		if (size - position == 0) {
			rejectConsumeEof();
		}
		position = position + 1;
	}

	private void rejectConsumeEof() {
		assert LA(1) == IntStream.EOF;
		throw new IllegalStateException("cannot consume EOF");
	}

	@Override
	public final int index() {
		return position;
	}

	@Override
	public final int size() {
		return size;
	}

	/** mark/release do nothing; we have entire buffer */
	@Override
	public final int mark() {
		return -1;
	}

	@Override
	public final void release(int marker) {
	}

	@Override
	public final void seek(int index) {
		// The negative throw lives in another method so this one stays a
		// leaf. accept() calls seek once per token; an exception edge here
		// stops the JIT from inlining it into that path.
		if (index >= 0) {
			position = index > size ? size : index;
			return;
		}
		rejectNegativeSeek(index);
	}

	private static void rejectNegativeSeek(int index) {
		throw new IllegalArgumentException("cannot seek to negative index " + index);
	}

	@Override
	public final String getSourceName() {
		if (name == null || name.isEmpty()) {
			return UNKNOWN_SOURCE_NAME;
		}

		return name;
	}

	@Override
	public final String toString() {
		return getText(0, size - 1);
	}

	@Override
	public final boolean supportsUnicodeCodePoints() {
		return true;
	}

	// 8-bit storage for code points <= U+00FF.
	private static final class CodePoint8BitCharStream extends CodePointCharStream {
		private final byte[] byteArray;

		private CodePoint8BitCharStream(int position, int remaining, String name, byte[] byteArray, int arrayOffset) {
			super(position, remaining, name);
			// TODO
			assert arrayOffset == 0;
			this.byteArray = byteArray;
		}

		/** Return the UTF-16 encoded string for the given interval */
		@Override
		public String getText(Interval interval) {
			return getText(interval.a, interval.b);
		}

		@Override
		public String getText(int start, int stop) {
			int startIdx = Math.min(start, size);
			int len = computeTextLength(start, stop, startIdx, size);
			if (startIdx < 0 || len < 0 || startIdx > byteArray.length - len) {
				throw new StringIndexOutOfBoundsException("start=" + startIdx + ", len=" + len);
			}
			if (len == 0) {
				return "";
			}
			if (len == 1) {
				int b = byteArray[startIdx] & 0xFF;
				return b < 128 ? ASCII_STRINGS[b] : String.valueOf((char) b);
			}
			if (len == 2 && byteArray[startIdx] == '\r' && byteArray[startIdx + 1] == '\n') {
				return CRLF;
			}
			// Direct Latin-1 widening avoids JDK 8 ISO_8859_1$Decoder allocation
			// in String(byte[], int, int, Charset).
			char[] chars = new char[len];
			for (int i = 0; i < len; i++) {
				chars[i] = (char) (byteArray[startIdx + i] & 0xFF);
			}
			return new String(chars);
		}

		@Override
		public int LA(int i) {
			// PERF: Keep LA(1) compact for HotSpot inlining; outline non-1 lookahead.
			if (i == 1) {
				int p = position;
				return p < size ? byteArray[p] & 0xFF : IntStream.EOF;
			}
			return laSlow(i);
		}

		private int laSlow(int i) {
			int offset;
			switch (Integer.signum(i)) {
				case -1:
					offset = position + i;
					if (offset < 0) {
						return IntStream.EOF;
					}
					return byteArray[offset] & 0xFF;
				case 0:
					// Undefined
					return 0;
				case 1:
					offset = position + i - 1;
					if (offset >= size) {
						return IntStream.EOF;
					}
					return byteArray[offset] & 0xFF;
			}
			throw new UnsupportedOperationException("Not reached");
		}

		@Override
		Object getInternalStorage() {
			return byteArray;
		}
	}

	// 16-bit internal storage for code points between U+0100 and U+FFFF.
	private static final class CodePoint16BitCharStream extends CodePointCharStream {
		private final char[] charArray;

		private CodePoint16BitCharStream(int position, int remaining, String name, char[] charArray, int arrayOffset) {
			super(position, remaining, name);
			this.charArray = charArray;
			// TODO
			assert arrayOffset == 0;
		}

		/** Return the UTF-16 encoded string for the given interval */
		@Override
		public String getText(Interval interval) {
			return getText(interval.a, interval.b);
		}

		@Override
		public String getText(int start, int stop) {
			int startIdx = Math.min(start, size);
			int len = computeTextLength(start, stop, startIdx, size);
			if (startIdx >= 0 && len >= 0 && startIdx <= charArray.length - len) {
				if (len == 0) {
					return "";
				}
				if (len == 1) {
					char c = charArray[startIdx];
					if (c < 128) {
						return ASCII_STRINGS[c];
					}
				}
				else if (len == 2 && charArray[startIdx] == '\r' && charArray[startIdx + 1] == '\n') {
					return CRLF;
				}
			}

			// We know there are no surrogates in this
			// array, since otherwise we would be given a
			// 32-bit int[] array.
			//
			// So, it's safe to treat this as if it were
			// UTF-16.
			return new String(charArray, startIdx, len);
		}

		@Override
		public int LA(int i) {
			if (i == 1) {
				int p = position;
				return p < size ? charArray[p] & 0xFFFF : IntStream.EOF;
			}
			return laSlow(i);
		}

		private int laSlow(int i) {
			int offset;
			switch (Integer.signum(i)) {
				case -1:
					offset = position + i;
					if (offset < 0) {
						return IntStream.EOF;
					}
					return charArray[offset] & 0xFFFF;
				case 0:
					// Undefined
					return 0;
				case 1:
					offset = position + i - 1;
					if (offset >= size) {
						return IntStream.EOF;
					}
					return charArray[offset] & 0xFFFF;
			}
			throw new UnsupportedOperationException("Not reached");
		}

		@Override
		Object getInternalStorage() {
			return charArray;
		}
	}

	// 32-bit internal storage for code points between U+10000 and U+10FFFF.
	private static final class CodePoint32BitCharStream extends CodePointCharStream {
		private final int[] intArray;

		private CodePoint32BitCharStream(int position, int remaining, String name, int[] intArray, int arrayOffset) {
			super(position, remaining, name);
			this.intArray = intArray;
			// TODO
			assert arrayOffset == 0;
		}

		/** Return the UTF-16 encoded string for the given interval */
		@Override
		public String getText(Interval interval) {
			return getText(interval.a, interval.b);
		}

		@Override
		public String getText(int start, int stop) {
			int startIdx = Math.min(start, size);
			int len = computeTextLength(start, stop, startIdx, size);

			// Note that we pass the int[] code points to the String constructor --
			// this is supported, and the constructor will convert to UTF-16 internally.
			return new String(intArray, startIdx, len);
		}

		@Override
		public int LA(int i) {
			if (i == 1) {
				return position < size ? intArray[position] : IntStream.EOF;
			}
			return laSlow(i);
		}

		private int laSlow(int i) {
			int offset;
			switch (Integer.signum(i)) {
				case -1:
					offset = position + i;
					if (offset < 0) {
						return IntStream.EOF;
					}
					return intArray[offset];
				case 0:
					// Undefined
					return 0;
				case 1:
					offset = position + i - 1;
					if (offset >= size) {
						return IntStream.EOF;
					}
					return intArray[offset];
			}
			throw new UnsupportedOperationException("Not reached");
		}

		@Override
		Object getInternalStorage() {
			return intArray;
		}
	}
}
