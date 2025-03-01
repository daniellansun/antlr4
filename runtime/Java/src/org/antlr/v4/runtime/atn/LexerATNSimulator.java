/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CharStream;
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

import java.util.Locale;

/** "dup" of ParserInterpreter */
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

	/** @deprecated This field is no longer used. */
	@Deprecated
	public static int match_calls = 0;

	public LexerATNSimulator(@NotNull ATN atn) {
		this(null, atn);
	}

	public LexerATNSimulator(@Nullable Lexer recog, @NotNull ATN atn) {
		super(atn);
		this.recog = recog;
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

	@Override
	public void reset() {
		prevAccept.reset();
		startIndex = -1;
		line = 1;
		charPositionInLine = 0;
		mode = Lexer.DEFAULT_MODE;
	}

	protected int matchATN(@NotNull CharStream input) {
		ATNState startState = atn.modeToStartState.get(mode);

		if ( debug ) {
			System.out.format(Locale.getDefault(), "matchATN mode %d start: %s\n", mode, startState);
		}

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

		if ( debug ) {
			System.out.format(Locale.getDefault(), "DFA after matchATN: %s\n", atn.modeToDFA[old_mode].toLexerString());
		}

		return predict;
	}

	protected int execATN(@NotNull CharStream input, @NotNull DFAState ds0) {
		//System.out.println("enter exec index "+input.index()+" from "+ds0.configs);
		if ( debug ) {
			System.out.format(Locale.getDefault(), "start state closure=%s\n", ds0.configs);
		}

		if (ds0.isAcceptState()) {
			// allow zero-length tokens
			captureSimState(prevAccept, input, ds0);
		}

		int t = input.LA(1);
		@NotNull
		DFAState s = ds0; // s is current/from DFA state

		while ( true ) { // while more work
			if ( debug ) {
				System.out.format(Locale.getDefault(), "execATN loop starting closure: %s\n", s.configs);
			}

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
				consume(input);
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
		DFAState target = s.getTarget(t);
		if (debug && target != null) {
			System.out.println("reuse state "+s.stateNumber+
							   " edge to "+target.stateNumber);
		}

		return target;
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
		ATNConfigSet reach = new OrderedATNConfigSet();

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
		for (ATNConfig c : closure) {
			boolean currentAltReachedAcceptState = c.getAlt() == skipAlt;
			if (currentAltReachedAcceptState && c.hasPassedThroughNonGreedyDecision()) {
				continue;
			}

			if ( debug ) {
				System.out.format(Locale.getDefault(), "testing %s at %s\n", getTokenName(t), c.toString(recog, true));
			}

			final ATNState state = c.getState();
			for (int ti = 0, n = state.getNumberOfOptimizedTransitions(); ti < n; ti++) {               // for each optimized transition
				Transition trans = state.getOptimizedTransition(ti);
				ATNState target = getReachableTarget(trans, t);
				if ( target!=null ) {
					LexerActionExecutor lexerActionExecutor = c.getLexerActionExecutor();
					if (lexerActionExecutor != null) {
						lexerActionExecutor = lexerActionExecutor.fixOffsetBeforeMatch(input.index() - startIndex);
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
		if ( debug ) {
			System.out.format(Locale.getDefault(), "ACTION %s\n", lexerActionExecutor);
		}

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
		ATNConfigSet configs = new OrderedATNConfigSet();
		for (int i=0; i<p.getNumberOfTransitions(); i++) {
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
		if ( debug ) {
			System.out.println("closure("+config.toString(recog, true)+")");
		}

		final ATNState configState = config.getState();
		if ( configState instanceof RuleStopState ) {
			if ( debug ) {
				if ( recog!=null ) {
					System.out.format(Locale.getDefault(), "closure at %s rule stop %s\n", recog.getRuleNames()[configState.ruleIndex], config);
				}
				else {
					System.out.format(Locale.getDefault(), "closure at rule stop %s\n", config);
				}
			}

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
				ATNState returnState = atn.states.get(returnStateNumber);
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

        for (int i = 0, n = configState.getNumberOfOptimizedTransitions(); i < n; i++) {
			Transition t = configState.getOptimizedTransition(i);
			ATNConfig c = getEpsilonTarget(input, config, t, configs, speculative, treatEofAsEpsilon);
			if ( c!=null ) {
				currentAltReachedAcceptState = closure(input, c, configs, currentAltReachedAcceptState, speculative, treatEofAsEpsilon);
			}
		}

		return currentAltReachedAcceptState;
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
		ATNConfig c;

		switch (t.getSerializationType()) {
		case Transition.RULE:
			RuleTransition ruleTransition = (RuleTransition)t;
			if (optimize_tail_calls && ruleTransition.optimizedTailCall && !config.getContext().hasEmpty()) {
				c = config.transform(t.target, true);
			}
			else {
				PredictionContext newContext = config.getContext().getChild(ruleTransition.followState.stateNumber);
				c = config.transform(t.target, newContext, true);
			}

			break;

		case Transition.PRECEDENCE:
			throw new UnsupportedOperationException("Precedence predicates are not supported in lexers.");

		case Transition.PREDICATE:
			/*  Track traversing semantic predicates. If we traverse,
			    we cannot add a DFA state for this "reach" computation
				because the DFA would not test the predicate again in the
				future. Rather than creating collections of semantic predicates
				like v3 and testing them on prediction, v4 will test them on the
				fly all the time using the ATN not the DFA. This is slower but
				semantically it's not used that often. One of the key elements to
				this predicate mechanism is not adding DFA states that see
				predicates immediately afterwards in the ATN. For example,

				a : ID {p1}? | ID {p2}? ;

				should create the start state for rule 'a' (to save start state
				competition), but should not create target of ID state. The
				collection of ATN states the following ID references includes
				states reached by traversing predicates. Since this is when we
				test them, we cannot cash the DFA state target of ID.
			*/
			PredicateTransition pt = (PredicateTransition)t;
			if ( debug ) {
				System.out.println("EVAL rule "+pt.ruleIndex+":"+pt.predIndex);
			}
			configs.markExplicitSemanticContext();
			if (evaluatePredicate(input, pt.ruleIndex, pt.predIndex, speculative)) {
				c = config.transform(t.target, true);
			}
			else {
				c = null;
			}

			break;

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
				c = config.transform(t.target, lexerActionExecutor, true);
				break;
			}
			else {
				// ignore actions in referenced rules
				c = config.transform(t.target, true);
				break;
			}

		case Transition.EPSILON:
			c = config.transform(t.target, true);
			break;

		case Transition.ATOM:
		case Transition.RANGE:
		case Transition.SET:
			if (treatEofAsEpsilon) {
				if (t.matches(CharStream.EOF, Lexer.MIN_CHAR_VALUE, Lexer.MAX_CHAR_VALUE)) {
					c = config.transform(t.target, false);
					break;
				}
			}

			c = null;
			break;

		default:
			c = null;
			break;
		}

		return c;
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
		if ( debug ) {
			System.out.println("EDGE "+p+" -> "+q+" upon "+((char)t));
		}

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
		for (ATNConfig c : configs) {
			if ( c.getState() instanceof RuleStopState )	{
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

	public void consume(@NotNull CharStream input) {
		int curChar = input.LA(1);
		if ( curChar=='\n' ) {
			line++;
			charPositionInLine=0;
		}
		else {
			charPositionInLine++;
		}
		input.consume();
	}

	@NotNull
	public String getTokenName(int t) {
		if ( t==-1 ) return "EOF";
		//if ( atn.g!=null ) return atn.g.getTokenDisplayName(t);
		return "'"+(char)t+"'";
	}
}
