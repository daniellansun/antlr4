/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime;

import org.junit.Test;

import java.nio.CharBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link CodePointCharStream} 8/16/32-bit fast paths, overflow-safe
 * {@code getText}, {@link CodePointBuffer.Builder} widening, and EOF {@code consume()}.
 */
public class TestCodePointCharStreamFastPaths {

	@Test
	public void testFromStringAndGetTextAcrossAllWidths() {
		CodePointCharStream empty = CharStreams.fromString("");
		assertEquals(0, empty.size());
		assertEquals("", empty.getText(0, -1));
		assertEquals(IntStream.EOF, empty.LA(1));

		// 8-bit Latin-1 (1-char ASCII, 1-char Latin-1, CRLF, multi-char, overflow)
		CodePointCharStream s8 = CharStreams.fromString("abcd\r\n\u00e9xyz");
		assertEquals("a", s8.getText(0, 0));
		assertEquals("\u00e9", s8.getText(6, 6));
		assertEquals("\r\n", s8.getText(4, 5));
		assertEquals("", s8.getText(2, 1));
		assertEquals("abcd\r\n\u00e9xyz", s8.getText(0, Integer.MAX_VALUE));
		try { s8.getText(-1, 2); fail(); } catch (StringIndexOutOfBoundsException expected) {}
		try { s8.getText(2, Integer.MIN_VALUE); fail(); } catch (StringIndexOutOfBoundsException expected) {}

		// 16-bit BMP
		CodePointCharStream s16 = CharStreams.fromString("a\r\n\u4e2d\u6587b");
		assertEquals("a", s16.getText(0, 0));
		assertEquals("\r\n", s16.getText(1, 2));
		assertEquals("\u4e2d\u6587", s16.getText(3, 4));
		assertEquals("", s16.getText(2, 1));
		assertEquals("a\r\n\u4e2d\u6587b", s16.getText(0, Integer.MAX_VALUE));
		try { s16.getText(2, Integer.MIN_VALUE); fail(); } catch (StringIndexOutOfBoundsException expected) {}

		// 32-bit SMP: surrogate pair, lone high/low surrogates, consecutive highs, trailing high
		String smpStr = "x\uD83D\uDE00\uD800a\uDC00\uD800\uD801\uDE00\uD83D";
		CodePointCharStream s32 = CharStreams.fromString(smpStr);
		assertEquals(smpStr, s32.getText(0, Integer.MAX_VALUE));
		assertEquals(0x1F600, s32.LA(2));
		assertEquals(0xD800, s32.LA(3));
		assertEquals('a', s32.LA(4));
		assertEquals(0xDC00, s32.LA(5));
		assertEquals(0xD800, s32.LA(6));
		assertEquals(Character.toCodePoint('\uD801', '\uDE00'), s32.LA(7));
		assertEquals(0xD83D, s32.LA(8));

		CodePointCharStream s32b = CharStreams.fromString("\uD83D\uDE00");
		assertEquals(1, s32b.size());
		assertEquals("\uD83D\uDE00", s32b.getText(0, 0));
		try { s32b.getText(0, Integer.MIN_VALUE); fail(); } catch (StringIndexOutOfBoundsException expected) {}
	}

	@Test
	public void testCodePointBufferAppendAndEofConsume() {
		CodePointBuffer.Builder builder = CodePointBuffer.builder(4);
		builder.append("abc".toCharArray(), 0, 3);
		builder.append(new char[] { '\u4e2d', '\uD83D', '\uDE00', '\uD800' }, 0, 4);
		CodePointBuffer buf = builder.build();
		assertEquals(CodePointBuffer.Type.INT, buf.getType());
		assertEquals("abc\u4e2d\uD83D\uDE00\uD800", CodePointCharStream.fromBuffer(buf).toString());

		CodePointBuffer.Builder cbChar = CodePointBuffer.builder(4);
		cbChar.append(CharBuffer.wrap("\u4e2d".toCharArray()));
		cbChar.append(CharBuffer.wrap("ab".toCharArray()));
		assertEquals(CodePointBuffer.Type.CHAR, cbChar.build().getType());

		CodePointBuffer.Builder cbInt = CodePointBuffer.builder(4);
		cbInt.append(CharBuffer.wrap("\uD83D\uDE00".toCharArray()));
		cbInt.append(CharBuffer.wrap("x".toCharArray()));
		assertEquals(CodePointBuffer.Type.INT, cbInt.build().getType());

		for (String sample : new String[] { "a", "\u4e2d", "\uD83D\uDE00" }) {
			CodePointCharStream cs = CharStreams.fromString(sample);
			cs.consume();
			try {
				cs.consume();
				fail("Expected IllegalStateException at EOF");
			}
			catch (IllegalStateException expected) {
			}
		}
	}
}
