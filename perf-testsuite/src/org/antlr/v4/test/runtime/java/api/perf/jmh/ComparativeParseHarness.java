/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.runtime.java.api.perf.jmh;

import org.antlr.v4.runtime.Lexer;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic, machine-readable end-to-end harness for A/B performance
 * verification across commits. Complements JMH with:
 * <ul>
 *   <li>serial vs parallel multi-file parse (Groovy-style front ends)</li>
 *   <li>warm shared DFA vs cold (clear-per-file) ATN hot paths</li>
 *   <li>SLL, LL, two-stage, and {@code ProfilingATNSimulator} strategies</li>
 *   <li>lexer {@code reset}/{@code setInputStream} reuse</li>
 *   <li>trimmed-mean timing with explicit warmup passes</li>
 * </ul>
 *
 * <p>Run:
 * <pre>
 *   mvn -pl perf-testsuite -am package -DskipTests
 *   java -Xms2g -Xmx2g -cp perf-testsuite/target/classes:...(deps) \
 *     org.antlr.v4.test.runtime.java.api.perf.jmh.ComparativeParseHarness \
 *     --label 4.13.2.12 --files 80 --warmup 5 --iters 15 --threads 0
 * </pre>
 *
 * <p>Output is TSV (one result row per scenario) plus a human summary, so
 * results from two commits can be joined on the {@code scenario} column.
 */
public final class ComparativeParseHarness {

	private static final Locale LOCALE = Locale.ROOT;

	private static final class ScenarioResult {
		final String scenario;
		final int threads;
		final boolean clearDfa;
		final String strategy;
		final int files;
		final long chars;
		final int iters;
		final double meanMs;
		final double medianMs;
		final double p90Ms;
		final double charsPerMs;
		final double tokensPerMs;
		final int syntaxErrors;

		ScenarioResult(String scenario, int threads, boolean clearDfa, String strategy,
					   int files, long chars, int iters,
					   double meanMs, double medianMs, double p90Ms,
					   double charsPerMs, double tokensPerMs, int syntaxErrors) {
			this.scenario = scenario;
			this.threads = threads;
			this.clearDfa = clearDfa;
			this.strategy = strategy;
			this.files = files;
			this.chars = chars;
			this.iters = iters;
			this.meanMs = meanMs;
			this.medianMs = medianMs;
			this.p90Ms = p90Ms;
			this.charsPerMs = charsPerMs;
			this.tokensPerMs = tokensPerMs;
			this.syntaxErrors = syntaxErrors;
		}
	}

	public static void main(String[] args) throws Exception {
		Map<String, String> opts = parseArgs(args);
		String label = opt(opts, "label", "unknown");
		int maxFiles = Integer.parseInt(opt(opts, "files", "80"));
		int warmup = Integer.parseInt(opt(opts, "warmup", "5"));
		int iters = Integer.parseInt(opt(opts, "iters", "15"));
		int threads = Integer.parseInt(opt(opts, "threads", "0"));
		if (threads <= 0) {
			threads = Math.max(2, Runtime.getRuntime().availableProcessors());
		}
		boolean skipParallel = Boolean.parseBoolean(opt(opts, "skipParallel", "false"));
		// Default synthetic=true so A/B across commits compares identical input
		// (runtime sources differ between commits and would bias results).
		boolean forceSynthetic = Boolean.parseBoolean(opt(opts, "synthetic", "true"));
		int methodsPerFile = Integer.parseInt(opt(opts, "methods", "12"));
		int singleMethods = Integer.parseInt(opt(opts, "singleMethods", "40"));
		String singleRel = opt(opts, "single", "org/antlr/v4/runtime/atn/ParserATNSimulator.java");

		printHeader(System.out, label, maxFiles, warmup, iters, threads);

		List<CorpusLoader.SourceFile> corpus;
		CorpusLoader.SourceFile single;
		String corpusKind;
		if (forceSynthetic) {
			corpus = CorpusLoader.syntheticJava7Corpus(maxFiles, methodsPerFile);
			single = CorpusLoader.syntheticJava7Corpus(1, singleMethods).get(0);
			corpusKind = "synthetic-java7";
		}
		else {
			corpus = CorpusLoader.loadAcceptedOrSynthetic(maxFiles, Math.min(10, maxFiles));
			boolean synthetic = corpus.isEmpty() || corpus.get(0).name.startsWith("Bench");
			if (!synthetic) {
				try {
					CorpusLoader.SourceFile candidate = CorpusLoader.loadLargest(singleRel);
					if (ParseWorkload.acceptsTwoStage(candidate.text)) {
						single = candidate;
					}
					else {
						single = corpus.get(0);
					}
				}
				catch (IOException ex) {
					single = corpus.get(0);
				}
			}
			else {
				single = CorpusLoader.syntheticJava7Corpus(1, singleMethods).get(0);
			}
			corpusKind = synthetic ? "synthetic-java7" : "runtime-accepted";
		}

		System.err.printf(LOCALE,
			"Corpus: %d files (%s), %d chars total; single=%s (%d chars)%n",
			corpus.size(), corpusKind,
			CorpusLoader.totalChars(corpus), single.name, single.charCount);

		List<ScenarioResult> results = new ArrayList<ScenarioResult>();

		// --- single-file micro scenarios ---
		results.add(runLex("lex_single_warm", single, false, warmup, iters));
		results.add(runLex("lex_single_cold", single, true, warmup, iters));
		results.add(runParse("parse_single_two_stage_warm", single,
			ParseWorkload.PredictionStrategy.TWO_STAGE, false, warmup, iters));
		results.add(runParse("parse_single_two_stage_cold", single,
			ParseWorkload.PredictionStrategy.TWO_STAGE, true, warmup, iters));
		results.add(runParse("parse_single_ll_warm", single,
			ParseWorkload.PredictionStrategy.LL, false, warmup, iters));
		results.add(runParse("parse_single_ll_cold", single,
			ParseWorkload.PredictionStrategy.LL, true, warmup, iters));

		// --- multi-file serial (Groovy-like sequential compile) ---
		// cold = clear DFA before every file (pure ATN hot path)
		results.add(runBatch("batch_serial_two_stage_warm", corpus,
			ParseWorkload.PredictionStrategy.TWO_STAGE, /*perFileClear*/ false,
			/*clearAtStart*/ false, 1, warmup, iters));
		results.add(runBatch("batch_serial_two_stage_cold_per_file", corpus,
			ParseWorkload.PredictionStrategy.TWO_STAGE, true, false, 1, warmup, iters));
		results.add(runBatch("batch_serial_two_stage_cold_build", corpus,
			ParseWorkload.PredictionStrategy.TWO_STAGE, false, true, 1, warmup, iters));
		results.add(runBatch("batch_serial_ll_warm", corpus,
			ParseWorkload.PredictionStrategy.LL, false, false, 1, warmup, iters));

		// --- multi-file parallel (Groovy-like parallel compile) ---
		// cold_build = clear once per iteration then fill DFA under concurrency
		if (!skipParallel) {
			results.add(runBatch("batch_parallel_two_stage_warm", corpus,
				ParseWorkload.PredictionStrategy.TWO_STAGE, false, false, threads, warmup, iters));
			results.add(runBatch("batch_parallel_two_stage_cold_build", corpus,
				ParseWorkload.PredictionStrategy.TWO_STAGE, false, true, threads, warmup, iters));
			results.add(runBatch("batch_parallel_ll_warm", corpus,
				ParseWorkload.PredictionStrategy.LL, false, false, threads, warmup, iters));
		}

		// --- supplemental probes (after the 13 product cells so ProfilingATNSimulator
		//     / reportAmbiguities cannot mutate the shared DFA those cells walk) ---
		results.add(runLexReuse("lex_reset_reuse_warm", single, warmup, iters));
		results.add(runParse("parse_single_sll_warm", single,
			ParseWorkload.PredictionStrategy.SLL, false, warmup, iters));
		results.add(runBatch("batch_serial_sll_warm", corpus,
			ParseWorkload.PredictionStrategy.SLL, false, false, 1, warmup, iters));
		if (!skipParallel) {
			results.add(runBatch("batch_parallel_sll_warm", corpus,
				ParseWorkload.PredictionStrategy.SLL, false, false, threads, warmup, iters));
		}
		results.add(runParse("parse_single_profiling_warm", single,
			ParseWorkload.PredictionStrategy.PROFILING, false, warmup, iters));
		results.add(runBatch("batch_serial_profiling_warm", corpus,
			ParseWorkload.PredictionStrategy.PROFILING, false, false, 1, warmup, iters));

		printTsv(System.out, label, results);
		printSummary(System.err, label, results);
	}

	private static ScenarioResult runLex(String name, CorpusLoader.SourceFile file,
										 boolean clearDfa, int warmup, int iters) {
		// Warmup
		for (int i = 0; i < warmup; i++) {
			ParseWorkload.lexFile(file.text, clearDfa);
		}
		long[] ns = new long[iters];
		long tokens = 0;
		for (int i = 0; i < iters; i++) {
			long t0 = System.nanoTime();
			tokens = ParseWorkload.lexFile(file.text, clearDfa);
			ns[i] = System.nanoTime() - t0;
		}
		return toResult(name, 1, clearDfa, "LEX", 1, file.charCount, iters, ns, tokens, 0);
	}

	/**
	 * Same source rebound onto one lexer via {@link Lexer#setInputStream}
	 * (calls {@link Lexer#reset}). Isolates the reset / interp-reset path
	 * from lexer construction.
	 */
	private static ScenarioResult runLexReuse(String name, CorpusLoader.SourceFile file,
											  int warmup, int iters) {
		Lexer lexer = ParseWorkload.JAVA.newLexer(file.text);
		for (int i = 0; i < warmup; i++) {
			ParseWorkload.lexReuse(lexer, file.text);
		}
		long[] ns = new long[iters];
		long tokens = 0;
		for (int i = 0; i < iters; i++) {
			long t0 = System.nanoTime();
			tokens = ParseWorkload.lexReuse(lexer, file.text);
			ns[i] = System.nanoTime() - t0;
		}
		return toResult(name, 1, false, "LEX_REUSE", 1, file.charCount, iters, ns, tokens, 0);
	}

	private static ScenarioResult runParse(String name, CorpusLoader.SourceFile file,
										   ParseWorkload.PredictionStrategy strategy,
										   boolean clearDfa, int warmup, int iters) {
		for (int i = 0; i < warmup; i++) {
			try {
				ParseWorkload.parseFile(file.text, strategy, clearDfa, false);
			}
			catch (RuntimeException ignore) {
				// Warmup must not abort the harness.
			}
		}
		long[] ns = new long[iters];
		long tokens = 0;
		int errors = 0;
		for (int i = 0; i < iters; i++) {
			long t0 = System.nanoTime();
			try {
				tokens = ParseWorkload.parseFile(file.text, strategy, clearDfa, false);
			}
			catch (RuntimeException ex) {
				errors++;
			}
			ns[i] = System.nanoTime() - t0;
		}
		return toResult(name, 1, clearDfa, strategy.name(), 1, file.charCount, iters, ns, tokens, errors);
	}

	/**
	 * @param clearDfaPerFile clear before every file (serial ATN-path stress)
	 * @param clearDfaAtStart clear once before each measured batch (DFA rebuild)
	 */
	private static ScenarioResult runBatch(String name, List<CorpusLoader.SourceFile> files,
										   ParseWorkload.PredictionStrategy strategy,
										   boolean clearDfaPerFile, boolean clearDfaAtStart,
										   int threads, int warmup, int iters) throws Exception {
		// Establish steady-state for warm scenarios.
		if (!clearDfaPerFile && !clearDfaAtStart) {
			ParseWorkload.clearSharedDfa();
		}
		for (int i = 0; i < warmup; i++) {
			runOneBatch(files, strategy, clearDfaPerFile, clearDfaAtStart, threads);
		}
		long[] ns = new long[iters];
		long tokens = 0;
		int errors = 0;
		long chars = CorpusLoader.totalChars(files);
		for (int i = 0; i < iters; i++) {
			ParseWorkload.Stats st = runOneBatch(files, strategy, clearDfaPerFile, clearDfaAtStart, threads);
			ns[i] = st.nanos;
			tokens = st.tokens;
			errors += st.syntaxErrors;
		}
		boolean clearFlag = clearDfaPerFile || clearDfaAtStart;
		return toResult(name, threads, clearFlag, strategy.name(),
			files.size(), chars, iters, ns, tokens, errors);
	}

	private static ParseWorkload.Stats runOneBatch(List<CorpusLoader.SourceFile> files,
												   ParseWorkload.PredictionStrategy strategy,
												   boolean clearDfaPerFile,
												   boolean clearDfaAtStart,
												   int threads) throws Exception {
		if (threads <= 1) {
			if (clearDfaAtStart && !clearDfaPerFile) {
				ParseWorkload.clearSharedDfa();
			}
			return ParseWorkload.parseSerial(files, strategy, clearDfaPerFile, false);
		}
		return ParseWorkload.parseParallel(files, strategy, clearDfaAtStart, false, threads);
	}

	private static ScenarioResult toResult(String name, int threads, boolean clearDfa, String strategy,
										   int files, long chars, int iters, long[] ns,
										   long tokens, int errors) {
		double[] ms = new double[ns.length];
		for (int i = 0; i < ns.length; i++) {
			ms[i] = ns[i] / 1_000_000.0;
		}
		double mean = trimmedMean(ms, 0.1);
		double median = percentile(ms, 0.50);
		double p90 = percentile(ms, 0.90);
		double meanNs = mean * 1_000_000.0;
		double charsPerMs = meanNs == 0 ? 0 : chars / (meanNs / 1_000_000.0);
		double tokensPerMs = meanNs == 0 ? 0 : tokens / (meanNs / 1_000_000.0);
		return new ScenarioResult(name, threads, clearDfa, strategy, files, chars, iters,
			mean, median, p90, charsPerMs, tokensPerMs, errors);
	}

	/** Drop top/bottom {@code trimFraction} then average (robust to GC hiccups). */
	private static double trimmedMean(double[] values, double trimFraction) {
		double[] copy = values.clone();
		Arrays.sort(copy);
		int drop = (int) Math.floor(copy.length * trimFraction);
		if (drop * 2 >= copy.length) {
			drop = 0;
		}
		double sum = 0;
		int n = 0;
		for (int i = drop; i < copy.length - drop; i++) {
			sum += copy[i];
			n++;
		}
		return sum / n;
	}

	private static double percentile(double[] values, double p) {
		double[] copy = values.clone();
		Arrays.sort(copy);
		if (copy.length == 1) {
			return copy[0];
		}
		double idx = p * (copy.length - 1);
		int lo = (int) Math.floor(idx);
		int hi = (int) Math.ceil(idx);
		if (lo == hi) {
			return copy[lo];
		}
		double w = idx - lo;
		return copy[lo] * (1 - w) + copy[hi] * w;
	}

	private static void printHeader(PrintStream out, String label, int files, int warmup, int iters, int threads) {
		RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
		out.println("# ComparativeParseHarness");
		out.println("# label=" + label);
		out.println("# java=" + System.getProperty("java.version")
			+ " (" + System.getProperty("java.vm.name") + ")");
		out.println("# os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
		out.println("# processors=" + Runtime.getRuntime().availableProcessors());
		out.println("# files=" + files + " warmup=" + warmup + " iters=" + iters + " parallelThreads=" + threads);
		out.println("# vmArgs=" + rt.getInputArguments());
		out.println("# commitHint=set via --label");
		out.println("#");
	}

	private static void printTsv(PrintStream out, String label, List<ScenarioResult> results) {
		out.println("label\tscenario\tthreads\tclearDfa\tstrategy\tfiles\tchars\titers\tmean_ms\tmedian_ms\tp90_ms\tchars_per_ms\ttokens_per_ms\tsyntax_errors");
		for (ScenarioResult r : results) {
			out.printf(LOCALE,
				"%s\t%s\t%d\t%b\t%s\t%d\t%d\t%d\t%.4f\t%.4f\t%.4f\t%.2f\t%.2f\t%d%n",
				label, r.scenario, r.threads, r.clearDfa, r.strategy, r.files, r.chars, r.iters,
				r.meanMs, r.medianMs, r.p90Ms, r.charsPerMs, r.tokensPerMs, r.syntaxErrors);
		}
	}

	private static void printSummary(PrintStream err, String label, List<ScenarioResult> results) {
		err.println();
		err.println("=== Summary (" + label + ") ===");
		err.printf(LOCALE, "%-40s %10s %10s %10s %12s%n", "scenario", "mean_ms", "median_ms", "p90_ms", "chars/ms");
		for (ScenarioResult r : results) {
			err.printf(LOCALE, "%-40s %10.3f %10.3f %10.3f %12.1f%n",
				r.scenario, r.meanMs, r.medianMs, r.p90Ms, r.charsPerMs);
		}
	}

	private static Map<String, String> parseArgs(String[] args) {
		Map<String, String> m = new LinkedHashMap<String, String>();
		for (int i = 0; i < args.length; i++) {
			String a = args[i];
			if (a.startsWith("--") && i + 1 < args.length) {
				m.put(a.substring(2), args[++i]);
			}
			else if (a.startsWith("--")) {
				m.put(a.substring(2), "true");
			}
		}
		return m;
	}

	private static String opt(Map<String, String> m, String key, String dflt) {
		String v = m.get(key);
		return v == null ? dflt : v;
	}
}
