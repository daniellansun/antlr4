/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.misc.Interval;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("deprecation")
public class TestANTLRInputStream {
	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	@Test
	public void fromString() {
		ANTLRInputStream s = new ANTLRInputStream("abc");
		assertEquals(3, s.size());
		assertEquals(0, s.index());
		assertEquals('a', s.LA(1));
		assertEquals('b', s.LA(2));
		assertEquals('c', s.LT(3));
		assertEquals(IntStream.EOF, s.LA(4));
		assertEquals("abc", s.toString());
		assertFalse(s.supportsUnicodeCodePoints());
	}

	@Test
	public void consumeAndSeekAndReset() {
		ANTLRInputStream s = new ANTLRInputStream("xy");
		s.consume();
		assertEquals(1, s.index());
		assertEquals('y', s.LA(1));
		s.seek(0);
		assertEquals(0, s.index());
		assertEquals('x', s.LA(1));
		s.consume();
		s.consume();
		assertEquals(IntStream.EOF, s.LA(1));
		s.reset();
		assertEquals(0, s.index());
	}

	@Test(expected = IllegalStateException.class)
	public void consumeEofThrows() {
		ANTLRInputStream s = new ANTLRInputStream("");
		s.consume();
	}

	@Test
	public void fromCharArray() {
		char[] data = {'h', 'i', '!'};
		ANTLRInputStream s = new ANTLRInputStream(data, 2);
		assertEquals(2, s.size());
		assertEquals('h', s.LA(1));
		assertEquals('i', s.LA(2));
		assertEquals(IntStream.EOF, s.LA(3));
		// toString uses the full backing array; getText respects n via interval
		assertEquals("hi", s.getText(Interval.of(0, 1)));
	}

	@Test
	public void fromReader() throws IOException {
		ANTLRInputStream s = new ANTLRInputStream(new StringReader("from-reader"));
		assertEquals(11, s.size());
		assertEquals("from-reader", s.getText(Interval.of(0, 10)));
		assertEquals('f', s.LA(1));
	}

	@Test
	public void fromInputStream() throws IOException {
		byte[] bytes = "stream".getBytes(StandardCharsets.UTF_8);
		ANTLRInputStream s = new ANTLRInputStream(new ByteArrayInputStream(bytes));
		assertEquals(6, s.size());
		assertEquals("stream", s.getText(Interval.of(0, 5)));
	}

	@Test
	public void getTextAndSourceName() {
		ANTLRInputStream s = new ANTLRInputStream("012345");
		s.name = "test-src";
		assertEquals("test-src", s.getSourceName());
		assertEquals("234", s.getText(Interval.of(2, 4)));
		assertEquals(-1, s.mark());
		s.release(0); // no-op
	}

	@Test
	public void emptySourceNameIsUnknown() {
		ANTLRInputStream s = new ANTLRInputStream("a");
		assertEquals(IntStream.UNKNOWN_SOURCE_NAME, s.getSourceName());
	}

	@Test
	public void lookBehind() {
		ANTLRInputStream s = new ANTLRInputStream("ab");
		s.consume();
		assertEquals('a', s.LA(-1));
		assertEquals(IntStream.EOF, s.LA(-2));
	}

	@Test
	public void fileStream() throws IOException {
		File f = tempFolder.newFile("antlr-file.txt");
		Files.write(f.toPath(), "file-data".getBytes(StandardCharsets.UTF_8));
		ANTLRFileStream fs = new ANTLRFileStream(f.getAbsolutePath());
		assertEquals("file-data", fs.toString());
		assertEquals(f.getAbsolutePath(), fs.getSourceName());
	}

	@Test
	public void fileStreamWithEncoding() throws IOException {
		File f = tempFolder.newFile("antlr-file-enc.txt");
		Files.write(f.toPath(), "enc".getBytes(StandardCharsets.UTF_8));
		ANTLRFileStream fs = new ANTLRFileStream(f.getAbsolutePath(), "UTF-8");
		assertEquals("enc", fs.toString());
	}

	@Test
	public void defaultCtorAndLoadEdges() throws IOException {
		ANTLRInputStream empty = new ANTLRInputStream();
		assertEquals(0, empty.size());

		// Reader with initialSize overload (initialSize must be >= chunk or load grows carefully)
		ANTLRInputStream sized = new ANTLRInputStream(new StringReader("abcd"), 1024);
		assertEquals(4, sized.size());
		assertEquals("abcd", sized.getText(Interval.of(0, 3)));

		// InputStream with initialSize / chunk size overloads — keep chunk <= capacity growth path
		byte[] bytes = "0123456789ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8);
		ANTLRInputStream is1 = new ANTLRInputStream(new ByteArrayInputStream(bytes), 1024);
		assertEquals(20, is1.size());
		// initialSize=8, readChunkSize=4: load doubles buffer when p+chunk exceeds length
		ANTLRInputStream is2 = new ANTLRInputStream(new ByteArrayInputStream(bytes), 8, 4);
		assertEquals(20, is2.size());

		// load with null reader is no-op; non-positive sizes fall back to defaults
		ANTLRInputStream loadTarget = new ANTLRInputStream();
		loadTarget.load(null, 0, 0);
		assertEquals(0, loadTarget.size());
		loadTarget.load(new StringReader("z"), 0, 0);
		assertEquals(1, loadTarget.size());
		assertEquals('z', loadTarget.LA(1));
	}

	@Test
	public void seekBeforePAndLaZero() {
		ANTLRInputStream s = new ANTLRInputStream("abc");
		s.consume();
		s.consume();
		assertEquals(2, s.index());
		// seek backward
		s.seek(1);
		assertEquals(1, s.index());
		assertEquals('b', s.LA(1));
		// LA(0) is undefined → 0
		assertEquals(0, s.LA(0));
		// seek past end clamps
		s.seek(100);
		assertEquals(s.size(), s.index());
	}
}
