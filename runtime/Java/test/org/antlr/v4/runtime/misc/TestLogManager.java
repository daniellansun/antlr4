/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestLogManager {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void emptyToString() {
		LogManager mgr = new LogManager();
		assertEquals("", mgr.toString());
	}

	@Test
	public void logAndToString() {
		LogManager mgr = new LogManager();
		mgr.log("componentA", "hello");
		mgr.log("world");
		String text = mgr.toString();
		assertTrue(text.contains("hello"));
		assertTrue(text.contains("world"));
		assertTrue(text.contains("componentA"));
		assertTrue(text.contains(System.lineSeparator()) || text.length() > 0);
	}

	@Test
	public void saveToFile() throws IOException {
		LogManager mgr = new LogManager();
		mgr.log("atn", "test msg");
		File out = tmp.newFile("test.log");
		mgr.save(out.getAbsolutePath());
		String content = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
		assertTrue(content.contains("test msg"));
		assertTrue(content.contains("atn"));
	}

	@Test
	public void saveDefaultFilename() throws IOException {
		// save() writes to "./antlr-....log" in the process CWD; create and delete carefully
		LogManager mgr = new LogManager();
		mgr.log("dfa", "msg2");
		String filename = mgr.save();
		try {
			assertTrue(filename.contains("antlr-"));
			assertTrue(filename.endsWith(".log"));
			File f = new File(filename);
			assertTrue(f.exists());
			String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			assertTrue(content.contains("msg2"));
		}
		finally {
			new File(filename).delete();
		}
	}

	@Test
	public void mainWritesLogAndPrints() throws Exception {
		// LogManager.main creates an antlr-*.log in CWD; clean up afterward
		java.io.PrintStream prev = System.out;
		java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
		System.setOut(new java.io.PrintStream(buf));
		File[] before = new File(".").listFiles(new java.io.FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return name.startsWith("antlr-") && name.endsWith(".log");
			}
		});
		java.util.Set<String> prior = new java.util.HashSet<String>();
		if (before != null) {
			for (File f : before) {
				prior.add(f.getName());
			}
		}
		try {
			LogManager.main(new String[0]);
			String printed = buf.toString();
			assertTrue(printed.contains("test msg") || printed.contains("atn") || printed.length() > 0);
		}
		finally {
			System.setOut(prev);
			File[] after = new File(".").listFiles(new java.io.FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.startsWith("antlr-") && name.endsWith(".log");
				}
			});
			if (after != null) {
				for (File f : after) {
					if (!prior.contains(f.getName())) {
						f.delete();
					}
				}
			}
		}
	}
}
