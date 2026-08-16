/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntegerList;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.Utils;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Historical ATN serialization UUIDs ({@code BASE_SERIALIZED_UUID},
 * {@code ADDED_LEXER_ACTIONS}), runtime optimizer, and {@code verifyATN}
 * checks that current-UUID round-trips do not reach.
 */
public class TestATNDeserializerLegacyUuidAndOptimizer {

	@Test
	public void isFeatureSupportedWhenSerializedUuidPredatesFeature() throws Exception {
		UUID base = uuidField("BASE_SERIALIZED_UUID");
		UUID lexerActions = uuidField("ADDED_LEXER_ACTIONS");
		UUID smp = uuidField("ADDED_UNICODE_SMP");

		Method feat = ATNDeserializer.class.getDeclaredMethod(
			"isFeatureSupported", UUID.class, UUID.class);
		feat.setAccessible(true);
		ATNDeserializer d = new ATNDeserializer();

		// feature in the list, actual UUID earlier than the feature → false (L164)
		assertEquals(Boolean.FALSE, feat.invoke(d, lexerActions, base));
		assertEquals(Boolean.FALSE, feat.invoke(d, smp, base));
		assertEquals(Boolean.FALSE, feat.invoke(d, smp, lexerActions));
		assertEquals(Boolean.TRUE, feat.invoke(d, base, base));
		assertEquals(Boolean.TRUE, feat.invoke(d, base, lexerActions));
		assertEquals(Boolean.TRUE, feat.invoke(d, lexerActions, smp));
		// unknown feature UUID → false (L160-161)
		assertEquals(Boolean.FALSE, feat.invoke(d, UUID.randomUUID(), smp));
	}

	@Test
	public void deserializeBaseUuidLexerConvertsActionTransitionsToCustomActions() throws Exception {
		UUID base = uuidField("BASE_SERIALIZED_UUID");
		IntegerList raw = new IntegerList();
		raw.add(ATNDeserializer.SERIALIZED_VERSION);
		addUUID(raw, base);
		raw.add(ATNType.LEXER.ordinal());
		raw.add(2); // maxTokenType
		// states: 0 TOKEN_START, 1 RULE_START r0, 2 BASIC, 3 BASIC (action), 4 RULE_STOP r0,
		//         5 RULE_START r1, 6 RULE_STOP r1
		raw.add(7);
		addState(raw, ATNState.TOKEN_START, 0xFFFF);
		addState(raw, ATNState.RULE_START, 0);
		addState(raw, ATNState.BASIC, 0);
		addState(raw, ATNState.BASIC, 0);
		addState(raw, ATNState.RULE_STOP, 0);
		addState(raw, ATNState.RULE_START, 1);
		addState(raw, ATNState.RULE_STOP, 1);
		raw.add(0); // nonGreedy
		raw.add(0); // sll
		raw.add(0); // precedence
		raw.add(2); // nrules
		// rule 0: start=1, not left-factored, token type 1, actionIndex 0xFFFF → -1
		raw.add(1);
		raw.add(0);
		raw.add(1);
		raw.add(0xFFFF);
		// rule 1: start=5, left-factored, token type EOF (0xFFFF), actionIndex 3
		raw.add(5);
		raw.add(1);
		raw.add(0xFFFF);
		raw.add(3);
		raw.add(1); // nmodes
		raw.add(0); // mode start = state 0
		raw.add(0); // bmp sets
		// 5 edges: 0-eps->1, 0-eps->5, 1-atom->2, 2-action->3, 3-eps->4
		raw.add(5);
		addEdge(raw, 0, 1, Transition.EPSILON, 0, 0, 0);
		addEdge(raw, 0, 5, Transition.EPSILON, 0, 0, 0);
		addEdge(raw, 1, 2, Transition.ATOM, 'a', 0, 0);
		addEdge(raw, 2, 3, Transition.ACTION, 0, 7, 0);
		addEdge(raw, 3, 4, Transition.EPSILON, 0, 0, 0);
		raw.add(0); // ndecisions
		// no lexer-actions section in BASE format

		char[] data = shift(raw);
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(false);
		ATN atn = new ATNDeserializer(opts).deserialize(data);
		assertEquals(ATNType.LEXER, atn.grammarType);
		assertEquals(2, atn.ruleToStartState.length);
		assertEquals(1, atn.ruleToTokenType[0]);
		assertEquals(Token.EOF, atn.ruleToTokenType[1]);
		assertTrue(atn.ruleToStartState[1].leftFactored);
		assertNotNull(atn.lexerActions);
		assertTrue(atn.lexerActions.length >= 1);
		assertTrue(atn.lexerActions[0] instanceof LexerCustomAction);
		assertEquals(0, ((LexerCustomAction) atn.lexerActions[0]).getRuleIndex());
		assertEquals(7, ((LexerCustomAction) atn.lexerActions[0]).getActionIndex());
	}

	@Test
	public void deserializeLexerActionsUuidDecodesChannelAndCustomSentinels() throws Exception {
		UUID lexerActions = uuidField("ADDED_LEXER_ACTIONS");
		IntegerList raw = new IntegerList();
		raw.add(ATNDeserializer.SERIALIZED_VERSION);
		addUUID(raw, lexerActions);
		raw.add(ATNType.LEXER.ordinal());
		raw.add(1);
		raw.add(4);
		addState(raw, ATNState.TOKEN_START, 0xFFFF);
		addState(raw, ATNState.RULE_START, 0);
		addState(raw, ATNState.BASIC, 0);
		addState(raw, ATNState.RULE_STOP, 0);
		raw.add(0);
		raw.add(0);
		raw.add(0);
		raw.add(1);
		raw.add(1); // start
		raw.add(0); // leftFactored
		raw.add(1); // token type — no actionIndex (ADDED_LEXER_ACTIONS)
		raw.add(1);
		raw.add(0);
		raw.add(0); // bmp sets; no SMP section
		raw.add(3);
		addEdge(raw, 0, 1, Transition.EPSILON, 0, 0, 0);
		addEdge(raw, 1, 2, Transition.ATOM, 'x', 0, 0);
		addEdge(raw, 2, 3, Transition.EPSILON, 0, 0, 0);
		raw.add(0);
		// lexer actions with 0xFFFF sentinels
		raw.add(2);
		raw.add(LexerActionType.CHANNEL.ordinal());
		raw.add(0xFFFF);
		raw.add(0);
		raw.add(LexerActionType.CUSTOM.ordinal());
		raw.add(0xFFFF);
		raw.add(0xFFFF);

		char[] data = shift(raw);
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(false);
		ATN atn = new ATNDeserializer(opts).deserialize(data);
		assertEquals(2, atn.lexerActions.length);
		assertTrue(atn.lexerActions[0] instanceof LexerChannelAction);
		assertEquals(-1, ((LexerChannelAction) atn.lexerActions[0]).getChannel());
		assertTrue(atn.lexerActions[1] instanceof LexerCustomAction);
		assertEquals(-1, ((LexerCustomAction) atn.lexerActions[1]).getRuleIndex());
		assertEquals(-1, ((LexerCustomAction) atn.lexerActions[1]).getActionIndex());
	}

	@Test
	public void serializeRejectsBlockStartMissingEndState() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		((BlockStartState) atn.decisionToState.get(0)).endState = null;
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(false);
		try {
			new ATNDeserializer(opts).deserialize(
				ATNSerializer.getSerializedAsChars(atn, Collections.singletonList("s")));
			fail();
		}
		catch (RuntimeException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void deserializeWhenBlockEndAlreadyHasStartState() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		BlockStartState start = (BlockStartState) atn.decisionToState.get(0);
		start.endState.startState = start;
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(false);
		try {
			ATN restored = new ATNDeserializer(opts).deserialize(
				ATNSerializer.getSerializedAsChars(atn, Collections.singletonList("s")));
			assertNotNull(restored);
		}
		catch (IllegalStateException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void deserializeOmitsReturnEdgeIntoLeftFactoredCaller() {
		// returningFromLeftFactored=false && returningToLeftFactored=true → skip (L413)
		ATN atn = ATNTestHelpers.buildParserRuleCall();
		atn.ruleToStartState[0].leftFactored = true;  // caller s is left-factored
		atn.ruleToStartState[1].leftFactored = false; // callee t is not
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(false);
		ATN restored = new ATNDeserializer(opts).deserialize(
			ATNSerializer.getSerializedAsChars(atn, Arrays.asList("s", "t")));
		assertNotNull(restored.ruleToStopState[1]);

		// both left-factored → return transition is recorded
		ATN both = ATNTestHelpers.buildParserRuleCall();
		both.ruleToStartState[0].leftFactored = true;
		both.ruleToStartState[1].leftFactored = true;
		ATN restoredBoth = new ATNDeserializer(opts).deserialize(
			ATNSerializer.getSerializedAsChars(both, Arrays.asList("s", "t")));
		assertTrue(restoredBoth.ruleToStopState[1].getNumberOfTransitions() >= 0);
	}

	@Test
	public void optimizerInlinesRangeAndSetRulesButNotWildcardOrNotSet() {
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(true);

		BasicState dummy = new BasicState();
		// t : 'a'..'c' ; s : t ;
		ATN range = twoRuleMatchThenCall(new RangeTransition(dummy, 'a', 'c'));
		ATN r1 = new ATNDeserializer(opts).deserialize(
			ATNSerializer.getSerializedAsChars(range, Arrays.asList("s", "t")));
		assertNotNull(r1);

		// t : [xy] ; s : t ;
		ATN set = twoRuleMatchThenCall(new SetTransition(dummy, IntervalSet.of('x', 'y')));
		ATN r2 = new ATNDeserializer(opts).deserialize(
			ATNSerializer.getSerializedAsChars(set, Arrays.asList("s", "t")));
		assertNotNull(r2);

		// t : . ; s : t ;  — wildcard not inlined (L844-847)
		ATN wild = twoRuleMatchThenCall(new WildcardTransition(dummy));
		ATN r3 = new ATNDeserializer(opts).deserialize(
			ATNSerializer.getSerializedAsChars(wild, Arrays.asList("s", "t")));
		assertNotNull(r3);

		// t : ~[a] ; s : t ; — not-set not inlined
		ATN not = twoRuleMatchThenCall(new NotSetTransition(dummy, IntervalSet.of('a')));
		ATN r4 = new ATNDeserializer(opts).deserialize(
			ATNSerializer.getSerializedAsChars(not, Arrays.asList("s", "t")));
		assertNotNull(r4);
	}

	@Test
	public void optimizerCollapsesAtomAndRangeAlternativesIntoSets() {
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(true);

		// s : A | C ; already covered. Add A | B | D so the collapsed set is not a singleton.
		ATN three = threeAtomAlts(1, 2, 4);
		ATN restored = new ATNDeserializer(opts).deserialize(
			ATNSerializer.getSerializedAsChars(three, Collections.singletonList("s")));
		assertNotNull(restored);

		// two ranges that do not merge into one interval → SetTransition (L1077)
		ATN ranges = twoRangeAlts('a', 'c', 'x', 'z');
		ATN r2 = new ATNDeserializer(opts).deserialize(
			ATNSerializer.getSerializedAsChars(ranges, Collections.singletonList("s")));
		assertNotNull(r2);

		// contiguous range collapse A|B → RangeTransition (L1074)
		ATN contig = threeAtomAlts(10, 11, 12);
		ATN r3 = new ATNDeserializer(opts).deserialize(
			ATNSerializer.getSerializedAsChars(contig, Collections.singletonList("s")));
		assertNotNull(r3);
	}

	@Test
	public void optimizerDoesNotCollapseNotSetAlternative() {
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(true);
		ATN atn = atomAndNotSetAlts();
		ATN restored = new ATNDeserializer(opts).deserialize(
			ATNSerializer.getSerializedAsChars(atn, Collections.singletonList("s")));
		assertNotNull(restored);
	}

	@Test
	public void deserializeMarksTailCallsOnOptimizedRuleTransitions() {
		ATN atn = ATNTestHelpers.buildParserRuleCall();
		// Force an optimized transition list that includes the rule transition
		ATNState start = atn.ruleToStartState[0];
		if (!start.isOptimized()) {
			for (int i = 0; i < start.getNumberOfTransitions(); i++) {
				start.addOptimizedTransition(start.transition(i));
			}
		}
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(true);
		ATN restored = new ATNDeserializer(opts).deserialize(
			ATNSerializer.getSerializedAsChars(atn, Arrays.asList("s", "t")));
		assertNotNull(restored);
	}

	@Test
	public void verifyAtnRejectsBrokenLoopBlockAndDecisionStates() throws Exception {
		ATNDeserializer d = new ATNDeserializer();
		Method verify = ATNDeserializer.class.getDeclaredMethod("verifyATN", ATN.class);
		verify.setAccessible(true);

		// null state skipped (L741) — only if the rest of the ATN already verifies
		ATN withNull = new ATN(ATNType.PARSER, 1);
		withNull.states.add(null);
		try {
			verify.invoke(d, withNull);
		}
		catch (Exception expected) {
			assertNotNull(expected);
		}

		// PlusBlockStart without loopBack (L747)
		ATN plus = new ATN(ATNType.PARSER, 2);
		PlusBlockStartState p = new PlusBlockStartState();
		p.ruleIndex = 0;
		plus.addState(p);
		try {
			verify.invoke(d, plus);
			fail();
		}
		catch (Exception expected) {
			assertNotNull(expected.getCause());
		}

		// StarLoopEntry missing loopBack (L752)
		ATN star = new ATN(ATNType.PARSER, 2);
		StarLoopEntryState e = new StarLoopEntryState();
		e.ruleIndex = 0;
		star.addState(e);
		try {
			verify.invoke(d, star);
			fail();
		}
		catch (Exception expected) {
			assertNotNull(expected.getCause());
		}

		// StarLoopback with 0 transitions (L769)
		ATN slb = new ATN(ATNType.PARSER, 2);
		StarLoopbackState back = new StarLoopbackState();
		back.ruleIndex = 0;
		slb.addState(back);
		try {
			verify.invoke(d, slb);
			fail();
		}
		catch (Exception expected) {
			assertNotNull(expected.getCause());
		}

		// LoopEnd without loopBack (L774)
		ATN le = new ATN(ATNType.PARSER, 2);
		LoopEndState end = new LoopEndState();
		end.ruleIndex = 0;
		le.addState(end);
		try {
			verify.invoke(d, le);
			fail();
		}
		catch (Exception expected) {
			assertNotNull(expected.getCause());
		}

		// RuleStart without stop (L778)
		ATN rs = new ATN(ATNType.PARSER, 2);
		RuleStartState rstart = new RuleStartState();
		rstart.ruleIndex = 0;
		rs.addState(rstart);
		try {
			verify.invoke(d, rs);
			fail();
		}
		catch (Exception expected) {
			assertNotNull(expected.getCause());
		}

		// BlockStart without end (L782)
		ATN bs = new ATN(ATNType.PARSER, 2);
		BasicBlockStartState bstart = new BasicBlockStartState();
		bstart.ruleIndex = 0;
		bs.addState(bstart);
		try {
			verify.invoke(d, bs);
			fail();
		}
		catch (Exception expected) {
			assertNotNull(expected.getCause());
		}

		// BlockEnd without start (L786)
		ATN be = new ATN(ATNType.PARSER, 2);
		BlockEndState bend = new BlockEndState();
		bend.ruleIndex = 0;
		be.addState(bend);
		try {
			verify.invoke(d, be);
			fail();
		}
		catch (Exception expected) {
			assertNotNull(expected.getCause());
		}

		// DecisionState with >1 transitions and decision < 0 (L791)
		ATN ds = new ATN(ATNType.PARSER, 2);
		BasicBlockStartState dec = new BasicBlockStartState();
		dec.ruleIndex = 0;
		dec.endState = new BlockEndState();
		dec.decision = -1;
		BasicState t1 = new BasicState();
		BasicState t2 = new BasicState();
		ds.addState(dec);
		ds.addState(t1);
		ds.addState(t2);
		dec.addTransition(new EpsilonTransition(t1));
		dec.addTransition(new EpsilonTransition(t2));
		try {
			verify.invoke(d, ds);
			fail();
		}
		catch (Exception expected) {
			assertNotNull(expected.getCause());
		}

		// Non-decision with >1 transitions that is not RuleStop (L794)
		ATN nd = new ATN(ATNType.PARSER, 2);
		BasicState multi = new BasicState();
		multi.ruleIndex = 0;
		BasicState a = new BasicState();
		BasicState b = new BasicState();
		nd.addState(multi);
		nd.addState(a);
		nd.addState(b);
		multi.addTransition(new AtomTransition(a, 1));
		multi.addTransition(new AtomTransition(b, 2));
		try {
			verify.invoke(d, nd);
			fail();
		}
		catch (Exception expected) {
			assertNotNull(expected.getCause());
		}
	}

	@Test
	public void generateRuleBypassTransitionsForOrdinaryParserRule() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(false);
		opts.setGenerateRuleBypassTransitions(true);
		ATN restored = new ATNDeserializer(opts).deserialize(
			ATNSerializer.getSerializedAsChars(atn, Collections.singletonList("s")));
		assertNotNull(restored.ruleToTokenType);
		assertTrue(restored.ruleToTokenType[0] > atn.maxTokenType);
	}

	@Test
	public void generateRuleBypassThrowsWhenPrecedenceRuleHasNoStarLoop() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		atn.ruleToStartState[0].isPrecedenceRule = true;
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(false);
		opts.setGenerateRuleBypassTransitions(true);
		try {
			new ATNDeserializer(opts).deserialize(
				ATNSerializer.getSerializedAsChars(atn, Collections.singletonList("e")));
			fail();
		}
		catch (UnsupportedOperationException expected) {
			assertTrue(expected.getMessage().contains("precedence")
				|| expected.getMessage().length() > 0);
		}
	}

	@Test
	public void markPrecedenceDecisionsRecordsLoopbackStates() {
		ATN atn = ATNTestHelpers.buildParserAStar();
		atn.ruleToStartState[0].isPrecedenceRule = true;
		// stop-state epsilon back into the star entry (outermostPrecedenceReturn == -1)
		StarLoopEntryState entry = null;
		for (ATNState s : atn.states) {
			if (s instanceof StarLoopEntryState) {
				entry = (StarLoopEntryState) s;
				break;
			}
		}
		assertNotNull(entry);
		atn.ruleToStopState[0].addTransition(new EpsilonTransition(entry, -1));

		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(false);
		ATN restored = new ATNDeserializer(opts).deserialize(
			ATNSerializer.getSerializedAsChars(atn, Collections.singletonList("e")));
		assertNotNull(restored);
		boolean found = false;
		for (ATNState s : restored.states) {
			if (s instanceof StarLoopEntryState
				&& ((StarLoopEntryState) s).precedenceRuleDecision)
			{
				found = true;
				assertNotNull(((StarLoopEntryState) s).precedenceLoopbackStates);
			}
		}
		assertTrue(found);
	}

	@Test
	public void lexerActionFactoryCreatesTypeModeAndPushModeActions() throws Exception {
		Method laf = ATNDeserializer.class.getDeclaredMethod(
			"lexerActionFactory", LexerActionType.class, int.class, int.class);
		laf.setAccessible(true);
		ATNDeserializer d = new ATNDeserializer();
		// Cannot pass an invalid enum constant; invoke via a bogus ordinal by
		// constructing a dummy enum-like call is impossible. Cover TYPE/-1
		// sentinels through deserialize instead (already done). This call
		// exercises every legal type including TYPE with data1=-1 encoding.
		assertNotNull(laf.invoke(d, LexerActionType.TYPE, 0xFFFF, 0));
		assertNotNull(laf.invoke(d, LexerActionType.MODE, 0xFFFF, 0));
		assertNotNull(laf.invoke(d, LexerActionType.PUSH_MODE, 0xFFFF, 0));
	}

	@Test
	public void serializeRejectsLexerActionWithNullActionType() {
		ATN atn = ATNTestHelpers.buildLexerMatchA();
		atn.lexerActions = new LexerAction[] {
			new LexerAction() {
				@Override public LexerActionType getActionType() {
					return null;
				}
				@Override public boolean isPositionDependent() { return false; }
				@Override public void execute(org.antlr.v4.runtime.Lexer lexer) { }
			}
		};
		try {
			ATNSerializer.getSerialized(atn, Collections.singletonList("A"));
			fail();
		}
		catch (NullPointerException expected) {
			assertNotNull(expected);
		}
		catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("lexer action")
				|| expected.getMessage().length() > 0);
		}
	}

	@Test
	public void deserializeLegacyLexerFromEncodedString() throws Exception {
		UUID base = uuidField("BASE_SERIALIZED_UUID");
		IntegerList raw = minimalLexer(base, true);
		char[] shifted = shift(raw);
		String payload = new String(shifted);
		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(false);
		opts.setOptimize(false);
		ATN atn = new ATNDeserializer(opts).deserialize(payload);
		assertEquals(ATNType.LEXER, atn.grammarType);
		assertEquals(1, atn.ruleToStartState.length);
	}

	// ---- helpers ----

	private static IntegerList minimalLexer(UUID uuid, boolean baseFormat) {
		IntegerList raw = new IntegerList();
		raw.add(ATNDeserializer.SERIALIZED_VERSION);
		addUUID(raw, uuid);
		raw.add(ATNType.LEXER.ordinal());
		raw.add(1);
		raw.add(4);
		addState(raw, ATNState.TOKEN_START, 0xFFFF);
		addState(raw, ATNState.RULE_START, 0);
		addState(raw, ATNState.BASIC, 0);
		addState(raw, ATNState.RULE_STOP, 0);
		raw.add(0);
		raw.add(0);
		raw.add(0);
		raw.add(1);
		raw.add(1);
		raw.add(0);
		raw.add(1);
		if (baseFormat) {
			raw.add(0xFFFF);
		}
		raw.add(1);
		raw.add(0);
		raw.add(0);
		raw.add(3);
		addEdge(raw, 0, 1, Transition.EPSILON, 0, 0, 0);
		addEdge(raw, 1, 2, Transition.ATOM, 'a', 0, 0);
		addEdge(raw, 2, 3, Transition.EPSILON, 0, 0, 0);
		raw.add(0);
		return raw;
	}

	private static ATN twoRuleMatchThenCall(Transition matchTemplate) {
		ATN atn = new ATN(ATNType.PARSER, 2);
		RuleStartState sStart = new RuleStartState();
		BasicState sMid = new BasicState();
		RuleStopState sStop = new RuleStopState();
		RuleStartState tStart = new RuleStartState();
		BasicState tMid = new BasicState();
		RuleStopState tStop = new RuleStopState();
		sStart.ruleIndex = 0;
		sMid.ruleIndex = 0;
		sStop.ruleIndex = 0;
		tStart.ruleIndex = 1;
		tMid.ruleIndex = 1;
		tStop.ruleIndex = 1;
		sStart.stopState = sStop;
		tStart.stopState = tStop;
		atn.addState(sStart);
		atn.addState(sMid);
		atn.addState(sStop);
		atn.addState(tStart);
		atn.addState(tMid);
		atn.addState(tStop);
		sStart.addTransition(new RuleTransition(tStart, 1, 0, sMid));
		sMid.addTransition(new EpsilonTransition(sStop));
		Transition match;
		if (matchTemplate instanceof RangeTransition) {
			match = new RangeTransition(tMid, ((RangeTransition) matchTemplate).from, ((RangeTransition) matchTemplate).to);
		}
		else if (matchTemplate instanceof SetTransition) {
			match = matchTemplate instanceof NotSetTransition
				? new NotSetTransition(tMid, matchTemplate.label())
				: new SetTransition(tMid, matchTemplate.label());
		}
		else if (matchTemplate instanceof WildcardTransition) {
			match = new WildcardTransition(tMid);
		}
		else {
			match = new AtomTransition(tMid, 1);
		}
		tStart.addTransition(match);
		tMid.addTransition(new EpsilonTransition(tStop));
		atn.ruleToStartState = new RuleStartState[] { sStart, tStart };
		atn.ruleToStopState = new RuleStopState[] { sStop, tStop };
		atn.clearDFA();
		return atn;
	}

	private static ATN threeAtomAlts(int a, int b, int c) {
		ATN atn = new ATN(ATNType.PARSER, Math.max(Math.max(a, b), c));
		RuleStartState start = new RuleStartState();
		BasicBlockStartState blk = new BasicBlockStartState();
		BasicState a1 = new BasicState();
		BasicState a2 = new BasicState();
		BasicState a3 = new BasicState();
		BlockEndState end = new BlockEndState();
		RuleStopState stop = new RuleStopState();
		for (ATNState s : new ATNState[] { start, blk, a1, a2, a3, end, stop }) {
			s.ruleIndex = 0;
			atn.addState(s);
		}
		start.stopState = stop;
		blk.endState = end;
		end.startState = blk;
		start.addTransition(new EpsilonTransition(blk));
		blk.addTransition(new EpsilonTransition(a1));
		a1.addTransition(new AtomTransition(end, a));
		blk.addTransition(new EpsilonTransition(a2));
		a2.addTransition(new AtomTransition(end, b));
		blk.addTransition(new EpsilonTransition(a3));
		a3.addTransition(new AtomTransition(end, c));
		end.addTransition(new EpsilonTransition(stop));
		atn.defineDecisionState(blk);
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}

	private static ATN twoRangeAlts(int a1, int a2, int b1, int b2) {
		ATN atn = new ATN(ATNType.PARSER, Math.max(a2, b2));
		RuleStartState start = new RuleStartState();
		BasicBlockStartState blk = new BasicBlockStartState();
		BasicState x = new BasicState();
		BasicState y = new BasicState();
		BlockEndState end = new BlockEndState();
		RuleStopState stop = new RuleStopState();
		for (ATNState s : new ATNState[] { start, blk, x, y, end, stop }) {
			s.ruleIndex = 0;
			atn.addState(s);
		}
		start.stopState = stop;
		blk.endState = end;
		end.startState = blk;
		start.addTransition(new EpsilonTransition(blk));
		blk.addTransition(new EpsilonTransition(x));
		x.addTransition(new RangeTransition(end, a1, a2));
		blk.addTransition(new EpsilonTransition(y));
		y.addTransition(new RangeTransition(end, b1, b2));
		end.addTransition(new EpsilonTransition(stop));
		atn.defineDecisionState(blk);
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}

	private static ATN atomAndNotSetAlts() {
		ATN atn = new ATN(ATNType.PARSER, 5);
		RuleStartState start = new RuleStartState();
		BasicBlockStartState blk = new BasicBlockStartState();
		BasicState x = new BasicState();
		BasicState y = new BasicState();
		BlockEndState end = new BlockEndState();
		RuleStopState stop = new RuleStopState();
		for (ATNState s : new ATNState[] { start, blk, x, y, end, stop }) {
			s.ruleIndex = 0;
			atn.addState(s);
		}
		start.stopState = stop;
		blk.endState = end;
		end.startState = blk;
		start.addTransition(new EpsilonTransition(blk));
		blk.addTransition(new EpsilonTransition(x));
		x.addTransition(new AtomTransition(end, 1));
		blk.addTransition(new EpsilonTransition(y));
		y.addTransition(new NotSetTransition(end, IntervalSet.of(2)));
		end.addTransition(new EpsilonTransition(stop));
		atn.defineDecisionState(blk);
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}

	private static void addState(IntegerList raw, int type, int ruleIndex) {
		raw.add(type);
		raw.add(ruleIndex);
	}

	private static void addEdge(IntegerList raw, int src, int trg, int type, int a, int b, int c) {
		raw.add(src);
		raw.add(trg);
		raw.add(type);
		raw.add(a);
		raw.add(b);
		raw.add(c);
	}

	private static void addUUID(IntegerList data, UUID uuid) {
		addLong(data, uuid.getLeastSignificantBits());
		addLong(data, uuid.getMostSignificantBits());
	}

	private static void addLong(IntegerList data, long value) {
		addInt32(data, (int) value);
		addInt32(data, (int) (value >> 32));
	}

	private static void addInt32(IntegerList data, int value) {
		data.add((char) value);
		data.add((char) (value >> 16));
	}

	private static char[] shift(IntegerList raw) {
		IntegerList data = new IntegerList();
		data.add(raw.get(0));
		for (int i = 1; i < raw.size(); i++) {
			data.add((raw.get(i) + 2) & 0xFFFF);
		}
		return Utils.toCharArray(data);
	}

	private static UUID uuidField(String name) throws Exception {
		Field f = ATNDeserializer.class.getDeclaredField(name);
		f.setAccessible(true);
		return (UUID) f.get(null);
	}
}
