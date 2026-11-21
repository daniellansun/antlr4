/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CharStream;
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
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ATN interpreter for lexers. Builds and consults a per-mode DFA cache while
 * falling back to ATN simulation for unseen edges, retaining an occupancy-aware
 * {@link RetainedConfigSet} reach buffer between {@link #computeTargetState}
 * calls (stored DFA states always receive a readonly clone).
 */
public class LexerATNSimulator extends ATNSimulator {

	public static final boolean debug = false;
	public static final boolean dfa_debug = false;

	public static final int MIN_DFA_EDGE = 0;
	public static final int MAX_DFA_EDGE = Character.MAX_CODE_POINT;

	public boolean optimize_tail_calls = true;

	/** Tracks the most recent accept coordinates (index, line, column, DFA state). */
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
	 * Per-simulator predicate trace buffers for {@link LexerPredEdgeTable}
	 * replay and recording, allocated lazily on the first predicated DFA miss.
	 */
	@Nullable
	private LexerPredEdgeTable.PredTrace predTrace;

	/**
	 * Whether this concrete simulator class is eligible for the fast
	 * {@link CodePointCharStream} DFA runner and {@link LexerPredEdgeTable}
	 * predicate-edge cache.
	 */
	private final boolean useFastExec;

	/**
	 * All non-private, non-final, non-static methods declared on
	 * {@link LexerATNSimulator} and {@link ATNSimulator} (excluding
	 * {@link #fastDfaEligibleClass()}). Discovered dynamically from class
	 * metadata rather than a hardcoded name list so new protected hooks are
	 * never silently bypassed and private/overloaded helpers never collide.
	 * {@code null} if restricted by a {@link SecurityManager}.
	 */
	@Nullable
	private static final Method[] OVERRIDABLE_HOOKS = collectOverridableHooks();

	/**
	 * Cached structural override check for legacy helper-only subclasses (such
	 * as Apache Groovy's and ANTLR4's {@code PositionAdjustingLexerATNSimulator})
	 * that inherit {@link #fastDfaEligibleClass()} without overriding any hook.
	 */
	private static final ClassValue<Boolean> NO_HOOK_OVERRIDES = new ClassValue<Boolean>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			if (type == LexerATNSimulator.class) return Boolean.TRUE;
			Method[] hooks = OVERRIDABLE_HOOKS;
			if (hooks == null) return Boolean.FALSE;
			try {
				ClassLoader baseLoader = LexerATNSimulator.class.getClassLoader();
				String basePkg = packageName(LexerATNSimulator.class);
				for (Class<?> c = type; c != null && c != LexerATNSimulator.class; c = c.getSuperclass()) {
					boolean samePkg = c.getClassLoader() == baseLoader && basePkg.equals(packageName(c));
					for (Method hook : hooks) {
						int hm = hook.getModifiers();
						if (!Modifier.isPublic(hm) && !Modifier.isProtected(hm) && !samePkg) continue;
						try {
							Method sub = c.getDeclaredMethod(hook.getName(), hook.getParameterTypes());
							if (!Modifier.isPrivate(sub.getModifiers()) && !Modifier.isStatic(sub.getModifiers())) {
								return Boolean.FALSE;
							}
						}
						catch (NoSuchMethodException ignored) { }
					}
				}
				return Boolean.TRUE;
			}
			catch (SecurityException | LinkageError ex) {
				return Boolean.FALSE;
			}
		}
	};

	@Nullable
	private static Method[] collectOverridableHooks() {
		try {
			List<Method> hooks = new ArrayList<Method>();
			for (Class<?> owner : new Class<?>[] { LexerATNSimulator.class, ATNSimulator.class }) {
				for (Method m : owner.getDeclaredMethods()) {
					int mods = m.getModifiers();
					if (Modifier.isPrivate(mods) || Modifier.isStatic(mods) || Modifier.isFinal(mods) || m.isSynthetic()) continue;
					if ("fastDfaEligibleClass".equals(m.getName()) && m.getParameterTypes().length == 0) continue;
					hooks.add(m);
				}
			}
			return hooks.toArray(new Method[hooks.size()]);
		}
		catch (SecurityException ex) {
			return null;
		}
	}

	private static String packageName(Class<?> c) {
		String name = c.getName();
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(0, dot) : "";
	}

	/**
	 * Single-entry lock-free cache of the most recently verified helper-only
	 * {@link LexerATNSimulator} subclass that overrides no hooks. Avoids
	 * per-file {@link ClassValue#get} lookups when constructing subclasses such
	 * as Groovy's {@code PositionAdjustingLexerATNSimulator}.
	 */
	private static volatile Class<?> lastFastEligibleClass;

	private static boolean checkNoHookOverrides(Class<? extends LexerATNSimulator> actual) {
		if (NO_HOOK_OVERRIDES.get(actual)) {
			lastFastEligibleClass = actual;
			return true;
		}
		return false;
	}

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
		Class<? extends LexerATNSimulator> actual = getClass();
		Class<? extends LexerATNSimulator> eligible = fastDfaEligibleClass();
		this.useFastExec = (actual == eligible)
			|| (eligible == LexerATNSimulator.class
				&& (actual == lastFastEligibleClass || checkNoHookOverrides(actual)));
	}

	/**
	 * Returns the exact concrete {@link LexerATNSimulator} class eligible for
	 * the fast {@link CodePointCharStream} DFA runner. Defaults to
	 * {@code LexerATNSimulator.class}. Helper-only subclasses that override no
	 * {@link LexerATNSimulator} or {@link ATNSimulator} methods remain eligible
	 * automatically; subclasses may also override this to return their own
	 * class literal to opt in explicitly without reflective inspection, or
	 * return {@code null} to opt out explicitly. Implementations must not read
	 * subclass instance fields because this method is invoked during superclass
	 * construction.
	 *
	 * @since 4.13.2.20
	 */
	@Nullable
	protected Class<? extends LexerATNSimulator> fastDfaEligibleClass() {
		return LexerATNSimulator.class;
	}

	/**
	 * Returns whether this simulator instance is eligible for the fast
	 * {@link CodePointCharStream} DFA runner.
	 *
	 * @since 4.13.2.20
	 */
	public final boolean isFastDFAEligible() {
		return useFastExec;
	}

	/**
	 * Synchronizes simulator mode, token start index, line, and column from a
	 * register-resident fast DFA loop before action execution, skip fusion, or
	 * predicate resolution.
	 *
	 * @since 4.13.2.20
	 */
	public final void syncFastState(int mode, int startIndex, int line, int charPos) {
		this.mode = mode;
		this.startIndex = startIndex;
		this.line = line;
		this.charPositionInLine = charPos;
	}

	/**
	 * Resolves a missing lexer DFA edge on the fast path, consulting and
	 * populating {@link LexerPredEdgeTable} for predicate-suppressed ASCII
	 * transitions. Callers must flush stream position and call
	 * {@link #syncFastState} beforehand and reload stream/line/column afterward.
	 *
	 * @since 4.13.2.20
	 */
	@NotNull
	public final DFAState resolveFastTarget(@NotNull CharStream input,
											@NotNull DFAState ds0,
											@NotNull DFAState s,
											int t)
	{
		return LexerPredEdgeTable.resolveFast(this, input, ds0, s, t);
	}

	/**
	 * Handles EOF or throws {@link LexerNoViableAltException} when a fast DFA
	 * loop terminates without reaching an accept state.
	 *
	 * @since 4.13.2.20
	 */
	public final int handleFastNoViableAlt(@NotNull CharStream input,
										   int mode,
										   int startIndex,
										   @NotNull DFAState s,
										   int t,
										   int curLine,
										   int curCharPos)
	{
		this.mode = mode;
		this.startIndex = startIndex;
		this.line = curLine;
		this.charPositionInLine = curCharPos;
		if (t == IntStream.EOF && input.index() == startIndex) {
			return Token.EOF;
		}
		throw new LexerNoViableAltException(recog, input, startIndex, s.configs);
	}

	@NotNull
	LexerPredEdgeTable.PredTrace predTrace() {
		LexerPredEdgeTable.PredTrace pt = predTrace;
		if (pt == null) {
			pt = new LexerPredEdgeTable.PredTrace();
			predTrace = pt;
		}
		return pt;
	}

	public void copyState(@NotNull LexerATNSimulator simulator) {
		this.charPositionInLine = simulator.charPositionInLine;
		this.line = simulator.line;
		this.mode = simulator.mode;
		this.startIndex = simulator.startIndex;
	}

	public int match(@NotNull CharStream input, int mode) {
		this.mode = mode;
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

	@NotNull
	final ATNConfigSet obtainRetainedReach(int hint) {
		if (retainedReach == null) {
			retainedReach = new RetainedConfigSet(true);
		}
		return retainedReach.obtain(hint);
	}

	final void releaseRetainedReach() {
		retainedReach.release();
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

		return execATN(input, next);
	}

	protected int execATN(@NotNull CharStream input, @NotNull DFAState ds0) {
		if (ds0.isAcceptState()) {
			// allow zero-length tokens
			captureSimState(prevAccept, input, ds0);
		}

		int t = input.LA(1);
		@NotNull
		DFAState s = ds0; // s is current/from DFA state

		while ( true ) { // while more work
			// Follow cached DFA edges while available; compute and cache on miss.
			DFAState target = getExistingTargetState(s, t);
			if (target == null) {
				target = useFastExec ? resolveFastTarget(input, ds0, s, t) : computeTargetState(input, s, t);
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
							if (predTrace != null) {
								predTrace.recordAbort = true;
							}
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

		final ATNState configState = config.getState();
		if ( configState.stateType == ATNState.RULE_STOP ) {
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
			configs.markExplicitSemanticContext();
			boolean predResult;
			LexerPredEdgeTable.PredTrace trace = predTrace;
			if (trace != null && trace.captureActive) {
				predResult = trace.evaluateOrReplay(this, input, pt.ruleIndex, pt.predIndex, speculative);
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
