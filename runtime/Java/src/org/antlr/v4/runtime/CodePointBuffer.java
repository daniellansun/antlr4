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

		public void append(CharBuffer utf16In) {
			ensureRemaining(utf16In.remaining());
			if (utf16In.hasArray()) {
				appendArray(utf16In);
			} else {
				// TODO
				throw new UnsupportedOperationException("TODO");
			}
		}

		/**
		 * Appends UTF-16 code units from a {@link String} without first copying
		 * them into a temporary {@link CharBuffer}. This is used by
		 * {@link CharStreams#fromString(String, String)}; it preserves the same
		 * byte/char/int storage selection and surrogate handling as
		 * {@link #append(CharBuffer)}.
		 *
		 * <p>Package-private because the runtime's public string factory is
		 * {@link CharStreams#fromString(String)}.</p>
		 */
		void append(String utf16In) {
			ensureRemaining(utf16In.length());
			switch (type) {
				case BYTE:
					appendStringByte(utf16In, 0);
					break;
				case CHAR:
					appendStringChar(utf16In, 0);
					break;
				case INT:
					appendStringInt(utf16In, 0);
					break;
			}
		}

		private void appendArray(CharBuffer utf16In) {
			assert utf16In.hasArray();

			switch (type) {
				case BYTE:
					appendArrayByte(utf16In);
					break;
				case CHAR:
					appendArrayChar(utf16In);
					break;
				case INT:
					appendArrayInt(utf16In);
					break;
			}
		}

		private void appendStringByte(String utf16In, int start) {
			assert prevHighSurrogate == -1;

			byte[] outByte = byteBuffer.array();
			int outOffset = byteBuffer.arrayOffset() + byteBuffer.position();
			final int length = utf16In.length();

			for (int i = start; i < length; i++) {
				char c = utf16In.charAt(i);
				if (c <= 0xFF) {
					outByte[outOffset++] = (byte)(c & 0xFF);
				}
				else {
					byteBuffer.position(outOffset - byteBuffer.arrayOffset());
					if (!Character.isHighSurrogate(c)) {
						byteToCharBuffer(length - i);
						appendStringChar(utf16In, i);
					}
					else {
						byteToIntBuffer(length - i);
						appendStringInt(utf16In, i);
					}
					return;
				}
			}

			byteBuffer.position(outOffset - byteBuffer.arrayOffset());
		}

		private void appendStringChar(String utf16In, int start) {
			assert prevHighSurrogate == -1;

			char[] outChar = charBuffer.array();
			int outOffset = charBuffer.arrayOffset() + charBuffer.position();
			final int length = utf16In.length();

			for (int i = start; i < length; i++) {
				char c = utf16In.charAt(i);
				if (!Character.isHighSurrogate(c)) {
					outChar[outOffset++] = c;
				}
				else {
					charBuffer.position(outOffset - charBuffer.arrayOffset());
					charToIntBuffer(length - i);
					appendStringInt(utf16In, i);
					return;
				}
			}

			charBuffer.position(outOffset - charBuffer.arrayOffset());
		}

		private void appendStringInt(String utf16In, int start) {
			int[] outInt = intBuffer.array();
			int outOffset = intBuffer.arrayOffset() + intBuffer.position();
			final int length = utf16In.length();

			for (int i = start; i < length; i++) {
				char c = utf16In.charAt(i);
				if (prevHighSurrogate != -1) {
					if (Character.isLowSurrogate(c)) {
						outInt[outOffset++] = Character.toCodePoint((char)prevHighSurrogate, c);
						prevHighSurrogate = -1;
					}
					else {
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
				outInt[outOffset++] = prevHighSurrogate & 0xFFFF;
			}

			intBuffer.position(outOffset - intBuffer.arrayOffset());
		}

		private void appendArrayByte(CharBuffer utf16In) {
			assert prevHighSurrogate == -1;

			char[] in = utf16In.array();
			int inOffset = utf16In.arrayOffset() + utf16In.position();
			int inLimit = utf16In.arrayOffset() + utf16In.limit();

			byte[] outByte = byteBuffer.array();
			int outOffset = byteBuffer.arrayOffset() + byteBuffer.position();

			while (inOffset < inLimit) {
				char c = in[inOffset];
				if (c <= 0xFF) {
					outByte[outOffset] = (byte)(c & 0xFF);
				} else {
					utf16In.position(inOffset - utf16In.arrayOffset());
					byteBuffer.position(outOffset - byteBuffer.arrayOffset());
					if (!Character.isHighSurrogate(c)) {
						byteToCharBuffer(utf16In.remaining());
						appendArrayChar(utf16In);
						return;
					} else {
						byteToIntBuffer(utf16In.remaining());
						appendArrayInt(utf16In);
						return;
					}
				}
				inOffset++;
				outOffset++;
			}

			utf16In.position(inOffset - utf16In.arrayOffset());
			byteBuffer.position(outOffset - byteBuffer.arrayOffset());
		}

		private void appendArrayChar(CharBuffer utf16In) {
			assert prevHighSurrogate == -1;

			char[] in = utf16In.array();
			int inOffset = utf16In.arrayOffset() + utf16In.position();
			int inLimit = utf16In.arrayOffset() + utf16In.limit();

			char[] outChar = charBuffer.array();
			int outOffset = charBuffer.arrayOffset() + charBuffer.position();

			while (inOffset < inLimit) {
				char c = in[inOffset];
				if (!Character.isHighSurrogate(c)) {
					outChar[outOffset] = c;
				} else {
					utf16In.position(inOffset - utf16In.arrayOffset());
					charBuffer.position(outOffset - charBuffer.arrayOffset());
					charToIntBuffer(utf16In.remaining());
					appendArrayInt(utf16In);
					return;
				}
				inOffset++;
				outOffset++;
			}

			utf16In.position(inOffset - utf16In.arrayOffset());
			charBuffer.position(outOffset - charBuffer.arrayOffset());
		}

		private void appendArrayInt(CharBuffer utf16In) {
			char[] in = utf16In.array();
			int inOffset = utf16In.arrayOffset() + utf16In.position();
			int inLimit = utf16In.arrayOffset() + utf16In.limit();

			int[] outInt = intBuffer.array();
			int outOffset = intBuffer.arrayOffset() + intBuffer.position();

			while (inOffset < inLimit) {
				char c = in[inOffset];
				inOffset++;
				if (prevHighSurrogate != -1) {
					if (Character.isLowSurrogate(c)) {
						outInt[outOffset] = Character.toCodePoint((char) prevHighSurrogate, c);
						outOffset++;
						prevHighSurrogate = -1;
					} else {
						// Dangling high surrogate
						outInt[outOffset] = prevHighSurrogate;
						outOffset++;
						if (Character.isHighSurrogate(c)) {
							prevHighSurrogate = c & 0xFFFF;
						} else {
							outInt[outOffset] = c & 0xFFFF;
							outOffset++;
							prevHighSurrogate = -1;
						}
					}
				} else if (Character.isHighSurrogate(c)) {
					prevHighSurrogate = c & 0xFFFF;
				} else {
					outInt[outOffset] = c & 0xFFFF;
					outOffset++;
				}
			}

			if (prevHighSurrogate != -1) {
				// Dangling high surrogate
				outInt[outOffset] = prevHighSurrogate & 0xFFFF;
				outOffset++;
			}

			utf16In.position(inOffset - utf16In.arrayOffset());
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
}
