/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.ActionTransition;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.DecisionState;
import org.antlr.v4.runtime.atn.LoopEndState;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PrecedencePredicateTransition;
import org.antlr.v4.runtime.atn.PredicateTransition;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.StarLoopEntryState;
import org.antlr.v4.runtime.atn.Transition;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Tuple;
import org.antlr.v4.runtime.misc.Tuple2;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Collection;
import java.util.Deque;

/** A parser simulator that mimics what ANTLR's generated
 *  parser code does. A ParserATNSimulator is used to make
 *  predictions via adaptivePredict but this class moves a pointer through the
 *  ATN to simulate parsing. ParserATNSimulator just
 *  makes us efficient rather than having to backtrack, for example.
 *
 *  This properly creates parse trees even for left recursive rules.
 *
 *  We rely on the left recursive rule invocation and special predicate
 *  transitions to make left recursive rules work.
 *
 *  See TestParserInterpreter for examples.
 */
public class ParserInterpreter extends Parser {
	protected final String grammarFileName;
	protected final ATN atn;
	/** This identifies StarLoopEntryState's that begin the (...)*
	 *  precedence loops of left recursive rules.
	 */
	protected final BitSet pushRecursionContextStates;

	@Deprecated
	protected final String[] tokenNames;
	protected final String[] ruleNames;
	@NotNull
	private final Vocabulary vocabulary;

	/** This stack corresponds to the _parentctx, _parentState pair of locals
	 *  that would exist on call stack frames with a recursive descent parser;
	 *  in the generated function for a left-recursive rule you'd see:
	 *
	 *  private EContext e(int _p) throws RecognitionException {
	 *      ParserRuleContext _parentctx = _ctx;    // Pair.a
	 *      int _parentState = getState();          // Pair.b
	 *      ...
	 *  }
	 *
	 *  Those values are used to create new recursive rule invocation contexts
	 *  associated with left operand of an alt like "expr '*' expr".
	 */
	protected final Deque<Tuple2<ParserRuleContext, Integer>> _parentContextStack =
		new ArrayDeque<Tuple2<ParserRuleContext, Integer>>();

	/** We need a map from (decision,inputIndex)->forced alt for computing ambiguous
	 *  parse trees. For now, we allow exactly one override.
	 */
	protected int overrideDecision = -1;
	protected int overrideDecisionInputIndex = -1;
	protected int overrideDecisionAlt = -1;
	protected boolean overrideDecisionReached = false; // latch and only override once; error might trigger infinite loop

	/** What is the current context when we override a decisions?  This tells
	 *  us what the root of the parse tree is when using override
	 *  for an ambiguity/lookahead check.
	 */
	protected InterpreterRuleContext overrideDecisionRoot = null;


	protected InterpreterRuleContext rootContext;

	/** A copy constructor that creates a new parser interpreter by reusing
	 *  the fields of a previous interpreter.
	 *
	 *  @param old The interpreter to copy
	 *
	 *  @since 4.5
	 */
	public ParserInterpreter(@NotNull ParserInterpreter old) {
		super(old.getInputStream());
		this.grammarFileName = old.grammarFileName;
		this.atn = old.atn;
		this.pushRecursionContextStates = old.pushRecursionContextStates;
		this.tokenNames = old.tokenNames;
		this.ruleNames = old.ruleNames;
		this.vocabulary = old.vocabulary;
		setInterpreter(new ParserATNSimulator(this, atn));
	}

	/**
	 * @deprecated Use {@link #ParserInterpreter(String, Vocabulary, Collection, ATN, TokenStream)} instead.
	 */
	@Deprecated
	public ParserInterpreter(String grammarFileName, Collection<String> tokenNames,
							 Collection<String> ruleNames, ATN atn, TokenStream input) {
		this(grammarFileName, VocabularyImpl.fromTokenNames(tokenNames.toArray(new String[0])), ruleNames, atn, input);
	}

	public ParserInterpreter(String grammarFileName, @NotNull Vocabulary vocabulary,
							 Collection<String> ruleNames, ATN atn, TokenStream input)
	{
		super(input);
		this.grammarFileName = grammarFileName;
		this.atn = atn;
		this.tokenNames = new String[atn.maxTokenType];
		for (int i = 0; i < tokenNames.length; i++) {
			tokenNames[i] = vocabulary.getDisplayName(i);
		}

		this.ruleNames = ruleNames.toArray(new String[0]);
		this.vocabulary = vocabulary;

		// identify the ATN states where pushNewRecursionContext() must be called
		this.pushRecursionContextStates = new BitSet(atn.states.size());
		for (ATNState state : atn.states) {
			if (!(state instanceof StarLoopEntryState)) {
				continue;
			}

			if (((StarLoopEntryState)state).precedenceRuleDecision) {
				this.pushRecursionContextStates.set(state.stateNumber);
			}
		}

		// get atn simulator that knows how to do predictions
		setInterpreter(new ParserATNSimulator(this, atn));
	}

	@Override
	public void reset() {
		super.reset();
		overrideDecisionReached = false;
		overrideDecisionRoot = null;
	}

	@Override
	public ATN getATN() {
		return atn;
	}

	@Override
	@Deprecated
	public String[] getTokenNames() {
		return tokenNames;
	}

	@Override
	public Vocabulary getVocabulary() {
		return vocabulary;
	}

	@Override
	public String[] getRuleNames() {
		return ruleNames;
	}

	@Override
	public String getGrammarFileName() {
		return grammarFileName;
	}

	/** Begin parsing at startRuleIndex */
	public ParserRuleContext parse(int startRuleIndex) {
		RuleStartState startRuleStartState = atn.ruleToStartState[startRuleIndex];

		rootContext = createInterpreterRuleContext(null, ATNState.INVALID_STATE_NUMBER, startRuleIndex);
		if (startRuleStartState.isPrecedenceRule) {
			enterRecursionRule(rootContext, startRuleStartState.stateNumber, startRuleIndex, 0);
		}
		else {
			enterRule(rootContext, startRuleStartState.stateNumber, startRuleIndex);
		}

		while ( true ) {
			ATNState p = getATNState();
			switch ( p.getStateType() ) {
			case ATNState.RULE_STOP :
				// pop; return from rule
				if ( _ctx.isEmpty() ) {
					if (startRuleStartState.isPrecedenceRule) {
						ParserRuleContext result = _ctx;
						Tuple2<ParserRuleContext, Integer> parentContext = _parentContextStack.pop();
						unrollRecursionContexts(parentContext.getItem1());
						return result;
					}
					else {
						exitRule();
						return rootContext;
					}
				}

				visitRuleStopState(p);
				break;

			default :
				try {
					visitState(p);
				}
				catch (RecognitionException e) {
					setState(atn.ruleToStopState[p.ruleIndex].stateNumber);
					getContext().exception = e;
					getErrorHandler().reportError(this, e);
					recover(e);
				}

				break;
			}
		}
	}

	@Override
	public void enterRecursionRule(ParserRuleContext localctx, int state, int ruleIndex, int precedence) {
		_parentContextStack.push(Tuple.create(_ctx, localctx.invokingState));
		super.enterRecursionRule(localctx, state, ruleIndex, precedence);
	}

	protected ATNState getATNState() {
		return atn.states.get(getState());
	}

	protected void visitState(ATNState p) {
		int predictedAlt = 1;
		if (p.getNumberOfTransitions() > 1) {
			predictedAlt = visitDecisionState((DecisionState) p);
		}

		Transition transition = p.transition(predictedAlt - 1);
		switch (transition.getSerializationType()) {
		case Transition.EPSILON:
			if ( pushRecursionContextStates.get(p.stateNumber) &&
				 !(transition.target instanceof LoopEndState))
			{
				// We are at the start of a left recursive rule's (...)* loop
				// and we're not taking the exit branch of loop.
				InterpreterRuleContext localctx =
					createInterpreterRuleContext(_parentContextStack.peek().getItem1(),
												 _parentContextStack.peek().getItem2(),
												 _ctx.getRuleIndex());
				pushNewRecursionContext(localctx,
										atn.ruleToStartState[p.ruleIndex].stateNumber,
										_ctx.getRuleIndex());
			}
			break;

		case Transition.ATOM:
			match(((AtomTransition)transition).label);
			break;

		case Transition.RANGE:
		case Transition.SET:
		case Transition.NOT_SET:
			if (!transition.matches(_input.LA(1), Token.MIN_USER_TOKEN_TYPE, 65535)) {
				recoverInline();
			}
			matchWildcard();
			break;

		case Transition.WILDCARD:
			matchWildcard();
			break;

		case Transition.RULE:
			RuleStartState ruleStartState = (RuleStartState)transition.target;
			int ruleIndex = ruleStartState.ruleIndex;
			InterpreterRuleContext newctx = createInterpreterRuleContext(_ctx, p.stateNumber, ruleIndex);
			if (ruleStartState.isPrecedenceRule) {
				enterRecursionRule(newctx, ruleStartState.stateNumber, ruleIndex, ((RuleTransition)transition).precedence);
			}
			else {
				enterRule(newctx, transition.target.stateNumber, ruleIndex);
			}
			break;

		case Transition.PREDICATE:
			PredicateTransition predicateTransition = (PredicateTransition)transition;
			if (!sempred(_ctx, predicateTransition.ruleIndex, predicateTransition.predIndex)) {
				throw new FailedPredicateException(this);
			}

			break;

		case Transition.ACTION:
			ActionTransition actionTransition = (ActionTransition)transition;
			action(_ctx, actionTransition.ruleIndex, actionTransition.actionIndex);
			break;

		case Transition.PRECEDENCE:
			if (!precpred(_ctx, ((PrecedencePredicateTransition)transition).precedence)) {
				throw new FailedPredicateException(this, String.format("precpred(_ctx, %d)", ((PrecedencePredicateTransition)transition).precedence));
			}
			break;

		default:
			throw new UnsupportedOperationException("Unrecognized ATN transition type.");
		}

		setState(transition.target.stateNumber);
	}

	/** Method visitDecisionState() is called when the interpreter reaches
	 *  a decision state (instance of DecisionState). It gives an opportunity
	 *  for subclasses to track interesting things.
	 */
	protected int visitDecisionState(DecisionState p) {
		int predictedAlt;
		getErrorHandler().sync(this);
		int decision = p.decision;
		if (decision == overrideDecision && _input.index() == overrideDecisionInputIndex && !overrideDecisionReached) {
			predictedAlt = overrideDecisionAlt;
			overrideDecisionReached = true;
		}
		else {
			predictedAlt = getInterpreter().adaptivePredict(_input, decision, _ctx);
		}
		return predictedAlt;
	}

	/** Provide simple "factory" for InterpreterRuleContext's.
	 *  @since 4.5.1
	 */
	protected InterpreterRuleContext createInterpreterRuleContext(
		ParserRuleContext parent,
		int invokingStateNumber,
		int ruleIndex)
	{
		return new InterpreterRuleContext(parent, invokingStateNumber, ruleIndex);
	}

	protected void visitRuleStopState(ATNState p) {
		RuleStartState ruleStartState = atn.ruleToStartState[p.ruleIndex];
		if (ruleStartState.isPrecedenceRule) {
			Tuple2<ParserRuleContext, Integer> parentContext = _parentContextStack.pop();
			unrollRecursionContexts(parentContext.getItem1());
			setState(parentContext.getItem2());
		}
		else {
			exitRule();
		}

		RuleTransition ruleTransition = (RuleTransition)atn.states.get(getState()).transition(0);
		setState(ruleTransition.followState.stateNumber);
	}

	/** Override this parser interpreters normal decision-making process
	 *  at a particular decision and input token index. Instead of
	 *  allowing the adaptive prediction mechanism to choose the
	 *  first alternative within a block that leads to a successful parse,
	 *  force it to take the alternative, 1..n for n alternatives.
	 *
	 *  As an implementation limitation right now, you can only specify one
	 *  override. This is sufficient to allow construction of different
	 *  parse trees for ambiguous input. It means re-parsing the entire input
	 *  in general because you're never sure where an ambiguous sequence would
	 *  live in the various parse trees. For example, in one interpretation,
	 *  an ambiguous input sequence would be matched completely in expression
	 *  but in another it could match all the way back to the root.
	 *
	 *  s : e '!'? ;
	 *  e : ID
	 *    | ID '!'
	 *    ;
	 *
	 *  Here, x! can be matched as (s (e ID) !) or (s (e ID !)). In the first
	 *  case, the ambiguous sequence is fully contained only by the root.
	 *  In the second case, the ambiguous sequences fully contained within just
	 *  e, as in: (e ID !).
	 *
	 *  Rather than trying to optimize this and make
	 *  some intelligent decisions for optimization purposes, I settled on
	 *  just re-parsing the whole input and then using
	 *  {link Trees#getRootOfSubtreeEnclosingRegion} to find the minimal
	 *  subtree that contains the ambiguous sequence. I originally tried to
	 *  record the call stack at the point the parser detected and ambiguity but
	 *  left recursive rules create a parse tree stack that does not reflect
	 *  the actual call stack. That impedance mismatch was enough to make
	 *  it it challenging to restart the parser at a deeply nested rule
	 *  invocation.
	 *
	 *  Only parser interpreters can override decisions so as to avoid inserting
	 *  override checking code in the critical ALL(*) prediction execution path.
	 *
	 *  @since 4.5
	 */
	public void addDecisionOverride(int decision, int tokenIndex, int forcedAlt) {
		overrideDecision = decision;
		overrideDecisionInputIndex = tokenIndex;
		overrideDecisionAlt = forcedAlt;
	}

	public InterpreterRuleContext getOverrideDecisionRoot() {
		return overrideDecisionRoot;
	}

	/** Rely on the error handler for this parser but, if no tokens are consumed
	 *  to recover, add an error node. Otherwise, nothing is seen in the parse
	 *  tree.
	 */
	protected void recover(RecognitionException e) {
		int i = _input.index();
		getErrorHandler().recover(this, e);
		if ( _input.index()==i ) {
			// no input consumed, better add an error node
			if ( e instanceof InputMismatchException ) {
				InputMismatchException ime = (InputMismatchException)e;
				Token tok = e.getOffendingToken();
				int expectedTokenType = Token.INVALID_TYPE;
				if ( !ime.getExpectedTokens().isNil() ) {
					expectedTokenType = ime.getExpectedTokens().getMinElement(); // get any element
				}
				Token errToken =
					getTokenFactory().create(Tuple.create(tok.getTokenSource(), tok.getTokenSource().getInputStream()),
				                             expectedTokenType, tok.getText(),
				                             Token.DEFAULT_CHANNEL,
				                            -1, -1, // invalid start/stop
				                             tok.getLine(), tok.getCharPositionInLine());
				_ctx.addErrorNode(createErrorNode(_ctx,errToken));
			}
			else { // NoViableAlt
				Token tok = e.getOffendingToken();
				Token errToken =
					getTokenFactory().create(Tuple.create(tok.getTokenSource(), tok.getTokenSource().getInputStream()),
				                             Token.INVALID_TYPE, tok.getText(),
				                             Token.DEFAULT_CHANNEL,
				                            -1, -1, // invalid start/stop
				                             tok.getLine(), tok.getCharPositionInLine());
				_ctx.addErrorNode(createErrorNode(_ctx,errToken));
			}
		}
	}

	protected Token recoverInline() {
		return _errHandler.recoverInline(this);
	}

	/** Return the root of the parse, which can be useful if the parser
	 *  bails out. You still can access the top node. Note that,
	 *  because of the way left recursive rules add children, it's possible
	 *  that the root will not have any children if the start rule immediately
	 *  called and left recursive rule that fails.
	 *
	 * @since 4.5.1
	 */
	public InterpreterRuleContext getRootContext() {
		return rootContext;
	}
}

