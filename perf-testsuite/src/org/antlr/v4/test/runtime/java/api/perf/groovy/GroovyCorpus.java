/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.runtime.java.api.perf.groovy;

import org.antlr.v4.test.runtime.java.api.perf.jmh.CorpusLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Synthetic sources accepted by {@code GroovyLike.g4}: nls/sep, fields,
 * methods, left-recursive expressions, elvis, closures, and postfix calls.
 */
public final class GroovyCorpus {

	private GroovyCorpus() {
	}

	public static List<CorpusLoader.SourceFile> synthetic(int files, int methodsPerFile) {
		List<CorpusLoader.SourceFile> out = new ArrayList<CorpusLoader.SourceFile>(files);
		for (int i = 0; i < files; i++) {
			out.add(new CorpusLoader.SourceFile("Bench" + i + ".groovy", fileText(i, methodsPerFile)));
		}
		return out;
	}

	public static String fileText(int fileIndex, int methods) {
		StringBuilder sb = new StringBuilder(512 + methods * 220);
		sb.append("package bench").append(fileIndex).append('\n');
		sb.append('\n');
		sb.append("class Bench").append(fileIndex).append(" {\n");
		sb.append("    def N = ").append(fileIndex + 1).append('\n');
		sb.append("    def name = \"bench\"\n");
		sb.append('\n');
		for (int m = 0; m < methods; m++) {
			sb.append("    def m").append(m).append("(x, y) {\n");
			sb.append("        def acc = x + y * N\n");
			sb.append("        if (acc > 0) {\n");
			sb.append("            acc = acc ?: x\n");
			sb.append("        } else {\n");
			sb.append("            acc = x ?: 1\n");
			sb.append("        }\n");
			sb.append("        def xs = { acc * N }\n");
			sb.append("        def s = \"v\"\n");
			sb.append("        return xs + s + call(acc)\n");
			sb.append("    }\n");
			sb.append('\n');
		}
		sb.append("    def call(v) {\n");
		sb.append("        v + N\n");
		sb.append("    }\n");
		sb.append("}\n");
		return sb.toString();
	}
}
