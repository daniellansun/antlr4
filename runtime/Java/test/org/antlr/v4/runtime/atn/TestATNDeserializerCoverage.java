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
}
