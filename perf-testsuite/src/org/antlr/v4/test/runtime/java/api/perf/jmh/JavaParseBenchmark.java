/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.runtime.java.api.perf.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH black-box benchmarks for the optimized-fork Java runtime.
 *
 * <p>Scenarios use only public runtime APIs so the same sources can be
 * overlaid on both baseline and candidate commits for A/B comparison.
 *
 * <p>Build and run:
 * <pre>
 *   mvn -pl perf-testsuite -am package -DskipTests -Pjmh
 *   java -jar perf-testsuite/target/benchmarks.jar -rf json -rff results.json
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class JavaParseBenchmark {

	@State(Scope.Benchmark)
	public static class SingleFileState {
		/** Synthetic methods-per-file controls single-file stress. */
		@Param({"40"})
		public int methods;

		/** When true, clear shared DFA before each op (ATN hot path). */
		@Param({"false", "true"})
		public boolean clearDfa;

		public String text;
		public String label;

		@Setup(Level.Trial)
		public void setup() throws Exception {
			CorpusLoader.SourceFile src = CorpusLoader.syntheticJava7Corpus(1, methods).get(0);
			text = src.text;
			label = src.name;
			ParseWorkload.touch();
			if (!clearDfa) {
				ParseWorkload.lexFile(text, false);
				ParseWorkload.parseFile(text, ParseWorkload.PredictionStrategy.TWO_STAGE, false, false);
			}
		}
	}

	@State(Scope.Benchmark)
	public static class MultiFileState {
		/** Cap corpus size for stable JMH iteration time. */
		@Param({"40"})
		public int maxFiles;

		@Param({"false", "true"})
		public boolean clearDfaPerFile;

		public List<CorpusLoader.SourceFile> files;

		@Setup(Level.Trial)
		public void setup() throws Exception {
			files = CorpusLoader.loadAcceptedOrSynthetic(maxFiles, Math.min(10, maxFiles));
			ParseWorkload.touch();
			if (!clearDfaPerFile) {
				ParseWorkload.parseSerial(files, ParseWorkload.PredictionStrategy.TWO_STAGE, false, false);
			}
		}
	}

	@Benchmark
	public void lex_single(SingleFileState st, Blackhole bh) {
		bh.consume(ParseWorkload.lexFile(st.text, st.clearDfa));
	}

	@Benchmark
	public void parse_two_stage_single(SingleFileState st, Blackhole bh) {
		bh.consume(ParseWorkload.parseFile(
			st.text, ParseWorkload.PredictionStrategy.TWO_STAGE, st.clearDfa, false));
	}

	@Benchmark
	public void parse_sll_single(SingleFileState st, Blackhole bh) {
		bh.consume(ParseWorkload.parseFile(
			st.text, ParseWorkload.PredictionStrategy.SLL, st.clearDfa, false));
	}

	@Benchmark
	public void parse_serial_batch(MultiFileState st, Blackhole bh) {
		// clearDfaPerFile=true is valid only for serial shared-ATN use.
		bh.consume(ParseWorkload.parseSerial(
			st.files, ParseWorkload.PredictionStrategy.TWO_STAGE, st.clearDfaPerFile, false));
	}

	@Benchmark
	public void parse_parallel_batch(MultiFileState st, Blackhole bh) throws Exception {
		int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
		// Parallel: clear once per op when clearDfaPerFile (DFA rebuild under load).
		bh.consume(ParseWorkload.parseParallel(
			st.files, ParseWorkload.PredictionStrategy.TWO_STAGE, st.clearDfaPerFile, false, threads));
	}
}
