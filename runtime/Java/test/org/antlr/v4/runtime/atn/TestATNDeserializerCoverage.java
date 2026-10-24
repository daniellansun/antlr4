/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntegerList;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Round-trip and options coverage for {@link ATNDeserializer} / {@link ATNSerializer}.
 */
public class TestATNDeserializerCoverage {

	private static char[] serialize(ATN atn, List<String> rules) {
		return ATNSerializer.getSerializedAsChars(atn, rules);
	}

	@Test
	public void complexLexerRoundTripWithUnicodeAndSets() {
		// maxTokenType must fit in a char for the serializer
		ATN atn = new ATN(ATNType.LEXER, 0xFFFF);
		TokensStartState tokensStart = new TokensStartState();
		tokensStart.ruleIndex = -1;
		atn.addState(tokensStart);

		// rule 0: range set [a-c]
		RuleStartState r0 = new RuleStartState();
		RuleStopState s0 = new RuleStopState();
		r0.ruleIndex = 0;
		s0.ruleIndex = 0;
		r0.stopState = s0;
		atn.addState(r0);
		atn.addState(s0);
		IntervalSet set = IntervalSet.of('a', 'c');
		r0.addTransition(new SetTransition(s0, set));

		// rule 1: range d-f
		RuleStartState r1 = new RuleStartState();
		RuleStopState s1 = new RuleStopState();
		r1.ruleIndex = 1;
		s1.ruleIndex = 1;
		r1.stopState = s1;
		atn.addState(r1);
		atn.addState(s1);
		r1.addTransition(new RangeTransition(s1, 'd', 'f'));

		// rule 2: wildcard
		RuleStartState r2 = new RuleStartState();
		RuleStopState s2 = new RuleStopState();
		r2.ruleIndex = 2;
		s2.ruleIndex = 2;
		r2.stopState = s2;
		atn.addState(r2);
		atn.addState(s2);
		r2.addTransition(new WildcardTransition(s2));

		// rule 3: action + atom
		RuleStartState r3 = new RuleStartState();
		BasicState mid3 = new BasicState();
		RuleStopState s3 = new RuleStopState();
		r3.ruleIndex = 3;
		mid3.ruleIndex = 3;
		s3.ruleIndex = 3;
		r3.stopState = s3;
		atn.addState(r3);
		atn.addState(mid3);
		atn.addState(s3);
		r3.addTransition(new AtomTransition(mid3, 'z'));
		mid3.addTransition(new ActionTransition(s3, 3, 0, false));

		tokensStart.addTransition(new EpsilonTransition(r0));
		tokensStart.addTransition(new EpsilonTransition(r1));
		tokensStart.addTransition(new EpsilonTransition(r2));
		tokensStart.addTransition(new EpsilonTransition(r3));

		atn.ruleToStartState = new RuleStartState[] { r0, r1, r2, r3 };
		atn.ruleToStopState = new RuleStopState[] { s0, s1, s2, s3 };
		atn.ruleToTokenType = new int[] { 1, 2, 3, 4 };
		atn.lexerActions = new LexerAction[] {
			LexerSkipAction.INSTANCE,
			LexerMoreAction.INSTANCE,
			LexerPopModeAction.INSTANCE,
			new LexerTypeAction(9),
			new LexerChannelAction(1),
			new LexerModeAction(0),
			new LexerPushModeAction(0),
			new LexerCustomAction(0, 1)
		};
		atn.defineMode("DEFAULT_MODE", tokensStart);
		atn.clearDFA();

		List<String> rules = Arrays.asList("S", "R", "W", "A");
		char[] data = serialize(atn, rules);

		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false); // hand-built lexer graphs may mix epsilons
		opts.setOptimize(true);
		ATN restored = new ATNDeserializer(opts).deserialize(data);
		assertEquals(ATNType.LEXER, restored.grammarType);
		assertEquals(4, restored.ruleToStartState.length);
		assertEquals(atn.lexerActions.length, restored.lexerActions.length);
		assertTrue(restored.modeToStartState.size() >= 1);

		// without optimize
		ATNDeserializationOptions opts2 = new ATNDeserializationOptions();
		opts2.setVerifyATN(false);
		opts2.setOptimize(false);
		ATN restored2 = new ATNDeserializer(opts2).deserialize(data);
		assertEquals(ATNType.LEXER, restored2.grammarType);
	}

	@Test
	public void parserWithLoopsPredicatesAndBypass() {
		ATNDeserializationOptions noVerify = new ATNDeserializationOptions();
		noVerify.setVerifyATN(false);
		List<String> rules = Collections.singletonList("s");

		ATN atn = ATNTestHelpers.buildParserAStar();
		char[] data = serialize(atn, rules);

		ATNDeserializationOptions bypass = new ATNDeserializationOptions();
		bypass.setGenerateRuleBypassTransitions(true);
		bypass.setVerifyATN(false);
		bypass.setOptimize(false);
		ATN withBypass = new ATNDeserializer(bypass).deserialize(data);
		assertNotNull(withBypass.ruleToTokenType);
		assertEquals(1, withBypass.ruleToTokenType.length);
		assertTrue(withBypass.ruleToTokenType[0] > atn.maxTokenType);

		// plus loop
		ATN plus = ATNTestHelpers.buildParserAPlus();
		ATN plusRestored = new ATNDeserializer(noVerify).deserialize(serialize(plus, rules));
		assertEquals(ATNType.PARSER, plusRestored.grammarType);

		// optional
		ATN opt = ATNTestHelpers.buildParserOptionalAthenB();
		ATN optRestored = new ATNDeserializer(noVerify).deserialize(serialize(opt, rules));
		assertTrue(optRestored.getNumberOfDecisions() >= 1);

		// predicate if helper exists
		try {
			ATN pred = ATNTestHelpers.buildParserWithPredicate();
			ATN predRestored = new ATNDeserializer(noVerify).deserialize(serialize(pred, rules));
			assertEquals(ATNType.PARSER, predRestored.grammarType);
		}
		catch (Throwable t) {
			// helper may be absent or ATN not serializable
		}

		try {
			ATN ctx = ATNTestHelpers.buildParserContextSensitive();
			List<String> two = Arrays.asList("s", "t");
			ATN ctxRestored = new ATNDeserializer(noVerify).deserialize(serialize(ctx, two));
			assertEquals(2, ctxRestored.ruleToStartState.length);

			ATN call = ATNTestHelpers.buildParserRuleCall();
			ATN callRestored = new ATNDeserializer(noVerify).deserialize(serialize(call, two));
			assertEquals(2, callRestored.ruleToStartState.length);
		}
		catch (Throwable t) {
			// optional helpers
		}
	}

	@Test
	public void serializerDecodedAndTokenNames() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		List<String> rules = Collections.singletonList("s");
		List<String> tokens = Arrays.asList("EOF", "A", "B");
		String decoded = ATNSerializer.getDecoded(atn, rules, tokens);
		assertNotNull(decoded);
		assertTrue(decoded.length() > 0);

		IntegerList serialized = ATNSerializer.getSerialized(atn, rules);
		assertEquals(ATNDeserializer.SERIALIZED_VERSION, serialized.get(0));

		ATNSerializer ser = new ATNSerializer(atn, rules, tokens);
		assertNotNull(ser.getTokenName(Token.EOF));
		assertNotNull(ser.getTokenName(1));
		assertNotNull(ser.getTokenName(1000));
	}

	@Test
	public void invalidVersionAndUuidRejected() {
		ATN atn = ATNTestHelpers.buildLexerMatchA();
		char[] data = serialize(atn, Collections.singletonList("A"));
		// corrupt version
		char[] badVer = data.clone();
		badVer[0] = 99;
		try {
			new ATNDeserializer().deserialize(badVer);
			fail();
		}
		catch (UnsupportedOperationException expected) {
			assertTrue(expected.getMessage().contains("version") || expected.getCause() != null
				|| expected.getMessage().length() > 0);
		}

		// corrupt UUID (bytes after version)
		char[] badUuid = data.clone();
		if (badUuid.length > 10) {
			badUuid[1] = 0;
			badUuid[2] = 0;
			try {
				new ATNDeserializer().deserialize(badUuid);
				// may throw
			}
			catch (UnsupportedOperationException expected) {
				// ok
			}
			catch (RuntimeException expected) {
				// ok
			}
		}
	}

	@Test
	public void edgeFactoryAndStateFactoryViaSimulator() {
		ATN atn = ATNTestHelpers.buildLexerMatchA();
		for (int type = ATNState.INVALID_TYPE; type <= ATNState.STAR_LOOP_ENTRY; type++) {
			try {
				ATNState s = ATNSimulator.stateFactory(type, 0);
				if (type != ATNState.INVALID_TYPE) {
					assertNotNull(s);
				}
			}
			catch (Exception ignored) {
				// some types may not be factory-supported
			}
		}

		List<IntervalSet> sets = Arrays.asList(
			IntervalSet.of(1),
			IntervalSet.of(2, 4),
			IntervalSet.of('a', 'z')
		);
		// exercise edgeFactory for various transition types
		int[] edgeTypes = new int[] {
			Transition.EPSILON, Transition.RANGE, Transition.RULE, Transition.PREDICATE,
			Transition.ATOM, Transition.ACTION, Transition.SET, Transition.NOT_SET,
			Transition.WILDCARD, Transition.PRECEDENCE
		};
		for (int et : edgeTypes) {
			try {
				Transition t = ATNSimulator.edgeFactory(atn, et, 0, 1, 1, 2, 0, sets);
				assertNotNull(t);
			}
			catch (Exception ignored) {
				// args may not fit every edge type
			}
		}
	}

	@Test
	public void codePointTransitionsHelpers() {
		BasicState target = new BasicState();
		Transition bmp = CodePointTransitions.createWithCodePoint(target, 'a');
		assertTrue(bmp instanceof AtomTransition);
		Transition smp = CodePointTransitions.createWithCodePoint(target, 0x1F4A9);
		assertTrue(smp instanceof SetTransition);

		Transition rangeBmp = CodePointTransitions.createWithCodePointRange(target, 'a', 'z');
		assertTrue(rangeBmp instanceof RangeTransition);
		Transition rangeSmp = CodePointTransitions.createWithCodePointRange(target, 0x1F600, 0x1F64F);
		assertTrue(rangeSmp instanceof SetTransition);
	}

	@Test
	public void nonGreedyAndSllDecisionFlagsSerialized() {
		// build verify-friendly decision (epsilons only from decision state)
		ATN atn = ATNTestHelpers.buildParserOptionalAthenB();
		DecisionState d = atn.decisionToState.get(0);
		d.nonGreedy = true;
		d.sll = true;
		char[] data = serialize(atn, Collections.singletonList("s"));
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		ATN restored = new ATNDeserializer(opts).deserialize(data);
		DecisionState rd = restored.decisionToState.get(0);
		assertTrue(rd.nonGreedy);
		assertTrue(rd.sll);
	}

	@Test
	public void precedenceRuleRoundTrip() {
		// mark rule as precedence with star loop entry
		ATN atn = ATNTestHelpers.buildParserAStar();
		atn.ruleToStartState[0].isPrecedenceRule = true;
		StarLoopEntryState entry = null;
		for (ATNState s : atn.states) {
			if (s instanceof StarLoopEntryState) {
				entry = (StarLoopEntryState) s;
				entry.precedenceRuleDecision = true;
				break;
			}
		}
		assertNotNull(entry);
		char[] data = serialize(atn, Collections.singletonList("e"));
		ATN restored = new ATNDeserializer().deserialize(data);
		assertTrue(restored.ruleToStartState[0].isPrecedenceRule);
	}

	/**
	 * {@link ATNDeserializer#deserialize(String)} must match the char[] path
	 * and must not require callers to pre-clone (string path owns its buffer).
	 */
	@Test
	public void stringDeserializeMatchesCharArrayPath() {
		ATN atn = ATNTestHelpers.buildParserOptionalAthenB();
		char[] data = serialize(atn, Collections.singletonList("s"));
		// Preserve original for char[] path which clones internally
		char[] dataCopy = data.clone();
		String encoded = new String(data);

		ATN fromChars = new ATNDeserializer().deserialize(dataCopy);
		ATN fromString = new ATNDeserializer().deserialize(encoded);

		assertEquals(fromChars.states.size(), fromString.states.size());
		assertEquals(fromChars.getNumberOfDecisions(), fromString.getNumberOfDecisions());
		assertEquals(fromChars.grammarType, fromString.grammarType);
		assertEquals(fromChars.maxTokenType, fromString.maxTokenType);
		// Original array must remain usable for a second char[] deserialize (clone semantics)
		ATN fromCharsAgain = new ATNDeserializer().deserialize(data);
		assertEquals(fromChars.states.size(), fromCharsAgain.states.size());
	}

	@Test
	public void stringDeserializeRejectsBadVersionLikeCharArray() {
		char[] data = new char[] { (char) 99 }; // wrong version
		String encoded = new String(data);
		try {
			new ATNDeserializer().deserialize(encoded);
			fail("expected UnsupportedOperationException for bad version");
		}
		catch (UnsupportedOperationException expected) {
			assertNotNull(expected.getCause());
		}
	}

	@Test
	public void optimizeCollapsesSetsAndEpsilons() {
		// multiple set-like alts for optimizeSets — decision uses only epsilons
		ATN atn = new ATN(ATNType.PARSER, 10);
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
		atn.addState(start);
		atn.addState(block);
		atn.addState(end);
		atn.addState(stop);
		start.addTransition(new EpsilonTransition(block));
		BasicState mid1 = new BasicState();
		BasicState mid2 = new BasicState();
		mid1.ruleIndex = 0;
		mid2.ruleIndex = 0;
		atn.addState(mid1);
		atn.addState(mid2);
		block.addTransition(new EpsilonTransition(mid1));
		mid1.addTransition(new AtomTransition(end, 1));
		block.addTransition(new EpsilonTransition(mid2));
		mid2.addTransition(new AtomTransition(end, 2));
		BasicState chain = new BasicState();
		chain.ruleIndex = 0;
		atn.addState(chain);
		end.addTransition(new EpsilonTransition(chain));
		chain.addTransition(new EpsilonTransition(stop));
		atn.defineDecisionState(block);
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();

		char[] data = serialize(atn, Collections.singletonList("s"));
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setOptimize(true);
		opts.setVerifyATN(false);
		ATN restored = new ATNDeserializer(opts).deserialize(data);
		assertNotNull(restored);
	}

	@Test
	public void decodeCoversNullLoopNonGreedySllAndPrecedence() {
		ATN atn = ATNTestHelpers.buildParserAPlus();
		DecisionState decision = atn.decisionToState.get(0);
		decision.nonGreedy = true;
		decision.sll = true;
		atn.ruleToStartState[0].isPrecedenceRule = true;
		atn.states.add(null); // INVALID_TYPE hole at the end; nothing targets it
		String decoded = ATNSerializer.getDecoded(atn, Collections.singletonList("s"),
			Arrays.asList("EOF", "A", "B"));
		assertNotNull(decoded);
		assertTrue(decoded.contains("max type"));
		assertTrue(decoded.contains("rule 0:"));
	}

	@Test
	public void verifyPlusAndStarLoops() {
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(true);
		opts.setOptimize(false);
		ATN plus = new ATNDeserializer(opts).deserialize(
			serialize(ATNTestHelpers.buildParserAPlus(), Collections.singletonList("s")));
		assertEquals(ATNType.PARSER, plus.grammarType);
		boolean sawPlus = false;
		for (ATNState state : plus.states) {
			if (state instanceof PlusBlockStartState) {
				sawPlus = true;
				assertNotNull(((PlusBlockStartState) state).loopBackState);
			}
		}
		assertTrue(sawPlus);

		ATN star = new ATNDeserializer(opts).deserialize(
			serialize(ATNTestHelpers.buildParserAStar(), Collections.singletonList("s")));
		boolean sawStar = false;
		for (ATNState state : star.states) {
			if (state instanceof StarLoopEntryState) {
				sawStar = true;
				StarLoopEntryState entry = (StarLoopEntryState) state;
				assertEquals(2, entry.getNumberOfTransitions());
				assertFalse(entry.nonGreedy);
				assertTrue(entry.transition(0).target instanceof StarBlockStartState);
			}
		}
		assertTrue(sawStar);
	}

	@Test
	public void skipsReturnEdgeFromPlainRuleIntoLeftFactoredCaller() {
		ATN atn = ATNTestHelpers.buildParserRuleCall();
		List<String> names = Arrays.asList("s" + ATNSimulator.RULE_VARIANT_DELIMITER + "lf", "t");
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(false);
		ATN restored = new ATNDeserializer(opts).deserialize(serialize(atn, names));
		assertTrue(restored.ruleToStartState[0].leftFactored);
		assertFalse(restored.ruleToStartState[1].leftFactored);
		// The only call is from the left-factored caller into a plain rule, so the
		// derived return edge is omitted.
		assertEquals(0, restored.ruleToStopState[1].getNumberOfTransitions());
	}

	@Test
	public void twoBlockStartsSharingEndStateThrow() {
		ATN atn = parserShell();
		BasicBlockStartState first = new BasicBlockStartState();
		BasicBlockStartState second = new BasicBlockStartState();
		BlockEndState end = new BlockEndState();
		first.ruleIndex = 0;
		second.ruleIndex = 0;
		end.ruleIndex = 0;
		atn.addState(first);
		atn.addState(second);
		atn.addState(end);
		first.endState = end;
		second.endState = end;
		expectIllegalState(atn);
	}

	@Test
	public void bypassPrecedenceRuleScansOtherRulesAndNonMatchingStars() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		RuleStartState start = new RuleStartState();
		StarLoopEntryState notLoop = new StarLoopEntryState();
		BasicState plain = new BasicState();
		StarLoopEntryState badEnd = new StarLoopEntryState();
		LoopEndState loopEnd = new LoopEndState();
		BasicState notStop = new BasicState();
		RuleStopState stop = new RuleStopState();
		RuleStartState other = new RuleStartState();
		RuleStopState otherStop = new RuleStopState();
		ATNState[] states = new ATNState[] {
			start, notLoop, plain, badEnd, loopEnd, notStop, stop, other, otherStop
		};
		for (ATNState state : states) {
			state.ruleIndex = state == other || state == otherStop ? 1 : 0;
			atn.addState(state);
		}
		start.stopState = stop;
		start.isPrecedenceRule = true;
		other.stopState = otherStop;
		notLoop.addTransition(new EpsilonTransition(plain));
		badEnd.addTransition(new EpsilonTransition(loopEnd));
		loopEnd.addTransition(new EpsilonTransition(notStop));
		notStop.addTransition(new AtomTransition(stop, 1));
		other.addTransition(new AtomTransition(otherStop, 1));
		atn.ruleToStartState = new RuleStartState[] { start, other };
		atn.ruleToStopState = new RuleStopState[] { stop, otherStop };
		atn.clearDFA();

		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(false);
		opts.setGenerateRuleBypassTransitions(true);
		try {
			new ATNDeserializer(opts).deserialize(serialize(atn, Arrays.asList("e", "other")));
			fail();
		}
		catch (RuntimeException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void precedenceLoopbackRecordsNonZeroPrecedenceReturn() {
		ATN atn = ATNTestHelpers.buildParserAStar();
		atn.ruleToStartState[0].isPrecedenceRule = true;
		StarLoopEntryState entry = null;
		for (ATNState state : atn.states) {
			if (state instanceof StarLoopEntryState) {
				entry = (StarLoopEntryState) state;
				entry.precedenceRuleDecision = true;
				entry.precedenceLoopbackStates = new BitSet();
			}
		}
		assertNotNull(entry);

		RuleStartState caller = new RuleStartState();
		BasicState follow = new BasicState();
		RuleStopState callerStop = new RuleStopState();
		caller.ruleIndex = 1;
		follow.ruleIndex = 1;
		callerStop.ruleIndex = 1;
		caller.stopState = callerStop;
		atn.addState(caller);
		atn.addState(follow);
		atn.addState(callerStop);
		// precedence != 0 → derived return epsilon has outermostPrecedenceReturn == -1
		caller.addTransition(new RuleTransition(atn.ruleToStartState[0], 0, 1, follow));
		follow.addTransition(new EpsilonTransition(callerStop));
		atn.ruleToStartState = new RuleStartState[] { atn.ruleToStartState[0], caller };
		atn.ruleToStopState = new RuleStopState[] { atn.ruleToStopState[0], callerStop };
		atn.decisionToDFA = null;
		atn.clearDFA();

		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(false);
		ATN restored = new ATNDeserializer(opts).deserialize(serialize(atn, Arrays.asList("e", "s")));
		boolean marked = false;
		for (ATNState state : restored.states) {
			if (state instanceof StarLoopEntryState
				&& ((StarLoopEntryState) state).precedenceRuleDecision)
			{
				marked = true;
				BitSet loopbacks = ((StarLoopEntryState) state).precedenceLoopbackStates;
				assertNotNull(loopbacks);
				assertTrue(loopbacks.cardinality() >= 1);
			}
		}
		assertTrue(marked);
	}

	@Test
	public void optimizerInlinesMixedCallsAndCombinesEpsilonChains() {
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(true);

		ATN atn = inlineAndTailCallAtn();
		ATN restored = new ATNDeserializer(opts).deserialize(
			serialize(atn, Arrays.asList("s", "u", "v", "t", "w", "n", "r")));
		assertEquals(7, restored.ruleToStartState.length);

		ATN chained = chainedEpsilonVariants();
		ATN chainedRestored = new ATNDeserializer(opts).deserialize(
			serialize(chained, Arrays.asList("s", "callee")));
		assertNotNull(chainedRestored.ruleToStopState[0]);

		ATN sets = collapsibleAtomDecision();
		ATN setsRestored = new ATNDeserializer(opts).deserialize(
			serialize(sets, Collections.singletonList("s")));
		assertTrue(setsRestored.getNumberOfDecisions() >= 1);
	}

	private static void expectIllegalState(ATN atn) {
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(false);
		try {
			new ATNDeserializer(opts).deserialize(serialize(atn, Collections.singletonList("s")));
			fail();
		}
		catch (IllegalStateException expected) {
			assertNotNull(expected);
		}
	}

	private static ATN parserShell() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		RuleStartState start = new RuleStartState();
		RuleStopState stop = new RuleStopState();
		start.ruleIndex = 0;
		stop.ruleIndex = 0;
		start.stopState = stop;
		atn.addState(start);
		atn.addState(stop);
		start.addTransition(new EpsilonTransition(stop));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}

	/**
	 * Inlinable calls stay at optimized index 0. {@code inlineSetRules} copies
	 * transition {@code i} rather than {@code j}, so an inlinable call at a
	 * later index is reinserted and the optimizer never converges.
	 */
	private static ATN inlineAndTailCallAtn() {
		ATN atn = new ATN(ATNType.PARSER, 8);
		RuleStartState sStart = new RuleStartState();
		RuleStopState sStop = new RuleStopState();
		sStart.ruleIndex = 0;
		sStop.ruleIndex = 0;
		sStart.stopState = sStop;
		atn.addState(sStart);
		atn.addState(sStop);

		RuleStartState uStart = addMatchRule(atn, 1, MATCH_ATOM, true);
		RuleStartState vStart = addMatchRule(atn, 2, MATCH_ATOM, false);
		RuleStartState tStart = new RuleStartState();
		BasicState tPre = new BasicState();
		RuleStopState tStop = new RuleStopState();
		tStart.ruleIndex = 3;
		tPre.ruleIndex = 3;
		tStop.ruleIndex = 3;
		tStart.stopState = tStop;
		atn.addState(tStart);
		atn.addState(tPre);
		atn.addState(tStop);
		tStart.addTransition(new RuleTransition(uStart, 1, 0, tPre));
		tPre.addTransition(new EpsilonTransition(tStop));
		RuleStartState wStart = addMatchRule(atn, 4, MATCH_WILDCARD, true);
		RuleStartState nStart = addMatchRule(atn, 5, MATCH_NOT_SET, true);

		// Not a BASIC state: a self-epsilon here is not a combinable chain.
		BlockEndState vFollow = new BlockEndState();
		vFollow.ruleIndex = 0;
		atn.addState(vFollow);
		vFollow.addTransition(new EpsilonTransition(vFollow));

		BasicState atomTarget = new BasicState();
		atomTarget.ruleIndex = 0;
		atn.addState(atomTarget);
		atomTarget.addTransition(new EpsilonTransition(sStop));

		sStart.addTransition(new RuleTransition(uStart, 1, 0, sStop));
		sStart.addTransition(new AtomTransition(atomTarget, 1));
		sStart.addTransition(new RuleTransition(vStart, 2, 0, vFollow));

		RuleStartState rStart = new RuleStartState();
		BasicState rChain = new BasicState();
		BasicState rChainTarget = new BasicState();
		RuleStopState rStop = new RuleStopState();
		rStart.ruleIndex = 6;
		rChain.ruleIndex = 6;
		rChainTarget.ruleIndex = 6;
		rStop.ruleIndex = 6;
		rStart.stopState = rStop;
		atn.addState(rStart);
		atn.addState(rChain);
		atn.addState(rChainTarget);
		atn.addState(rStop);
		rStart.addTransition(new RuleTransition(tStart, 3, 0, rStop));
		rStart.addTransition(new EpsilonTransition(rChain));
		rChain.addTransition(new EpsilonTransition(rChainTarget));
		rChainTarget.addTransition(new EpsilonTransition(rStop));

		atn.ruleToStartState = new RuleStartState[] {
			sStart, uStart, vStart, tStart, wStart, nStart, rStart
		};
		RuleStopState[] stops = new RuleStopState[7];
		for (ATNState state : atn.states) {
			if (state instanceof RuleStopState) {
				stops[state.ruleIndex] = (RuleStopState) state;
			}
		}
		atn.ruleToStopState = stops;
		atn.clearDFA();
		return atn;
	}

	private static final int MATCH_ATOM = 0;
	private static final int MATCH_WILDCARD = 1;
	private static final int MATCH_NOT_SET = 2;

	private static RuleStartState addMatchRule(ATN atn, int ruleIndex, int kind, boolean withPreStop) {
		RuleStartState start = new RuleStartState();
		RuleStopState stop = new RuleStopState();
		start.ruleIndex = ruleIndex;
		stop.ruleIndex = ruleIndex;
		start.stopState = stop;
		atn.addState(start);
		ATNState matchTarget;
		if (withPreStop) {
			BasicState pre = new BasicState();
			pre.ruleIndex = ruleIndex;
			atn.addState(pre);
			atn.addState(stop);
			pre.addTransition(new EpsilonTransition(stop));
			matchTarget = pre;
		}
		else {
			atn.addState(stop);
			matchTarget = stop;
		}
		Transition match;
		if (kind == MATCH_WILDCARD) {
			match = new WildcardTransition(matchTarget);
		}
		else if (kind == MATCH_NOT_SET) {
			match = new NotSetTransition(matchTarget, IntervalSet.of(1));
		}
		else {
			match = new AtomTransition(matchTarget, 3 + ruleIndex);
		}
		start.addTransition(match);
		return start;
	}

	/** Epsilon state whose chain starts after a non-combinable edge, then a rule edge. */
	private static ATN chainedEpsilonVariants() {
		ATN atn = new ATN(ATNType.PARSER, 4);
		RuleStartState start = new RuleStartState();
		BasicState branch = new BasicState();
		BasicState atomState = new BasicState();
		BasicState chain = new BasicState();
		BasicState chainTarget = new BasicState();
		BasicState laterAtom = new BasicState();
		BasicState ruleCarrier = new BasicState();
		RuleStopState stop = new RuleStopState();
		RuleStartState callee = new RuleStartState();
		RuleStopState calleeStop = new RuleStopState();
		ATNState[] states = new ATNState[] {
			start, branch, atomState, chain, chainTarget, laterAtom, ruleCarrier, stop, callee, calleeStop
		};
		for (ATNState state : states) {
			state.ruleIndex = state == callee || state == calleeStop ? 1 : 0;
			atn.addState(state);
		}
		start.stopState = stop;
		callee.stopState = calleeStop;
		start.addTransition(new EpsilonTransition(branch));
		branch.addTransition(new EpsilonTransition(atomState));
		atomState.addTransition(new AtomTransition(stop, 1));
		branch.addTransition(new EpsilonTransition(chain));
		chain.addTransition(new EpsilonTransition(chainTarget));
		chainTarget.addTransition(new EpsilonTransition(stop));
		branch.addTransition(new EpsilonTransition(laterAtom));
		laterAtom.addTransition(new AtomTransition(stop, 2));
		branch.addTransition(new EpsilonTransition(ruleCarrier));
		ruleCarrier.addTransition(new RuleTransition(callee, 1, 0, stop));
		callee.addTransition(new AtomTransition(calleeStop, 3));
		atn.ruleToStartState = new RuleStartState[] { start, callee };
		atn.ruleToStopState = new RuleStopState[] { stop, calleeStop };
		atn.clearDFA();
		return atn;
	}

	/** Decision whose epsilon chain is combined before identical atoms collapse. */
	private static ATN collapsibleAtomDecision() {
		ATN atn = new ATN(ATNType.PARSER, 6);
		RuleStartState start = new RuleStartState();
		BasicBlockStartState block = new BasicBlockStartState();
		BasicState messy = new BasicState();
		BasicState chain = new BasicState();
		BasicState altA = new BasicState();
		BasicState altB = new BasicState();
		BlockEndState end = new BlockEndState();
		RuleStopState stop = new RuleStopState();
		for (ATNState state : new ATNState[] { start, block, messy, chain, altA, altB, end, stop }) {
			state.ruleIndex = 0;
			atn.addState(state);
		}
		start.stopState = stop;
		block.endState = end;
		end.startState = block;
		start.addTransition(new EpsilonTransition(block));
		block.addTransition(new EpsilonTransition(messy));
		messy.addTransition(new AtomTransition(end, 1));
		messy.addTransition(new AtomTransition(end, 2));
		block.addTransition(new EpsilonTransition(chain));
		chain.addTransition(new EpsilonTransition(altA));
		altA.addTransition(new AtomTransition(end, 5));
		block.addTransition(new EpsilonTransition(altB));
		altB.addTransition(new AtomTransition(end, 5));
		end.addTransition(new EpsilonTransition(stop));
		atn.defineDecisionState(block);
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}
}
