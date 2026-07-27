/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.runtime.java.api.perf;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNConfig;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.antlr.v4.runtime.atn.OrderedATNConfigSet;
import org.antlr.v4.runtime.atn.PredictionContext;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.TokensStartState;
import org.antlr.v4.runtime.misc.MurmurHash;

/**
 * Standalone micro-benchmark for ATN hot-path optimizations. Lives in the
 * performance testsuite (not the runtime unit-test tree). Run with:
 * <pre>
 *   mvn -pl perf-testsuite -DskipTests exec:java \
 *     -Dexec.mainClass=org.antlr.v4.test.runtime.java.api.perf.ATNHotPathMicroBenchmark
 * </pre>
 * or directly from the IDE / {@code java -cp ...}.
 *
 * <p>Reports nanosecond/op and relative speedups for:
 * <ul>
 *   <li>{@link ATNConfig#hashCode()} cache hit vs full MurmurHash recompute</li>
 *   <li>Lexer ATN reach with retained vs fresh {@link OrderedATNConfigSet}</li>
 *   <li>SLL config iteration without intermediate {@link java.util.ArrayList}</li>
 * </ul>
 */
public final class ATNHotPathMicroBenchmark {

	private static final int WARMUP = 20_000;
	private static final int ITERS = 200_000;

	public static void main(String[] args) throws Exception {
		System.out.println("=== ATN / Lexer hot-path micro-benchmarks (JDK "
			+ System.getProperty("java.version") + ") ===");
		System.out.println();

		benchHashCodeCache();
		System.out.println();
		benchLexerReachReuse();
		System.out.println();
		benchSllConfigIteration();
		System.out.println();
		benchLexerMatchThroughput();
		System.out.println();
		System.out.println("Done.");
	}

	/**
	 * Compare cached {@link ATNConfig#hashCode()} against an equivalent
	 * uncached MurmurHash of the same fields.
	 */
	private static void benchHashCodeCache() {
		BasicState state = new BasicState();
		state.stateNumber = 42;
		ATNConfig config = ATNConfig.create(state, 3, PredictionContext.EMPTY_FULL);
		// Force cache fill.
		config.hashCode();

		// Warmup
		int sink = 0;
		for (int i = 0; i < WARMUP; i++) {
			sink ^= config.hashCode();
			sink ^= uncachedHash(config);
		}

		long t0 = System.nanoTime();
		for (int i = 0; i < ITERS; i++) {
			sink ^= config.hashCode();
		}
		long cachedNs = System.nanoTime() - t0;

		t0 = System.nanoTime();
		for (int i = 0; i < ITERS; i++) {
			sink ^= uncachedHash(config);
		}
		long uncachedNs = System.nanoTime() - t0;

		// Prevent DCE
		if (sink == Integer.MIN_VALUE) {
			System.out.println(sink);
		}

		double cachedPerOp = (double) cachedNs / ITERS;
		double uncachedPerOp = (double) uncachedNs / ITERS;
		System.out.println("ATNConfig.hashCode()");
		System.out.printf("  cached   : %8.2f ns/op  (%d iters)%n", cachedPerOp, ITERS);
		System.out.printf("  uncached : %8.2f ns/op  (%d iters)%n", uncachedPerOp, ITERS);
		System.out.printf("  speedup  : %8.2fx (cached vs full MurmurHash)%n",
			uncachedPerOp / cachedPerOp);
	}

	private static int uncachedHash(ATNConfig config) {
		int h = MurmurHash.initialize(7);
		h = MurmurHash.update(h, config.getState().stateNumber);
		h = MurmurHash.update(h, config.getAlt());
		h = MurmurHash.update(h, config.getReachesIntoOuterContext() ? 1 : 0);
		h = MurmurHash.update(h, config.getContext());
		h = MurmurHash.update(h, config.getSemanticContext());
		h = MurmurHash.update(h, config.hasPassedThroughNonGreedyDecision() ? 1 : 0);
		h = MurmurHash.update(h, config.getLexerActionExecutor());
		return MurmurHash.finish(h, 7);
	}

	/**
	 * Measure cost of clearing+reusing an OrderedATNConfigSet vs allocating a
	 * new one each time (models LexerATNSimulator.computeTargetState).
	 * Config objects are prebuilt so the measurement isolates set alloc/clear.
	 */
	private static void benchLexerReachReuse() {
		final int configsPerReach = 32;
		final int rounds = 100_000;
		ATNConfig[] pool = new ATNConfig[configsPerReach];
		for (int i = 0; i < configsPerReach; i++) {
			BasicState state = new BasicState();
			state.stateNumber = i;
			pool[i] = ATNConfig.create(state, 1, PredictionContext.EMPTY_FULL);
			pool[i].hashCode(); // prime hash cache (as after first busy/merge use)
		}

		// Warmup
		OrderedATNConfigSet reusable = new OrderedATNConfigSet(configsPerReach);
		for (int r = 0; r < 10_000; r++) {
			fillReach(reusable, pool);
			reusable.clear();
			OrderedATNConfigSet fresh = new OrderedATNConfigSet(configsPerReach);
			fillReach(fresh, pool);
		}

		long t0 = System.nanoTime();
		for (int r = 0; r < rounds; r++) {
			fillReach(reusable, pool);
			reusable.clear();
		}
		long reuseNs = System.nanoTime() - t0;

		t0 = System.nanoTime();
		for (int r = 0; r < rounds; r++) {
			OrderedATNConfigSet fresh = new OrderedATNConfigSet(configsPerReach);
			fillReach(fresh, pool);
		}
		long allocNs = System.nanoTime() - t0;

		// Busy-set: production uses HPPC ObjectHashSet; keep HashSet as baseline.
		java.util.HashSet<ATNConfig> jdkBusy = new java.util.HashSet<ATNConfig>(configsPerReach * 2);
		com.carrotsearch.hppc.ObjectHashSet<ATNConfig> hppcBusy =
			new com.carrotsearch.hppc.ObjectHashSet<ATNConfig>(configsPerReach * 2);
		for (int r = 0; r < 10_000; r++) {
			jdkBusy.clear();
			hppcBusy.clear();
			for (ATNConfig c : pool) {
				jdkBusy.add(c);
				hppcBusy.add(c);
			}
		}
		t0 = System.nanoTime();
		for (int r = 0; r < rounds; r++) {
			jdkBusy.clear();
			for (ATNConfig c : pool) {
				jdkBusy.add(c);
			}
		}
		long jdkBusyNs = System.nanoTime() - t0;

		t0 = System.nanoTime();
		for (int r = 0; r < rounds; r++) {
			hppcBusy.clear();
			for (ATNConfig c : pool) {
				hppcBusy.add(c);
			}
		}
		long hppcBusyNs = System.nanoTime() - t0;

		System.out.println("OrderedATNConfigSet reach (fill " + configsPerReach + " prebuilt configs)");
		System.out.printf("  reused clear : %8.2f ns/op  (%d rounds)%n",
			(double) reuseNs / rounds, rounds);
		System.out.printf("  fresh alloc  : %8.2f ns/op  (%d rounds)%n",
			(double) allocNs / rounds, rounds);
		System.out.printf("  speedup      : %8.2fx (reuse vs fresh alloc)%n",
			(double) allocNs / reuseNs);
		System.out.printf("  HashSet busy : %8.2f ns/op  (clear+add %d, cached hash)%n",
			(double) jdkBusyNs / rounds, configsPerReach);
		System.out.printf("  ObjectHashSet: %8.2f ns/op  (clear+add %d, production busy set)%n",
			(double) hppcBusyNs / rounds, configsPerReach);
		System.out.printf("  busy speedup : %8.2fx (ObjectHashSet vs HashSet)%n",
			(double) jdkBusyNs / hppcBusyNs);
	}

	private static void fillReach(OrderedATNConfigSet set, ATNConfig[] pool) {
		for (ATNConfig c : pool) {
			set.add(c);
		}
	}

	/**
	 * Models SLL computeTargetState: index walk over ATNConfigSet vs copying
	 * into a new ArrayList first.
	 */
	private static void benchSllConfigIteration() {
		final int n = 64;
		final int rounds = 200_000;
		ATNConfigSet configs = new ATNConfigSet(n);
		for (int i = 0; i < n; i++) {
			BasicState state = new BasicState();
			state.stateNumber = i;
			configs.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_LOCAL));
		}

		int sink = 0;
		for (int r = 0; r < 20_000; r++) {
			for (int i = 0; i < configs.size(); i++) {
				sink ^= configs.get(i).getAlt();
			}
			java.util.ArrayList<ATNConfig> copy = new java.util.ArrayList<ATNConfig>(configs);
			for (int i = 0; i < copy.size(); i++) {
				sink ^= copy.get(i).getAlt();
			}
		}

		long t0 = System.nanoTime();
		for (int r = 0; r < rounds; r++) {
			for (int i = 0, sz = configs.size(); i < sz; i++) {
				sink ^= configs.get(i).getAlt();
			}
		}
		long directNs = System.nanoTime() - t0;

		t0 = System.nanoTime();
		for (int r = 0; r < rounds; r++) {
			java.util.ArrayList<ATNConfig> copy = new java.util.ArrayList<ATNConfig>(configs);
			for (int i = 0, sz = copy.size(); i < sz; i++) {
				sink ^= copy.get(i).getAlt();
			}
		}
		long copyNs = System.nanoTime() - t0;

		if (sink == Integer.MIN_VALUE) {
			System.out.println(sink);
		}

		System.out.println("SLL reach config iteration (" + n + " configs)");
		System.out.printf("  direct index : %8.2f ns/op  (%d rounds)%n",
			(double) directNs / rounds, rounds);
		System.out.printf("  ArrayList copy: %8.2f ns/op  (%d rounds)%n",
			(double) copyNs / rounds, rounds);
		System.out.printf("  speedup       : %8.2fx (direct vs copy)%n",
			(double) copyNs / directNs);
	}

	/**
	 * End-to-end lexer match throughput on a small ATN with DFA cleared each
	 * outer batch so ATN simulation / reach remains on the hot path.
	 */
	private static void benchLexerMatchThroughput() {
		ATN atn = buildTinyLexerAtn();
		LexerATNSimulator sim = new LexerATNSimulator(atn);
		String input = "ababababab";
		final int outer = 2_000;
		final int inner = 50;

		// Warmup (builds DFA)
		for (int i = 0; i < 200; i++) {
			lexAll(sim, input);
		}

		// Steady-state DFA (warm DFA)
		long t0 = System.nanoTime();
		for (int o = 0; o < outer; o++) {
			for (int i = 0; i < inner; i++) {
				lexAll(sim, input);
			}
		}
		long warmNs = System.nanoTime() - t0;
		int warmOps = outer * inner;

		// ATN-heavy: clear DFA each outer iteration
		t0 = System.nanoTime();
		for (int o = 0; o < outer; o++) {
			atn.clearDFA();
			for (int i = 0; i < inner; i++) {
				lexAll(sim, input);
			}
		}
		long coldNs = System.nanoTime() - t0;

		System.out.println("LexerATNSimulator.match (\"" + input + "\", " + input.length() + " chars)");
		System.out.printf("  warm DFA     : %8.2f us/lex  (%d ops)%n",
			warmNs / 1000.0 / warmOps, warmOps);
		System.out.printf("  DFA cleared  : %8.2f us/lex  (%d ops, ATN+reach hot)%n",
			coldNs / 1000.0 / warmOps, warmOps);
	}

	private static void lexAll(LexerATNSimulator sim, String text) {
		org.antlr.v4.runtime.CharStream cs = CharStreams.fromString(text);
		while (cs.LA(1) != org.antlr.v4.runtime.IntStream.EOF) {
			int ttype = sim.match(cs, Lexer.DEFAULT_MODE);
			if (ttype == org.antlr.v4.runtime.Token.EOF) {
				break;
			}
		}
	}

	private static ATN buildTinyLexerAtn() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);

		TokensStartState tokensStart = new TokensStartState();
		tokensStart.ruleIndex = -1;
		atn.addState(tokensStart);

		RuleStartState aStart = new RuleStartState();
		aStart.ruleIndex = 0;
		atn.addState(aStart);
		BasicState aMid = new BasicState();
		aMid.ruleIndex = 0;
		atn.addState(aMid);
		RuleStopState aStop = new RuleStopState();
		aStop.ruleIndex = 0;
		atn.addState(aStop);
		aStart.stopState = aStop;
		aStart.addTransition(new AtomTransition(aMid, 'a'));
		aMid.addTransition(new EpsilonTransition(aStop));

		RuleStartState bStart = new RuleStartState();
		bStart.ruleIndex = 1;
		atn.addState(bStart);
		BasicState bMid = new BasicState();
		bMid.ruleIndex = 1;
		atn.addState(bMid);
		RuleStopState bStop = new RuleStopState();
		bStop.ruleIndex = 1;
		atn.addState(bStop);
		bStart.stopState = bStop;
		bStart.addTransition(new AtomTransition(bMid, 'b'));
		bMid.addTransition(new EpsilonTransition(bStop));

		tokensStart.addTransition(new EpsilonTransition(aStart));
		tokensStart.addTransition(new EpsilonTransition(bStart));

		atn.ruleToStartState = new RuleStartState[] { aStart, bStart };
		atn.ruleToStopState = new RuleStopState[] { aStop, bStop };
		atn.ruleToTokenType = new int[] { 1, 2 };
		atn.defineMode("DEFAULT_MODE", tokensStart);
		return atn;
	}
}
