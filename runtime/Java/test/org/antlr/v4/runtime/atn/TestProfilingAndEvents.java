/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.dfa.DFAState;
import org.junit.Test;

import java.util.BitSet;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestProfilingAndEvents {

	private static TokenStream emptyTokenStream() {
		return new CommonTokenStream(new ListTokenSource(Collections.<org.antlr.v4.runtime.Token>emptyList()));
	}

	private static DFAState newDfaState() {
		return new DFAState(
			new org.antlr.v4.runtime.dfa.EmptyEdgeMap<DFAState>(0, 10),
			new org.antlr.v4.runtime.dfa.EmptyEdgeMap<DFAState>(-1, 10),
			new ATNConfigSet());
	}

	private static SimulatorState simState(boolean useContext) {
		DFAState s0 = newDfaState();
		return new SimulatorState(null, s0, useContext, null);
	}

	@Test
	public void testSimulatorState() {
		DFAState s0 = newDfaState();
		SimulatorState st = new SimulatorState(null, s0, false, null);
		assertNotNull(st.outerContext);
		assertSame(s0, st.s0);
		assertFalse(st.useContext);
		assertNull(st.remainingOuterContext);

		ParserRuleContext ctx = new ParserRuleContext();
		SimulatorState st2 = new SimulatorState(ctx, s0, true, ctx);
		assertSame(ctx, st2.outerContext);
		assertTrue(st2.useContext);
	}

	@Test
	public void testDecisionEventInfoHierarchy() {
		TokenStream input = emptyTokenStream();
		SimulatorState state = simState(true);
		BitSet alts = new BitSet();
		alts.set(1);
		alts.set(2);

		AmbiguityInfo ambig = new AmbiguityInfo(0, state, alts, input, 0, 1);
		assertEquals(0, ambig.decision);
		assertSame(alts, ambig.getAmbiguousAlternatives());
		assertTrue(ambig.fullCtx);
		assertEquals(0, ambig.startIndex);
		assertEquals(1, ambig.stopIndex);
		assertSame(input, ambig.input);
		assertSame(state, ambig.state);

		ContextSensitivityInfo cs = new ContextSensitivityInfo(1, state, input, 0, 2);
		assertEquals(1, cs.decision);
		assertTrue(cs.fullCtx);

		ErrorInfo err = new ErrorInfo(2, state, input, 0, 3);
		assertEquals(2, err.decision);
		assertTrue(err.fullCtx); // useContext from state

		LookaheadEventInfo look = new LookaheadEventInfo(3, state, 1, input, 0, 4, false);
		assertEquals(1, look.predictedAlt);
		assertFalse(look.fullCtx);

		PredicateEvalInfo pe = new PredicateEvalInfo(state, 4, input, 0, 5, SemanticContext.NONE, true, 1);
		assertSame(SemanticContext.NONE, pe.semctx);
		assertTrue(pe.evalResult);
		assertEquals(1, pe.predictedAlt);

		DecisionEventInfo base = new DecisionEventInfo(9, null, input, 1, 2, false);
		assertEquals(9, base.decision);
		assertNull(base.state);
		assertFalse(base.fullCtx);
	}

	@Test
	public void testConflictInfo() {
		BitSet alts = new BitSet();
		alts.set(1);
		alts.set(2);
		ConflictInfo c1 = new ConflictInfo(alts, true);
		ConflictInfo c2 = new ConflictInfo(alts, true);
		ConflictInfo c3 = new ConflictInfo(alts, false);
		assertTrue(c1.isExact());
		assertSame(alts, c1.getConflictedAlts());
		assertEquals(c1, c2);
		assertEquals(c1.hashCode(), c2.hashCode());
		assertNotEquals(c1, c3);
		assertEquals(c1, c1);
		assertNotEquals(c1, null);
		assertNotEquals(c1, "x");
	}

	@Test
	public void testDecisionInfo() {
		DecisionInfo info = new DecisionInfo(5);
		assertEquals(5, info.decision);
		assertEquals(0, info.invocations);
		assertNotNull(info.contextSensitivities);
		assertNotNull(info.errors);
		assertNotNull(info.ambiguities);
		assertNotNull(info.predicateEvals);
		assertTrue(info.toString().contains("decision=5"));
	}

	@Test
	public void testATNDeserializationOptions() {
		ATNDeserializationOptions def = ATNDeserializationOptions.getDefaultOptions();
		assertTrue(def.isReadOnly());
		assertTrue(def.isVerifyATN());
		assertFalse(def.isGenerateRuleBypassTransitions());
		assertTrue(def.isOptimize());

		try {
			def.setVerifyATN(false);
			org.junit.Assert.fail();
		}
		catch (IllegalStateException expected) {
			// ok
		}

		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		assertFalse(opts.isReadOnly());
		opts.setVerifyATN(false);
		opts.setGenerateRuleBypassTransitions(true);
		opts.setOptimize(false);
		assertFalse(opts.isVerifyATN());
		assertTrue(opts.isGenerateRuleBypassTransitions());
		assertFalse(opts.isOptimize());

		ATNDeserializationOptions copy = new ATNDeserializationOptions(opts);
		assertFalse(copy.isVerifyATN());
		assertTrue(copy.isGenerateRuleBypassTransitions());
		assertFalse(copy.isOptimize());
		copy.makeReadOnly();
		assertTrue(copy.isReadOnly());
	}

	@Test
	public void testParseInfoAndProfilingSimulator() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		org.antlr.v4.runtime.ParserInterpreter parser = ATNTestHelpers.createParser(atn, 1);
		ProfilingATNSimulator profiler = new ProfilingATNSimulator(parser);
		parser.setInterpreter(profiler);

		ParseInfo pi = new ParseInfo(profiler);
		assertNotNull(pi.getDecisionInfo());
		assertEquals(atn.getNumberOfDecisions(), pi.getDecisionInfo().length);
		assertTrue(pi.getLLDecisions().isEmpty());
		assertEquals(0, pi.getTotalTimeInPrediction());
		assertEquals(0, pi.getTotalSLLLookaheadOps());
		assertEquals(0, pi.getTotalLLLookaheadOps());
		assertEquals(0, pi.getTotalSLLATNLookaheadOps());
		assertEquals(0, pi.getTotalLLATNLookaheadOps());
		assertEquals(0, pi.getTotalATNLookaheadOps());
		// DFA may already have start states
		assertTrue(pi.getDFASize() >= 0);
		if (atn.getNumberOfDecisions() > 0) {
			assertTrue(pi.getDFASize(0) >= 0);
		}

		assertNull(profiler.getCurrentState());
		assertSame(pi.getDecisionInfo(), profiler.getDecisionInfo());
		assertTrue(profiler.snapshotStartState());

		// exercise adaptivePredict via parse
		parser.getInputStream().seek(0);
		org.antlr.v4.runtime.ParserRuleContext tree = parser.parse(0);
		assertNotNull(tree);
		assertTrue(profiler.getDecisionInfo()[0].invocations >= 1);
	}

	@Test
	public void productionSimulatorDoesNotForceStartSnapshots() {
		assertFalse(new ParserATNSimulator(new ATN(ATNType.PARSER, 2)).snapshotStartState());
		ParserATNSimulator subclass = new ParserATNSimulator(new ATN(ATNType.PARSER, 2)) {
			@Override
			protected boolean snapshotStartState() {
				return true;
			}
		};
		assertTrue(subclass.snapshotStartState());
	}
}
