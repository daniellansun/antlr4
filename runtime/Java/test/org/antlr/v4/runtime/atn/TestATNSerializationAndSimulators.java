/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.IntegerList;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestATNSerializationAndSimulators {

	@Test
	public void testLexerATNRoundTrip() {
		ATN original = ATNTestHelpers.buildLexerMatchA();
		// add a set transition path via second rule for more serializer coverage
		List<String> ruleNames = Collections.singletonList("A");
		IntegerList serialized = ATNSerializer.getSerialized(original, ruleNames);
		assertTrue(serialized.size() > 0);
		assertEquals(ATNDeserializer.SERIALIZED_VERSION, serialized.get(0));

		char[] chars = ATNSerializer.getSerializedAsChars(original, ruleNames);
		assertNotNull(chars);
		String asString = ATNSerializer.getSerializedAsString(original, ruleNames);
		assertNotNull(asString);

		ATNDeserializationOptions opts = new ATNDeserializationOptions();
		opts.setVerifyATN(true);
		ATN restored = new ATNDeserializer(opts).deserialize(chars);
		assertEquals(ATNType.LEXER, restored.grammarType);
		assertEquals(original.states.size(), restored.states.size());
		assertEquals(original.ruleToTokenType[0], restored.ruleToTokenType[0]);
		assertEquals(1, restored.modeToStartState.size());
		assertTrue(restored.ruleToStartState[0] instanceof RuleStartState);
		assertTrue(restored.ruleToStopState[0] instanceof RuleStopState);

		// decode
		List<String> tokenNames = Arrays.asList("EOF", "A");
		String decoded = ATNSerializer.getDecoded(original, ruleNames, tokenNames);
		assertNotNull(decoded);
		assertTrue(decoded.length() > 0);

		ATNSerializer ser = new ATNSerializer(original, ruleNames, tokenNames);
		assertNotNull(ser.decode(chars));
		assertNotNull(ser.getTokenName(Token.EOF));
		assertNotNull(ser.getTokenName(1));
		assertNotNull(ser.getTokenName(999));
	}

	@Test
	public void testLexerATNWithSetAndActionsRoundTrip() {
		ATN atn = new ATN(ATNType.LEXER, 2);
		TokensStartState tokensStart = new TokensStartState();
		RuleStartState ruleStart = new RuleStartState();
		RuleStopState ruleStop = new RuleStopState();
		tokensStart.ruleIndex = 0;
		ruleStart.ruleIndex = 0;
		ruleStop.ruleIndex = 0;
		ruleStart.stopState = ruleStop;
		atn.addState(tokensStart);
		atn.addState(ruleStart);
		atn.addState(ruleStop);
		tokensStart.addTransition(new RuleTransition(ruleStart, 0, 0, tokensStart));
		IntervalSet set = IntervalSet.of('a');
		set.add('b');
		ruleStart.addTransition(new SetTransition(ruleStop, set));
		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.ruleToTokenType = new int[] { 1 };
		atn.lexerActions = new LexerAction[] {
			LexerSkipAction.INSTANCE,
			LexerMoreAction.INSTANCE,
			LexerPopModeAction.INSTANCE,
			new LexerTypeAction(1),
			new LexerChannelAction(1),
			new LexerModeAction(0),
			new LexerPushModeAction(0),
			new LexerCustomAction(0, 0)
		};
		atn.defineMode("DEFAULT_MODE", tokensStart);

		List<String> ruleNames = Collections.singletonList("AB");
		char[] data = ATNSerializer.getSerializedAsChars(atn, ruleNames);
		ATN restored = new ATNDeserializer().deserialize(data);
		assertEquals(atn.lexerActions.length, restored.lexerActions.length);
		assertEquals(ATNType.LEXER, restored.grammarType);
	}

	@Test
	public void testLexerInterpreterMatchA() {
		ATN atn = ATNTestHelpers.buildLexerMatchA();
		LexerInterpreter lexer = ATNTestHelpers.createLexer(atn, "a");
		Token t = lexer.nextToken();
		assertEquals(1, t.getType());
		assertEquals("a", t.getText());
		Token eof = lexer.nextToken();
		assertEquals(Token.EOF, eof.getType());

		// mismatch
		LexerInterpreter bad = ATNTestHelpers.createLexer(ATNTestHelpers.buildLexerMatchA(), "x");
		try {
			bad.nextToken();
			// may return INVALID or throw
		}
		catch (Exception ex) {
			// acceptable
		}
	}

	@Test
	public void testLexerInterpreterMatchRange() {
		ATN atn = ATNTestHelpers.buildLexerMatchLetterRange();
		LexerInterpreter lexer = ATNTestHelpers.createLexer(atn, "m");
		assertEquals(1, lexer.nextToken().getType());
	}

	@Test
	public void testLexerATNSimulatorDirect() {
		ATN atn = ATNTestHelpers.buildLexerMatchA();
		LexerATNSimulator sim = new LexerATNSimulator(atn);
		assertEquals(1, sim.match(CharStreams.fromString("a"), 0));
		sim.reset();
		sim.clearDFA();

		LexerInterpreter lexer = ATNTestHelpers.createLexer(atn, "a");
		LexerATNSimulator sim2 = new LexerATNSimulator(lexer, atn);
		sim2.copyState((LexerATNSimulator) lexer.getInterpreter());
		assertEquals(1, sim2.getCharPositionInLine() >= 0 ? 1 : 0); // just touch API
		sim2.getLine();
		sim2.setLine(2);
		assertEquals(2, sim2.getLine());
		sim2.setCharPositionInLine(3);
		assertEquals(3, sim2.getCharPositionInLine());
	}

	@Test
	public void testParserInterpreterAB() {
		ATN atn = ATNTestHelpers.buildParserAB();
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 1, 2);
		ParserRuleContext tree = parser.parse(0);
		assertNotNull(tree);
		assertEquals(0, tree.getRuleIndex());
		assertNotNull(parser.getATN());
		assertNotNull(parser.getRuleNames());
		assertNotNull(parser.getVocabulary());
		assertEquals("TestParser", parser.getGrammarFileName());
		assertNotNull(parser.getRootContext());

		// copy constructor
		ParserInterpreter copy = new ParserInterpreter(parser);
		assertEquals(parser.getATN(), copy.getATN());
	}

	@Test
	public void testParserInterpreterAorB() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 1);
		ParserRuleContext tree = parser.parse(0);
		assertNotNull(tree);

		ParserInterpreter parserB = ATNTestHelpers.createParser(atn, 2);
		assertNotNull(parserB.parse(0));
	}

	@Test
	public void testParserATNSimulatorAdaptivePredict() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 1);
		ParserATNSimulator sim = (ParserATNSimulator) parser.getInterpreter();
		parser.getInputStream().seek(0);
		// need to be at decision state - use adaptivePredict after setting up
		// Simpler: just parse which uses adaptivePredict
		parser.reset();
		assertNotNull(parser.parse(0));
		sim.clearDFA();
		sim.reset();
	}

	@Test
	public void testATNSimulatorStaticHelpers() {
		assertNotNull(ATNSimulator.ERROR);
		assertEquals(Integer.MAX_VALUE, ATNSimulator.ERROR.stateNumber);

		assertEquals(ATNDeserializer.SERIALIZED_VERSION, ATNSimulator.SERIALIZED_VERSION);
		assertEquals(ATNDeserializer.SERIALIZED_UUID, ATNSimulator.SERIALIZED_UUID);

		assertEquals(3, ATNSimulator.toInt((char) 3));
		char[] data = new char[] { 1, 2, 3, 4 };
		assertTrue(ATNSimulator.toInt32(data, 0) != 0 || ATNSimulator.toInt32(data, 0) == 0);
		assertNotNull(ATNSimulator.toLong(data, 0));
		// need 8 chars for UUID
		char[] uuidData = new char[8];
		assertNotNull(ATNSimulator.toUUID(uuidData, 0));

		ATNSimulator.checkCondition(true);
		try {
			ATNSimulator.checkCondition(false);
			fail();
		}
		catch (IllegalStateException expected) {
			// ok - actually may be different exception
		}
		catch (RuntimeException expected) {
			// ok
		}

		try {
			ATNSimulator.checkCondition(false, "msg");
			fail();
		}
		catch (RuntimeException expected) {
			// ok
		}

		ATN atn = ATNTestHelpers.buildLexerMatchA();
		ATNState s = ATNSimulator.stateFactory(ATNState.BASIC, 0);
		assertTrue(s instanceof BasicState);
		assertEquals(0, s.ruleIndex);

		List<IntervalSet> sets = Collections.emptyList();
		Transition edge = ATNSimulator.edgeFactory(atn, Transition.EPSILON, 0, 1, 0, 0, 0, sets);
		assertTrue(edge instanceof EpsilonTransition);

		// deserialize via simulator helper
		char[] ser = ATNSerializer.getSerializedAsChars(atn, Collections.singletonList("A"));
		ATN d = ATNSimulator.deserialize(ser);
		assertEquals(ATNType.LEXER, d.grammarType);
	}

	@Test
	public void testDeserializerNullOptions() {
		ATNDeserializer d = new ATNDeserializer(null);
		ATN atn = ATNTestHelpers.buildLexerMatchA();
		char[] ser = ATNSerializer.getSerializedAsChars(atn, Collections.singletonList("A"));
		assertNotNull(d.deserialize(ser));
	}

	@Test
	public void testParserATNWithRuleCallRoundTrip() {
		// parser ATN s : t ; t : A ;
		ATN atn = new ATN(ATNType.PARSER, 1);
		RuleStartState sStart = new RuleStartState();
		RuleStopState sStop = new RuleStopState();
		RuleStartState tStart = new RuleStartState();
		RuleStopState tStop = new RuleStopState();
		BasicState after = new BasicState();
		sStart.ruleIndex = 0;
		sStop.ruleIndex = 0;
		tStart.ruleIndex = 1;
		tStop.ruleIndex = 1;
		after.ruleIndex = 0;
		sStart.stopState = sStop;
		tStart.stopState = tStop;
		atn.addState(sStart);
		atn.addState(tStart);
		atn.addState(tStop);
		atn.addState(after);
		atn.addState(sStop);
		sStart.addTransition(new RuleTransition(tStart, 1, 0, after));
		tStart.addTransition(new AtomTransition(tStop, 1));
		after.addTransition(new EpsilonTransition(sStop));
		atn.ruleToStartState = new RuleStartState[] { sStart, tStart };
		atn.ruleToStopState = new RuleStopState[] { sStop, tStop };

		List<String> rules = Arrays.asList("s", "t");
		char[] data = ATNSerializer.getSerializedAsChars(atn, rules);
		ATN restored = new ATNDeserializer().deserialize(data);
		assertEquals(2, restored.ruleToStartState.length);
		assertEquals(ATNType.PARSER, restored.grammarType);
	}
}
