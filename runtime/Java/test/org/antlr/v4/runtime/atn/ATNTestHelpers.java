/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.VocabularyImpl;

import java.util.Collections;
import java.util.List;

/**
 * Shared helpers for building minimal hand-crafted ATNs used by runtime unit tests.
 */
final class ATNTestHelpers {
	private ATNTestHelpers() {
	}

	/**
	 * Build a lexer ATN that matches a single letter {@code 'a'} as token type 1.
	 * Uses epsilon from TokensStart (same pattern as production lexer ATNs / existing tests).
	 * <pre>
	 * TokensStart -eps-&gt; RuleStart -Atom('a')-&gt; mid -eps-&gt; RuleStop
	 * </pre>
	 */
	static ATN buildLexerMatchA() {
		// maxTokenType must fit in a char for ATNSerializer; lexer DFA edges use code points separately.
		ATN atn = new ATN(ATNType.LEXER, 1);

		TokensStartState tokensStart = new TokensStartState();
		tokensStart.ruleIndex = -1;
		RuleStartState ruleStart = new RuleStartState();
		BasicState mid = new BasicState();
		RuleStopState ruleStop = new RuleStopState();

		ruleStart.ruleIndex = 0;
		mid.ruleIndex = 0;
		ruleStop.ruleIndex = 0;
		ruleStart.stopState = ruleStop;

		atn.addState(tokensStart);
		atn.addState(ruleStart);
		atn.addState(mid);
		atn.addState(ruleStop);

		tokensStart.addTransition(new EpsilonTransition(ruleStart));
		ruleStart.addTransition(new AtomTransition(mid, 'a'));
		mid.addTransition(new EpsilonTransition(ruleStop));

		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.ruleToTokenType = new int[] { 1 };
		atn.lexerActions = new LexerAction[0];
		atn.defineMode("DEFAULT_MODE", tokensStart);
		atn.clearDFA();
		return atn;
	}

	/**
	 * Build a lexer ATN matching one letter of an identifier-ish class [a-z] as token type 1.
	 */
	static ATN buildLexerMatchLetterRange() {
		ATN atn = new ATN(ATNType.LEXER, 1);

		TokensStartState tokensStart = new TokensStartState();
		tokensStart.ruleIndex = -1;
		RuleStartState ruleStart = new RuleStartState();
		BasicState mid = new BasicState();
		RuleStopState ruleStop = new RuleStopState();

		ruleStart.ruleIndex = 0;
		mid.ruleIndex = 0;
		ruleStop.ruleIndex = 0;
		ruleStart.stopState = ruleStop;

		atn.addState(tokensStart);
		atn.addState(ruleStart);
		atn.addState(mid);
		atn.addState(ruleStop);

		tokensStart.addTransition(new EpsilonTransition(ruleStart));
		ruleStart.addTransition(new RangeTransition(mid, 'a', 'z'));
		mid.addTransition(new EpsilonTransition(ruleStop));

		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.ruleToTokenType = new int[] { 1 };
		atn.lexerActions = new LexerAction[0];
		atn.defineMode("DEFAULT_MODE", tokensStart);
		atn.clearDFA();
		return atn;
	}

	/**
	 * Parser ATN for {@code s : A B ;} with token types A=1, B=2 (no decisions).
	 */
	static ATN buildParserAB() {
		ATN atn = new ATN(ATNType.PARSER, 2);

		RuleStartState ruleStart = new RuleStartState();
		BasicState mid = new BasicState();
		RuleStopState ruleStop = new RuleStopState();

		ruleStart.ruleIndex = 0;
		mid.ruleIndex = 0;
		ruleStop.ruleIndex = 0;
		ruleStart.stopState = ruleStop;

		atn.addState(ruleStart);
		atn.addState(mid);
		atn.addState(ruleStop);

		ruleStart.addTransition(new AtomTransition(mid, 1));
		mid.addTransition(new AtomTransition(ruleStop, 2));

		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.clearDFA();
		return atn;
	}

	/**
	 * Parser ATN for {@code s : A | B ;} with a block decision.
	 */
	static ATN buildParserAorB() {
		ATN atn = new ATN(ATNType.PARSER, 2);

		RuleStartState ruleStart = new RuleStartState();
		BasicBlockStartState blockStart = new BasicBlockStartState();
		BlockEndState blockEnd = new BlockEndState();
		RuleStopState ruleStop = new RuleStopState();

		ruleStart.ruleIndex = 0;
		blockStart.ruleIndex = 0;
		blockEnd.ruleIndex = 0;
		ruleStop.ruleIndex = 0;
		ruleStart.stopState = ruleStop;
		blockStart.endState = blockEnd;
		blockEnd.startState = blockStart;

		atn.addState(ruleStart);
		atn.addState(blockStart);
		atn.addState(blockEnd);
		atn.addState(ruleStop);

		ruleStart.addTransition(new EpsilonTransition(blockStart));
		blockStart.addTransition(new AtomTransition(blockEnd, 1));
		blockStart.addTransition(new AtomTransition(blockEnd, 2));
		blockEnd.addTransition(new EpsilonTransition(ruleStop));

		atn.defineDecisionState(blockStart);
		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.clearDFA();
		return atn;
	}

	static LexerInterpreter createLexer(ATN atn, String input) {
		Vocabulary vocab = new VocabularyImpl(new String[] { null, "'a'" }, new String[] { null, "A" });
		List<String> ruleNames = Collections.singletonList("A");
		List<String> modeNames = Collections.singletonList("DEFAULT_MODE");
		CharStream cs = CharStreams.fromString(input);
		return new LexerInterpreter("TestLexer", vocab, ruleNames, null, modeNames, atn, cs);
	}

	static ParserInterpreter createParser(ATN atn, int... tokenTypes) {
		Vocabulary vocab = new VocabularyImpl(
			new String[] { null, "'A'", "'B'" },
			new String[] { null, "A", "B" });
		List<String> ruleNames = Collections.singletonList("s");

		// Build a token stream by feeding synthetic tokens from a trivial list.
		// Use CommonTokenStream over a list-backed lexer is complex; use TokenFactory via CommonToken.
		org.antlr.v4.runtime.ListTokenSource source = new org.antlr.v4.runtime.ListTokenSource(
			createTokens(tokenTypes));
		CommonTokenStream tokens = new CommonTokenStream(source);
		return new ParserInterpreter("TestParser", vocab, ruleNames, atn, tokens);
	}

	static java.util.List<Token> createTokens(int... tokenTypes) {
		java.util.ArrayList<Token> list = new java.util.ArrayList<Token>();
		org.antlr.v4.runtime.TokenFactory factory = org.antlr.v4.runtime.CommonTokenFactory.DEFAULT;
		for (int i = 0; i < tokenTypes.length; i++) {
			org.antlr.v4.runtime.CommonToken t = (org.antlr.v4.runtime.CommonToken)
				factory.create(tokenTypes[i], String.valueOf(tokenTypes[i]));
			t.setTokenIndex(i);
			list.add(t);
		}
		org.antlr.v4.runtime.CommonToken eof = (org.antlr.v4.runtime.CommonToken)
			factory.create(Token.EOF, "<EOF>");
		eof.setTokenIndex(tokenTypes.length);
		list.add(eof);
		return list;
	}

	/**
	 * Create a parser with custom vocabulary/rule names.
	 */
	static ParserInterpreter createParser(ATN atn, Vocabulary vocab, List<String> ruleNames, int... tokenTypes) {
		ListTokenSource source = new ListTokenSource(createTokens(tokenTypes));
		CommonTokenStream tokens = new CommonTokenStream(source);
		return new ParserInterpreter("TestParser", vocab, ruleNames, atn, tokens);
	}

	static Vocabulary vocabABC() {
		return new VocabularyImpl(
			new String[] { null, "'A'", "'B'", "'C'" },
			new String[] { null, "A", "B", "C" });
	}

	/**
	 * Parser ATN for {@code s : A* ;} (star loop over token A=1).
	 */
	static ATN buildParserAStar() {
		ATN atn = new ATN(ATNType.PARSER, 2);

		RuleStartState ruleStart = new RuleStartState();
		StarLoopEntryState entry = new StarLoopEntryState();
		StarBlockStartState blkStart = new StarBlockStartState();
		BlockEndState blkEnd = new BlockEndState();
		StarLoopbackState loop = new StarLoopbackState();
		LoopEndState end = new LoopEndState();
		RuleStopState ruleStop = new RuleStopState();

		for (ATNState s : new ATNState[] { ruleStart, entry, blkStart, blkEnd, loop, end, ruleStop }) {
			s.ruleIndex = 0;
			atn.addState(s);
		}
		ruleStart.stopState = ruleStop;
		blkStart.endState = blkEnd;
		blkEnd.startState = blkStart;
		entry.loopBackState = loop;
		end.loopBackState = loop;

		ruleStart.addTransition(new EpsilonTransition(entry));
		// greedy: enter loop first, exit second
		entry.addTransition(new EpsilonTransition(blkStart));
		entry.addTransition(new EpsilonTransition(end));
		blkStart.addTransition(new AtomTransition(blkEnd, 1));
		blkEnd.addTransition(new EpsilonTransition(loop));
		loop.addTransition(new EpsilonTransition(entry));
		end.addTransition(new EpsilonTransition(ruleStop));

		atn.defineDecisionState(entry);
		// blkStart is also a decision if multi-alt; single alt still ok without define
		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.clearDFA();
		return atn;
	}

	/**
	 * Parser ATN for {@code s : A+ ;} (plus loop over token A=1).
	 */
	static ATN buildParserAPlus() {
		ATN atn = new ATN(ATNType.PARSER, 2);

		RuleStartState ruleStart = new RuleStartState();
		PlusBlockStartState blkStart = new PlusBlockStartState();
		BlockEndState blkEnd = new BlockEndState();
		PlusLoopbackState loop = new PlusLoopbackState();
		LoopEndState end = new LoopEndState();
		RuleStopState ruleStop = new RuleStopState();

		for (ATNState s : new ATNState[] { ruleStart, blkStart, blkEnd, loop, end, ruleStop }) {
			s.ruleIndex = 0;
			atn.addState(s);
		}
		ruleStart.stopState = ruleStop;
		blkStart.endState = blkEnd;
		blkEnd.startState = blkStart;
		blkStart.loopBackState = loop;
		end.loopBackState = loop;

		ruleStart.addTransition(new EpsilonTransition(blkStart));
		blkStart.addTransition(new AtomTransition(blkEnd, 1));
		blkEnd.addTransition(new EpsilonTransition(loop));
		// greedy: loop first, exit second
		loop.addTransition(new EpsilonTransition(blkStart));
		loop.addTransition(new EpsilonTransition(end));
		end.addTransition(new EpsilonTransition(ruleStop));

		atn.defineDecisionState(loop);
		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.clearDFA();
		return atn;
	}

	/**
	 * Parser ATN for {@code s : A? B ;} (optional A then required B).
	 * Uses epsilon edges from the decision (verifyATN-compatible).
	 */
	static ATN buildParserOptionalAthenB() {
		ATN atn = new ATN(ATNType.PARSER, 2);

		RuleStartState ruleStart = new RuleStartState();
		BasicBlockStartState optStart = new BasicBlockStartState();
		BasicState altA = new BasicState();
		BlockEndState optEnd = new BlockEndState();
		BasicState afterOpt = new BasicState();
		RuleStopState ruleStop = new RuleStopState();

		for (ATNState s : new ATNState[] { ruleStart, optStart, altA, optEnd, afterOpt, ruleStop }) {
			s.ruleIndex = 0;
			atn.addState(s);
		}
		ruleStart.stopState = ruleStop;
		optStart.endState = optEnd;
		optEnd.startState = optStart;

		ruleStart.addTransition(new EpsilonTransition(optStart));
		optStart.addTransition(new EpsilonTransition(altA)); // take A
		altA.addTransition(new AtomTransition(optEnd, 1));
		optStart.addTransition(new EpsilonTransition(optEnd)); // empty
		optEnd.addTransition(new EpsilonTransition(afterOpt));
		afterOpt.addTransition(new AtomTransition(ruleStop, 2)); // B

		atn.defineDecisionState(optStart);
		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.clearDFA();
		return atn;
	}

	/**
	 * Exactly ambiguous: {@code s : A | A ;}
	 */
	static ATN buildParserAmbiguousAA() {
		ATN atn = new ATN(ATNType.PARSER, 2);

		RuleStartState ruleStart = new RuleStartState();
		BasicBlockStartState blockStart = new BasicBlockStartState();
		BasicState alt1 = new BasicState();
		BasicState alt2 = new BasicState();
		BlockEndState blockEnd = new BlockEndState();
		RuleStopState ruleStop = new RuleStopState();

		for (ATNState s : new ATNState[] { ruleStart, blockStart, alt1, alt2, blockEnd, ruleStop }) {
			s.ruleIndex = 0;
			atn.addState(s);
		}
		ruleStart.stopState = ruleStop;
		blockStart.endState = blockEnd;
		blockEnd.startState = blockStart;

		ruleStart.addTransition(new EpsilonTransition(blockStart));
		blockStart.addTransition(new EpsilonTransition(alt1));
		alt1.addTransition(new AtomTransition(blockEnd, 1));
		blockStart.addTransition(new EpsilonTransition(alt2));
		alt2.addTransition(new AtomTransition(blockEnd, 1));
		blockEnd.addTransition(new EpsilonTransition(ruleStop));

		atn.defineDecisionState(blockStart);
		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.clearDFA();
		return atn;
	}

	/**
	 * {@code s : t A | t B ; t : A ;} — classic context-sensitive decision.
	 * Tokens: A=1, B=2.
	 */
	static ATN buildParserContextSensitive() {
		ATN atn = new ATN(ATNType.PARSER, 2);

		// rule s
		RuleStartState sStart = new RuleStartState();
		BasicBlockStartState blockStart = new BasicBlockStartState();
		// alt1: t A
		BasicState a1 = new BasicState();
		BasicState a1after = new BasicState();
		// alt2: t B
		BasicState a2 = new BasicState();
		BasicState a2after = new BasicState();
		BlockEndState blockEnd = new BlockEndState();
		RuleStopState sStop = new RuleStopState();

		// rule t : A
		RuleStartState tStart = new RuleStartState();
		RuleStopState tStop = new RuleStopState();

		sStart.ruleIndex = 0;
		blockStart.ruleIndex = 0;
		a1.ruleIndex = 0;
		a1after.ruleIndex = 0;
		a2.ruleIndex = 0;
		a2after.ruleIndex = 0;
		blockEnd.ruleIndex = 0;
		sStop.ruleIndex = 0;
		tStart.ruleIndex = 1;
		tStop.ruleIndex = 1;

		sStart.stopState = sStop;
		tStart.stopState = tStop;
		blockStart.endState = blockEnd;
		blockEnd.startState = blockStart;

		for (ATNState s : new ATNState[] {
			sStart, blockStart, a1, a1after, a2, a2after, blockEnd, sStop, tStart, tStop
		}) {
			atn.addState(s);
		}

		sStart.addTransition(new EpsilonTransition(blockStart));
		// alt 1: call t then match A(1)
		blockStart.addTransition(new EpsilonTransition(a1));
		a1.addTransition(new RuleTransition(tStart, 1, 0, a1after));
		a1after.addTransition(new AtomTransition(blockEnd, 1));
		// alt 2: call t then match B(2)
		blockStart.addTransition(new EpsilonTransition(a2));
		a2.addTransition(new RuleTransition(tStart, 1, 0, a2after));
		a2after.addTransition(new AtomTransition(blockEnd, 2));
		blockEnd.addTransition(new EpsilonTransition(sStop));

		tStart.addTransition(new AtomTransition(tStop, 1));

		atn.defineDecisionState(blockStart);
		atn.ruleToStartState = new RuleStartState[] { sStart, tStart };
		atn.ruleToStopState = new RuleStopState[] { sStop, tStop };
		atn.clearDFA();
		return atn;
	}

	/**
	 * {@code s : A | B} with semantic predicate on alt1: {@code {p}? A | B}
	 */
	static ATN buildParserWithPredicate() {
		ATN atn = new ATN(ATNType.PARSER, 2);

		RuleStartState ruleStart = new RuleStartState();
		BasicBlockStartState blockStart = new BasicBlockStartState();
		BasicState predGate = new BasicState();
		BasicState afterPred = new BasicState();
		BasicState alt2 = new BasicState();
		BlockEndState blockEnd = new BlockEndState();
		RuleStopState ruleStop = new RuleStopState();

		for (ATNState s : new ATNState[] { ruleStart, blockStart, predGate, afterPred, alt2, blockEnd, ruleStop }) {
			s.ruleIndex = 0;
			atn.addState(s);
		}
		ruleStart.stopState = ruleStop;
		blockStart.endState = blockEnd;
		blockEnd.startState = blockStart;

		ruleStart.addTransition(new EpsilonTransition(blockStart));
		// alt1: pred then A (all epsilons from decision)
		blockStart.addTransition(new PredicateTransition(predGate, 0, 0, false));
		predGate.addTransition(new EpsilonTransition(afterPred));
		afterPred.addTransition(new AtomTransition(blockEnd, 1));
		// alt2: B
		blockStart.addTransition(new EpsilonTransition(alt2));
		alt2.addTransition(new AtomTransition(blockEnd, 2));
		blockEnd.addTransition(new EpsilonTransition(ruleStop));

		atn.defineDecisionState(blockStart);
		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.clearDFA();
		return atn;
	}

	/**
	 * {@code s : t ; t : A ;} simple rule call.
	 */
	static ATN buildParserRuleCall() {
		ATN atn = new ATN(ATNType.PARSER, 2);

		RuleStartState sStart = new RuleStartState();
		BasicState after = new BasicState();
		RuleStopState sStop = new RuleStopState();
		RuleStartState tStart = new RuleStartState();
		RuleStopState tStop = new RuleStopState();

		sStart.ruleIndex = 0;
		after.ruleIndex = 0;
		sStop.ruleIndex = 0;
		tStart.ruleIndex = 1;
		tStop.ruleIndex = 1;
		sStart.stopState = sStop;
		tStart.stopState = tStop;

		atn.addState(sStart);
		atn.addState(tStart);
		atn.addState(tStop);
		atn.addState(after);
		atn.addState(sStop);

		sStart.addTransition(new RuleTransition(tStart, 1, 0, after));
		after.addTransition(new EpsilonTransition(sStop));
		tStart.addTransition(new AtomTransition(tStop, 1));

		atn.ruleToStartState = new RuleStartState[] { sStart, tStart };
		atn.ruleToStopState = new RuleStopState[] { sStop, tStop };
		atn.clearDFA();
		return atn;
	}

	/**
	 * {@code s : . B ;} wildcard then B.
	 */
	static ATN buildParserWildcardThenB() {
		ATN atn = new ATN(ATNType.PARSER, 3);

		RuleStartState ruleStart = new RuleStartState();
		BasicState mid = new BasicState();
		RuleStopState ruleStop = new RuleStopState();

		for (ATNState s : new ATNState[] { ruleStart, mid, ruleStop }) {
			s.ruleIndex = 0;
			atn.addState(s);
		}
		ruleStart.stopState = ruleStop;
		ruleStart.addTransition(new WildcardTransition(mid));
		mid.addTransition(new AtomTransition(ruleStop, 2));

		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.clearDFA();
		return atn;
	}

	/**
	 * Lexer with skip action on whitespace-like rule and more/pushMode for coverage.
	 * Modes: DEFAULT matches 'a' as type 1, or WS ' ' as skip; mode 1 matches 'b' as type 2.
	 */
	static ATN buildLexerWithModesAndActions() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);

		TokensStartState defaultStart = new TokensStartState();
		defaultStart.ruleIndex = -1;
		TokensStartState mode1Start = new TokensStartState();
		mode1Start.ruleIndex = -1;
		atn.addState(defaultStart);
		atn.addState(mode1Start);

		// rule 0: 'a' -> type 1, push mode 1
		RuleStartState aStart = new RuleStartState();
		BasicState aMid = new BasicState();
		BasicState aAction = new BasicState();
		RuleStopState aStop = new RuleStopState();
		aStart.ruleIndex = 0;
		aMid.ruleIndex = 0;
		aAction.ruleIndex = 0;
		aStop.ruleIndex = 0;
		aStart.stopState = aStop;
		atn.addState(aStart);
		atn.addState(aMid);
		atn.addState(aAction);
		atn.addState(aStop);
		aStart.addTransition(new AtomTransition(aMid, 'a'));
		// action index 0 = pushMode(1)
		aMid.addTransition(new ActionTransition(aAction, 0, 0, false));
		aAction.addTransition(new EpsilonTransition(aStop));

		// rule 1: ' ' -> skip
		RuleStartState wsStart = new RuleStartState();
		BasicState wsMid = new BasicState();
		BasicState wsAction = new BasicState();
		RuleStopState wsStop = new RuleStopState();
		wsStart.ruleIndex = 1;
		wsMid.ruleIndex = 1;
		wsAction.ruleIndex = 1;
		wsStop.ruleIndex = 1;
		wsStart.stopState = wsStop;
		atn.addState(wsStart);
		atn.addState(wsMid);
		atn.addState(wsAction);
		atn.addState(wsStop);
		wsStart.addTransition(new AtomTransition(wsMid, ' '));
		// action index 1 = skip
		wsMid.addTransition(new ActionTransition(wsAction, 1, 1, false));
		wsAction.addTransition(new EpsilonTransition(wsStop));

		// rule 2: 'b' in mode 1 -> type 2, popMode
		RuleStartState bStart = new RuleStartState();
		BasicState bMid = new BasicState();
		BasicState bAction = new BasicState();
		RuleStopState bStop = new RuleStopState();
		bStart.ruleIndex = 2;
		bMid.ruleIndex = 2;
		bAction.ruleIndex = 2;
		bStop.ruleIndex = 2;
		bStart.stopState = bStop;
		atn.addState(bStart);
		atn.addState(bMid);
		atn.addState(bAction);
		atn.addState(bStop);
		bStart.addTransition(new AtomTransition(bMid, 'b'));
		// action index 2 = popMode
		bMid.addTransition(new ActionTransition(bAction, 2, 2, false));
		bAction.addTransition(new EpsilonTransition(bStop));

		defaultStart.addTransition(new EpsilonTransition(aStart));
		defaultStart.addTransition(new EpsilonTransition(wsStart));
		mode1Start.addTransition(new EpsilonTransition(bStart));

		atn.ruleToStartState = new RuleStartState[] { aStart, wsStart, bStart };
		atn.ruleToStopState = new RuleStopState[] { aStop, wsStop, bStop };
		atn.ruleToTokenType = new int[] { 1, Token.INVALID_TYPE, 2 }; // skip rule type ignored when action skips
		atn.lexerActions = new LexerAction[] {
			new LexerPushModeAction(1),
			LexerSkipAction.INSTANCE,
			LexerPopModeAction.INSTANCE
		};
		atn.defineMode("DEFAULT_MODE", defaultStart);
		atn.defineMode("MODE1", mode1Start);
		atn.clearDFA();
		return atn;
	}
}
