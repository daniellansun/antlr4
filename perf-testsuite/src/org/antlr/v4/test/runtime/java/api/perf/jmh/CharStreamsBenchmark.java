/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.test.runtime.java.api.perf.jmh;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.IntStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Measures {@link CharStreams#fromString(String)} independently from lexing.
 *
 * <p>The three data shapes exercise the byte, UTF-16, and code-point backing
 * stores selected by {@code CodePointBuffer}. The source-sized input makes
 * allocation and conversion work visible without grammar or DFA effects.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class CharStreamsBenchmark {

	@State(Scope.Thread)
	public static class StringState {
		@Param({"1024", "20640"})
		public int utf16Length;

		@Param({"ascii", "bmp", "smp"})
		public String content;

		public String text;
		public CodePointCharStream stream;

		@Setup
		public void setup() {
			StringBuilder builder = new StringBuilder(utf16Length);
			if ("ascii".equals(content)) {
				appendAscii(builder, utf16Length);
			}
			else if ("bmp".equals(content)) {
				appendBmp(builder, utf16Length);
			}
			else {
				appendSmp(builder, utf16Length);
			}
			text = builder.toString();
			stream = CharStreams.fromString(text);
		}
	}

	@Benchmark
	public void from_string(StringState state, Blackhole blackhole) {
		blackhole.consume(CharStreams.fromString(state.text));
	}

	/**
	 * Mirrors the dominant lexer access pattern: look ahead one code point,
	 * consume it, and restart from the beginning for the next invocation.
	 */
	@Benchmark
	public void la_one_then_consume(StringState state, Blackhole blackhole) {
		CodePointCharStream stream = state.stream;
		int sum = 0;
		int codePoint;
		while ((codePoint = stream.LA(1)) != IntStream.EOF) {
			sum += codePoint;
			stream.consume();
		}
		stream.seek(0);
		blackhole.consume(sum);
	}

	private static void appendAscii(StringBuilder builder, int length) {
		final String fragment = "class Benchmark { int field; void method() { field++; } }\n";
		while (builder.length() < length) {
			builder.append(fragment);
		}
		builder.setLength(length);
	}

	private static void appendBmp(StringBuilder builder, int length) {
		final String fragment = "\u4E2D\u6587\u6D4B\u8BD5";
		while (builder.length() < length) {
			builder.append(fragment);
		}
		builder.setLength(length);
	}

	private static void appendSmp(StringBuilder builder, int length) {
		while (builder.length() + 2 <= length) {
			builder.appendCodePoint(0x1F600);
		}
		if (builder.length() < length) {
			builder.append('x');
		}
	}
}
