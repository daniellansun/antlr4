/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.misc.Interval;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Complements {@link TestCodePointCharStream} with additional edge cases for
 * BMP/SMP storage, seek/mark, and mixed content.
 */
public class TestCodePointCharStreamExtras {
	@Test
	public void seekAndIndexAcrossBmp() {
		CodePointCharStream s = CharStreams.fromString("\u611B\u597D\u4E16");
		assertEquals(3, s.size());
		s.seek(2);
		assertEquals(2, s.index());
		assertEquals(0x4E16, s.LA(1));
		s.seek(0);
		assertEquals(0x611B, s.LA(1));
	}

	@Test
	public void seekAndIndexAcrossSmp() {
		String input = new StringBuilder()
			.appendCodePoint(0x1F600)
			.appendCodePoint(0x1F601)
			.append('X')
			.toString();
		CodePointCharStream s = CharStreams.fromString(input);
		assertEquals(3, s.size());
		assertEquals(0x1F600, s.LA(1));
		s.consume();
		assertEquals(0x1F601, s.LA(1));
		s.seek(2);
		assertEquals('X', s.LA(1));
		s.seek(0);
		assertEquals(0x1F600, s.LA(1));
	}

	@Test
	public void markAndReleaseAreNoOpsButConsistent() {
		CodePointCharStream s = CharStreams.fromString("ab");
		int m = s.mark();
		s.consume();
		s.release(m);
		assertEquals(1, s.index());
	}

	@Test
	public void negativeLookaheadOnBmp() {
		CodePointCharStream s = CharStreams.fromString("AB");
		s.consume();
		assertEquals('A', s.LA(-1));
		assertEquals(IntStream.EOF, s.LA(-2));
	}

	@Test
	public void negativeLookaheadOnSmp() {
		String input = new StringBuilder().appendCodePoint(0x1F4A9).append('Z').toString();
		CodePointCharStream s = CharStreams.fromString(input);
		s.consume();
		assertEquals(0x1F4A9, s.LA(-1));
		assertEquals('Z', s.LA(1));
	}

	@Test
	public void mixedAsciiBmpSmpGetText() {
		String input = new StringBuilder("A")
			.append('\u4E2D')
			.appendCodePoint(0x1F600)
			.append("B")
			.toString();
		CodePointCharStream s = CharStreams.fromString(input);
		assertEquals(4, s.size());
		assertEquals("A\u4E2D", s.getText(Interval.of(0, 1)));
		assertEquals(new StringBuilder().appendCodePoint(0x1F600).append('B').toString(),
			s.getText(Interval.of(2, 3)));
		assertEquals(input, s.toString());
	}

	@Test
	public void byteStorageForAscii() {
		CodePointCharStream s = CharStreams.fromString("ascii-only-0123");
		assertTrue(s.getInternalStorage() instanceof byte[]);
	}

	@Test
	public void charStorageForBmp() {
		CodePointCharStream s = CharStreams.fromString("\u0100\u0101");
		assertTrue(s.getInternalStorage() instanceof char[]);
	}

	@Test
	public void intStorageForSmp() {
		CodePointCharStream s = CharStreams.fromString(
			new StringBuilder().appendCodePoint(0x10000).toString());
		assertTrue(s.getInternalStorage() instanceof int[]);
	}

	@Test
	public void laZeroIsUndefinedButDoesNotThrow() {
		CodePointCharStream s = CharStreams.fromString("a");
		// LA(0) is undefined; implementation returns 0
		assertEquals(0, s.LA(0));
	}

	@Test
	public void consumeEntireStreamThenEof() {
		CodePointCharStream s = CharStreams.fromString("xy");
		s.consume();
		s.consume();
		assertEquals(IntStream.EOF, s.LA(1));
		assertEquals(2, s.index());
	}

	@Test
	public void getTextFullRange() {
		CodePointCharStream s = CharStreams.fromString("0123");
		assertEquals("0123", s.getText(Interval.of(0, 3)));
		assertEquals("0", s.getText(Interval.of(0, 0)));
	}

	@Test
	public void sourceNameDefaultAndCustom() {
		assertEquals(IntStream.UNKNOWN_SOURCE_NAME, CharStreams.fromString("a").getSourceName());
		assertEquals("custom", CharStreams.fromString("a", "custom").getSourceName());
	}

	@Test
	public void laZeroAndEofOn16And32BitStreams() {
		// 16-bit BMP storage: LA(0), past-end EOF, lookbehind past start
		CodePointCharStream bmp = CharStreams.fromString("\u0100\u0101");
		assertTrue(bmp.getInternalStorage() instanceof char[]);
		assertEquals(0, bmp.LA(0));
		assertEquals(IntStream.EOF, bmp.LA(3));
		assertEquals(IntStream.EOF, bmp.LA(-1)); // position 0, look behind
		bmp.consume();
		assertEquals(0x0100, bmp.LA(-1));
		assertEquals(0x0101, bmp.LA(1));
		assertEquals("\u0100\u0101", bmp.getText(Interval.of(0, 1)));

		// 32-bit SMP storage
		String smp = new StringBuilder().appendCodePoint(0x1F600).appendCodePoint(0x1F601).toString();
		CodePointCharStream s32 = CharStreams.fromString(smp);
		assertTrue(s32.getInternalStorage() instanceof int[]);
		assertEquals(0, s32.LA(0));
		assertEquals(IntStream.EOF, s32.LA(3));
		assertEquals(IntStream.EOF, s32.LA(-1));
		s32.consume();
		assertEquals(0x1F600, s32.LA(-1));
		assertEquals(0x1F601, s32.LA(1));
		assertEquals(smp, s32.getText(Interval.of(0, 1)));
	}
}
