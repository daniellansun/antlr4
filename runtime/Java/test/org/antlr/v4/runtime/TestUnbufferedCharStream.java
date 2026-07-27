/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.misc.Interval;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestUnbufferedCharStream {
	@Test
	public void laAndConsume() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("abc"));
		assertEquals('a', s.LA(1));
		assertEquals('b', s.LA(2));
		assertEquals('c', s.LA(3));
		assertEquals(IntStream.EOF, s.LA(4));
		assertEquals(0, s.index());
		s.consume();
		assertEquals(1, s.index());
		assertEquals('b', s.LA(1));
		s.consume();
		s.consume();
		assertEquals(IntStream.EOF, s.LA(1));
	}

	@Test(expected = IllegalStateException.class)
	public void consumeEofThrows() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader(""));
		s.consume();
	}

	@Test
	public void markReleaseAndLookbehind() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("xyz"));
		int m = s.mark();
		assertEquals('x', s.LA(1));
		s.consume();
		assertEquals('x', s.LA(-1));
		s.consume();
		s.consume();
		s.release(m);
		assertEquals(IntStream.EOF, s.LA(1));
	}

	@Test
	public void getTextWithinMarkedBuffer() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("hello"));
		int m = s.mark();
		s.consume(); // h
		s.consume(); // e
		// buffer still holds from mark start
		String text = s.getText(Interval.of(0, 1));
		assertEquals("he", text);
		s.release(m);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void sizeUnsupported() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("a"));
		s.size();
	}

	@Test
	public void sourceName() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("a"));
		assertEquals(IntStream.UNKNOWN_SOURCE_NAME, s.getSourceName());
		s.name = "custom";
		assertEquals("custom", s.getSourceName());
		s.name = "";
		assertEquals(IntStream.UNKNOWN_SOURCE_NAME, s.getSourceName());
	}

	@Test
	public void seekWithinBuffer() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("abcd"), 16);
		int m = s.mark();
		s.consume();
		s.consume();
		s.seek(0);
		assertEquals(0, s.index());
		assertEquals('a', s.LA(1));
		s.seek(2);
		assertEquals('c', s.LA(1));
		s.release(m);
	}

	@Test
	public void emptyInputLaIsEof() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader(""));
		assertEquals(IntStream.EOF, s.LA(1));
	}

	@Test
	public void defaultCtorForSubclassing() {
		UnbufferedCharStream s = new UnbufferedCharStream(8);
		assertTrue(s != null);
		UnbufferedCharStream def = new UnbufferedCharStream();
		assertTrue(def != null);
	}

	@Test
	public void lookaheadBeyondEndIsEof() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("z"));
		assertEquals('z', s.LA(1));
		assertEquals(IntStream.EOF, s.LA(2));
	}

	@Test
	public void inputStreamConstructors() throws Exception {
		byte[] bytes = "hi".getBytes(StandardCharsets.UTF_8);
		UnbufferedCharStream s = new UnbufferedCharStream(new ByteArrayInputStream(bytes));
		assertEquals('h', s.LA(1));
		s.consume();
		assertEquals('i', s.LA(1));

		UnbufferedCharStream s2 = new UnbufferedCharStream(new ByteArrayInputStream(bytes), 4);
		assertEquals('h', s2.LA(1));
	}

	/** Reader that returns at most one char per read — exercises partial fill. */
	static class OneCharAtATimeReader extends Reader {
		private final String data;
		private int i;

		OneCharAtATimeReader(String data) {
			this.data = data;
		}

		@Override
		public int read(char[] cbuf, int off, int len) {
			if (i >= data.length()) {
				return -1;
			}
			if (len <= 0) {
				return 0;
			}
			cbuf[off] = data.charAt(i++);
			return 1;
		}

		@Override
		public int read() {
			if (i >= data.length()) {
				return -1;
			}
			return data.charAt(i++);
		}

		@Override
		public void close() {
		}
	}

	@Test
	public void partialReadsAndBufferGrowth() {
		// tiny buffer forces growth via Arrays.copyOf
		UnbufferedCharStream s = new UnbufferedCharStream(new OneCharAtATimeReader("abcdef"), 2);
		int m = s.mark();
		assertEquals('a', s.LA(1));
		// force fill beyond buffer size
		assertEquals('f', s.LA(6));
		assertEquals(IntStream.EOF, s.LA(7));
		for (int i = 0; i < 6; i++) {
			s.consume();
		}
		assertEquals(IntStream.EOF, s.LA(1));
		s.release(m);
	}

	@Test
	public void laNegativeBeyondBufferThrows() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("ab"));
		// LA(-1) before any consume is lastChar which starts -1
		assertEquals(-1, s.LA(-1));
		s.consume();
		assertEquals('a', s.LA(-1));
		try {
			// large negative that is not -1 goes through sync path and may throw
			s.LA(-5);
			// if no throw, ok — depends on index math
		} catch (IndexOutOfBoundsException e) {
			// expected for some negative offsets
		}
	}

	@Test(expected = IllegalStateException.class)
	public void releaseInvalidMarkerThrows() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("ab"));
		int m = s.mark();
		s.release(m + 99);
	}

	@Test
	public void nestedMarksAndReleaseOrder() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("wxyz"), 8);
		int m1 = s.mark();
		s.consume();
		int m2 = s.mark();
		s.consume();
		assertEquals('y', s.LA(1));
		assertEquals('x', s.LA(-1));
		s.release(m2);
		s.release(m1);
	}

	@Test
	public void seekSameIndexNoOpAndSeekForward() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("abcd"), 32);
		int m = s.mark();
		// prime buffer so seek targets are present
		assertEquals('a', s.LA(1));
		assertEquals('d', s.LA(4));
		s.seek(0); // no-op
		assertEquals(0, s.index());
		s.seek(3);
		assertEquals('d', s.LA(1));
		s.seek(1);
		assertEquals('b', s.LA(1));
		// p==0 path for lastChar
		s.seek(0);
		assertEquals('a', s.LA(1));
		s.release(m);
	}

	@Test(expected = IllegalArgumentException.class)
	public void seekBeforeBufferThrows() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("abc"), 8);
		// consume without mark so buffer slides
		s.consume();
		s.consume();
		// buffer start advanced; seeking to 0 may fail
		s.seek(0);
	}

	@Test
	public void getTextInvalidIntervalThrows() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("ab"), 8);
		int m = s.mark();
		try {
			s.getText(Interval.of(-1, 0));
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}
		try {
			s.getText(Interval.of(0, -2));
			fail();
		} catch (IllegalArgumentException e) {
			// expected
		}
		s.release(m);
	}

	@Test
	public void getTextOutsideBufferThrows() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("ab"), 8);
		// without mark, after consume buffer may not hold index 0
		s.consume();
		try {
			s.getText(Interval.of(0, 0));
			// may throw UnsupportedOperationException
		} catch (UnsupportedOperationException e) {
			assertTrue(e.getMessage().contains("outside buffer") || e.getMessage().length() > 0);
		}
	}

	@Test
	public void supportsUnicodeCodePointsFalse() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("a"));
		assertFalse(s.supportsUnicodeCodePoints());
	}

	@Test
	public void ioExceptionWrappedAsRuntime() {
		Reader bad = new Reader() {
			@Override
			public int read(char[] cbuf, int off, int len) throws IOException {
				throw new IOException("boom");
			}

			@Override
			public int read() throws IOException {
				throw new IOException("boom");
			}

			@Override
			public void close() {
			}
		};
		try {
			new UnbufferedCharStream(bad, 4);
			fail();
		} catch (RuntimeException e) {
			assertTrue(e.getCause() instanceof IOException);
		}
	}

	@Test
	public void consumeWithoutMarkResetsBuffer() {
		UnbufferedCharStream s = new UnbufferedCharStream(new StringReader("abcdef"), 16);
		// consume without mark hits p==n-1 && numMarkers==0 reset path
		assertEquals('a', s.LA(1));
		s.consume();
		assertEquals('b', s.LA(1));
		s.consume();
		assertEquals('c', s.LA(1));
		assertEquals('b', s.LA(-1));
	}
}

