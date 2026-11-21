/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.dfa.AcceptStateInfo;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.MurmurHash;
import org.junit.Test;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Zero-reflection unit tests for {@link ConflictDetector}, {@link DFAState},
 * {@link ATNState#singleNonEpsilonTransition}, {@link ATNConfigSet},
 * {@link EpsilonClosure}, {@link ReachComputation}, and {@link ParserATNSimulator}'s
 * cursorless {@code execDFASllWarm}, {@code computeStartState}, and LL paths.
 */
public class TestConflictDetectorAndWarmSll {

	private static ATN buildPredicatedParserATN() {
		// Decision 0:
		//   alt 1: {pred(0,0)}? A
		//   alt 2: {pred(0,1)}? A
		//   alt 3: B C
		ATN atn = new ATN(ATNType.PARSER, 3);
		RuleStartState r0 = new RuleStartState(); r0.ruleIndex = 0;
		RuleStopState stop = new RuleStopState(); stop.ruleIndex = 0; r0.stopState = stop;
		BasicBlockStartState dec = new BasicBlockStartState(); dec.ruleIndex = 0;
		BlockEndState end = new BlockEndState(); end.ruleIndex = 0; dec.endState = end;
		BasicState a1 = new BasicState(); a1.ruleIndex = 0;
		BasicState a1p = new BasicState(); a1p.ruleIndex = 0;
		BasicState a2 = new BasicState(); a2.ruleIndex = 0;
		BasicState a2p = new BasicState(); a2p.ruleIndex = 0;
		BasicState a3 = new BasicState(); a3.ruleIndex = 0;
		BasicState a3b = new BasicState(); a3b.ruleIndex = 0;

		for (ATNState st : new ATNState[] { r0, stop, dec, end, a1, a1p, a2, a2p, a3, a3b }) {
			atn.addState(st);
		}
		atn.defineDecisionState(dec);
		r0.addTransition(new EpsilonTransition(dec));
		dec.addTransition(new EpsilonTransition(a1));
		dec.addTransition(new EpsilonTransition(a2));
		dec.addTransition(new EpsilonTransition(a3));
		a1.addTransition(new PredicateTransition(a1p, 0, 0, false));
		a1p.addTransition(new AtomTransition(end, 1));
		a2.addTransition(new PredicateTransition(a2p, 0, 1, false));
		a2p.addTransition(new AtomTransition(end, 1));
		a3.addTransition(new AtomTransition(a3b, 2));
		a3b.addTransition(new AtomTransition(end, 3));
		end.addTransition(new EpsilonTransition(stop));

		atn.ruleToStartState = new RuleStartState[] { r0 };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}

	@Test
	public void testConflictDetectorAllFastAndSlowPaths() {
		ATN atn = buildPredicatedParserATN();
		PredictionContext ctx1 = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, 10);
		PredictionContext ctx2 = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, 20);
		PredictionContext ctx1Dup = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, 10);
		PredictionContext ctxJoined = PredictionContext.join(ctx1, ctx2, PredictionContextCache.UNCACHED);
		RuleStopState stopState = atn.ruleToStopState[0];

		// 1. Unique alt / <= 1 config -> null
		ATNConfigSet single = new ATNConfigSet();
		BasicState s1 = new BasicState(); s1.stateNumber = 1;
		single.add(ATNConfig.create(s1, 1, ctx1));
		assertNull(ConflictDetector.detectOnce(single, null));

		// 2. All distinct states with same alt -> uniqueAlt != INVALID -> null
		ATNConfigSet distinct = new ATNConfigSet();
		for (int i = 0; i < 6; i++) {
			BasicState st = new BasicState(); st.stateNumber = i + 1;
			distinct.add(ATNConfig.create(st, 1, ctx1));
		}
		assertNull(ConflictDetector.detectOnce(distinct, PredictionContextCache.UNCACHED));

		// 3. Exact conflict across 2 states (also exercises null contextCache -> UNCACHED)
		BasicState s5 = new BasicState(); s5.stateNumber = 5;
		BasicState s3 = new BasicState(); s3.stateNumber = 3;
		ATNConfigSet exactConflict = new ATNConfigSet();
		exactConflict.add(ATNConfig.create(s5, 2, ctx1));
		exactConflict.add(ATNConfig.create(s3, 1, ctx1));
		exactConflict.add(ATNConfig.create(s5, 1, ctx1));
		exactConflict.add(ATNConfig.create(s3, 2, ctx1));
		ConflictInfo exactInfo = ConflictDetector.detectOnce(exactConflict, null);
		assertNotNull(exactInfo);
		assertTrue(exactInfo.isExact());
		assertTrue(exactInfo.getConflictedAlts().get(1));
		assertTrue(exactInfo.getConflictedAlts().get(2));

		// 4. Disjoint contexts -> NOT conflicted
		ATNConfigSet disjoint = new ATNConfigSet();
		disjoint.add(ATNConfig.create(s5, 1, ctx1));
		disjoint.add(ATNConfig.create(s5, 2, ctx2));
		assertNull(ConflictDetector.detectOnce(disjoint, PredictionContextCache.UNCACHED));

		// 5. Non-identical but structurally equal contexts under UNCACHED -> exact == true
		ATNConfigSet structEqual = new ATNConfigSet();
		structEqual.add(ATNConfig.create(s5, 1, ctx1));
		structEqual.add(ATNConfig.create(s5, 2, ctx1Dup));
		ConflictInfo structInfo = ConflictDetector.detectOnce(structEqual, PredictionContextCache.UNCACHED);
		assertNotNull(structInfo);
		assertTrue(structInfo.isExact());

		// 6. Strict subsumption (minAlt has joined {10,20}, alt 2 has only {10}) -> exact == false
		ATNConfigSet strictSubsumption = new ATNConfigSet();
		strictSubsumption.add(ATNConfig.create(s5, 1, ctxJoined));
		strictSubsumption.add(ATNConfig.create(s5, 2, ctx1));
		ConflictInfo subInfo = ConflictDetector.detectOnce(strictSubsumption, new PredictionContextCache());
		assertNotNull(subInfo);
		assertFalse(subInfo.isExact());

		// 7. Last state has different represented alt mask -> exact == false (regression fix!)
		ATNConfigSet lastStateMismatch = new ATNConfigSet();
		BasicState s9 = new BasicState(); s9.stateNumber = 9;
		lastStateMismatch.add(ATNConfig.create(s3, 1, ctx1));
		lastStateMismatch.add(ATNConfig.create(s3, 2, ctx1));
		lastStateMismatch.add(ATNConfig.create(s5, 1, ctx1));
		lastStateMismatch.add(ATNConfig.create(s5, 2, ctx1));
		lastStateMismatch.add(ATNConfig.create(s9, 1, ctx1)); // last state missing alt 2!
		ConflictInfo lastInfo = ConflictDetector.detectOnce(lastStateMismatch, PredictionContextCache.UNCACHED);
		assertNotNull(lastInfo);
		assertFalse(lastInfo.isExact());

		// 8. State lacks minAlt -> returns null
		ATNConfigSet missingMinAlt = new ATNConfigSet();
		missingMinAlt.add(ATNConfig.create(s3, 1, ctx1));
		missingMinAlt.add(ATNConfig.create(s3, 2, ctx1));
		missingMinAlt.add(ATNConfig.create(s9, 2, ctx1));
		assertNull(ConflictDetector.detectOnce(missingMinAlt, PredictionContextCache.UNCACHED));

		// 9. Stop state (stateKey == -1) + unmerged semantic contexts -> verifyExactSlow
		ATNConfigSet stopUnmerged = new ATNConfigSet();
		SemanticContext p0 = new SemanticContext.Predicate(0, 0, false);
		SemanticContext p1 = new SemanticContext.Predicate(0, 1, false);
		stopUnmerged.add(ATNConfig.create(stopState, 1, ctx1, p0));
		stopUnmerged.add(ATNConfig.create(stopState, 1, ctx2, p1));
		stopUnmerged.add(ATNConfig.create(stopState, 2, ctx1, p0));
		stopUnmerged.add(ATNConfig.create(stopState, 2, ctx2, p1));
		assertTrue(stopUnmerged.hasUnmerged());
		ConflictInfo stopExact = ConflictDetector.detectOnce(stopUnmerged, new PredictionContextCache());
		assertNotNull(stopExact);
		assertTrue(stopExact.isExact());

		// Stop state where alt 2 only covers ctx1 while minAlt covers join(ctx1, ctx2) -> verifyExactSlow returns false
		ATNConfigSet stopInexact = new ATNConfigSet();
		stopInexact.add(ATNConfig.create(stopState, 1, ctx1, p0));
		stopInexact.add(ATNConfig.create(stopState, 1, ctx2, p1));
		stopInexact.add(ATNConfig.create(stopState, 2, ctx1, p0));
		ConflictInfo stopInexactInfo = ConflictDetector.detectOnce(stopInexact, new PredictionContextCache());
		assertNotNull(stopInexactInfo);
		assertFalse(stopInexactInfo.isExact());

		// 10. Read-only ATNConfigSet conservatively verifies exactness
		ATNConfigSet roBuilder = new ATNConfigSet();
		roBuilder.add(ATNConfig.create(s5, 1, ctxJoined));
		roBuilder.add(ATNConfig.create(s5, 2, ctx1));
		ATNConfigSet roSet = roBuilder.clone(true);
		ConflictInfo roInfo = ConflictDetector.detectOnce(roSet, PredictionContextCache.UNCACHED);
		assertNotNull(roInfo);
		assertFalse(roInfo.isExact());

		// 11. alt >= 64 overflow path (exact true and exact false across multiple states,
		//     including hash-bucket linear-probe collisions inside verifyExactSlow!)
		ATNConfigSet overflowExact = new ATNConfigSet();
		for (int stNum = 1; stNum <= 12; stNum++) {
			BasicState st = new BasicState(); st.stateNumber = stNum;
			overflowExact.add(ATNConfig.create(st, 64, ctx1));
			overflowExact.add(ATNConfig.create(st, 70, ctx1));
		}
		ConflictInfo ovExact = ConflictDetector.detectOnce(overflowExact, new PredictionContextCache());
		assertNotNull(ovExact);
		assertTrue(ovExact.isExact());
		assertTrue(ovExact.getConflictedAlts().get(64));
		assertTrue(ovExact.getConflictedAlts().get(70));

		ATNConfigSet overflowMismatch = new ATNConfigSet();
		overflowMismatch.add(ATNConfig.create(s3, 1, ctx1));
		overflowMismatch.add(ATNConfig.create(s3, 70, ctx1));
		overflowMismatch.add(ATNConfig.create(s5, 1, ctx1));
		overflowMismatch.add(ATNConfig.create(s5, 71, ctx1));
		ConflictInfo ovMismatch = ConflictDetector.detectOnce(overflowMismatch, new PredictionContextCache());
		assertNotNull(ovMismatch);
		assertFalse(ovMismatch.isExact());

		// 12. Large config set forcing ensureTable table growth (> INITIAL_CAPACITY) + EMPTY_LOCAL minCtx
		ATNConfigSet largeSet = new ATNConfigSet();
		for (int i = 0; i < 40; i++) {
			BasicState st = new BasicState(); st.stateNumber = 100 + i;
			largeSet.add(ATNConfig.create(st, 1, PredictionContext.EMPTY_LOCAL));
			largeSet.add(ATNConfig.create(st, 2, ctx1));
		}
		ConflictInfo largeInfo = ConflictDetector.detectOnce(largeSet, new PredictionContextCache());
		assertNotNull(largeInfo);
		assertFalse(largeInfo.isExact());

		// 13. OrderedATNConfigSet (subclass retaining duplicate (state, alt) entries outside unmerged)
		//     must still verify exactness via verifyExactSlow!
		OrderedATNConfigSet ordExact = new OrderedATNConfigSet();
		ordExact.add(ATNConfig.create(s5, 1, ctx1));
		ordExact.add(ATNConfig.create(s5, 1, ctx2));
		ordExact.add(ATNConfig.create(s5, 2, ctx1));
		ordExact.add(ATNConfig.create(s5, 2, ctx2));
		ConflictInfo ordInfo = ConflictDetector.detectOnce(ordExact, new PredictionContextCache());
		assertNotNull(ordInfo);
		assertTrue("OrderedATNConfigSet with matching joined contexts must be exact", ordInfo.isExact());

		// 14. Writable ATNConfigSet with stale uniqueAlt after remove(int) leaving only 1 alt -> null!
		ATNConfigSet removedSet = new ATNConfigSet();
		removedSet.add(ATNConfig.create(s3, 1, ctx1));
		removedSet.add(ATNConfig.create(s5, 1, ctx1));
		removedSet.add(ATNConfig.create(s5, 2, ctx1));
		removedSet.remove(2); // removes alt 2, leaving only alt 1 across 2 states with uniqueAlt == INVALID
		assertNull("Single remaining alt after remove() must NOT report conflict",
			ConflictDetector.detectOnce(removedSet, PredictionContextCache.UNCACHED));
	}

	@Test
	public void testATNStateSingleNonEpsilonAndConfigSetAddTransformedAndDFAState() {
		// 1. ATNState.singleNonEpsilonTransition freeze population & invalidation across all mutation sites
		BasicState s = new BasicState(); s.stateNumber = 1;
		BasicState t1 = new BasicState(); t1.stateNumber = 2;
		BasicState t2 = new BasicState(); t2.stateNumber = 3;
		assertNull(s.singleNonEpsilonTransition);
		AtomTransition a1 = new AtomTransition(t1, 1);
		s.addTransition(a1);
		s.freezeOptimizedTransitions();
		assertSame(a1, s.singleNonEpsilonTransition);
		// Adding a second transition invalidates snapshot; re-freezing leaves null (2 edges)
		s.addTransition(new AtomTransition(t2, 2));
		assertNull(s.singleNonEpsilonTransition);
		s.freezeOptimizedTransitions();
		assertNull(s.singleNonEpsilonTransition);
		// Removing second leaves 1 non-epsilon edge after freeze
		s.removeTransition(1);
		assertNull(s.singleNonEpsilonTransition);
		s.freezeOptimizedTransitions();
		assertSame(a1, s.singleNonEpsilonTransition);
		// Replacing with epsilon invalidates and freezes to noNonEpsilonOptimizedTransitions
		s.setTransition(0, new EpsilonTransition(t1));
		assertNull(s.singleNonEpsilonTransition);
		s.freezeOptimizedTransitions();
		assertNull(s.singleNonEpsilonTransition);
		assertTrue(s.noNonEpsilonOptimizedTransitions);
		// Replacing back with non-epsilon restores singleNonEpsilonTransition on freeze
		AtomTransition a2 = new AtomTransition(t2, 2);
		s.setTransition(0, a2);
		s.freezeOptimizedTransitions();
		assertSame(a2, s.singleNonEpsilonTransition);
		// Optimized transition mutations invalidate snapshot too
		s.addOptimizedTransition(a1);
		assertNull(s.singleNonEpsilonTransition);
		s.addOptimizedTransition(a2);
		s.setOptimizedTransition(0, a2);
		assertNull(s.singleNonEpsilonTransition);
		s.removeOptimizedTransition(1);
		s.freezeOptimizedTransitions();
		assertSame(a2, s.singleNonEpsilonTransition);
		s.removeOptimizedTransition(0);
		assertNull(s.singleNonEpsilonTransition);

		// 2. ATNConfigSet.addTransformed (positive, negative stateNumber, non-aliasing, lazy clone,
		//    OrderedATNConfigSet subclass fallback, unmerged collision/promotion, and optimizeConfigs 2-slot cache)
		ATNConfigSet set = new ATNConfigSet();
		PredictionContext ctx1 = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, 10);
		PredictionContext ctx2 = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, 20);
		ATNConfig src = ATNConfig.create(s, 1, ctx1);
		assertTrue(set.addTransformed(t1, src, ctx1, null));
		// Negative stateNumber exercises hash fallback cleanly
		BasicState negState = new BasicState(); negState.stateNumber = -1;
		assertTrue(set.addTransformed(negState, src, ctx1, null));
		// Merging into existing (t1, alt=1) joins context without aliasing src
		assertTrue(set.addTransformed(t1, src, ctx2, new PredictionContextCache()));
		assertSame(ctx1, src.getContext()); // src was not mutated
		assertEquals(2, set.size());

		// OrderedATNConfigSet subclass fallback in addTransformed (same context & changed context)
		OrderedATNConfigSet ordered = new OrderedATNConfigSet();
		assertTrue(ordered.addTransformed(t1, src, ctx1, null));
		assertTrue(ordered.addTransformed(t2, src, ctx2, null));
		assertEquals(2, ordered.size());

		// Unmerged list paths in addTransformed: insert distinct semCtx -> unmerged.add, then match & promote!
		ATNConfigSet unmergedSet = new ATNConfigSet();
		SemanticContext sp1 = new SemanticContext.Predicate(0, 1, false);
		SemanticContext sp2 = new SemanticContext.Predicate(0, 2, false);
		SemanticContext sp3 = new SemanticContext.Predicate(0, 3, false);
		ATNConfig baseP1 = ATNConfig.create(t1, 1, ctx1, sp1);
		ATNConfig baseP2 = ATNConfig.create(t1, 1, ctx1, sp2);
		ATNConfig baseP3 = ATNConfig.create(t1, 1, ctx1, sp3);
		unmergedSet.add(baseP1);
		unmergedSet.add(baseP2); // goes to unmerged
		// addTransformed with sp3 -> scans unmerged, misses, adds to unmerged
		assertTrue(unmergedSet.addTransformed(t1, baseP3, ctx1, null));
		// addTransformed matching sp2 with ctx2 -> merges inside unmerged & returns true
		assertTrue(unmergedSet.addTransformed(t1, baseP2, ctx2, new PredictionContextCache()));
		// addTransformed matching sp2 again with identical joined context -> returns false
		PredictionContext joined12 = PredictionContext.join(ctx1, ctx2, PredictionContextCache.UNCACHED);
		assertFalse(unmergedSet.addTransformed(t1, baseP2, joined12, new PredictionContextCache()));

		// optimizeConfigs 2-slot cache hit on lastRaw2 (alternating ctx1, ctx2, ctx1, ctx2)
		ATN optAtn = buildPredicatedParserATN();
		ATNConfigSet optSet = new ATNConfigSet();
		optSet.add(ATNConfig.create(s, 1, ctx1));
		optSet.add(ATNConfig.create(t1, 1, ctx2));
		optSet.add(ATNConfig.create(t2, 1, ctx1));
		optSet.add(ATNConfig.create(negState, 1, ctx2));
		optSet.optimizeConfigs(new ParserATNSimulator(optAtn));

		// Lazy cachedHashCode clone preserves equivalence and invalidates on outerContextDepth bump
		ATNConfigSet cloned = set.clone(false);
		assertEquals(set.hashCode(), cloned.hashCode());
		assertEquals(set, cloned);

		ATNConfigSet set1 = new ATNConfigSet();
		ATNConfigSet set2 = new ATNConfigSet();
		ATNConfig cLow = ATNConfig.create(s, 1, ctx1);
		ATNConfig cHigh = ATNConfig.create(s, 1, ctx1);
		cHigh.setOuterContextDepth(1);
		set1.add(cLow);
		int hashBefore = set1.hashCode();
		set1.add(cHigh);
		set2.add(cHigh);
		assertEquals(set2.hashCode(), set1.hashCode());
		assertTrue(set1.equals(set2));
		assertTrue(hashBefore != set1.hashCode() || set1.equals(set2));

		// ATNConfig subclass overriding hasPassedThroughNonGreedyDecision() + cachedHashCode fast reject
		ATNConfig subCfg = new ATNConfig(s, 1, ctx1) {
			@Override public boolean hasPassedThroughNonGreedyDecision() { return true; }
		};
		int expectedHash = MurmurHash.initialize(7);
		expectedHash = MurmurHash.update(expectedHash, subCfg.getState().stateNumber);
		expectedHash = MurmurHash.update(expectedHash, subCfg.getAlt());
		expectedHash = MurmurHash.update(expectedHash, subCfg.getReachesIntoOuterContext() ? 1 : 0);
		expectedHash = MurmurHash.update(expectedHash, subCfg.getContext());
		expectedHash = MurmurHash.update(expectedHash, subCfg.getSemanticContext());
		expectedHash = MurmurHash.update(expectedHash, 1);
		expectedHash = MurmurHash.update(expectedHash, subCfg.getLexerActionExecutor());
		expectedHash = MurmurHash.finish(expectedHash, 7);
		if (expectedHash == 0) expectedHash = 1;
		assertEquals(expectedHash, subCfg.hashCode());

		ATNConfig diffCfg = new ATNConfig(cLow, t1, ctx2) {};
		assertFalse(cLow.equals(diffCfg));

		// 3. DFAState.getUnconflictedPrediction(), COW asciiEdges, contextSymbols, ArrayEdgeMap
		ATN lexAtn = ATNTestHelpers.buildLexerMatchA();
		DFA lexDfa = lexAtn.modeToDFA[0];
		// Mutable configs: getUnconflictedPrediction stays INVALID_ALT_NUMBER for safety
		DFAState dsMut = new DFAState(lexDfa, new ATNConfigSet());
		dsMut.setAcceptState(new AcceptStateInfo(3));
		assertEquals(ATN.INVALID_ALT_NUMBER, dsMut.getUnconflictedPrediction());

		// Read-only unconflicted configs: caches prediction alt
		DFAState ds = new DFAState(lexDfa, new ATNConfigSet().clone(true));
		assertEquals(ATN.INVALID_ALT_NUMBER, ds.getUnconflictedPrediction());
		ds.setAcceptState(new AcceptStateInfo(3));
		assertEquals(3, ds.getUnconflictedPrediction());

		// Read-only conflicted configs: returns INVALID_ALT_NUMBER
		ATNConfigSet confSet = new ATNConfigSet();
		confSet.setConflictInfo(new ConflictInfo(new BitSet(), true));
		DFAState dsConf = new DFAState(lexDfa, confSet.clone(true));
		dsConf.setAcceptState(new AcceptStateInfo(3));
		assertEquals(ATN.INVALID_ALT_NUMBER, dsConf.getUnconflictedPrediction());

		DFAState dst = new DFAState(lexDfa, new ATNConfigSet());
		ds.setTarget('x', dst);
		ds.setTarget('x', dst);
		assertSame(dst, ds.getLexerTarget('x'));
		assertNull(ds.getLexerTarget('y'));

		ATN pAtn = buildPredicatedParserATN();
		DFAState pState = new DFAState(pAtn.decisionToDFA[0], new ATNConfigSet());
		pState.setContextSensitive(pAtn);
		pState.setContextSymbol(1);
		assertTrue(pState.isContextSensitive());
		assertTrue(pState.isContextSymbol(1));
		assertTrue(pState.contextSymbolAfterEdge(1));
		assertFalse(pState.contextSymbolAfterEdge(-99));
		for (int sym = 0; sym <= 3; sym++) {
			pState.setTarget(sym, pState);
		}
		assertSame(pState, pState.getTarget(1));
	}

	@Test
	public void testEpsilonClosureAndReachComputationDirectAndSubclassHooks() {
		ATN atn = new ATN(ATNType.PARSER, 4);
		RuleStartState r0 = new RuleStartState(); r0.ruleIndex = 0;
		RuleStopState s0 = new RuleStopState(); s0.ruleIndex = 0; r0.stopState = s0;
		BasicState b1 = new BasicState(); b1.ruleIndex = 0;
		BasicState b2 = new BasicState(); b2.ruleIndex = 0;
		BasicState b3 = new BasicState(); b3.ruleIndex = 0;
		BasicState b4 = new BasicState(); b4.ruleIndex = 0;
		BasicState b5 = new BasicState(); b5.ruleIndex = 0;
		for (ATNState st : new ATNState[] { r0, s0, b1, b2, b3, b4, b5 }) atn.addState(st);
		atn.ruleToStartState = new RuleStartState[] { r0 };
		atn.ruleToStopState = new RuleStopState[] { s0 };

		r0.addTransition(new ActionTransition(b1, 0, 0, false));
		b1.addTransition(new PredicateTransition(b2, 0, 0, true));
		b2.addTransition(new PredicateTransition(b3, 0, 1, false));
		b3.addTransition(new PrecedencePredicateTransition(b4, 2));
		b4.addTransition(new EpsilonTransition(b5, 1));
		b5.addTransition(new AtomTransition(s0, 1));
		atn.clearDFA();

		ParserATNSimulator sim = new ParserATNSimulator(atn);
		EpsilonClosure ec = new EpsilonClosure(sim);
		ATNConfigSet configs = new ATNConfigSet();
		ATNConfigSet intermediate = new ATNConfigSet();
		Set<ATNConfig> busy = new HashSet<ATNConfig>();
		PredictionContext ctx = new SingletonPredictionContext(PredictionContext.EMPTY_LOCAL, s0.stateNumber);

		ec.closeOne(ATNConfig.create(r0, 1, ctx), configs, intermediate, busy, true, true, null, 0, false);
		assertFalse(configs.isEmpty());
		ec.closeOne(ATNConfig.create(r0, 1, ctx), configs, null, new HashSet<ATNConfig>(), true, false, PredictionContextCache.UNCACHED, 1, true);
		ec.closeOne(ATNConfig.create(s0, 1, PredictionContext.EMPTY_LOCAL), configs, null, new HashSet<ATNConfig>(), false, true, PredictionContextCache.UNCACHED, 0, true);
		ec.closeOne(ATNConfig.create(s0, 1, PredictionContext.EMPTY_FULL), configs, null, new HashSet<ATNConfig>(), false, false, PredictionContextCache.UNCACHED, 0, false);

		// Subclass overriding closure() & getReachableTarget() exercises !direct delegation
		// plus StarLoopEntryState precedence suppression, RuleTransition optimizedTailCall / configAtRuleStop,
		// ctx-dependent PredicateTransition at depth 0, treatEofAsEpsilon AtomTransition, and ReachComputation noNonEpsilon fast skip!
		ATN extraAtn = new ATN(ATNType.PARSER, 4);
		RuleStartState er0 = new RuleStartState(); er0.ruleIndex = 0; er0.isPrecedenceRule = true;
		RuleStopState es0 = new RuleStopState(); es0.ruleIndex = 0; er0.stopState = es0;
		StarLoopEntryState sle = new StarLoopEntryState(); sle.ruleIndex = 0; sle.precedenceRuleDecision = true;
		BasicState sleBody = new BasicState(); sleBody.ruleIndex = 0;
		BasicState sleExit = new BasicState(); sleExit.ruleIndex = 0;
		BasicState callTail = new BasicState(); callTail.ruleIndex = 0;
		BasicState callStop = new BasicState(); callStop.ruleIndex = 0;
		BasicState ctxPred = new BasicState(); ctxPred.ruleIndex = 0;
		BasicState eofState = new BasicState(); eofState.ruleIndex = 0;
		BasicState epsOnly = new BasicState(); epsOnly.ruleIndex = 0;
		for (ATNState st : new ATNState[] { er0, es0, sle, sleBody, sleExit, callTail, callStop, ctxPred, eofState, epsOnly }) {
			extraAtn.addState(st);
		}
		extraAtn.ruleToStartState = new RuleStartState[] { er0 };
		extraAtn.ruleToStopState = new RuleStopState[] { es0 };
		sle.addTransition(new EpsilonTransition(sleBody));
		sle.addTransition(new EpsilonTransition(sleExit));
		sleBody.addTransition(new AtomTransition(es0, 1));
		sleExit.addTransition(new AtomTransition(es0, 2));
		RuleTransition rtTail = new RuleTransition(er0, 0, 0, sleExit);
		rtTail.optimizedTailCall = true;
		callTail.addTransition(rtTail);
		RuleTransition rtStop = new RuleTransition(er0, 0, 0, es0);
		callStop.addTransition(rtStop);
		ctxPred.addTransition(new PrecedencePredicateTransition(sleExit, 2));
		eofState.addTransition(new AtomTransition(es0, Token.EOF));
		epsOnly.addTransition(new EpsilonTransition(es0));
		epsOnly.freezeOptimizedTransitions();
		assertTrue(epsOnly.noNonEpsilonOptimizedTransitions);
		extraAtn.clearDFA();

		ParserATNSimulator extraSim = new ParserATNSimulator(extraAtn);
		EpsilonClosure extraEc = new EpsilonClosure(extraSim);
		// Precedence suppression on StarLoopEntryState (lines 251-252): !suppressed with empty context
		ATNConfig notSuppressed = ATNConfig.create(sle, 1, PredictionContext.EMPTY_LOCAL);
		extraEc.closeOne(notSuppressed, new ATNConfigSet(), null, new HashSet<ATNConfig>(), true, false, PredictionContextCache.UNCACHED, 0, false);
		// configAtRuleStop (lines 304-310)
		extraEc.closeOne(ATNConfig.create(callStop, 1, PredictionContext.EMPTY_LOCAL), new ATNConfigSet(), null, new HashSet<ATNConfig>(), true, false, PredictionContextCache.UNCACHED, 0, false);
		// optimizedTailCall with tailCallPreservesSll == false & EMPTY_LOCAL (lines 312-319)
		extraSim.tail_call_preserves_sll = false;
		extraEc.closeOne(ATNConfig.create(callTail, 1, PredictionContext.EMPTY_LOCAL), new ATNConfigSet(), null, new HashSet<ATNConfig>(), true, false, PredictionContextCache.UNCACHED, 0, false);
		// PrecedencePredicateTransition at depth 0 with collectPredicates == true (lines 472-474)
		extraEc.closeOne(ATNConfig.create(ctxPred, 1, PredictionContext.EMPTY_LOCAL), new ATNConfigSet(), null, new HashSet<ATNConfig>(), true, true, PredictionContextCache.UNCACHED, 0, false);
		// AtomTransition(EOF) with treatEofAsEpsilon == true (lines 485-486)
		extraEc.closeOne(ATNConfig.create(eofState, 1, PredictionContext.EMPTY_LOCAL), new ATNConfigSet(), null, new HashSet<ATNConfig>(), true, false, PredictionContextCache.UNCACHED, 0, true);
		// ReachComputation skipping frozen noNonEpsilonOptimizedTransitions state (line 163)
		ATNConfigSet epsSet = new ATNConfigSet();
		epsSet.add(ATNConfig.create(epsOnly, 1, PredictionContext.EMPTY_LOCAL));
		BasicBlockStartState extraDec = new BasicBlockStartState(); extraDec.ruleIndex = 0;
		extraAtn.addState(extraDec);
		extraAtn.defineDecisionState(extraDec);
		DFA extraDfa = extraAtn.decisionToDFA[0];
		assertNull(extraSim.computeReachSet(extraDfa,
			new SimulatorState(ParserRuleContext.emptyContext(), new DFAState(extraDfa, epsSet), false, ParserRuleContext.emptyContext()),
			1, PredictionContextCache.UNCACHED));

		final int[] hookCalls = new int[2];
		ATN pAtn = buildPredicatedParserATN();
		ParserATNSimulator subSim = new ParserATNSimulator(pAtn) {
			@Override
			protected void closure(ATNConfig config, ATNConfigSet c, ATNConfigSet inter,
								   Set<ATNConfig> cb, boolean cp, boolean hmc,
								   PredictionContextCache cache, int depth, boolean eof) {
				hookCalls[0]++;
				super.closure(config, c, inter, cb, cp, hmc, cache, depth, eof);
			}
			@Override
			protected ATNState getReachableTarget(ATNConfig source, Transition trans, int ttype) {
				hookCalls[1]++;
				return super.getReachableTarget(source, trans, ttype);
			}
		};
		ATNConfigSet startSet = new ATNConfigSet();
		startSet.add(ATNConfig.create(pAtn.states.get(0), 1, PredictionContext.EMPTY_LOCAL));
		ATNConfigSet closed = new ATNConfigSet();
		subSim.closure(startSet, closed, true, true, PredictionContextCache.UNCACHED, false);
		DFA pDfa = pAtn.decisionToDFA[0];
		SimulatorState simState = new SimulatorState(ParserRuleContext.emptyContext(), new DFAState(pDfa, closed), false, ParserRuleContext.emptyContext());
		SimulatorState reached = subSim.computeReachSet(pDfa, simState, 2, PredictionContextCache.UNCACHED);
		assertNotNull(reached);
		assertTrue(hookCalls[0] > 0);
		assertTrue(hookCalls[1] > 0);
	}

	@Test
	public void testWarmSllCursorlessPredicatesRepeatedEofAndLLModes() {
		final ATN atn = buildPredicatedParserATN();
		final boolean[] preds = new boolean[] { false, true };
		ListTokenSource src = new ListTokenSource(Arrays.<Token>asList(
			new CommonToken(1, "A"), new CommonToken(2, "B"), new CommonToken(3, "C"), new CommonToken(Token.EOF, "<EOF>")
		));
		CommonTokenStream ts = new CommonTokenStream(src);
		Parser parser = new Parser(ts) {
			{ _interp = new ParserATNSimulator(this, atn); }
			@Override public String[] getTokenNames() { return new String[] { "<INVALID>", "A", "B", "C" }; }
			@Override public String[] getRuleNames() { return new String[] { "r" }; }
			@Override public String getGrammarFileName() { return "P.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public boolean sempred(RuleContext ctx, int ruleIndex, int predIndex) {
				return preds[predIndex];
			}
		};
		parser.removeErrorListeners();
		ParserATNSimulator sim = parser.getInterpreter();
		sim.setPredictionMode(PredictionMode.SLL);

		// Cold build + warm SLL predicate evaluation (alt 2, alt 1, both true -> 1, both false -> NVA)
		assertEquals(2, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		assertEquals(2, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		assertEquals(2, sim.adaptivePredict(ts, 0, null)); // null outerContext normalization
		preds[0] = true; preds[1] = false;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		preds[0] = true; preds[1] = true;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		preds[0] = false; preds[1] = false;
		try {
			sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext());
			fail("Expected NoViableAltException");
		}
		catch (RuntimeException expected) {
		}

		// Multi-token SLL warm path ('B' 'C') -> cursorless forward scan leaves ts.index() restored
		ts.seek(1);
		assertEquals(3, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		assertEquals(1, ts.index());
		assertEquals(3, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		assertEquals(1, ts.index());

		// Multi-token SLL warm path skipping an off-channel token ('B' [HIDDEN] 'C')
		CommonToken hiddenTok = new CommonToken(1, "WS");
		hiddenTok.setChannel(Token.HIDDEN_CHANNEL);
		CommonTokenStream hiddenTs = new CommonTokenStream(new ListTokenSource(Arrays.<Token>asList(
			new CommonToken(2, "B"), hiddenTok, new CommonToken(3, "C"), new CommonToken(Token.EOF, "<EOF>")
		)));
		hiddenTs.fill();
		assertEquals(3, sim.adaptivePredict(hiddenTs, 0, ParserRuleContext.emptyContext()));
		assertEquals(0, hiddenTs.index());

		// LL and LL_EXACT_AMBIG_DETECTION with predicates and conflicts
		ts.seek(0);
		preds[0] = true; preds[1] = false;
		sim.setPredictionMode(PredictionMode.LL);
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		preds[0] = true; preds[1] = true;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		sim.setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		preds[0] = true; preds[1] = false;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		preds[0] = false; preds[1] = false;
		try {
			sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext());
			fail();
		}
		catch (RuntimeException expected) {
		}

		// Warm ERROR state, s0.isAcceptState(), and non-accepting repeated EOF in execDFASllWarm
		DFA dfa = atn.decisionToDFA[0];
		DFAState errS0 = new DFAState(dfa, new ATNConfigSet());
		errS0.stateNumber = 0;
		errS0.setTarget(1, ATNSimulator.ERROR);
		DFAState eofLoop = new DFAState(dfa, new ATNConfigSet());
		eofLoop.stateNumber = 699;
		eofLoop.setTarget(Token.EOF, eofLoop);
		errS0.setTarget(Token.EOF, eofLoop);
		dfa.s0.set(errS0);
		sim.setPredictionMode(PredictionMode.SLL);
		ts.seek(0);
		try { sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()); fail(); } catch (RuntimeException expected) {}
		ts.seek(3); // EOF -> repeatedEof must throw NVA, NOT NPE
		try {
			sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext());
			fail();
		}
		catch (RuntimeException expected) {
			assertFalse(expected instanceof NullPointerException);
		}
		// s0.isAcceptState() immediate accept
		errS0.setAcceptState(new AcceptStateInfo(3));
		ts.seek(0);
		assertEquals(3, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));

		// Predicated conflict + non-conflict states with reportAmbiguities
		DFAState freshS0 = new DFAState(dfa, new ATNConfigSet());
		freshS0.stateNumber = 0;
		DFAState sMid = new DFAState(dfa, new ATNConfigSet());
		sMid.stateNumber = 701;
		DFAState sPredConflict = new DFAState(dfa, new ATNConfigSet());
		sPredConflict.stateNumber = 700;
		sPredConflict.setAcceptState(new AcceptStateInfo(1));
		sPredConflict.configs.setConflictInfo(new ConflictInfo(new BitSet(), false));
		sPredConflict.predicates = new DFAState.PredPrediction[] {
			new DFAState.PredPrediction(SemanticContext.NONE, 1),
			new DFAState.PredPrediction(new SemanticContext.Predicate(0, 0, false), 1),
			new DFAState.PredPrediction(new SemanticContext.Predicate(0, 1, false), 2)
		};
		sMid.setTarget(2, sPredConflict);
		freshS0.setTarget(1, sMid);
		dfa.s0.set(freshS0);
		ts.seek(0);
		preds[0] = true; preds[1] = false;
		sim.setPredictionMode(PredictionMode.LL);
		sim.reportAmbiguities = false;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		preds[0] = true; preds[1] = true;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		sim.reportAmbiguities = true;
		preds[0] = true; preds[1] = false;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		preds[0] = true; preds[1] = true;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));

		DFAState freshS0b = new DFAState(dfa, new ATNConfigSet());
		freshS0b.stateNumber = 0;
		DFAState sMid2 = new DFAState(dfa, new ATNConfigSet());
		sMid2.stateNumber = 703;
		DFAState sPredNoConf = new DFAState(dfa, new ATNConfigSet());
		sPredNoConf.stateNumber = 702;
		sPredNoConf.setAcceptState(new AcceptStateInfo(1));
		sPredNoConf.predicates = new DFAState.PredPrediction[] {
			new DFAState.PredPrediction(new SemanticContext.Predicate(0, 0, false), 1),
			new DFAState.PredPrediction(new SemanticContext.Predicate(0, 1, false), 2)
		};
		sMid2.setTarget(2, sPredNoConf);
		freshS0b.setTarget(1, sMid2);
		dfa.s0.set(freshS0b);
		sim.setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
		preds[0] = true; preds[1] = false;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		preds[0] = true; preds[1] = true;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		preds[0] = false; preds[1] = false;
		try { sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()); fail(); } catch (RuntimeException expected) {}
		sim.reportAmbiguities = false;

		// Cold execATN predicated SLL accept (valid alt & INVALID_ALT_NUMBER)
		atn.clearDFA();
		sim.setPredictionMode(PredictionMode.SLL);
		preds[0] = true; preds[1] = false;
		assertEquals(1, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));
		atn.clearDFA();
		preds[0] = false; preds[1] = false;
		try { sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()); fail(); } catch (RuntimeException expected) {}

		// Warm SLL fast accept on read-only s0 & read-only 1-token target + execDFASllWarmSlow kind=1 & kind=2!
		DFAState roS0 = new DFAState(dfa, new ATNConfigSet().clone(true));
		roS0.stateNumber = 0;
		roS0.setAcceptState(new AcceptStateInfo(2));
		dfa.s0.set(roS0);
		ts.seek(0);
		assertEquals(2, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));

		// 1-token unconflicted read-only target accept + non-CommonTokenStream fallback (kind == 1)
		DFAState s0One = new DFAState(dfa, new ATNConfigSet().clone(true));
		s0One.stateNumber = 0;
		DFAState roTarget = new DFAState(dfa, new ATNConfigSet().clone(true));
		roTarget.stateNumber = 1;
		roTarget.setAcceptState(new AcceptStateInfo(3));
		s0One.setTarget(1, roTarget);
		dfa.s0.set(s0One);
		ts.seek(0);
		assertEquals(3, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext()));

		// 1-token mutable accept target (line 638) + non-CommonTokenStream missing edge (line 690)
		DFAState s0MutTarget = new DFAState(dfa, new ATNConfigSet().clone(true));
		s0MutTarget.stateNumber = 0;
		DFAState mutAccept = new DFAState(dfa, new ATNConfigSet());
		mutAccept.stateNumber = 10;
		mutAccept.setAcceptState(new AcceptStateInfo(3));
		s0MutTarget.setTarget(1, mutAccept);
		dfa.s0.set(s0MutTarget);
		ts.seek(0);
		assertEquals(3, sim.adaptivePredict(ts, 0, ParserRuleContext.emptyContext())); // hits line 638!

		// Non-CommonTokenStream on missing edge (line 690 -> execDFASllWarmSlow kind == 1)
		org.antlr.v4.runtime.BufferedTokenStream btsMiss = new org.antlr.v4.runtime.BufferedTokenStream(
			new ListTokenSource(Arrays.<Token>asList(new CommonToken(2, "B"), new CommonToken(3, "C"), new CommonToken(Token.EOF, "<EOF>"))));
		try {
			sim.adaptivePredict(btsMiss, 0, ParserRuleContext.emptyContext());
		}
		catch (RuntimeException expected) {
		}

		// Real CommonTokenStream whose 2nd token is a non-CommonToken hits lines 653-656 -> kind == 1
		CommonTokenStream nonCtStream = new CommonTokenStream(new ListTokenSource(Arrays.<Token>asList(
			new CommonToken(1, "A"),
			new CommonToken(2, "B") {}, // anonymous subclass -> getClass() != CommonToken.class!
			new CommonToken(Token.EOF, "<EOF>")
		)));
		DFAState sMidWarm = new DFAState(dfa, new ATNConfigSet().clone(true));
		sMidWarm.stateNumber = 2;
		sMidWarm.setTarget(2, roTarget);
		s0One.setTarget(1, sMidWarm);
		dfa.s0.set(s0One);
		assertEquals(3, sim.adaptivePredict(nonCtStream, 0, ParserRuleContext.emptyContext()));

		// Repeated EOF in multi-step warm SLL hitting execDFASllWarmSlow kind == 2 (line 663 & line 744)
		CommonTokenStream eofOnly = new CommonTokenStream(new ListTokenSource(Arrays.<Token>asList(
			new CommonToken(1, "A"), new CommonToken(Token.EOF, "<EOF>")
		)));
		DFAState eofNonAcc = new DFAState(dfa, new ATNConfigSet().clone(true));
		eofNonAcc.stateNumber = 50;
		eofNonAcc.setTarget(Token.EOF, eofNonAcc);
		sMidWarm.setTarget(Token.EOF, eofNonAcc);
		eofOnly.LT(1); // ensure lazyInit() so index() == 0
		try {
			sim.adaptivePredict(eofOnly, 0, ParserRuleContext.emptyContext());
			fail("Expected NoViableAltException on repeated EOF");
		}
		catch (RuntimeException expected) {
		}
	}

	@Test
	public void testComputeStartStateDepthAndATNDeserializerReadonlySets() {
		// Verify O(D) computeStartState handles deep outer contexts (both SLL and fullCtx)
		ATN atn = buildPredicatedParserATN();
		BasicState caller = new BasicState(); caller.ruleIndex = 0;
		BasicState follow = new BasicState(); follow.ruleIndex = 0;
		atn.addState(caller);
		atn.addState(follow);
		caller.addTransition(new RuleTransition(atn.ruleToStartState[0], 0, 0, follow));
		follow.addTransition(new AtomTransition(atn.ruleToStopState[0], 1));

		ParserATNSimulator sim = new ParserATNSimulator(atn);
		ParserRuleContext ctx = ParserRuleContext.emptyContext();
		for (int i = 0; i < 6; i++) {
			ctx = new ParserRuleContext(ctx, caller.stateNumber);
		}
		DFA dfa = atn.decisionToDFA[0];
		SimulatorState startFull = sim.computeStartState(dfa, ctx, true);
		assertNotNull(startFull);
		assertTrue(startFull.useContext);
		SimulatorState startSll = sim.computeStartState(dfa, ctx, false);
		assertNotNull(startSll);
		assertFalse(startSll.useContext);

		// ATNDeserializer sets deserialized set transitions read-only (enabling fastBits)
		ATN multiSetAtn = new ATN(ATNType.PARSER, 20);
		RuleStartState r0 = new RuleStartState(); r0.ruleIndex = 0;
		RuleStopState s0 = new RuleStopState(); s0.ruleIndex = 0; r0.stopState = s0;
		multiSetAtn.addState(r0); multiSetAtn.addState(s0);
		IntervalSet set = new IntervalSet();
		set.add(1); set.add(3); set.add(5); set.add(7);
		r0.addTransition(new SetTransition(s0, set));
		multiSetAtn.ruleToStartState = new RuleStartState[] { r0 };
		multiSetAtn.ruleToStopState = new RuleStopState[] { s0 };
		char[] serialized = ATNSerializer.getSerializedAsChars(multiSetAtn, Arrays.asList("r"));
		ATN roundTrip = new ATNDeserializer().deserialize(serialized);
		Transition rtTrans = roundTrip.ruleToStartState[0].transition(0);
		assertTrue(rtTrans instanceof SetTransition);
		assertTrue(((SetTransition) rtTrans).set.isReadonly());
		assertTrue(((SetTransition) rtTrans).set.contains(5));
		assertFalse(((SetTransition) rtTrans).set.contains(4));
	}
}
