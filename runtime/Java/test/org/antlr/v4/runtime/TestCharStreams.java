/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestCharStreams {
	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	@Test
	public void fromStringBasic() {
		CodePointCharStream s = CharStreams.fromString("hello");
		assertEquals(5, s.size());
		assertEquals('h', s.LA(1));
		assertEquals(IntStream.UNKNOWN_SOURCE_NAME, s.getSourceName());
		assertEquals("hello", s.toString());
	}

	@Test
	public void fromStringWithSourceName() {
		CodePointCharStream s = CharStreams.fromString("abc", "my-src");
		assertEquals("my-src", s.getSourceName());
		assertEquals(3, s.size());
	}

	@Test
	public void fromStringWithSupplementaryPlanes() {
		String emoji = new StringBuilder().appendCodePoint(0x1F600).toString();
		CodePointCharStream s = CharStreams.fromString(emoji + "X");
		assertEquals(2, s.size());
		assertEquals(0x1F600, s.LA(1));
		assertEquals('X', s.LA(2));
	}

	@Test
	public void fromStringEmpty() {
		CodePointCharStream s = CharStreams.fromString("");
		assertEquals(0, s.size());
		assertEquals(IntStream.EOF, s.LA(1));
		assertEquals("", s.toString());
	}

	@Test
	public void fromStringSelectsCompactStorage() {
		CodePointCharStream ascii = CharStreams.fromString("latin-1-ok");
		assertTrue(ascii.getInternalStorage() instanceof byte[]);

		CodePointCharStream bmp = CharStreams.fromString("\u4E2D\u6587");
		assertTrue(bmp.getInternalStorage() instanceof char[]);

		String emoji = new StringBuilder().appendCodePoint(0x1F600).toString();
		CodePointCharStream smp = CharStreams.fromString(emoji);
		assertTrue(smp.getInternalStorage() instanceof int[]);
	}

	@Test
	public void fromStringMatchesFromReaderContent() throws IOException {
		String text = "mix-\u00E9-\u4E2D-" + new StringBuilder().appendCodePoint(0x1F4A9);
		CodePointCharStream fromString = CharStreams.fromString(text, "s");
		CodePointCharStream fromReader = CharStreams.fromReader(new StringReader(text), "s");
		assertEquals(fromReader.size(), fromString.size());
		assertEquals(fromReader.toString(), fromString.toString());
		for (int i = 0; i < fromString.size(); i++) {
			assertEquals(fromReader.LA(i + 1), fromString.LA(i + 1));
		}
	}

	@Test
	public void fromReader() throws IOException {
		CodePointCharStream s = CharStreams.fromReader(new StringReader("reader-data"));
		assertEquals("reader-data", s.toString());
		assertEquals(11, s.size());
	}

	@Test
	public void fromReaderWithSourceName() throws IOException {
		CodePointCharStream s = CharStreams.fromReader(new StringReader("xy"), "reader-src");
		assertEquals("reader-src", s.getSourceName());
		assertEquals("xy", s.toString());
	}

	@Test
	public void fromStreamUtf8() throws IOException {
		byte[] bytes = "café".getBytes(StandardCharsets.UTF_8);
		CharStream s = CharStreams.fromStream(new ByteArrayInputStream(bytes));
		assertEquals("café", s.toString());
	}

	@Test
	public void fromStreamWithCharset() throws IOException {
		byte[] bytes = "Grüße".getBytes(Charset.forName("ISO-8859-1"));
		CharStream s = CharStreams.fromStream(new ByteArrayInputStream(bytes), Charset.forName("ISO-8859-1"));
		assertEquals("Grüße", s.toString());
	}

	@Test
	public void fromFileNameUtf8() throws IOException {
		File f = tempFolder.newFile("utf8.txt");
		Files.write(f.toPath(), "file-content".getBytes(StandardCharsets.UTF_8));
		CharStream s = CharStreams.fromFileName(f.getAbsolutePath());
		assertEquals("file-content", s.toString());
		assertTrue(s.getSourceName().contains("utf8.txt"));
	}

	@Test
	public void fromFileNameWithCharset() throws IOException {
		File f = tempFolder.newFile("latin1.txt");
		byte[] bytes = "naïve".getBytes(Charset.forName("ISO-8859-1"));
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(bytes);
		}
		CharStream s = CharStreams.fromFileName(f.getAbsolutePath(), Charset.forName("ISO-8859-1"));
		assertEquals("naïve", s.toString());
	}

	@Test
	public void fromFile() throws IOException {
		File f = tempFolder.newFile("from-file.txt");
		Files.write(f.toPath(), "via-file".getBytes(StandardCharsets.UTF_8));
		CharStream s = CharStreams.fromFile(f);
		assertEquals("via-file", s.toString());
	}

	@Test
	public void fromFileWithCharset() throws IOException {
		File f = tempFolder.newFile("from-file-cs.txt");
		byte[] bytes = "¥€".getBytes(StandardCharsets.UTF_8);
		Files.write(f.toPath(), bytes);
		CharStream s = CharStreams.fromFile(f, StandardCharsets.UTF_8);
		assertEquals("¥€", s.toString());
	}

	@Test
	public void emptyStringStream() {
		CodePointCharStream s = CharStreams.fromString("");
		assertEquals(0, s.size());
		assertEquals(IntStream.EOF, s.LA(1));
	}
}
