/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.runtime.java.api.perf.jmh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the ANTLR Java runtime sources that are already packaged as
 * resources of {@code antlr4-perf-testsuite} (see {@code ../runtime} resource
 * directory in the module POM). The corpus approximates multi-file language
 * front-end workloads (e.g. Groovy serial/parallel compilation).
 */
public final class CorpusLoader {

	/** Resource root of the Java runtime sources inside the test classpath. */
	public static final String RUNTIME_SRC_ROOT = "Java/src";

	public static final class SourceFile {
		public final String name;
		public final String text;
		public final int charCount;

		public SourceFile(String name, String text) {
			this.name = name;
			this.text = text;
			this.charCount = text.length();
		}
	}

	private CorpusLoader() {
	}

	/**
	 * Load every {@code .java} file under the packaged runtime sources.
	 * Results are sorted by path for stable iteration order across runs.
	 */
	public static List<SourceFile> loadRuntimeJavaSources() throws IOException {
		return loadRuntimeJavaSources(Integer.MAX_VALUE);
	}

	/**
	 * Load up to {@code maxFiles} largest runtime {@code .java} sources
	 * (stable tie-break by path). Useful for keeping JMH iterations bounded.
	 */
	public static List<SourceFile> loadRuntimeJavaSources(int maxFiles) throws IOException {
		List<SourceFile> all = loadAllUnder(RUNTIME_SRC_ROOT, ".java");
		if (all.isEmpty()) {
			throw new IOException("No Java sources found under classpath resource '"
				+ RUNTIME_SRC_ROOT + "'. Ensure perf-testsuite resources include ../runtime.");
		}
		// Prefer larger files first so a capped corpus still stresses prediction.
		Collections.sort(all, new Comparator<SourceFile>() {
			@Override
			public int compare(SourceFile a, SourceFile b) {
				int bySize = Integer.compare(b.charCount, a.charCount);
				return bySize != 0 ? bySize : a.name.compareTo(b.name);
			}
		});
		if (all.size() > maxFiles) {
			return new ArrayList<SourceFile>(all.subList(0, maxFiles));
		}
		return all;
	}

	/** Load a single well-known large file used by the historical lexer bench. */
	public static SourceFile loadParserJava() throws IOException {
		String resource = RUNTIME_SRC_ROOT + "/org/antlr/v4/runtime/Parser.java";
		String text = readResource(resource);
		return new SourceFile("Parser.java", text);
	}

	public static SourceFile loadLargest(String relativeUnderRuntimeSrc) throws IOException {
		String resource = RUNTIME_SRC_ROOT + "/" + relativeUnderRuntimeSrc;
		String text = readResource(resource);
		int slash = relativeUnderRuntimeSrc.lastIndexOf('/');
		String name = slash >= 0 ? relativeUnderRuntimeSrc.substring(slash + 1) : relativeUnderRuntimeSrc;
		return new SourceFile(name, text);
	}

	public static int totalChars(List<SourceFile> files) {
		int n = 0;
		for (SourceFile f : files) {
			n += f.charCount;
		}
		return n;
	}

	/**
	 * Build a deterministic synthetic Java 1.7-shaped corpus that the bundled
	 * {@code Java.g4} accepts fully. Sized to stress lexer/parser without
	 * depending on real-world language level drift in runtime sources.
	 *
	 * @param fileCount number of compilation units
	 * @param methodsPerFile methods per class (drives expression/statement load)
	 */
	public static List<SourceFile> syntheticJava7Corpus(int fileCount, int methodsPerFile) {
		List<SourceFile> out = new ArrayList<SourceFile>(fileCount);
		for (int i = 0; i < fileCount; i++) {
			StringBuilder sb = new StringBuilder(8 * 1024);
			sb.append("package com.example.bench.p").append(i).append(";\n\n");
			sb.append("import java.util.List;\n");
			sb.append("import java.util.ArrayList;\n\n");
			sb.append("public class Bench").append(i).append(" extends Object implements Runnable {\n");
			sb.append("  private final int id = ").append(i).append(";\n");
			sb.append("  private static final String NAME = \"bench-").append(i).append("\";\n");
			sb.append("  private List values = new ArrayList();\n\n");
			for (int m = 0; m < methodsPerFile; m++) {
				sb.append("  public int m").append(m).append("(int a, int b, String s) {\n");
				sb.append("    int x = a + b * ").append(m + 1).append(";\n");
				sb.append("    if (x > 0 && s != null) {\n");
				sb.append("      x = x + s.length() - id;\n");
				sb.append("    } else if (x < 0) {\n");
				sb.append("      x = -x;\n");
				sb.append("    } else {\n");
				sb.append("      x = x ^ id;\n");
				sb.append("    }\n");
				sb.append("    for (int i = 0; i < 10; i++) {\n");
				sb.append("      x = x + i * (a - b);\n");
				sb.append("      if (i % 2 == 0) { values.add(Integer.valueOf(x)); }\n");
				sb.append("    }\n");
				sb.append("    while (x > 1000) { x = x / 2; }\n");
				sb.append("    try {\n");
				sb.append("      x = compute(x, a);\n");
				sb.append("    } catch (RuntimeException ex) {\n");
				sb.append("      x = 0;\n");
				sb.append("    }\n");
				sb.append("    return x + (s == null ? 0 : s.hashCode());\n");
				sb.append("  }\n\n");
			}
			sb.append("  private int compute(int x, int a) {\n");
			sb.append("    return (x << 1) + a - id;\n");
			sb.append("  }\n\n");
			sb.append("  public void run() {\n");
			sb.append("    m0(1, 2, NAME);\n");
			sb.append("  }\n");
			sb.append("}\n");
			out.add(new SourceFile("Bench" + i + ".java", sb.toString()));
		}
		return out;
	}

	/**
	 * Prefer real runtime sources that the bundled grammar accepts; if fewer
	 * than {@code minAccepted} survive, fall back to a synthetic corpus of
	 * {@code maxFiles} units so A/B runs remain comparable.
	 */
	public static List<SourceFile> loadAcceptedOrSynthetic(int maxFiles, int minAccepted) throws IOException {
		List<SourceFile> real = loadRuntimeJavaSources(Math.max(maxFiles * 3, maxFiles));
		List<SourceFile> accepted = new ArrayList<SourceFile>();
		for (SourceFile f : real) {
			if (ParseWorkload.acceptsTwoStage(f.text)) {
				accepted.add(f);
				if (accepted.size() >= maxFiles) {
					break;
				}
			}
		}
		if (accepted.size() >= minAccepted) {
			return accepted;
		}
		// Stable synthetic fallback (same across commits).
		return syntheticJava7Corpus(maxFiles, 12);
	}

	private static List<SourceFile> loadAllUnder(String rootResource, String suffix) throws IOException {
		ClassLoader cl = CorpusLoader.class.getClassLoader();
		URL rootUrl = cl.getResource(rootResource);
		if (rootUrl == null) {
			throw new IOException("Classpath resource not found: " + rootResource);
		}

		List<SourceFile> out = new ArrayList<SourceFile>();
		try {
			URI uri = rootUrl.toURI();
			if ("jar".equalsIgnoreCase(uri.getScheme())) {
				Map<String, String> env = new HashMap<String, String>();
				// URI form: jar:file:/...!/Java/src
				String s = uri.toString();
				int bang = s.indexOf("!");
				URI jarUri = URI.create(s.substring(0, bang));
				try (FileSystem fs = FileSystems.newFileSystem(jarUri, env)) {
					Path root = fs.getPath(s.substring(bang + 1));
					walk(root, root, suffix, out);
				}
			}
			else {
				Path root = Paths.get(uri);
				walk(root, root, suffix, out);
			}
		}
		catch (URISyntaxException e) {
			throw new IOException(e);
		}
		return out;
	}

	private static void walk(final Path root, Path start, final String suffix, final List<SourceFile> out)
		throws IOException {
		Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				String fileName = file.getFileName().toString();
				if (fileName.endsWith(suffix)) {
					byte[] bytes = Files.readAllBytes(file);
					String text = new String(bytes, StandardCharsets.UTF_8);
					String rel = root.relativize(file).toString().replace('\\', '/');
					out.add(new SourceFile(rel, text));
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static String readResource(String resource) throws IOException {
		ClassLoader cl = CorpusLoader.class.getClassLoader();
		InputStream in = cl.getResourceAsStream(resource);
		if (in == null) {
			throw new IOException("Classpath resource not found: " + resource);
		}
		try {
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int n;
			while ((n = in.read(chunk)) >= 0) {
				buf.write(chunk, 0, n);
			}
			return new String(buf.toByteArray(), StandardCharsets.UTF_8);
		}
		finally {
			in.close();
		}
	}
}
