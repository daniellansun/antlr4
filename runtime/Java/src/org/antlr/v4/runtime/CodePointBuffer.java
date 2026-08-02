/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.IntBuffer;

/**
 * Wrapper for {@link ByteBuffer} / {@link CharBuffer} / {@link IntBuffer}.
 *
 * Because Java lacks generics on primitive types, these three types
 * do not share an interface, so we have to write one manually.
 */
public class CodePointBuffer {
	public enum Type {
			BYTE,
			CHAR,
			INT
	}
	private final Type type;
	private final ByteBuffer byteBuffer;
	private final CharBuffer charBuffer;
	private final IntBuffer intBuffer;

	private CodePointBuffer(Type type, ByteBuffer byteBuffer, CharBuffer charBuffer, IntBuffer intBuffer) {
		this.type = type;
		this.byteBuffer = byteBuffer;
		this.charBuffer = charBuffer;
		this.intBuffer = intBuffer;
	}

	public static CodePointBuffer withBytes(ByteBuffer byteBuffer) {
		return new CodePointBuffer(Type.BYTE, byteBuffer, null, null);
	}

	public static CodePointBuffer withChars(CharBuffer charBuffer) {
		return new CodePointBuffer(Type.CHAR, null, charBuffer, null);
	}

	public static CodePointBuffer withInts(IntBuffer intBuffer) {
		return new CodePointBuffer(Type.INT, null, null, intBuffer);
	}

	public int position() {
		switch (type) {
			case BYTE:
				return byteBuffer.position();
			case CHAR:
				return charBuffer.position();
			case INT:
				return intBuffer.position();
		}
		throw new UnsupportedOperationException("Not reached");
	}

	public void position(int newPosition) {
		switch (type) {
			case BYTE:
				byteBuffer.position(newPosition);
				break;
			case CHAR:
				charBuffer.position(newPosition);
				break;
			case INT:
				intBuffer.position(newPosition);
				break;
		}
	}

	public int remaining() {
		switch (type) {
			case BYTE:
				return byteBuffer.remaining();
			case CHAR:
				return charBuffer.remaining();
			case INT:
				return intBuffer.remaining();
		}
		throw new UnsupportedOperationException("Not reached");
	}

	public int get(int offset) {
		switch (type) {
			case BYTE:
				return byteBuffer.get(offset);
			case CHAR:
				return charBuffer.get(offset);
			case INT:
				return intBuffer.get(offset);
		}
		throw new UnsupportedOperationException("Not reached");
	}

	Type getType() {
		return type;
	}

	int arrayOffset() {
		switch (type) {
			case BYTE:
				return byteBuffer.arrayOffset();
			case CHAR:
				return charBuffer.arrayOffset();
			case INT:
				return intBuffer.arrayOffset();
		}
		throw new UnsupportedOperationException("Not reached");
	}

	byte[] byteArray() {
		assert type == Type.BYTE;
		return byteBuffer.array();
	}

	char[] charArray() {
		assert type == Type.CHAR;
		return charBuffer.array();
	}

	int[] intArray() {
		assert type == Type.INT;
		return intBuffer.array();
	}

	public static Builder builder(int initialBufferSize) {
		return new Builder(initialBufferSize);
	}

	public static class Builder {
		private Type type;
		private ByteBuffer byteBuffer;
		private CharBuffer charBuffer;
		private IntBuffer intBuffer;
		private int prevHighSurrogate;
		/**
		 * Reused for array-backed {@link CharBuffer} appends so the shared
		 * {@link #appendCharSequence} path can scan {@code char[]} without
		 * allocating a view per call. Builders are not thread-safe.
		 */
		private final CharArrayRange charArrayRange = new CharArrayRange();

		private Builder(int initialBufferSize) {
			type = Type.BYTE;
			byteBuffer = ByteBuffer.allocate(initialBufferSize);
			charBuffer = null;
			intBuffer = null;
			prevHighSurrogate = -1;
		}

		Type getType() {
			return type;
		}

		ByteBuffer getByteBuffer() {
			return byteBuffer;
		}

		CharBuffer getCharBuffer() {
			return charBuffer;
		}

		IntBuffer getIntBuffer() {
			return intBuffer;
		}

		public CodePointBuffer build() {
			switch (type) {
				case BYTE:
					byteBuffer.flip();
					break;
				case CHAR:
					charBuffer.flip();
					break;
				case INT:
					intBuffer.flip();
					break;
			}
			return new CodePointBuffer(type, byteBuffer, charBuffer, intBuffer);
		}

		private static int roundUpToNextPowerOfTwo(int i) {
			int nextPowerOfTwo = 32 - Integer.numberOfLeadingZeros(i - 1);
			return (int) Math.pow(2, nextPowerOfTwo);
		}

		public void ensureRemaining(int remainingNeeded) {
			switch (type) {
				case BYTE:
					if (byteBuffer.remaining() < remainingNeeded) {
						int newCapacity = roundUpToNextPowerOfTwo(byteBuffer.capacity() + remainingNeeded);
						ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
						byteBuffer.flip();
						newBuffer.put(byteBuffer);
						byteBuffer = newBuffer;
					}
					break;
				case CHAR:
					if (charBuffer.remaining() < remainingNeeded) {
						int newCapacity = roundUpToNextPowerOfTwo(charBuffer.capacity() + remainingNeeded);
						CharBuffer newBuffer = CharBuffer.allocate(newCapacity);
						charBuffer.flip();
						newBuffer.put(charBuffer);
						charBuffer = newBuffer;
					}
					break;
				case INT:
					if (intBuffer.remaining() < remainingNeeded) {
						int newCapacity = roundUpToNextPowerOfTwo(intBuffer.capacity() + remainingNeeded);
						IntBuffer newBuffer = IntBuffer.allocate(newCapacity);
						intBuffer.flip();
						newBuffer.put(intBuffer);
						intBuffer = newBuffer;
					}
					break;
			}
		}

		/**
		 * Appends UTF-16 code units from {@code utf16In}.
		 *
		 * <p>Array-backed buffers and read-only buffers (including
		 * {@link CharBuffer#wrap(CharSequence)} views of a {@link String}) share
		 * one conversion implementation ({@link #appendCharSequence}). There is
		 * no intermediate mutable copy of the input; units are written straight
		 * into the compact byte / char / int storage. Array-backed buffers are
		 * scanned through a reusable {@code char[]} view to avoid per-unit
		 * {@link CharBuffer} bounds checks on the {@link CharStreams#fromReader}
		 * path.</p>
		 *
		 * <p>Each call is a complete UTF-16 sequence: an unpaired high surrogate
		 * at the end of this call is stored as a lone code unit and does not
		 * combine with the first unit of a later {@code append}.</p>
		 */
		public void append(CharBuffer utf16In) {
			ensureRemaining(utf16In.remaining());
			if (utf16In.hasArray()) {
				int remaining = utf16In.remaining();
				charArrayRange.reset(
					utf16In.array(),
					utf16In.arrayOffset() + utf16In.position(),
					remaining);
				appendCharSequence(charArrayRange, 0, remaining);
			}
			else {
				// CharBuffer is a CharSequence: length() == remaining() and
				// charAt(i) is relative to the current position.
				appendCharSequence(utf16In, 0, utf16In.length());
			}
			utf16In.position(utf16In.limit());
		}

		/**
		 * Appends UTF-16 code units from a {@link String} without allocating a
		 * temporary {@link CharBuffer} or char array. Semantically equivalent to
		 * {@link #append(CharBuffer) append}{@code (CharBuffer.wrap(utf16In))},
		 * but avoids the view object on the hot {@link CharStreams#fromString}
		 * path.
		 *
		 * <p>Package-private because the runtime's public string factory is
		 * {@link CharStreams#fromString(String)}.</p>
		 *
		 * <p>Like {@link #append(CharBuffer)}, each call is a complete UTF-16
		 * sequence; unpaired high surrogates are not carried across calls.</p>
		 */
		void append(String utf16In) {
			ensureRemaining(utf16In.length());
			appendCharSequence(utf16In, 0, utf16In.length());
		}

		/**
		 * Single UTF-16 conversion state machine used by both
		 * {@link #append(CharBuffer)} and {@link #append(String)}.
		 *
		 * @param utf16In source of UTF-16 code units ({@link String} or
		 *                {@link CharBuffer}); {@code charAt} indices are in
		 *                {@code [start, end)}
		 * @param start   inclusive start index into {@code utf16In}
		 * @param end     exclusive end index into {@code utf16In}
		 */
		private void appendCharSequence(CharSequence utf16In, int start, int end) {
			switch (type) {
				case BYTE:
					appendByte(utf16In, start, end);
					break;
				case CHAR:
					appendChar(utf16In, start, end);
					break;
				case INT:
					appendInt(utf16In, start, end);
					break;
			}
		}

		private void appendByte(CharSequence utf16In, int start, int end) {
			assert prevHighSurrogate == -1;

			byte[] outByte = byteBuffer.array();
			int outOffset = byteBuffer.arrayOffset() + byteBuffer.position();

			for (int i = start; i < end; i++) {
				char c = utf16In.charAt(i);
				if (c <= 0xFF) {
					outByte[outOffset++] = (byte) (c & 0xFF);
				}
				else {
					byteBuffer.position(outOffset - byteBuffer.arrayOffset());
					if (!Character.isHighSurrogate(c)) {
						byteToCharBuffer(end - i);
						appendChar(utf16In, i, end);
					}
					else {
						byteToIntBuffer(end - i);
						appendInt(utf16In, i, end);
					}
					return;
				}
			}

			byteBuffer.position(outOffset - byteBuffer.arrayOffset());
		}

		private void appendChar(CharSequence utf16In, int start, int end) {
			assert prevHighSurrogate == -1;

			char[] outChar = charBuffer.array();
			int outOffset = charBuffer.arrayOffset() + charBuffer.position();

			for (int i = start; i < end; i++) {
				char c = utf16In.charAt(i);
				if (!Character.isHighSurrogate(c)) {
					outChar[outOffset++] = c;
				}
				else {
					charBuffer.position(outOffset - charBuffer.arrayOffset());
					charToIntBuffer(end - i);
					appendInt(utf16In, i, end);
					return;
				}
			}

			charBuffer.position(outOffset - charBuffer.arrayOffset());
		}

		private void appendInt(CharSequence utf16In, int start, int end) {
			int[] outInt = intBuffer.array();
			int outOffset = intBuffer.arrayOffset() + intBuffer.position();

			for (int i = start; i < end; i++) {
				char c = utf16In.charAt(i);
				if (prevHighSurrogate != -1) {
					if (Character.isLowSurrogate(c)) {
						outInt[outOffset++] = Character.toCodePoint((char) prevHighSurrogate, c);
						prevHighSurrogate = -1;
					}
					else {
						// Dangling high surrogate before a non-low unit
						outInt[outOffset++] = prevHighSurrogate;
						if (Character.isHighSurrogate(c)) {
							prevHighSurrogate = c & 0xFFFF;
						}
						else {
							outInt[outOffset++] = c & 0xFFFF;
							prevHighSurrogate = -1;
						}
					}
				}
				else if (Character.isHighSurrogate(c)) {
					prevHighSurrogate = c & 0xFFFF;
				}
				else {
					outInt[outOffset++] = c & 0xFFFF;
				}
			}

			if (prevHighSurrogate != -1) {
				// End of this append: store unpaired high as a lone code unit.
				// Clear so a later append does not pair with a unit already written.
				outInt[outOffset++] = prevHighSurrogate & 0xFFFF;
				prevHighSurrogate = -1;
			}

			intBuffer.position(outOffset - intBuffer.arrayOffset());
		}

		private void byteToCharBuffer(int toAppend) {
			byteBuffer.flip();
			// CharBuffers hold twice as much per unit as ByteBuffers, so start with half the capacity.
			CharBuffer newBuffer = CharBuffer.allocate(Math.max(byteBuffer.remaining() + toAppend, byteBuffer.capacity() / 2));
			while (byteBuffer.hasRemaining()) {
				newBuffer.put((char) (byteBuffer.get() & 0xFF));
			}
			type = Type.CHAR;
			byteBuffer = null;
			charBuffer = newBuffer;
		}

		private void byteToIntBuffer(int toAppend) {
			byteBuffer.flip();
			// IntBuffers hold four times as much per unit as ByteBuffers, so start with one quarter the capacity.
			IntBuffer newBuffer = IntBuffer.allocate(Math.max(byteBuffer.remaining() + toAppend, byteBuffer.capacity() / 4));
			while (byteBuffer.hasRemaining()) {
				newBuffer.put(byteBuffer.get() & 0xFF);
			}
			type = Type.INT;
			byteBuffer = null;
			intBuffer = newBuffer;
		}

		private void charToIntBuffer(int toAppend) {
			charBuffer.flip();
			// IntBuffers hold two times as much per unit as ByteBuffers, so start with one half the capacity.
			IntBuffer newBuffer = IntBuffer.allocate(Math.max(charBuffer.remaining() + toAppend, charBuffer.capacity() / 2));
			while (charBuffer.hasRemaining()) {
				newBuffer.put(charBuffer.get() & 0xFFFF);
			}
			type = Type.INT;
			charBuffer = null;
			intBuffer = newBuffer;
		}
	}

	/**
	 * Zero-allocation {@link CharSequence} over a {@code char[]} slice.
	 * Used only as a transient view while {@link Builder} converts UTF-16 input;
	 * not exposed outside this class.
	 */
	private static final class CharArrayRange implements CharSequence {
		private char[] array;
		private int offset;
		private int length;

		void reset(char[] array, int offset, int length) {
			this.array = array;
			this.offset = offset;
			this.length = length;
		}

		@Override
		public int length() {
			return length;
		}

		@Override
		public char charAt(int index) {
			return array[offset + index];
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String toString() {
			return new String(array, offset, length);
		}
	}
}
