/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.LexerNoViableAltException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.dfa.AcceptStateInfo;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * ATN interpreter for lexers. Builds and consults a per-mode DFA cache while
 * falling back to ATN simulation for unseen edges.
 *
 * <p>PERF: Reach config sets are retained on the simulator and cleared between
 * edge computations so the common DFA-fill path avoids per-character
 * {@link OrderedATNConfigSet} allocation. Clear of the retained pool is
 * occupancy-aware (see {@link ATNConfigSet#clear()} /
 * {@link RetainedConfigSet}): empty clear is O(1) for the merge map (no bulk
 * {@code Arrays.fill}); non-empty clear nulls only the used list prefix and,
 * when few configs remain in a grown open-addressed table, removes known merge
 * keys (O(n)) instead of filling the full table capacity. Obtain/release under
 * {@link #computeTargetState} therefore does not pay repeated full-capacity
 * zeroing. A single lexer (and its simulator) is not used concurrently for
 * matching, so reuse is safe. Stored DFA states always receive a readonly
 * {@link ATNConfigSet#clone(boolean)} of the reach set, never the reusable
 * buffer itself.</p>
 */
public class LexerATNSimulator extends ATNSimulator {

	public static final boolean debug = false;
	public static final boolean dfa_debug = false;

	public static final int MIN_DFA_EDGE = 0;
	public static final int MAX_DFA_EDGE = Character.MAX_CODE_POINT;

	public boolean optimize_tail_calls = true;

	/** When we hit an accept state in either the DFA or the ATN, we
	 *  have to notify the character stream to start buffering characters
	 *  via {@link IntStream#mark} and record the current state. The current sim state
	 *  includes the current index into the input, the current line,
	 *  and current character position in that line. Note that the Lexer is
	 *  tracking the starting line and characterization of the token. These
	 *  variables track the "state" of the simulator when it hits an accept state.
	 *
	 *  <p>We track these variables separately for the DFA and ATN simulation
	 *  because the DFA simulation often has to fail over to the ATN
	 *  simulation. If the ATN simulation fails, we need the DFA to fall
	 *  back to its previously accepted state, if any. If the ATN succeeds,
	 *  then the ATN does the accept and the DFA simulator that invoked it
	 *  can simply return the predicted token type.</p>
	 */
	protected static class SimState {
		protected int index = -1;
		protected int line = 0;
		protected int charPos = -1;
		protected DFAState dfaState;

		protected void reset() {
			index = -1;
			line = 0;
			charPos = -1;
			dfaState = null;
		}
	}

	@Nullable
	protected final Lexer recog;

	/** The current token's starting index into the character stream.
	 *  Shared across DFA to ATN simulation in case the ATN fails and the
	 *  DFA did not have a previous accept state. In this case, we use the
	 *  ATN-generated exception object.
	 */
	protected int startIndex = -1;

	/** line number 1..n within the input */
	protected int line = 1;

	/** The index of the character relative to the beginning of the line 0..n-1 */
	protected int charPositionInLine = 0;

	protected int mode = Lexer.DEFAULT_MODE;

	/** Used during DFA/ATN exec to record the most recent accept configuration info */
	@NotNull
	protected final SimState prevAccept = new SimState();

	/**
	 * Retained reach set for {@link #computeTargetState}, allocated lazily on
	 * the first DFA miss so warm-DFA lexing incurs zero scratch allocation.
	 */
	@Nullable
	private RetainedConfigSet retainedReach;

	/**
	 * Closure busy set for the current outermost {@link #closure} call,
	 * allocated lazily only when closure depth exceeds {@link #CLOSURE_BUSY_DEPTH}.
	 */
	@Nullable
	private Map<ATNConfig, Boolean> closureBusy;

	/** Nesting depth of {@link #closure}. Zero outside a closure root. */
	private int closureDepth;

	/**
	 * Reusable predicate trace buffers for {@link LexerPredEdgeTable} lookup
	 * replay and recording, allocated lazily on first predicated DFA miss.
	 */
	@Nullable
	private int[] predRuleTrace;
	@Nullable
	private int[] predIndexTrace;
	@Nullable
	private boolean[] predResultTrace;
	private boolean predCaptureActive;
	private int predReplayLen;
	private int predStep;
	private boolean predRecordAbort;

	/**
	 * Concrete class of Latin-1 (8-bit) {@link CodePointCharStream} instances,
	 * obtained via {@link CharStreams#fromString(String)} without exposing any
	 * internal stream subclass on the public API. Dispatching 8-bit inputs into
	 * a dedicated {@link #execATNFast8Bit} method keeps {@code input.LA(1)}
	 * monomorphic even in mixed Latin-1 / UTF-16 workloads.
	 */
	private static final Class<?> CODEPOINT_8BIT_CLASS = CharStreams.fromString("").getClass();

	/**
	 * Whether this concrete simulator class leaves the core DFA/ATN execution
	 * hooks un-overridden, making it eligible for the register-resident fast
	 * execution path and predicate-guarded DFA edge cache.
	 *
	 * <p>Note: the register-resident fast path keeps accept state in local
	 * variables and does not update {@link #prevAccept}; subclasses that inspect
	 * {@link #prevAccept} must override one of the protected execution hooks.</p>
	 */
	private final boolean useFastExec;

	/**
	 * Whether the owning {@link Lexer} leaves {@link Lexer#nextToken()} and
	 * {@link Lexer#skip()} un-overridden, allowing safe in-place fusion of
	 * single built-in {@code -> skip} rules inside the fast DFA loop.
	 */
	private final boolean useSkipFusion;

	private static final ClassValue<Boolean> FAST_EXEC_ELIGIBLE = new ClassValue<Boolean>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			if (type == LexerATNSimulator.class) {
				return Boolean.TRUE;
			}
			try {
				for (Class<?> c = type; c != null && c != LexerATNSimulator.class; c = c.getSuperclass()) {
					for (Method m : c.getDeclaredMethods()) {
						String name = m.getName();
						if ("execATN".equals(name)
							|| "getExistingTargetState".equals(name)
							|| "captureSimState".equals(name)
							|| "failOrAccept".equals(name)
							|| "accept".equals(name)
							|| "computeTargetState".equals(name)
							|| "getReachableConfigSet".equals(name)
							|| "getReachableTarget".equals(name)
							|| "addDFAEdge".equals(name)
							|| "addDFAState".equals(name)
							|| "closure".equals(name)
							|| "getEpsilonTarget".equals(name)
							|| "evaluatePredicate".equals(name)
							|| "consume".equals(name)) {
							return Boolean.FALSE;
						}
					}
				}
				return Boolean.TRUE;
			}
			catch (SecurityException ex) {
				return Boolean.FALSE;
			}
		}
	};

	private static final ClassValue<Boolean> SKIP_FUSION_ELIGIBLE = new ClassValue<Boolean>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			try {
				for (Class<?> c = type; c != null && c != Lexer.class; c = c.getSuperclass()) {
					for (Method m : c.getDeclaredMethods()) {
						String name = m.getName();
						if ("nextToken".equals(name) || "skip".equals(name)) {
							return Boolean.FALSE;
						}
					}
				}
				return Boolean.TRUE;
			}
			catch (SecurityException ex) {
				return Boolean.FALSE;
			}
		}
	};

	/**
	 * Closure frames before {@link #closureBusy} is consulted. Shallow
	 * decisions pay no map probe. Deep enough that a same-config cycle stops
	 * well short of the thread stack, including when the ATN itself is large.
	 */
	private static final int CLOSURE_BUSY_DEPTH = 64;

	/** @deprecated This field is no longer used. */
	@Deprecated
	public static int match_calls = 0;

	public LexerATNSimulator(@NotNull ATN atn) {
		this(null, atn);
	}

	public LexerATNSimulator(@Nullable Lexer recog, @NotNull ATN atn) {
		super(atn);
		this.recog = recog;
		this.useFastExec = FAST_EXEC_ELIGIBLE.get(getClass()).booleanValue();
		this.useSkipFusion = recog != null && SKIP_FUSION_ELIGIBLE.get(recog.getClass()).booleanValue();
	}

	public void copyState(@NotNull LexerATNSimulator simulator) {
		this.charPositionInLine = simulator.charPositionInLine;
		this.line = simulator.line;
		this.mode = simulator.mode;
		this.startIndex = simulator.startIndex;
	}

	public int match(@NotNull CharStream input, int mode) {
		this.mode = mode;
		if (useFastExec && !debug) {
			DFAState s0 = atn.modeToDFA[mode].s0.get();
			if (s0 != null) {
				if (input.getClass() == CODEPOINT_8BIT_CLASS) {
					CodePointCharStream cs = (CodePointCharStream) input;
					this.startIndex = cs.index();
					return execATNFast8Bit(cs, s0);
				}
				if (input instanceof CodePointCharStream) {
					CodePointCharStream cs = (CodePointCharStream) input;
					this.startIndex = cs.index();
					return execATNFastCodePoint(cs, s0);
				}
				return matchFastGeneric(input, s0);
			}
		}
		return matchSlow(input, mode);
	}

	private int matchFastGeneric(@NotNull CharStream input, @NotNull DFAState s0) {
		int mark = input.mark();
		try {
			this.startIndex = input.index();
			return execATNFastGeneric(input, s0);
		}
		finally {
			input.release(mark);
		}
	}

	private int matchSlow(@NotNull CharStream input, int mode) {
		int mark = input.mark();
		try {
			this.startIndex = input.index();
			this.prevAccept.reset();
			DFAState s0 = atn.modeToDFA[mode].s0.get();
			if ( s0==null ) {
				return matchATN(input);
			}
			else {
				return execATN(input, s0);
			}
		}
        finally {
			input.release(mark);
		}
	}

	/**
	 * Monomorphic 8-bit Latin-1 {@link CodePointCharStream} fast path.
	 * Separating 8-bit inputs from 16/32-bit streams keeps {@code input.LA(1)}
	 * monomorphic so HotSpot C2 inlines the {@code byte[]} load directly into
	 * the DFA loop even when a corpus mixes Latin-1 and Unicode source files.
	 * Structuring the {@code t > '\r'} branch first eliminates redundant EOF
	 * comparisons on 96%+ of source characters and keeps this method above
	 * HotSpot's {@code FreqInlineSize} so it receives a dedicated C2 root
	 * compilation without hitting {@code NodeCountInliningCutoff} inside
	 * {@link Lexer#nextToken()}.
	 */
	private int execATNFast8Bit(@NotNull CodePointCharStream input, @NotNull DFAState ds0) {
		int curLine = this.line;
		int curCharPos = this.charPositionInLine;

		outer:
		while (true) {
			int acceptIndex = -1;
			int acceptLine = 0;
			int acceptCharPos = -1;
			DFAState acceptState = null;

			if (ds0.isAcceptState()) {
				acceptIndex = input.index();
				acceptLine = curLine;
				acceptCharPos = curCharPos;
				acceptState = ds0;
			}

			int t = input.LA(1);
			DFAState s = ds0;

			while (true) {
				DFAState target = s.getLexerTarget(t);
				if (target == null) {
					this.line = curLine;
					this.charPositionInLine = curCharPos;
					target = resolveTargetFast(input, ds0, s, t);
					curLine = this.line;
					curCharPos = this.charPositionInLine;
				}

				if (target == ERROR) {
					break;
				}

				if (t > '\r') {
					curCharPos++;
					input.consume();
					if (target.isAcceptState()) {
						acceptIndex = input.index();
						acceptLine = curLine;
						acceptCharPos = curCharPos;
						acceptState = target;
					}
				}
				else if (t != IntStream.EOF) {
					if (t == '\n' || (t == '\r' && input.LA(2) != '\n')) {
						curLine++;
						curCharPos = 0;
					}
					else {
						curCharPos++;
					}
					input.consume();
					if (target.isAcceptState()) {
						acceptIndex = input.index();
						acceptLine = curLine;
						acceptCharPos = curCharPos;
						acceptState = target;
					}
				}
				else if (target.isAcceptState()) {
					acceptIndex = input.index();
					acceptLine = curLine;
					acceptCharPos = curCharPos;
					acceptState = target;
					break;
				}

				t = input.LA(1);
				s = target;
			}

			if (acceptState != null) {
				if (input.index() != acceptIndex) {
					input.seek(acceptIndex);
				}
				this.line = acceptLine;
				this.charPositionInLine = acceptCharPos;
				AcceptStateInfo acceptInfo = acceptState.getAcceptStateInfo();
				LexerActionExecutor exec = acceptInfo.getLexerActionExecutor();
				if (exec != null && recog != null) {
					// Fuse single built-in -> skip rules (e.g. WS -> skip) in-place
					// when invoked from Lexer.nextToken() to avoid returning through
					// Lexer.nextToken -> match -> s0.get() -> class-check on every skip.
					if (useSkipFusion
						&& exec.isSingleSkip()
						&& acceptIndex > this.startIndex
						&& !recog._hitEOF
						&& recog._type == Token.INVALID_TYPE
						&& recog._token == null
						&& recog._channel == Token.DEFAULT_CHANNEL
						&& recog._text == null
						&& recog._tokenStartCharIndex == this.startIndex) {
						recog.skip();
						if (recog._type == Lexer.SKIP
							&& !recog._hitEOF
							&& recog._mode == this.mode
							&& recog._token == null
							&& recog._channel == Token.DEFAULT_CHANNEL
							&& recog._text == null
							&& acceptIndex < input.size()
							&& input.index() == acceptIndex
							&& this.line == acceptLine
							&& this.charPositionInLine == acceptCharPos) {
							this.startIndex = acceptIndex;
							recog._tokenStartCharIndex = acceptIndex;
							recog._tokenStartLine = acceptLine;
							recog._tokenStartCharPositionInLine = acceptCharPos;
							recog._type = Token.INVALID_TYPE;
							curLine = acceptLine;
							curCharPos = acceptCharPos;
							continue outer;
						}
						return acceptInfo.getPrediction();
					}
					exec.execute(recog, input, startIndex);
				}
				return acceptInfo.getPrediction();
			}

			return noViableAltFast(input, s, t, curLine, curCharPos);
		}
	}

	/**
	 * Register-resident fast path for non-8-bit {@link CodePointCharStream}
	 * inputs (16-bit BMP and 32-bit supplementary) when no protected simulator
	 * hook is overridden.
	 */
	private int execATNFastCodePoint(@NotNull CodePointCharStream input, @NotNull DFAState ds0) {
		int curLine = this.line;
		int curCharPos = this.charPositionInLine;

		outer:
		while (true) {
			int acceptIndex = -1;
			int acceptLine = 0;
			int acceptCharPos = -1;
			DFAState acceptState = null;

			if (ds0.isAcceptState()) {
				acceptIndex = input.index();
				acceptLine = curLine;
				acceptCharPos = curCharPos;
				acceptState = ds0;
			}

			int t = input.LA(1);
			DFAState s = ds0;

			while (true) {
				DFAState target = s.getLexerTarget(t);
				if (target == null) {
					this.line = curLine;
					this.charPositionInLine = curCharPos;
					target = resolveTargetFast(input, ds0, s, t);
					curLine = this.line;
					curCharPos = this.charPositionInLine;
				}

				if (target == ERROR) {
					break;
				}

				if (t > '\r') {
					curCharPos++;
					input.consume();
					if (target.isAcceptState()) {
						acceptIndex = input.index();
						acceptLine = curLine;
						acceptCharPos = curCharPos;
						acceptState = target;
					}
				}
				else if (t != IntStream.EOF) {
					if (t == '\n' || (t == '\r' && input.LA(2) != '\n')) {
						curLine++;
						curCharPos = 0;
					}
					else {
						curCharPos++;
					}
					input.consume();
					if (target.isAcceptState()) {
						acceptIndex = input.index();
						acceptLine = curLine;
						acceptCharPos = curCharPos;
						acceptState = target;
					}
				}
				else if (target.isAcceptState()) {
					acceptIndex = input.index();
					acceptLine = curLine;
					acceptCharPos = curCharPos;
					acceptState = target;
					break;
				}

				t = input.LA(1);
				s = target;
			}

			if (acceptState != null) {
				if (input.index() != acceptIndex) {
					input.seek(acceptIndex);
				}
				this.line = acceptLine;
				this.charPositionInLine = acceptCharPos;
				AcceptStateInfo acceptInfo = acceptState.getAcceptStateInfo();
				LexerActionExecutor exec = acceptInfo.getLexerActionExecutor();
				if (exec != null && recog != null) {
					if (useSkipFusion
						&& exec.isSingleSkip()
						&& acceptIndex > this.startIndex
						&& !recog._hitEOF
						&& recog._type == Token.INVALID_TYPE
						&& recog._token == null
						&& recog._channel == Token.DEFAULT_CHANNEL
						&& recog._text == null
						&& recog._tokenStartCharIndex == this.startIndex) {
						recog.skip();
						if (recog._type == Lexer.SKIP
							&& !recog._hitEOF
							&& recog._mode == this.mode
							&& recog._token == null
							&& recog._channel == Token.DEFAULT_CHANNEL
							&& recog._text == null
							&& acceptIndex < input.size()
							&& input.index() == acceptIndex
							&& this.line == acceptLine
							&& this.charPositionInLine == acceptCharPos) {
							this.startIndex = acceptIndex;
							recog._tokenStartCharIndex = acceptIndex;
							recog._tokenStartLine = acceptLine;
							recog._tokenStartCharPositionInLine = acceptCharPos;
							recog._type = Token.INVALID_TYPE;
							curLine = acceptLine;
							curCharPos = acceptCharPos;
							continue outer;
						}
						return acceptInfo.getPrediction();
					}
					exec.execute(recog, input, startIndex);
				}
				return acceptInfo.getPrediction();
			}

			return noViableAltFast(input, s, t, curLine, curCharPos);
		}
	}

	/**
	 * Register-resident fast path for arbitrary {@link CharStream} inputs when
	 * no protected simulator hook is overridden.
	 */
	private int execATNFastGeneric(@NotNull CharStream input, @NotNull DFAState ds0) {
		int curLine = this.line;
		int curCharPos = this.charPositionInLine;
		int acceptIndex = -1;
		int acceptLine = 0;
		int acceptCharPos = -1;
		DFAState acceptState = null;

		if (ds0.isAcceptState()) {
			acceptIndex = input.index();
			acceptLine = curLine;
			acceptCharPos = curCharPos;
			acceptState = ds0;
		}

		int t = input.LA(1);
		DFAState s = ds0;

		while (true) {
			DFAState target = s.getLexerTarget(t);
			if (target == null) {
				this.line = curLine;
				this.charPositionInLine = curCharPos;
				target = resolveTargetFast(input, ds0, s, t);
				curLine = this.line;
				curCharPos = this.charPositionInLine;
			}

			if (target == ERROR) {
				break;
			}

			if (t != IntStream.EOF) {
				if (t > '\r') {
					curCharPos++;
				}
				else if (t == '\n' || (t == '\r' && input.LA(2) != '\n')) {
					curLine++;
					curCharPos = 0;
				}
				else {
					curCharPos++;
				}
				input.consume();
			}

			if (target.isAcceptState()) {
				acceptIndex = input.index();
				acceptLine = curLine;
				acceptCharPos = curCharPos;
				acceptState = target;
				if (t == IntStream.EOF) {
					break;
				}
			}

			t = input.LA(1);
			s = target;
		}

		if (acceptState != null) {
			if (input.index() != acceptIndex) {
				input.seek(acceptIndex);
			}
			this.line = acceptLine;
			this.charPositionInLine = acceptCharPos;
			LexerActionExecutor exec = acceptState.getLexerActionExecutor();
			if (exec != null && recog != null) {
				exec.execute(recog, input, startIndex);
			}
			return acceptState.getPrediction();
		}

		return noViableAltFast(input, s, t, curLine, curCharPos);
	}

	private int noViableAltFast(@NotNull CharStream input, @NotNull DFAState s, int t, int curLine, int curCharPos) {
		this.line = curLine;
		this.charPositionInLine = curCharPos;
		if (t == IntStream.EOF && input.index() == startIndex) {
			return Token.EOF;
		}
		throw new LexerNoViableAltException(recog, input, startIndex, s.configs);
	}

	/**
	 * Resolves a missing DFA edge on the fast path, consulting and populating
	 * {@link LexerPredEdgeTable} for predicate-suppressed ASCII transitions so
	 * repeated occurrences evaluate only the exact predicates without scanning
	 * ATN configurations or running epsilon closure.
	 *
	 * <p>PERF: Kept as a single method above HotSpot's {@code FreqInlineSize}
	 * (325 bytes) so C2 compiles it standalone instead of inlining cold
	 * {@code addDFAState}/{@code ATNConfigSet} construction into
	 * {@link #execATNFast8Bit} / {@link #execATNFastCodePoint} and exhausting
	 * {@code NodeCountInliningCutoff} before the hot character loop.</p>
	 */
	@NotNull
	private DFAState resolveTargetFast(@NotNull CharStream input, @NotNull DFAState ds0, @NotNull DFAState s, int t) {
		if (recog == null || !optimize_tail_calls || (t & ~0x7F) != 0 || s.stateNumber < 0 || s.stateNumber == Integer.MAX_VALUE) {
			return computeTargetState(input, s, t);
		}

		LexerPredEdgeTable table = atn.getLexerPredEdgeTable(mode, ds0);
		int replayLen = 0;
		if (table != null) {
			LexerPredEdgeTable.PredNode node = table.getRoot(s.stateNumber, t);
			if (node != null) {
				ensurePredTraceBuffers();
				Object cur = node;
				while (cur instanceof LexerPredEdgeTable.PredNode) {
					LexerPredEdgeTable.PredNode pn = (LexerPredEdgeTable.PredNode) cur;
					boolean res = evaluatePredicate(input, pn.ruleIndex, pn.predIndex, true);
					if (replayLen < LexerPredEdgeTable.MAX_PRED_DEPTH) {
						predRuleTrace[replayLen] = pn.ruleIndex;
						predIndexTrace[replayLen] = pn.predIndex;
						predResultTrace[replayLen] = res;
						replayLen++;
					}
					cur = res ? pn.onTrue : pn.onFalse;
				}
				if (cur != null) {
					return (DFAState) cur;
				}
			}
		}

		ensurePredTraceBuffers();
		ATNConfigSet reach = obtainRetainedReach(s.configs.size());
		predCaptureActive = true;
		predReplayLen = replayLen;
		predStep = 0;
		predRecordAbort = false;
		try {
			getReachableConfigSet(input, s.configs, reach, t);

			boolean suppressEdge = reach.hasSemanticContext();
			DFAState target;
			if (reach.isEmpty()) {
				if (!suppressEdge) {
					addDFAEdge(s, t, ERROR);
				}
				target = ERROR;
			}
			else {
				target = addDFAEdge(s, t, reach);
			}

			if (suppressEdge && !predRecordAbort && predStep > 0) {
				atn.getOrCreateLexerPredEdgeTable(mode, ds0)
					.put(s.stateNumber, t, predRuleTrace, predIndexTrace, predResultTrace, predStep, target);
			}
			return target;
		}
		finally {
			predCaptureActive = false;
			predReplayLen = 0;
			retainedReach.release();
		}
	}

	private void ensurePredTraceBuffers() {
		if (predRuleTrace == null) {
			predRuleTrace = new int[LexerPredEdgeTable.MAX_PRED_DEPTH];
			predIndexTrace = new int[LexerPredEdgeTable.MAX_PRED_DEPTH];
			predResultTrace = new boolean[LexerPredEdgeTable.MAX_PRED_DEPTH];
		}
	}

	@NotNull
	private ATNConfigSet obtainRetainedReach(int hint) {
		if (retainedReach == null) {
			retainedReach = new RetainedConfigSet(true);
		}
		return retainedReach.obtain(hint);
	}

	@Override
	public void reset() {
		prevAccept.reset();
		startIndex = -1;
		line = 1;
		charPositionInLine = 0;
		mode = Lexer.DEFAULT_MODE;
		if (closureBusy != null && !closureBusy.isEmpty()) {
			closureBusy.clear();
		}
		closureDepth = 0;
	}

	protected int matchATN(@NotNull CharStream input) {
		ATNState startState = atn.modeToStartState.get(mode);

		/*if ( debug ) {
			System.out.format(Locale.getDefault(), "matchATN mode %d start: %s\n", mode, startState);
		}*/

		int old_mode = mode;

		ATNConfigSet s0_closure = computeStartState(input, startState);
		boolean suppressEdge = s0_closure.hasSemanticContext();
		if (suppressEdge) {
			s0_closure.clearExplicitSemanticContext();
		}

		DFAState next = addDFAState(s0_closure);
		if (!suppressEdge) {
			if (!atn.modeToDFA[mode].s0.compareAndSet(null, next)) {
				next = atn.modeToDFA[mode].s0.get();
			}
		}

		int predict = execATN(input, next);

		/*if ( debug ) {
			System.out.format(Locale.getDefault(), "DFA after matchATN: %s\n", atn.modeToDFA[old_mode].toLexerString());
		}*/

		return predict;
	}

	protected int execATN(@NotNull CharStream input, @NotNull DFAState ds0) {
		//System.out.println("enter exec index "+input.index()+" from "+ds0.configs);
		/*if ( debug ) {
			System.out.format(Locale.getDefault(), "start state closure=%s\n", ds0.configs);
		}*/

		if (ds0.isAcceptState()) {
			// allow zero-length tokens
			captureSimState(prevAccept, input, ds0);
		}

		int t = input.LA(1);
		@NotNull
		DFAState s = ds0; // s is current/from DFA state

		while ( true ) { // while more work
			/*if ( debug ) {
				System.out.format(Locale.getDefault(), "execATN loop starting closure: %s\n", s.configs);
			}*/

			// As we move src->trg, src->trg, we keep track of the previous trg to
			// avoid looking up the DFA state again, which is expensive.
			// If the previous target was already part of the DFA, we might
			// be able to avoid doing a reach operation upon t. If s!=null,
			// it means that semantic predicates didn't prevent us from
			// creating a DFA state. Once we know s!=null, we check to see if
			// the DFA state has an edge already for t. If so, we can just reuse
			// it's configuration set; there's no point in re-computing it.
			// This is kind of like doing DFA simulation within the ATN
			// simulation because DFA simulation is really just a way to avoid
			// computing reach/closure sets. Technically, once we know that
			// we have a previously added DFA state, we could jump over to
			// the DFA simulator. But, that would mean popping back and forth
			// a lot and making things more complicated algorithmically.
			// This optimization makes a lot of sense for loops within DFA.
			// A character will take us back to an existing DFA state
			// that already has lots of edges out of it. e.g., .* in comments.
			DFAState target = getExistingTargetState(s, t);
			if (target == null) {
				target = computeTargetState(input, s, t);
			}

			if (target == ERROR) {
				break;
			}

			// If this is a consumable input element, make sure to consume before
			// capturing the accept state so the input index, line, and char
			// position accurately reflect the state of the interpreter at the
			// end of the token.
			if (t != IntStream.EOF) {
				consume(input, t);
			}

			if (target.isAcceptState()) {
				captureSimState(prevAccept, input, target);
				if (t == IntStream.EOF) {
					break;
				}
			}

			t = input.LA(1);
			s = target; // flip; current DFA target becomes new src/from state
		}

		return failOrAccept(prevAccept, input, s.configs, t);
	}

	/**
	 * Get an existing target state for an edge in the DFA. If the target state
	 * for the edge has not yet been computed or is otherwise not available,
	 * this method returns {@code null}.
	 *
	 * @param s The current DFA state
	 * @param t The next input symbol
	 * @return The existing target DFA state for the given input symbol
	 * {@code t}, or {@code null} if the target state for this edge is not
	 * already cached
	 */
	@Nullable
	protected DFAState getExistingTargetState(@NotNull DFAState s, int t) {
		DFAState target = s.getLexerTarget(t);
		if (debug && target != null) {
			System.out.println("reuse state "+s.stateNumber+
							   " edge to "+target.stateNumber);
		}

		return target;
	}

	/**
	 * Consume {@code curChar} and update the line and column.
	 *
	 * <p>{@code curChar} is already {@link CharStream#LA(int) LA(1)} and has
	 * not been consumed. This method does not call {@code LA(1)}. It calls
	 * {@code LA(2)} only when {@code curChar} is a carriage return, to tell a
	 * bare CR from the CR of a CR/LF pair. CR at {@link IntStream#EOF} is a
	 * bare CR, because {@code LA(2)} is not a line feed. Line endings match
	 * {@link #consume(CharStream)}.</p>
	 *
	 * @param input character stream positioned at {@code curChar}
	 * @param curChar the code point just matched; must not be {@link IntStream#EOF}
	 */
	final void consume(@NotNull CharStream input, int curChar) {
		if ( curChar=='\n' || (curChar=='\r' && input.LA(2)!='\n') ) {
			line++;
			charPositionInLine=0;
		} else {
			charPositionInLine++;
		}
		input.consume();
	}

	/**
	 * Compute a target state for an edge in the DFA, and attempt to add the
	 * computed state and corresponding edge to the DFA.
	 *
	 * @param input The input stream
	 * @param s The current DFA state
	 * @param t The next input symbol
	 *
	 * @return The computed target DFA state for the given input symbol
	 * {@code t}. If {@code t} does not lead to a valid DFA state, this method
	 * returns {@link #ERROR}.
	 */
	@NotNull
	protected DFAState computeTargetState(@NotNull CharStream input, @NotNull DFAState s, int t) {
		// obtain() always empty; release() after addDFAState clones into the DFA.
		ATNConfigSet reach = obtainRetainedReach(s.configs.size());
		try {
			// if we don't find an existing DFA state
			// Fill reach starting from closure, following t transitions
			getReachableConfigSet(input, s.configs, reach, t);

			if ( reach.isEmpty() ) { // we got nowhere on t from s
				if (!reach.hasSemanticContext()) {
					// we got nowhere on t, don't throw out this knowledge; it'd
					// cause a failover from DFA later.
					addDFAEdge(s, t, ERROR);
				}

				// stop when we can't match any more char
				return ERROR;
			}

			// Add an edge from s to target DFA found/created for reach
			return addDFAEdge(s, t, reach);
		}
		finally {
			retainedReach.release();
		}
	}

	/**
	 * Underlying retained reach buffer after first {@link #computeTargetState},
	 * else {@code null}. Same-package tests may observe identity and emptiness.
	 */
	@Nullable
	final ATNConfigSet retainedReachBuffer() {
		return retainedReach != null ? retainedReach.buffer() : null;
	}

	protected int failOrAccept(SimState prevAccept, CharStream input,
							   ATNConfigSet reach, int t)
	{
		if (prevAccept.dfaState != null) {
			LexerActionExecutor lexerActionExecutor = prevAccept.dfaState.getLexerActionExecutor();
			accept(input, lexerActionExecutor, startIndex,
				prevAccept.index, prevAccept.line, prevAccept.charPos);
			return prevAccept.dfaState.getPrediction();
		}
		else {
			// if no accept and EOF is first char, return EOF
			if ( t==IntStream.EOF && input.index()==startIndex ) {
				return Token.EOF;
			}

			throw new LexerNoViableAltException(recog, input, startIndex, reach);
		}
	}

	/** Given a starting configuration set, figure out all ATN configurations
	 *  we can reach upon input {@code t}. Parameter {@code reach} is a return
	 *  parameter.
	 */
	protected void getReachableConfigSet(@NotNull CharStream input, @NotNull ATNConfigSet closure, @NotNull ATNConfigSet reach, int t) {
		// this is used to skip processing for configs which have a lower priority
		// than a config that already reached an accept state for the same rule
		int skipAlt = ATN.INVALID_ALT_NUMBER;
		// Index walk avoids Iterator allocation on the (often retained) closure set.
		for (int ci = 0, cn = closure.size(); ci < cn; ci++) {
			ATNConfig c = closure.get(ci);
			boolean currentAltReachedAcceptState = c.getAlt() == skipAlt;
			if (currentAltReachedAcceptState && c.hasPassedThroughNonGreedyDecision()) {
				continue;
			}

			/*if ( debug ) {
				System.out.format(Locale.getDefault(), "testing %s at %s\n", getTokenName(t), c.toString(recog, true));
			}*/

			final ATNState state = c.getState();
			final Transition[] frozen = state.frozenOptimizedTransitions();
			final int n = frozen != null ? frozen.length : state.getNumberOfOptimizedTransitions();
			for (int ti = 0; ti < n; ti++) {               // for each optimized transition
				Transition trans = frozen != null ? frozen[ti] : state.getOptimizedTransition(ti);
				if (useFastExec && trans.epsilon) {
					continue;
				}
				ATNState target = getReachableTarget(trans, t);
				if ( target!=null ) {
					LexerActionExecutor lexerActionExecutor = c.getLexerActionExecutor();
					if (lexerActionExecutor != null) {
						LexerActionExecutor fixed = lexerActionExecutor.fixOffsetBeforeMatch(input.index() - startIndex);
						if (fixed != lexerActionExecutor) {
							predRecordAbort = true;
							lexerActionExecutor = fixed;
						}
					}

					boolean treatEofAsEpsilon = t == CharStream.EOF;
					if (closure(input, c.transform(target, lexerActionExecutor, true), reach, currentAltReachedAcceptState, true, treatEofAsEpsilon)) {
						// any remaining configs for this alt have a lower priority than
						// the one that just reached an accept state.
						skipAlt = c.getAlt();
						break;
					}
				}
			}
		}
	}

	protected void accept(@NotNull CharStream input, LexerActionExecutor lexerActionExecutor,
						  int startIndex, int index, int line, int charPos)
	{
		/*if ( debug ) {
			System.out.format(Locale.getDefault(), "ACTION %s\n", lexerActionExecutor);
		}*/

		// seek to after last char in token
		input.seek(index);
		this.line = line;
		this.charPositionInLine = charPos;

		if (lexerActionExecutor != null && recog != null) {
			lexerActionExecutor.execute(recog, input, startIndex);
		}
	}

	@Nullable
	protected ATNState getReachableTarget(Transition trans, int t) {
		if (trans.matches(t, Lexer.MIN_CHAR_VALUE, Lexer.MAX_CHAR_VALUE)) {
			return trans.target;
		}

		return null;
	}

	@NotNull
	protected ATNConfigSet computeStartState(@NotNull CharStream input,
											 @NotNull ATNState p)
	{
		PredictionContext initialContext = PredictionContext.EMPTY_FULL;
		// Capacity hint from the mode start state's fan-out (shared scratch policy).
		int transitionCount = p.getNumberOfTransitions();
		ATNConfigSet configs = new OrderedATNConfigSet(ATNConfigSet.scratchCapacity(transitionCount));
		for (int i=0; i<transitionCount; i++) {
			ATNState target = p.transition(i).target;
			ATNConfig c = ATNConfig.create(target, i+1, initialContext);
			closure(input, c, configs, false, false, false);
		}
		return configs;
	}

	/**
	 * Since the alternatives within any lexer decision are ordered by
	 * preference, this method stops pursuing the closure as soon as an accept
	 * state is reached. After the first accept state is reached by depth-first
	 * search from {@code config}, all other (potentially reachable) states for
	 * this rule would have a lower priority.
	 *
	 * @return {@code true} if an accept state is reached, otherwise
	 * {@code false}.
	 */
	protected boolean closure(@NotNull CharStream input, @NotNull ATNConfig config, @NotNull ATNConfigSet configs, boolean currentAltReachedAcceptState, boolean speculative, boolean treatEofAsEpsilon) {
		boolean root = closureDepth++ == 0;
		if (root && closureBusy != null && !closureBusy.isEmpty()) {
			closureBusy.clear();
		}
		try {
		// Hash only after a normal decision would have finished, so a DFA-miss
		// closure does not probe the map on every frame. A same-config cycle
		// (EOF* or an epsilon loop) stops here instead of walking one frame
		// per ATN state and overflowing the stack.
		if (closureDepth > CLOSURE_BUSY_DEPTH && reuseClosure(config, currentAltReachedAcceptState)) {
			return currentAltReachedAcceptState;
		}

		/*if ( debug ) {
			System.out.println("closure("+config.toString(recog, true)+")");
		}*/

		final ATNState configState = config.getState();
		if ( configState.stateType == ATNState.RULE_STOP ) {
			/*if ( debug ) {
				if ( recog!=null ) {
					System.out.format(Locale.getDefault(), "closure at %s rule stop %s\n", recog.getRuleNames()[configState.ruleIndex], config);
				}
				else {
					System.out.format(Locale.getDefault(), "closure at rule stop %s\n", config);
				}
			}*/

			PredictionContext context = config.getContext();
			if ( context.isEmpty() ) {
				configs.add(config);
				return true;
			}
			else if ( context.hasEmpty() ) {
				configs.add(config.transform(configState, PredictionContext.EMPTY_FULL, true));
				currentAltReachedAcceptState = true;
			}

			for (int i = 0, n = context.size(); i < n; i++) {
				int returnStateNumber = context.getReturnState(i);
				if (returnStateNumber == PredictionContext.EMPTY_FULL_STATE_KEY) {
					continue;
				}

				PredictionContext newContext = context.getParent(i); // "pop" return state
				ATNState returnState = atn.getCachedState(returnStateNumber);
				ATNConfig c = config.transform(returnState, newContext, false);
				currentAltReachedAcceptState = closure(input, c, configs, currentAltReachedAcceptState, speculative, treatEofAsEpsilon);
			}

			return currentAltReachedAcceptState;
		}

		// optimization
		if ( !configState.onlyHasEpsilonTransitions() )	{
			if (!currentAltReachedAcceptState || !config.hasPassedThroughNonGreedyDecision()) {
				configs.add(config);
			}
		}

		final Transition[] frozen = configState.frozenOptimizedTransitions();
		final int n = frozen != null ? frozen.length : configState.getNumberOfOptimizedTransitions();
		for (int i = 0; i < n; i++) {
			Transition t = frozen != null ? frozen[i] : configState.getOptimizedTransition(i);
			// PERF: Non-epsilon edges never produce a closure target unless EOF
			// is being treated as epsilon (atom/range/set that matches EOF).
			if (!treatEofAsEpsilon && !t.epsilon) {
				continue;
			}
			ATNConfig c = getEpsilonTarget(input, config, t, configs, speculative, treatEofAsEpsilon);
			if ( c!=null ) {
				currentAltReachedAcceptState = closure(input, c, configs, currentAltReachedAcceptState, speculative, treatEofAsEpsilon);
			}
		}

		return currentAltReachedAcceptState;
		}
		finally {
			closureDepth--;
		}
	}

	/**
	 * @return {@code true} when {@code config} was already closed under an
	 * equally or more permissive accept flag
	 */
	private boolean reuseClosure(@NotNull ATNConfig config, boolean currentAltReachedAcceptState) {
		if (closureBusy == null) {
			closureBusy = new HashMap<ATNConfig, Boolean>();
		}
		Boolean previous = closureBusy.get(config);
		if (previous == null) {
			closureBusy.put(config, Boolean.valueOf(currentAltReachedAcceptState));
			return false;
		}
		if (!previous.booleanValue()) {
			return true;
		}
		if (!currentAltReachedAcceptState) {
			closureBusy.put(config, Boolean.FALSE);
			return false;
		}
		return true;
	}

	// side-effect: can alter configs.hasSemanticContext
	@Nullable
	protected ATNConfig getEpsilonTarget(@NotNull CharStream input,
									  @NotNull ATNConfig config,
									  @NotNull Transition t,
									  @NotNull ATNConfigSet configs,
									  boolean speculative,
									  boolean treatEofAsEpsilon)
	{
		// PERF: Ordered with the most common epsilon edge types first
		// (EPSILON, RULE, ACTION) so the interpreter/JIT keeps those paths hot.
		switch (t.serializationType) {
		case Transition.EPSILON:
			return config.transform(t.target, true);

		case Transition.RULE:
			RuleTransition ruleTransition = (RuleTransition)t;
			if (optimize_tail_calls && ruleTransition.optimizedTailCall && !config.getContext().hasEmpty()) {
				return config.transform(t.target, true);
			}
			PredictionContext newContext = config.getContext().getChild(ruleTransition.followState.stateNumber);
			return config.transform(t.target, newContext, true);

		case Transition.ACTION:
			if (config.getContext().hasEmpty()) {
				// execute actions anywhere in the start rule for a token.
				//
				// TODO: if the entry rule is invoked recursively, some
				// actions may be executed during the recursive call. The
				// problem can appear when hasEmpty() is true but
				// isEmpty() is false. In this case, the config needs to be
				// split into two contexts - one with just the empty path
				// and another with everything but the empty path.
				// Unfortunately, the current algorithm does not allow
				// getEpsilonTarget to return two configurations, so
				// additional modifications are needed before we can support
				// the split operation.
				LexerActionExecutor lexerActionExecutor = LexerActionExecutor.append(config.getLexerActionExecutor(), atn.lexerActions[((ActionTransition)t).actionIndex]);
				return config.transform(t.target, lexerActionExecutor, true);
			}
			// ignore actions in referenced rules
			return config.transform(t.target, true);

		case Transition.PREDICATE:
			PredicateTransition pt = (PredicateTransition)t;
			/*if ( debug ) {
				System.out.println("EVAL rule "+pt.ruleIndex+":"+pt.predIndex);
			}*/
			configs.markExplicitSemanticContext();
			boolean predResult;
			if (predCaptureActive) {
				if (predStep < predReplayLen && !predRecordAbort) {
					if (predRuleTrace[predStep] == pt.ruleIndex && predIndexTrace[predStep] == pt.predIndex) {
						predResult = predResultTrace[predStep++];
					}
					else {
						predRecordAbort = true;
						predResult = evaluatePredicate(input, pt.ruleIndex, pt.predIndex, speculative);
					}
				}
				else {
					predResult = evaluatePredicate(input, pt.ruleIndex, pt.predIndex, speculative);
					if (!predRecordAbort) {
						if (predStep < LexerPredEdgeTable.MAX_PRED_DEPTH) {
							predRuleTrace[predStep] = pt.ruleIndex;
							predIndexTrace[predStep] = pt.predIndex;
							predResultTrace[predStep] = predResult;
							predStep++;
						}
						else {
							predRecordAbort = true;
						}
					}
				}
			}
			else {
				predResult = evaluatePredicate(input, pt.ruleIndex, pt.predIndex, speculative);
			}
			if (predResult) {
				return config.transform(t.target, true);
			}
			return null;

		case Transition.ATOM:
		case Transition.RANGE:
		case Transition.SET:
			if (treatEofAsEpsilon) {
				if (t.matches(CharStream.EOF, Lexer.MIN_CHAR_VALUE, Lexer.MAX_CHAR_VALUE)) {
					return config.transform(t.target, false);
				}
			}
			return null;

		case Transition.PRECEDENCE:
			throw new UnsupportedOperationException("Precedence predicates are not supported in lexers.");

		default:
			return null;
		}
	}

	/**
	 * Evaluate a predicate specified in the lexer.
	 *
	 * <p>If {@code speculative} is {@code true}, this method was called before
	 * {@link #consume} for the matched character. This method should call
	 * {@link #consume} before evaluating the predicate to ensure position
	 * sensitive values, including {@link Lexer#getText}, {@link Lexer#getLine},
	 * and {@link Lexer#getCharPositionInLine}, properly reflect the current
	 * lexer state. This method should restore {@code input} and the simulator
	 * to the original state before returning (i.e. undo the actions made by the
	 * call to {@link #consume}.</p>
	 *
	 * @param input The input stream.
	 * @param ruleIndex The rule containing the predicate.
	 * @param predIndex The index of the predicate within the rule.
	 * @param speculative {@code true} if the current index in {@code input} is
	 * one character before the predicate's location.
	 *
	 * @return {@code true} if the specified predicate evaluates to
	 * {@code true}.
	 */
	protected boolean evaluatePredicate(@NotNull CharStream input, int ruleIndex, int predIndex, boolean speculative) {
		// assume true if no recognizer was provided
		if (recog == null) {
			return true;
		}

		if (!speculative) {
			return recog.sempred(null, ruleIndex, predIndex);
		}

		int savedCharPositionInLine = charPositionInLine;
		int savedLine = line;
		int index = input.index();
		int marker = input.mark();
		try {
			consume(input);
			return recog.sempred(null, ruleIndex, predIndex);
		}
		finally {
			charPositionInLine = savedCharPositionInLine;
			line = savedLine;
			input.seek(index);
			input.release(marker);
		}
	}

	protected void captureSimState(@NotNull SimState settings,
								   @NotNull CharStream input,
								   @NotNull DFAState dfaState)
	{
		settings.index = input.index();
		settings.line = line;
		settings.charPos = charPositionInLine;
		settings.dfaState = dfaState;
	}

	@NotNull
	protected DFAState addDFAEdge(@NotNull DFAState from,
								  int t,
								  @NotNull ATNConfigSet q)
	{
		/* leading to this call, ATNConfigSet.hasSemanticContext is used as a
		 * marker indicating dynamic predicate evaluation makes this edge
		 * dependent on the specific input sequence, so the static edge in the
		 * DFA should be omitted. The target DFAState is still created since
		 * execATN has the ability to resynchronize with the DFA state cache
		 * following the predicate evaluation step.
		 *
		 * TJP notes: next time through the DFA, we see a pred again and eval.
		 * If that gets us to a previously created (but dangling) DFA
		 * state, we can continue in pure DFA mode from there.
		 */
		boolean suppressEdge = q.hasSemanticContext();
		if (suppressEdge) {
			q.clearExplicitSemanticContext();
		}

		@NotNull
		DFAState to = addDFAState(q);

		if (suppressEdge) {
			return to;
		}

		addDFAEdge(from, t, to);
		return to;
	}

	protected void addDFAEdge(@NotNull DFAState p, int t, @NotNull DFAState q) {
		/*if ( debug ) {
			System.out.println("EDGE "+p+" -> "+q+" upon "+((char)t));
		}*/

		if ( p!=null ) {
			p.setTarget(t, q);
		}
	}

	/** Add a new DFA state if there isn't one with this set of
		configurations already. This method also detects the first
		configuration containing an ATN rule stop state. Later, when
		traversing the DFA, we will know which rule to accept.
	 */
	@NotNull
	protected DFAState addDFAState(@NotNull ATNConfigSet configs) {
		/* the lexer evaluates predicates on-the-fly; by this point configs
		 * should not contain any configurations with unevaluated predicates.
		 */
		assert !configs.hasSemanticContext();

		DFAState proposed = new DFAState(atn.modeToDFA[mode], configs);
		DFAState existing = atn.modeToDFA[mode].states.get(proposed);
		if ( existing!=null ) return existing;

		configs.optimizeConfigs(this);
		DFAState newState = new DFAState(atn.modeToDFA[mode], configs.clone(true));

		ATNConfig firstConfigWithRuleStopState = null;
		for (int i = 0, n = configs.size(); i < n; i++) {
			ATNConfig c = configs.get(i);
			if ( c.getState().getStateType() == ATNState.RULE_STOP )	{
				firstConfigWithRuleStopState = c;
				break;
			}
		}

		if ( firstConfigWithRuleStopState!=null ) {
			int prediction = atn.ruleToTokenType[firstConfigWithRuleStopState.getState().ruleIndex];
			LexerActionExecutor lexerActionExecutor = firstConfigWithRuleStopState.getLexerActionExecutor();
			newState.setAcceptState(new AcceptStateInfo(prediction, lexerActionExecutor));
		}

		return atn.modeToDFA[mode].addState(newState);
	}

	@NotNull
	public final DFA getDFA(int mode) {
		return atn.modeToDFA[mode];
	}

	/** Get the text matched so far for the current token.
	 */
	@NotNull
	public String getText(@NotNull CharStream input) {
		// index is first lookahead char, don't include.
		if (input instanceof CodePointCharStream) {
			return ((CodePointCharStream) input).getText(startIndex, input.index() - 1);
		}
		return input.getText(Interval.of(startIndex, input.index()-1));
	}

	public int getLine() {
		return line;
	}

	public void setLine(int line) {
		this.line = line;
	}

	public int getCharPositionInLine() {
		return charPositionInLine;
	}

	public void setCharPositionInLine(int charPositionInLine) {
		this.charPositionInLine = charPositionInLine;
	}

	/**
	 * Consume the current input code point and update the line and column.
	 *
	 * <p>A line feed, and a carriage return that is not followed by a line
	 * feed, increment the line and set the column to 0. A carriage return at
	 * end of input is that bare carriage return. The carriage return of a
	 * CR/LF pair only increments the column; the following line feed is the
	 * one line break. Any other code point only increments the column.</p>
	 *
	 * <p>The per-character DFA walk calls {@link #consume(CharStream, int)}
	 * and passes the code point it already loaded. That overload does not
	 * call {@code LA(1)} again. It calls {@code LA(2)} only for a carriage
	 * return.</p>
	 */
	public void consume(@NotNull CharStream input) {
		consume(input, input.LA(1));
	}

	@NotNull
	public String getTokenName(int t) {
		if ( t==-1 ) return "EOF";
		//if ( atn.g!=null ) return atn.g.getTokenDisplayName(t);
		return "'"+(char)t+"'";
	}
}
