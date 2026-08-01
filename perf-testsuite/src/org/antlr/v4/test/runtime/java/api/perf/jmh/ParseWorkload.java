/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.runtime.java.api.perf.jmh;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.test.runtime.java.api.JavaLexer;
import org.antlr.v4.test.runtime.java.api.JavaParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared parse/lex helpers for JMH and the comparative harness.
 *
 * <p>Threading model matches real multi-file front ends (Groovy, etc.):
 * <ul>
 *   <li>Each worker owns its own {@link JavaLexer}/{@link JavaParser}
 *       (simulators are not shared across concurrent predictions).</li>
 *   <li>DFA is stored on the static ATN and is therefore shared across
 *       workers when not cleared — the common "warm shared cache" case.</li>
 *   <li>{@code clearDfa} forces ATN simulation on the hot path (stresses
 *       retained scratch, HPPC maps, hash cache, sparse clear).</li>
 * </ul>
 */
public final class ParseWorkload {

	public enum PredictionStrategy {
		/** Single-stage SLL only (BailErrorStrategy). */
		SLL,
		/** Single-stage LL. */
		LL,
		/** Two-stage: SLL+Bail, retry full LL on cancellation (production default). */
		TWO_STAGE
	}

	public static final class Stats {
		public final long nanos;
		public final long tokens;
		public final long chars;
		public final int files;
		public final int syntaxErrors;

		public Stats(long nanos, long tokens, long chars, int files, int syntaxErrors) {
			this.nanos = nanos;
			this.tokens = tokens;
			this.chars = chars;
			this.files = files;
			this.syntaxErrors = syntaxErrors;
		}

		public double usPerFile() {
			return files == 0 ? 0.0 : (nanos / 1000.0) / files;
		}

		public double charsPerMs() {
			return nanos == 0 ? 0.0 : chars / (nanos / 1_000_000.0);
		}

		public double tokensPerMs() {
			return nanos == 0 ? 0.0 : tokens / (nanos / 1_000_000.0);
		}
	}

	private ParseWorkload() {
	}

	public static void clearSharedDfa() {
		// Generated recognizers share static ATN / decisionToDFA.
		new JavaLexer(CharStreams.fromString("")).getInterpreter().clearDFA();
		new JavaParser(new CommonTokenStream(new JavaLexer(CharStreams.fromString("")))).getInterpreter().clearDFA();
	}

	public static int lexFile(String text, boolean clearDfa) {
		if (clearDfa) {
			clearSharedDfa();
		}
		JavaLexer lexer = new JavaLexer(CharStreams.fromString(text));
		lexer.removeErrorListeners();
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		tokens.fill();
		return tokens.size();
	}

	/**
	 * @return token count; never throws for ordinary syntax issues when using
	 *         {@link PredictionStrategy#LL} or {@link PredictionStrategy#TWO_STAGE}
	 *         (error recovery / two-stage retry). {@link PredictionStrategy#SLL}
	 *         uses BailErrorStrategy and may throw {@link ParseCancellationException}.
	 */
	public static int parseFile(String text, PredictionStrategy strategy, boolean clearDfa, boolean buildTree) {
		if (clearDfa) {
			clearSharedDfa();
		}
		JavaLexer lexer = new JavaLexer(CharStreams.fromString(text));
		lexer.removeErrorListeners();
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		JavaParser parser = new JavaParser(tokens);
		parser.removeErrorListeners();
		parser.setBuildParseTree(buildTree);

		switch (strategy) {
			case SLL:
				// Optimistic SLL (same as two-stage stage-1). Callers that need
				// never-throw should use TWO_STAGE or LL.
				parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
				parser.setErrorHandler(new BailErrorStrategy());
				parser.compilationUnit();
				break;
			case LL:
				parser.getInterpreter().setPredictionMode(PredictionMode.LL);
				parser.setErrorHandler(new DefaultErrorStrategy());
				parser.compilationUnit();
				break;
			case TWO_STAGE:
				parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
				parser.setErrorHandler(new BailErrorStrategy());
				try {
					parser.compilationUnit();
				}
				catch (ParseCancellationException ex) {
					// Full restart of the token stream + parser for stage-2 LL.
					tokens.seek(0);
					parser.reset();
					parser.setInputStream(tokens);
					parser.setErrorHandler(new DefaultErrorStrategy());
					parser.getInterpreter().setPredictionMode(PredictionMode.LL);
					parser.setBuildParseTree(buildTree);
					parser.compilationUnit();
				}
				break;
			default:
				throw new IllegalArgumentException(String.valueOf(strategy));
		}
		return tokens.size();
	}

	/** True if two-stage parse completes without throwing. */
	public static boolean acceptsTwoStage(String text) {
		try {
			parseFile(text, PredictionStrategy.TWO_STAGE, false, false);
			return true;
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	/**
	 * Parse all files on the calling thread.
	 *
	 * @param clearDfaPerFile when true, clear shared DFA before <em>each</em>
	 *                        file (serial-only ATN hot path; invalid under
	 *                        concurrent shared-ATN use)
	 */
	public static Stats parseSerial(List<CorpusLoader.SourceFile> files,
									PredictionStrategy strategy,
									boolean clearDfaPerFile,
									boolean buildTree) {
		long tokens = 0;
		int errors = 0;
		long t0 = System.nanoTime();
		for (CorpusLoader.SourceFile f : files) {
			try {
				tokens += parseFile(f.text, strategy, clearDfaPerFile, buildTree);
			}
			catch (RecognitionException | ParseCancellationException ex) {
				errors++;
			}
		}
		long dt = System.nanoTime() - t0;
		return new Stats(dt, tokens, CorpusLoader.totalChars(files), files.size(), errors);
	}

	/**
	 * Parse all files with {@code threads} workers (file-granularity parallelism).
	 * Each task uses its own lexer/parser; DFA is shared via the static ATN
	 * (Groovy-style parallel front end).
	 *
	 * @param clearDfaAtStart when true, clear shared DFA once before the batch
	 *                        so this iteration measures concurrent DFA
	 *                        construction; never clears per-file (would race
	 *                        on the shared ATN)
	 */
	public static Stats parseParallel(List<CorpusLoader.SourceFile> files,
									  PredictionStrategy strategy,
									  boolean clearDfaAtStart,
									  boolean buildTree,
									  int threads) throws InterruptedException, ExecutionException {
		if (threads <= 1) {
			if (clearDfaAtStart) {
				clearSharedDfa();
			}
			return parseSerial(files, strategy, false, buildTree);
		}
		final AtomicInteger errorCount = new AtomicInteger();
		final AtomicInteger tokenCount = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
			private final AtomicInteger n = new AtomicInteger();
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "antlr-parse-" + n.getAndIncrement());
				t.setDaemon(true);
				return t;
			}
		});
		try {
			if (clearDfaAtStart) {
				clearSharedDfa();
			}
			List<Future<Integer>> futures = new ArrayList<Future<Integer>>(files.size());
			long t0 = System.nanoTime();
			for (final CorpusLoader.SourceFile f : files) {
				futures.add(pool.submit(new Callable<Integer>() {
					@Override
					public Integer call() {
						try {
							// Never clear per-file under parallel shared-ATN use.
							return parseFile(f.text, strategy, false, buildTree);
						}
						catch (RecognitionException | ParseCancellationException ex) {
							errorCount.incrementAndGet();
							return 0;
						}
					}
				}));
			}
			for (Future<Integer> fut : futures) {
				tokenCount.addAndGet(fut.get());
			}
			long dt = System.nanoTime() - t0;
			return new Stats(dt, tokenCount.get(), CorpusLoader.totalChars(files), files.size(), errorCount.get());
		}
		finally {
			pool.shutdownNow();
		}
	}

	/** Force class/ATN initialization and a tiny parse for JIT warmup helpers. */
	public static void touch() {
		parseFile("class T { int x; }\n", PredictionStrategy.TWO_STAGE, false, false);
	}
}
