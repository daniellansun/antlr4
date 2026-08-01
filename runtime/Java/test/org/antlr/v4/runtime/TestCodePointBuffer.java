/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.IntBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestCodePointBuffer {
	@Test
	public void withBytes() {
		ByteBuffer bb = ByteBuffer.wrap(new byte[]{65, 66, 67});
		CodePointBuffer buf = CodePointBuffer.withBytes(bb);
		assertEquals(0, buf.position());
		assertEquals(3, buf.remaining());
		assertEquals(65, buf.get(0));
		assertEquals(66, buf.get(1));
		buf.position(1);
		assertEquals(1, buf.position());
		assertEquals(2, buf.remaining());
	}

	@Test
	public void withChars() {
		CharBuffer cb = CharBuffer.wrap(new char[]{'x', 'y'});
		CodePointBuffer buf = CodePointBuffer.withChars(cb);
		assertEquals(2, buf.remaining());
		assertEquals('x', buf.get(0));
		assertEquals('y', buf.get(1));
	}

	@Test
	public void withInts() {
		IntBuffer ib = IntBuffer.wrap(new int[]{0x1F4A9, 0x61});
		CodePointBuffer buf = CodePointBuffer.withInts(ib);
		assertEquals(2, buf.remaining());
		assertEquals(0x1F4A9, buf.get(0));
		assertEquals(0x61, buf.get(1));
	}

	@Test
	public void builderAsciiStaysByte() {
		CodePointBuffer.Builder builder = CodePointBuffer.builder(8);
		CharBuffer cb = CharBuffer.allocate(3);
		cb.put("abc");
		cb.flip();
		builder.append(cb);
		CodePointBuffer buf = builder.build();
		assertEquals(3, buf.remaining());
		assertEquals('a', buf.get(0));
		assertEquals('b', buf.get(1));
		assertEquals('c', buf.get(2));

		CodePointCharStream stream = CodePointCharStream.fromBuffer(buf);
		assertTrue(stream.getInternalStorage() instanceof byte[]);
	}

	@Test
	public void builderBmpUpgradesToChar() {
		CodePointBuffer.Builder builder = CodePointBuffer.builder(4);
		CharBuffer cb = CharBuffer.allocate(2);
		cb.put("\u611B\u597D");
		cb.flip();
		builder.append(cb);
		CodePointBuffer buf = builder.build();
		assertEquals(2, buf.remaining());
		assertEquals(0x611B, buf.get(0));
		assertEquals(0x597D, buf.get(1));

		CodePointCharStream stream = CodePointCharStream.fromBuffer(buf);
		assertTrue(stream.getInternalStorage() instanceof char[]);
	}

	@Test
	public void builderSmpUpgradesToInt() {
		CodePointBuffer.Builder builder = CodePointBuffer.builder(4);
		String emoji = new StringBuilder().appendCodePoint(0x1F600).toString();
		CharBuffer cb = CharBuffer.allocate(emoji.length());
		cb.put(emoji);
		cb.flip();
		builder.append(cb);
		CodePointBuffer buf = builder.build();
		assertEquals(1, buf.remaining());
		assertEquals(0x1F600, buf.get(0));

		CodePointCharStream stream = CodePointCharStream.fromBuffer(buf);
		assertTrue(stream.getInternalStorage() instanceof int[]);
	}

	@Test
	public void builderStringAppendMatchesCharBufferAppendForAllStorageTypes() {
		assertEquivalentStringAndBufferAppend("ascii-only-0123");
		assertEquivalentStringAndBufferAppend("\u0100\u4E2D\u0101");
		assertEquivalentStringAndBufferAppend(
			"\u0100" + new StringBuilder().appendCodePoint(0x1F600).toString());
		assertEquivalentStringAndBufferAppend(
			"A" + new StringBuilder().appendCodePoint(0x1F600).append("B").toString());
		assertEquivalentStringAndBufferAppend(new String(new char[] {'A', '\uD83D', 'B'}));
		assertEquivalentStringAndBufferAppend(new String(new char[] {'A', '\uD83D', '\uD83D'}));
	}

	@Test
	public void builderStringAppendContinuesAcrossCharAndIntStorage() {
		CodePointBuffer.Builder charBuilder = CodePointBuffer.builder(1);
		charBuilder.append("\u0100");
		charBuilder.append("\u0101");
		assertEquals("\u0100\u0101", CodePointCharStream.fromBuffer(charBuilder.build()).toString());

		CodePointBuffer.Builder intBuilder = CodePointBuffer.builder(1);
		String emoji = new StringBuilder().appendCodePoint(0x1F600).toString();
		intBuilder.append(emoji);
		intBuilder.append("Z");
		assertEquals(emoji + "Z", CodePointCharStream.fromBuffer(intBuilder.build()).toString());
	}

	@Test
	public void builderMixedAsciiThenBmp() {
		CodePointBuffer.Builder builder = CodePointBuffer.builder(4);
		CharBuffer a = CharBuffer.allocate(1);
		a.put("A");
		a.flip();
		builder.append(a);
		CharBuffer b = CharBuffer.allocate(1);
		b.put("\u4E2D");
		b.flip();
		builder.append(b);
		CodePointBuffer buf = builder.build();
		assertEquals(2, buf.remaining());
		assertEquals('A', buf.get(0));
		assertEquals(0x4E2D, buf.get(1));
	}

	@Test
	public void builderEnsureRemainingGrows() {
		CodePointBuffer.Builder builder = CodePointBuffer.builder(1);
		CharBuffer cb = CharBuffer.allocate(20);
		cb.put("abcdefghijklmnopqrst");
		cb.flip();
		builder.append(cb);
		CodePointBuffer buf = builder.build();
		assertEquals(20, buf.remaining());
		assertEquals('a', buf.get(0));
		assertEquals('t', buf.get(19));
	}

	@Test
	public void fromBufferPreservesName() {
		CodePointBuffer.Builder builder = CodePointBuffer.builder(2);
		CharBuffer cb = CharBuffer.allocate(1);
		cb.put("Z");
		cb.flip();
		builder.append(cb);
		CodePointCharStream s = CodePointCharStream.fromBuffer(builder.build(), "named");
		assertEquals("named", s.getSourceName());
		assertEquals(1, s.size());
	}

	@Test
	public void builderAccessorsAndEnsureRemainingAllTypes() throws Exception {
		// ASCII path: getType/getByteBuffer via package-private methods (same package)
		CodePointBuffer.Builder b1 = CodePointBuffer.builder(1);
		assertEquals(CodePointBuffer.Type.BYTE, b1.getType());
		assertNotNull(b1.getByteBuffer());
		CharBuffer ascii = CharBuffer.allocate(8);
		ascii.put("abcdefgh");
		ascii.flip();
		b1.append(ascii); // grows via ensureRemaining BYTE
		CodePointBuffer built1 = b1.build();
		assertEquals(8, built1.remaining());
		built1.position(2);
		assertEquals(2, built1.position());
		assertEquals(6, built1.remaining());
		assertEquals('c', built1.get(2));

		// BMP path: CHAR ensureRemaining growth
		CodePointBuffer.Builder b2 = CodePointBuffer.builder(1);
		CharBuffer bmp = CharBuffer.allocate(4);
		bmp.put("\u0100\u0101\u0102\u0103");
		bmp.flip();
		b2.append(bmp);
		assertEquals(CodePointBuffer.Type.CHAR, b2.getType());
		assertNotNull(b2.getCharBuffer());
		CodePointBuffer built2 = b2.build();
		assertEquals(4, built2.remaining());
		built2.position(1);
		assertEquals(0x0101, built2.get(1));

		// SMP path: INT ensureRemaining growth + split surrogates across appends
		CodePointBuffer.Builder b3 = CodePointBuffer.builder(1);
		String emoji = new StringBuilder().appendCodePoint(0x1F600).appendCodePoint(0x1F601).toString();
		CharBuffer smp = CharBuffer.allocate(emoji.length());
		smp.put(emoji);
		smp.flip();
		b3.append(smp);
		assertEquals(CodePointBuffer.Type.INT, b3.getType());
		assertNotNull(b3.getIntBuffer());
		// force ensureRemaining on INT by appending more
		String more = new StringBuilder().appendCodePoint(0x1F602).appendCodePoint(0x1F603)
			.appendCodePoint(0x1F604).toString();
		CharBuffer moreCb = CharBuffer.allocate(more.length());
		moreCb.put(more);
		moreCb.flip();
		b3.append(moreCb);
		CodePointBuffer built3 = b3.build();
		assertEquals(5, built3.remaining());
		built3.position(0);
		assertEquals(0x1F600, built3.get(0));
		assertEquals(0, built3.arrayOffset());
	}

	@Test
	public void withIntsPositionAndArrayOffset() {
		IntBuffer ib = IntBuffer.wrap(new int[]{0x61, 0x62, 0x63});
		CodePointBuffer buf = CodePointBuffer.withInts(ib);
		assertEquals(3, buf.remaining());
		buf.position(1);
		assertEquals(1, buf.position());
		assertEquals(2, buf.remaining());
		assertEquals(0x62, buf.get(1));
		assertEquals(0, buf.arrayOffset());
	}

	@Test
	public void builderDanglingSurrogatesAndByteToIntUpgrade() {
		// high surrogate then non-low (dangling) on INT path
		CodePointBuffer.Builder b = CodePointBuffer.builder(8);
		// start with high surrogate alone then a BMP char via INT path after upgrade
		String highThenBmp = new String(new char[] { '\uD83D', 'A' }); // high + 'A' not low
		CharBuffer cb = CharBuffer.wrap(highThenBmp.toCharArray());
		b.append(cb);
		CodePointBuffer built = b.build();
		assertTrue(built.remaining() >= 1);

		// byte path upgrading to INT when high surrogate seen from ASCII buffer
		CodePointBuffer.Builder b2 = CodePointBuffer.builder(4);
		CharBuffer ascii = CharBuffer.wrap("ab".toCharArray());
		b2.append(ascii);
		// now append high surrogate to force byte->int
		String emoji = new StringBuilder().appendCodePoint(0x1F4A9).toString();
		b2.append(CharBuffer.wrap(emoji.toCharArray()));
		CodePointBuffer built2 = b2.build();
		assertEquals(CodePointBuffer.Type.INT, built2.getType());
		assertTrue(built2.remaining() >= 3);

		// char path upgrading to INT
		CodePointBuffer.Builder b3 = CodePointBuffer.builder(2);
		b3.append(CharBuffer.wrap("\u0100\u0101".toCharArray()));
		assertEquals(CodePointBuffer.Type.CHAR, b3.getType());
		b3.append(CharBuffer.wrap(emoji.toCharArray()));
		assertEquals(CodePointBuffer.Type.INT, b3.getType());
		CodePointBuffer built3 = b3.build();
		assertTrue(built3.remaining() >= 3);

		// dangling high surrogate at end of INT append
		CodePointBuffer.Builder b4 = CodePointBuffer.builder(2);
		b4.append(CharBuffer.wrap(new char[] { '\uD83D' })); // lone high
		CodePointBuffer built4 = b4.build();
		assertTrue(built4.remaining() >= 1);

		// high then another high (dangling then new high)
		CodePointBuffer.Builder b5 = CodePointBuffer.builder(4);
		b5.append(CharBuffer.wrap(new char[] { '\uD83D', '\uD83D', '\uDE00' }));
		CodePointBuffer built5 = b5.build();
		assertTrue(built5.remaining() >= 1);

		// ensureRemaining on each type after small initial capacity
		CodePointBuffer.Builder b6 = CodePointBuffer.builder(1);
		b6.ensureRemaining(64);
		b6.append(CharBuffer.wrap("xy".toCharArray()));
		b6.ensureRemaining(128);
		assertNotNull(b6.build());
	}

	private static void assertEquivalentStringAndBufferAppend(String input) {
		CodePointBuffer.Builder stringBuilder = CodePointBuffer.builder(input.length());
		stringBuilder.append(input);
		CodePointBuffer stringBuffer = stringBuilder.build();

		CodePointBuffer.Builder charBufferBuilder = CodePointBuffer.builder(input.length());
		charBufferBuilder.append(CharBuffer.wrap(input.toCharArray()));
		CodePointBuffer charBuffer = charBufferBuilder.build();

		assertEquals(charBuffer.getType(), stringBuffer.getType());
		assertEquals(charBuffer.remaining(), stringBuffer.remaining());
		for (int i = 0; i < charBuffer.remaining(); i++) {
			assertEquals(charBuffer.get(i), stringBuffer.get(i));
		}
		assertEquals(
			CodePointCharStream.fromBuffer(charBuffer).toString(),
			CodePointCharStream.fromBuffer(stringBuffer).toString());
	}
}
