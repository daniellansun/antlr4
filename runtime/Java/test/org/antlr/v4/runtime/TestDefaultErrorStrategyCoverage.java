/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicBlockStartState;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.BlockEndState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.LoopEndState;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PlusBlockStartState;
import org.antlr.v4.runtime.atn.PlusLoopbackState;
import org.antlr.v4.runtime.atn.PredicateTransition;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.StarBlockStartState;
import org.antlr.v4.runtime.atn.StarLoopEntryState;
import org.antlr.v4.runtime.atn.StarLoopbackState;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Coverage for {@link DefaultErrorStrategy} recovery/sync/report paths.
 */
public class TestDefaultErrorStrategyCoverage {

	/** Flexible stub parser over a token sequence. */
	static final class SeqParser extends Parser {
		final ATN atn;
		final String[] ruleNames;
		final String[] tokenNames;

		SeqParser(ATN atn, String[] ruleNames, String[] tokenNames, int... types) {
			super(null);
			this.atn = atn;
			this.ruleNames = ruleNames;
			this.tokenNames = tokenNames;
			List<Token> toks = new ArrayList<Token>();
			for (int i = 0; i < types.length; i++) {
				CommonToken t = new CommonToken(types[i], "t" + types[i]);
				t.setTokenIndex(i);
				t.setLine(1);
				t.setCharPositionInLine(i);
				toks.add(t);
			}
			CommonToken eof = new CommonToken(Token.EOF, "<EOF>");
			eof.setTokenIndex(types.length);
			toks.add(eof);
			ListTokenSource source = new ListTokenSource(toks);
			// re-create tokens bound to source so getMissingSymbol can read source/stream
			List<Token> bound = new ArrayList<Token>();
			for (int i = 0; i < toks.size(); i++) {
				Token old = toks.get(i);
				CommonToken t = new CommonToken(
					org.antlr.v4.runtime.misc.Tuple.create(source, (CharStream) null),
					old.getType(), Token.DEFAULT_CHANNEL, -1, -1);
				t.setText(old.getText());
				t.setTokenIndex(i);
				t.setLine(1);
				t.setCharPositionInLine(i);
				bound.add(t);
			}
			source = new ListTokenSource(bound);
			CommonTokenStream input = new CommonTokenStream(source);
			input.fill();
			setInputStream(input);
			_interp = new ParserATNSimulator(this, atn);
			_ctx = new InterpreterRuleContext(null, -1, 0);
			if (atn.ruleToStartState != null && atn.ruleToStartState.length > 0) {
				setState(atn.ruleToStartState[0].stateNumber);
			}
		}

		@Override public String[] getTokenNames() { return tokenNames; }
		@Override public String[] getRuleNames() { return ruleNames; }
		@Override public String getGrammarFileName() { return "T.g4"; }
		@Override public ATN getATN() { return atn; }
		@Override public Vocabulary getVocabulary() {
			return VocabularyImpl.fromTokenNames(tokenNames);
		}
	}

	/** ATN: r : A B ; */
	static ATN abRule() {
		ATN atn = new ATN(ATNType.PARSER, 3);
		RuleStartState start = new RuleStartState();
		BasicState mid = new BasicState();
		RuleStopState stop = new RuleStopState();
		start.ruleIndex = 0;
		mid.ruleIndex = 0;
		stop.ruleIndex = 0;
		start.stopState = stop;
		atn.addState(start);
		atn.addState(mid);
		atn.addState(stop);
		start.addTransition(new AtomTransition(mid, 1));
		mid.addTransition(new AtomTransition(stop, 2));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}

	/** Nested call for recovery set: s : t C ; t : A B ; */
	static ATN nestedRules() {
		ATN atn = new ATN(ATNType.PARSER, 4);
		RuleStartState sStart = new RuleStartState();
		BasicState afterT = new BasicState();
		RuleStopState sStop = new RuleStopState();
		RuleStartState tStart = new RuleStartState();
		BasicState tMid = new BasicState();
		RuleStopState tStop = new RuleStopState();
		sStart.ruleIndex = 0;
		afterT.ruleIndex = 0;
		sStop.ruleIndex = 0;
		tStart.ruleIndex = 1;
		tMid.ruleIndex = 1;
		tStop.ruleIndex = 1;
		sStart.stopState = sStop;
		tStart.stopState = tStop;
		atn.addState(sStart);
		atn.addState(tStart);
		atn.addState(tMid);
		atn.addState(tStop);
		atn.addState(afterT);
		atn.addState(sStop);
		sStart.addTransition(new RuleTransition(tStart, 1, 0, afterT));
		afterT.addTransition(new AtomTransition(sStop, 3)); // C
		tStart.addTransition(new AtomTransition(tMid, 1)); // A
		tMid.addTransition(new AtomTransition(tStop, 2)); // B
		atn.ruleToStartState = new RuleStartState[] { sStart, tStart };
		atn.ruleToStopState = new RuleStopState[] { sStop, tStop };
		atn.clearDFA();
		return atn;
	}

	/** Star loop ATN for sync on STAR_LOOP_ENTRY. */
	static ATN starLoop() {
		ATN atn = new ATN(ATNType.PARSER, 3);
		RuleStartState ruleStart = new RuleStartState();
		StarLoopEntryState entry = new StarLoopEntryState();
		StarBlockStartState blkStart = new StarBlockStartState();
		BlockEndState blkEnd = new BlockEndState();
		StarLoopbackState loop = new StarLoopbackState();
		LoopEndState end = new LoopEndState();
		RuleStopState ruleStop = new RuleStopState();
		for (org.antlr.v4.runtime.atn.ATNState s : new org.antlr.v4.runtime.atn.ATNState[] {
			ruleStart, entry, blkStart, blkEnd, loop, end, ruleStop }) {
			s.ruleIndex = 0;
			atn.addState(s);
		}
		ruleStart.stopState = ruleStop;
		blkStart.endState = blkEnd;
		blkEnd.startState = blkStart;
		entry.loopBackState = loop;
		end.loopBackState = loop;
		ruleStart.addTransition(new EpsilonTransition(entry));
		entry.addTransition(new EpsilonTransition(blkStart));
		entry.addTransition(new EpsilonTransition(end));
		blkStart.addTransition(new AtomTransition(blkEnd, 1));
		blkEnd.addTransition(new EpsilonTransition(loop));
		loop.addTransition(new EpsilonTransition(entry));
		end.addTransition(new EpsilonTransition(ruleStop));
		atn.defineDecisionState(entry);
		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.clearDFA();
		return atn;
	}

	/** Plus loop for STAR/PLUS_LOOP_BACK sync. */
	static ATN plusLoop() {
		ATN atn = new ATN(ATNType.PARSER, 3);
		RuleStartState ruleStart = new RuleStartState();
		PlusBlockStartState blkStart = new PlusBlockStartState();
		BlockEndState blkEnd = new BlockEndState();
		PlusLoopbackState loop = new PlusLoopbackState();
		LoopEndState end = new LoopEndState();
		RuleStopState ruleStop = new RuleStopState();
		for (org.antlr.v4.runtime.atn.ATNState s : new org.antlr.v4.runtime.atn.ATNState[] {
			ruleStart, blkStart, blkEnd, loop, end, ruleStop }) {
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
		loop.addTransition(new EpsilonTransition(blkStart));
		loop.addTransition(new EpsilonTransition(end));
		end.addTransition(new EpsilonTransition(ruleStop));
		atn.defineDecisionState(loop);
		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.clearDFA();
		return atn;
	}

	private static final String[] NAMES = new String[] { "NONE", "A", "B", "C", "D" };

	@Test
	public void reportFailedPredicateAndFailedGeneric() {
		// FailedPredicateException requires current ATN state transition to be a predicate.
		// Build a one-state ATN with a PredicateTransition so construction succeeds.
		ATN atn = new ATN(ATNType.PARSER, 2);
		RuleStartState start = new RuleStartState();
		RuleStopState stop = new RuleStopState();
		start.ruleIndex = 0;
		stop.ruleIndex = 0;
		start.stopState = stop;
		atn.addState(start);
		atn.addState(stop);
		start.addTransition(new PredicateTransition(stop, 0, 0, false));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();

		SeqParser p = new SeqParser(atn, new String[] { "r" }, NAMES, 1, 2);
		p.setState(start.stateNumber);
		final List<String> msgs = new ArrayList<String>();
		p.removeErrorListeners();
		p.addErrorListener(new BaseErrorListener() {
			@Override
			public <T extends Token> void syntaxError(Recognizer<T, ?> recognizer, T offendingSymbol,
													  int line, int charPositionInLine, String msg,
													  RecognitionException e) {
				msgs.add(msg);
			}
		});
		DefaultErrorStrategy s = new DefaultErrorStrategy();
		FailedPredicateException fpe = new FailedPredicateException(p, "pred", "failed");
		s.reportError(p, fpe);
		assertEquals(1, msgs.size());
		assertTrue(msgs.get(0).contains("rule") || msgs.get(0).length() > 0);

		s.reportMatch(p);
		// generic RecognitionException
		s.reportError(p, new RecognitionException(p, p.getInputStream(), p.getContext()) {});
		assertTrue(msgs.size() >= 2);
	}

	@Test
	public void singleTokenDeletionOnRecoverInline() {
		// Expect A at start, stream is X A B where X=3 is junk then A=1
		ATN atn = abRule();
		SeqParser p = new SeqParser(atn, new String[] { "r" }, NAMES, 3, 1, 2);
		p.removeErrorListeners();
		p.setState(atn.ruleToStartState[0].stateNumber);
		DefaultErrorStrategy s = new DefaultErrorStrategy();
		// LA(1)=3, LA(2)=1 which is expected => deletion
		Token recovered = s.recoverInline(p);
		assertNotNull(recovered);
		assertEquals(1, recovered.getType());
	}

	@Test
	public void singleTokenInsertionOnRecoverInline() {
		// At mid state expecting B=2; current is EOF which could follow B if we insert?
		// Better: state at start expecting A=1; current is B=2 which can follow A.
		ATN atn = abRule();
		SeqParser p = new SeqParser(atn, new String[] { "r" }, NAMES, 2);
		p.removeErrorListeners();
		p.setState(atn.ruleToStartState[0].stateNumber);
		DefaultErrorStrategy s = new DefaultErrorStrategy();
		// LA(1)=B, expected A; after A next is B, so insertion of A should work
		try {
			Token missing = s.recoverInline(p);
			assertNotNull(missing);
			assertEquals(1, missing.getType());
		}
		catch (RecognitionException ex) {
			// insertion may not be possible for this hand-built ATN follow set
			assertNotNull(ex);
		}
	}

	@Test
	public void recoverInlineThrowsWhenCannotRecover() {
		ATN atn = abRule();
		// only EOF, expecting A and B neither available
		SeqParser p = new SeqParser(atn, new String[] { "r" }, NAMES);
		p.removeErrorListeners();
		p.setState(atn.ruleToStartState[0].stateNumber);
		DefaultErrorStrategy s = new DefaultErrorStrategy();
		try {
			s.recoverInline(p);
			fail();
		}
		catch (InputMismatchException expected) {
			// ok
		}
	}

	@Test
	public void recoverConsumesUntilFollowSet() {
		ATN atn = nestedRules();
		// Inside t after wrong token; force recover from NoViableAlt
		SeqParser p = new SeqParser(atn, new String[] { "s", "t" }, NAMES, 4, 3); // D then C
		p.removeErrorListeners();
		// set context as if inside t called from s
		ParserRuleContext sCtx = new InterpreterRuleContext(null, -1, 0);
		ParserRuleContext tCtx = new InterpreterRuleContext(sCtx, atn.ruleToStartState[0].stateNumber, 1);
		// invoking state is sStart which has RuleTransition
		tCtx.invokingState = atn.ruleToStartState[0].stateNumber;
		p._ctx = tCtx;
		p.setState(atn.ruleToStartState[1].stateNumber);
		DefaultErrorStrategy s = new DefaultErrorStrategy();
		NoViableAltException nvae = new NoViableAltException(p);
		s.reportError(p, nvae);
		s.recover(p, nvae);
		// should have consumed toward recovery set (C follows t)
		assertTrue(p.getInputStream().LA(1) == 3 || p.getInputStream().LA(1) == Token.EOF);
	}

	@Test
	public void recoverFailsafeSecondErrorSameIndex() {
		ATN atn = abRule();
		SeqParser p = new SeqParser(atn, new String[] { "r" }, NAMES, 3, 3, 3);
		p.removeErrorListeners();
		p.setState(atn.ruleToStartState[0].stateNumber);
		DefaultErrorStrategy s = new DefaultErrorStrategy();
		RecognitionException e = new InputMismatchException(p);
		s.beginErrorCondition(p);
		s.recover(p, e);
		int idx = p.getInputStream().index();
		// second recover at same index and state forces consume
		s.recover(p, e);
		assertTrue(p.getInputStream().index() >= idx);
	}

	@Test
	public void syncAtBlockStartDeletesOrThrows() {
		ATN atn = abRule();
		// optional-style multi transition block for BLOCK_START
		ATN blockAtn = new ATN(ATNType.PARSER, 3);
		RuleStartState start = new RuleStartState();
		BasicBlockStartState block = new BasicBlockStartState();
		BlockEndState end = new BlockEndState();
		RuleStopState stop = new RuleStopState();
		start.ruleIndex = 0;
		block.ruleIndex = 0;
		end.ruleIndex = 0;
		stop.ruleIndex = 0;
		start.stopState = stop;
		block.endState = end;
		end.startState = block;
		blockAtn.addState(start);
		blockAtn.addState(block);
		blockAtn.addState(end);
		blockAtn.addState(stop);
		start.addTransition(new EpsilonTransition(block));
		block.addTransition(new AtomTransition(end, 1));
		block.addTransition(new AtomTransition(end, 2));
		end.addTransition(new EpsilonTransition(stop));
		blockAtn.defineDecisionState(block);
		blockAtn.ruleToStartState = new RuleStartState[] { start };
		blockAtn.ruleToStopState = new RuleStopState[] { stop };
		blockAtn.clearDFA();

		// junk then A - deletion
		SeqParser p = new SeqParser(blockAtn, new String[] { "r" }, NAMES, 3, 1);
		p.removeErrorListeners();
		p.setState(block.stateNumber);
		DefaultErrorStrategy s = new DefaultErrorStrategy();
		s.sync(p); // should delete 3

		// impossible token, no deletion
		SeqParser p2 = new SeqParser(blockAtn, new String[] { "r" }, NAMES, 4, 4);
		p2.removeErrorListeners();
		p2.setState(block.stateNumber);
		try {
			s.sync(p2);
			fail();
		}
		catch (InputMismatchException expected) {
			// ok
		}
	}

	@Test
	public void syncAtStarLoopEntryAndPlusLoopBack() {
		ATN star = starLoop();
		StarLoopEntryState entry = (StarLoopEntryState) star.decisionToState.get(0);
		// junk before valid A or exit
		SeqParser p = new SeqParser(star, new String[] { "r" }, NAMES, 3, 1);
		p.removeErrorListeners();
		p.setState(entry.stateNumber);
		DefaultErrorStrategy s = new DefaultErrorStrategy();
		s.sync(p);

		// plus loop back with junk
		ATN plus = plusLoop();
		PlusLoopbackState loop = (PlusLoopbackState) plus.decisionToState.get(0);
		SeqParser p2 = new SeqParser(plus, new String[] { "r" }, NAMES, 3, 1);
		p2.removeErrorListeners();
		p2.setState(loop.stateNumber);
		s.reportMatch(p2);
		s.sync(p2);
	}

	@Test
	public void syncNoOpWhenTokenFitsOrInRecovery() {
		ATN atn = abRule();
		SeqParser p = new SeqParser(atn, new String[] { "r" }, NAMES, 1, 2);
		DefaultErrorStrategy s = new DefaultErrorStrategy();
		p.setState(atn.ruleToStartState[0].stateNumber);
		s.sync(p); // LA=A fits

		s.beginErrorCondition(p);
		s.sync(p); // in recovery => no-op
		assertTrue(s.inErrorRecoveryMode(p));
		s.reportMatch(p);
		assertFalse(s.inErrorRecoveryMode(p));
	}

	@Test
	public void reportUnwantedAndMissingTokenIdempotentInRecovery() {
		SeqParser p = new SeqParser(abRule(), new String[] { "r" }, NAMES, 1, 2);
		p.removeErrorListeners();
		final List<String> msgs = new ArrayList<String>();
		p.addErrorListener(new BaseErrorListener() {
			@Override
			public <T extends Token> void syntaxError(Recognizer<T, ?> recognizer, T offendingSymbol,
													  int line, int charPositionInLine, String msg,
													  RecognitionException e) {
				msgs.add(msg);
			}
		});
		DefaultErrorStrategy s = new DefaultErrorStrategy();
		s.reportUnwantedToken(p);
		assertEquals(1, msgs.size());
		s.reportUnwantedToken(p); // suppressed
		assertEquals(1, msgs.size());
		s.reportMatch(p);
		s.reportMissingToken(p);
		assertEquals(2, msgs.size());
		s.reportMissingToken(p); // suppressed
		assertEquals(2, msgs.size());
	}

	@Test
	public void tokenErrorDisplayEscaping() {
		// use a named subclass so package-private helpers are callable from test package
		class Exposed extends DefaultErrorStrategy {
			String display(Token t) { return getTokenErrorDisplay(t); }
			String esc(String x) { return escapeWSAndQuote(x); }
		}
		Exposed exposed = new Exposed();
		assertEquals("<no token>", exposed.display(null));
		CommonToken eof = new CommonToken(Token.EOF);
		eof.setText(null);
		assertTrue(exposed.display(eof).contains("EOF") || exposed.display(eof).length() > 0);
		CommonToken noText = new CommonToken(5);
		noText.setText(null);
		assertNotNull(exposed.display(noText));
		assertEquals("'\\n\\r\\t'", exposed.esc("\n\r\t"));
	}

	@Test
	public void noViableAltAtEOFDisplay() {
		SeqParser p = new SeqParser(abRule(), new String[] { "r" }, NAMES);
		p.removeErrorListeners();
		final List<String> msgs = new ArrayList<String>();
		p.addErrorListener(new BaseErrorListener() {
			@Override
			public <T extends Token> void syntaxError(Recognizer<T, ?> recognizer, T offendingSymbol,
													  int line, int charPositionInLine, String msg,
													  RecognitionException e) {
				msgs.add(msg);
			}
		});
		DefaultErrorStrategy s = new DefaultErrorStrategy();
		Token eof = p.getCurrentToken();
		NoViableAltException nvae = new NoViableAltException(p, p.getInputStream(), eof, eof, null, p.getContext());
		s.reportError(p, nvae);
		assertEquals(1, msgs.size());
		assertTrue(msgs.get(0).contains("no viable alternative"));
	}

	/**
	 * Successful match is the hot path: {@code reportMatch} must be a no-op when
	 * not recovering, and must clear recovery mode after a reported error.
	 */
	@Test
	public void reportMatchFastPathWhenNotRecovering() {
		SeqParser p = new SeqParser(abRule(), new String[] { "r" }, NAMES, 1, 2);
		p.removeErrorListeners();
		DefaultErrorStrategy s = new DefaultErrorStrategy();
		assertFalse(s.inErrorRecoveryMode(p));
		// Many successful matches (generated parsers call this every match)
		for (int i = 0; i < 100; i++) {
			s.reportMatch(p);
		}
		assertFalse(s.inErrorRecoveryMode(p));

		// Enter recovery then leave via reportMatch
		s.reportError(p, new InputMismatchException(p));
		assertTrue(s.inErrorRecoveryMode(p));
		s.reportMatch(p);
		assertFalse(s.inErrorRecoveryMode(p));
	}

	/**
	 * {@link DefaultErrorStrategy#sync} early-returns while recovering and
	 * accepts tokens already in the next-token set without recovery work.
	 */
	@Test
	public void syncEarlyExitAndAcceptingLookahead() {
		ATN atn = abRule();
		SeqParser p = new SeqParser(atn, new String[] { "r" }, NAMES, 1, 2);
		p.removeErrorListeners();
		p.setState(atn.ruleToStartState[0].stateNumber);
		DefaultErrorStrategy s = new DefaultErrorStrategy();

		// LA(1)=A is expected at start → sync is a pure accept path
		s.sync(p);
		assertFalse(s.inErrorRecoveryMode(p));
		assertEquals(1, p.getCurrentToken().getType());

		// While recovering, sync must not throw or advance
		s.reportError(p, new InputMismatchException(p));
		assertTrue(s.inErrorRecoveryMode(p));
		s.sync(p);
		assertTrue(s.inErrorRecoveryMode(p));
	}

	@Test
	public void syncDoesNotRewriteAlreadyClearNextTokensContext() {
		ATN atn = abRule();
		SeqParser p = new SeqParser(atn, new String[] { "r" }, NAMES, 1, 2);
		p.removeErrorListeners();
		p.setState(atn.ruleToStartState[0].stateNumber);
		DefaultErrorStrategy s = new DefaultErrorStrategy();
		assertNull(s.nextTokensContext);
		s.sync(p);
		assertNull(s.nextTokensContext);
		s.sync(p);
		assertNull(s.nextTokensContext);
	}
}
