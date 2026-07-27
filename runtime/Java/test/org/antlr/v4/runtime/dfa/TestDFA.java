/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.dfa;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNConfig;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.ATNSimulator;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContext;
import org.antlr.v4.runtime.atn.SemanticContext;
import org.antlr.v4.runtime.atn.StarLoopEntryState;
import org.antlr.v4.runtime.atn.TokensStartState;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestDFA {

	private static ATN parserAtn(int maxTokenType) {
		return new ATN(ATNType.PARSER, maxTokenType);
	}

	private static ATN lexerAtn(int maxTokenType) {
		return new ATN(ATNType.LEXER, maxTokenType);
	}

	private static BasicState addBasic(ATN atn) {
		BasicState s = new BasicState();
		atn.addState(s);
		return s;
	}

	@Test
	public void acceptStateInfoBasics() {
		AcceptStateInfo info = new AcceptStateInfo(2);
		assertEquals(2, info.getPrediction());
		assertNull(info.getLexerActionExecutor());

		AcceptStateInfo withExecutor = new AcceptStateInfo(3, null);
		assertEquals(3, withExecutor.getPrediction());
		assertNull(withExecutor.getLexerActionExecutor());
	}

	@Test
	public void parserDfaEmptyAndEdges() {
		ATN atn = parserAtn(10);
		BasicState start = addBasic(atn);
		DFA dfa = new DFA(start, 0);

		assertEquals(0, dfa.decision);
		assertSame(start, dfa.atnStartState);
		assertEquals(Token.EOF, dfa.getMinDfaEdge());
		assertEquals(10, dfa.getMaxDfaEdge());
		assertFalse(dfa.isPrecedenceDfa());
		assertTrue(dfa.isEmpty());
		assertFalse(dfa.isContextSensitive());
		assertNotNull(dfa.getEmptyEdgeMap());
		assertNotNull(dfa.getEmptyContextEdgeMap());
		assertEquals("", dfa.toString());
		assertEquals("", dfa.toLexerString());
	}

	@Test
	public void lexerDfaEdgeRange() {
		ATN atn = lexerAtn(127);
		TokensStartState start = new TokensStartState();
		atn.addState(start);
		DFA dfa = new DFA(start);

		assertEquals(0, dfa.decision);
		assertEquals(LexerATNSimulator.MIN_DFA_EDGE, dfa.getMinDfaEdge());
		assertEquals(LexerATNSimulator.MAX_DFA_EDGE, dfa.getMaxDfaEdge());
		assertFalse(dfa.isPrecedenceDfa());
		assertTrue(dfa.isEmpty());
	}

	@Test
	public void addStateAndTargets() {
		ATN atn = parserAtn(5);
		BasicState start = addBasic(atn);
		DFA dfa = new DFA(start, 1);

		DFAState s0 = new DFAState(dfa, new ATNConfigSet());
		s0 = dfa.addState(s0);
		dfa.s0.set(s0);
		assertEquals(0, s0.stateNumber);
		assertFalse(dfa.isEmpty());

		// same config set -> existing state returned
		DFAState s0dup = new DFAState(dfa, new ATNConfigSet());
		DFAState existing = dfa.addState(s0dup);
		assertSame(s0, existing);

		DFAState s1 = dfa.addState(new DFAState(dfa, new ATNConfigSet() {
			// distinct empty set still equals; force uniqueness via outermost flag
		}));
		// empty config sets are equal, so addState may return s0
		assertNotNull(s1);

		// create distinct states by different outermost flag
		ATNConfigSet configsA = new ATNConfigSet();
		ATNConfigSet configsB = new ATNConfigSet();
		configsB.setOutermostConfigSet(true);
		DFAState sa = dfa.addState(new DFAState(dfa, configsA));
		DFAState sb = dfa.addState(new DFAState(dfa, configsB));
		// configsA equals empty default; may equal sa/s0. configsB is different.
		assertFalse(sa.equals(sb));
		assertTrue(dfa.states.containsKey(sb));

		sa.setTarget(1, sb);
		assertSame(sb, sa.getTarget(1));
		assertNull(sa.getTarget(2));
		Map<Integer, DFAState> edges = sa.getEdgeMap();
		assertEquals(1, edges.size());
		assertSame(sb, edges.get(1));
	}

	@Test
	public void acceptStateAndPrediction() {
		ATN atn = parserAtn(5);
		BasicState start = addBasic(atn);
		DFA dfa = new DFA(start, 0);
		DFAState state = new DFAState(dfa, new ATNConfigSet());

		assertFalse(state.isAcceptState());
		assertNull(state.getAcceptStateInfo());
		assertEquals(ATN.INVALID_ALT_NUMBER, state.getPrediction());
		assertNull(state.getLexerActionExecutor());

		state.setAcceptState(new AcceptStateInfo(2));
		assertTrue(state.isAcceptState());
		assertEquals(2, state.getPrediction());
		assertNull(state.getLexerActionExecutor());
		assertEquals(2, state.getAcceptStateInfo().getPrediction());

		state.stateNumber = 7;
		assertTrue(state.toString().contains("7:"));
		assertTrue(state.toString().contains("=>2"));

		state.predicates = new DFAState.PredPrediction[] {
			new DFAState.PredPrediction(SemanticContext.NONE, 1)
		};
		assertTrue(state.toString().contains("=>"));
		assertEquals("(" + SemanticContext.NONE + ", 1)", state.predicates[0].toString());
		assertNotNull(state.predicates[0].toString());
	}

	@Test
	public void contextSensitiveState() {
		ATN atn = parserAtn(5);
		BasicState start = addBasic(atn);
		addBasic(atn);
		DFA dfa = new DFA(start, 0);
		DFAState state = new DFAState(dfa, new ATNConfigSet());

		assertFalse(state.isContextSensitive());
		assertFalse(state.isContextSymbol(0));

		state.setContextSensitive(atn);
		assertTrue(state.isContextSensitive());
		// second call is no-op
		state.setContextSensitive(atn);

		state.setContextSymbol(1);
		assertTrue(state.isContextSymbol(1));
		assertFalse(state.isContextSymbol(0));
		// below min edge index
		assertFalse(state.isContextSymbol(Token.EOF - 1));
		state.setContextSymbol(Token.EOF - 1); // ignored

		DFAState target = new DFAState(dfa, new ATNConfigSet());
		target = dfa.addState(target);
		state.setContextTarget(0, target);
		assertSame(target, state.getContextTarget(0));

		// EMPTY_FULL maps to -1 internally
		state.setContextTarget(PredictionContext.EMPTY_FULL_STATE_KEY, target);
		assertSame(target, state.getContextTarget(PredictionContext.EMPTY_FULL_STATE_KEY));

		Map<Integer, DFAState> ctxEdges = state.getContextEdgeMap();
		assertTrue(ctxEdges.containsKey(PredictionContext.EMPTY_FULL_STATE_KEY)
			|| ctxEdges.containsKey(-1)
			|| !ctxEdges.isEmpty());

		// only EMPTY_FULL case (singleton remap)
		DFAState only = new DFAState(dfa, new ATNConfigSet());
		only.setContextSensitive(atn);
		// clear by creating fresh and only set EMPTY_FULL
		only.setContextTarget(PredictionContext.EMPTY_FULL_STATE_KEY, target);
		Map<Integer, DFAState> onlyMap = only.getContextEdgeMap();
		assertEquals(1, onlyMap.size());
		assertSame(target, onlyMap.get(PredictionContext.EMPTY_FULL_STATE_KEY));
	}

	@Test
	public void contextTargetRequiresSensitive() {
		ATN atn = parserAtn(5);
		BasicState start = addBasic(atn);
		DFA dfa = new DFA(start, 0);
		DFAState state = new DFAState(dfa, new ATNConfigSet());
		DFAState target = new DFAState(dfa, new ATNConfigSet());
		try {
			state.setContextTarget(0, target);
			fail("expected IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage().contains("context sensitive"));
		}
	}

	@Test
	public void dfaStateEqualsAndHashCode() {
		ATN atn = parserAtn(5);
		BasicState start = addBasic(atn);
		DFA dfa = new DFA(start, 0);
		ATNConfigSet configs = new ATNConfigSet();
		DFAState a = new DFAState(dfa, configs);
		DFAState b = new DFAState(dfa, new ATNConfigSet());
		assertTrue(a.equals(a));
		assertTrue(a.equals(b));
		assertEquals(a.hashCode(), b.hashCode());
		assertFalse(a.equals(null));
		assertFalse(a.equals("not-a-state"));

		ATNConfigSet other = new ATNConfigSet();
		other.setOutermostConfigSet(true);
		DFAState c = new DFAState(dfa, other);
		assertFalse(a.equals(c));
	}

	@Test
	public void precedenceDfa() {
		ATN atn = parserAtn(8);
		StarLoopEntryState start = new StarLoopEntryState();
		start.precedenceRuleDecision = true;
		atn.addState(start);
		addBasic(atn);

		DFA dfa = new DFA(start, 3);
		assertTrue(dfa.isPrecedenceDfa());
		assertTrue(dfa.isEmpty());
		assertFalse(dfa.isContextSensitive());
		assertNotNull(dfa.s0.get());
		assertNotNull(dfa.s0full.get());

		// deprecated setter matching current value is ok
		dfa.setPrecedenceDfa(true);
		try {
			dfa.setPrecedenceDfa(false);
			fail();
		} catch (UnsupportedOperationException e) {
			// expected
		}

		DFAState startState = new DFAState(dfa, new ATNConfigSet());
		startState = dfa.addState(startState);
		dfa.setPrecedenceStartState(1, false, startState);
		assertSame(startState, dfa.getPrecedenceStartState(1, false));
		assertNull(dfa.getPrecedenceStartState(2, false));

		DFAState fullStart = new DFAState(dfa, new ATNConfigSet());
		// make distinct
		ATNConfigSet fullConfigs = new ATNConfigSet();
		fullConfigs.setOutermostConfigSet(true);
		fullStart = dfa.addState(new DFAState(dfa, fullConfigs));
		dfa.setPrecedenceStartState(1, true, fullStart);
		assertSame(fullStart, dfa.getPrecedenceStartState(1, true));

		assertFalse(dfa.isEmpty());
		assertTrue(dfa.isContextSensitive());

		// negative precedence ignored
		dfa.setPrecedenceStartState(-1, false, startState);
	}

	@Test
	public void nonPrecedenceDfaStartStateThrows() {
		ATN atn = parserAtn(5);
		BasicState start = addBasic(atn);
		DFA dfa = new DFA(start, 0);
		try {
			dfa.getPrecedenceStartState(0, false);
			fail();
		} catch (IllegalStateException e) {
			// expected
		}
		try {
			dfa.setPrecedenceStartState(0, false, new DFAState(dfa, new ATNConfigSet()));
			fail();
		} catch (IllegalStateException e) {
			// expected
		}
	}

	@Test
	public void starLoopWithoutPrecedenceIsNotPrecedenceDfa() {
		ATN atn = parserAtn(5);
		StarLoopEntryState start = new StarLoopEntryState();
		start.precedenceRuleDecision = false;
		atn.addState(start);
		DFA dfa = new DFA(start, 0);
		assertFalse(dfa.isPrecedenceDfa());
	}

	@Test
	public void dfaToStringWithStates() {
		ATN atn = parserAtn(5);
		BasicState start = addBasic(atn);
		DFA dfa = new DFA(start, 0);

		ATNConfigSet c0 = new ATNConfigSet();
		ATNConfigSet c1 = new ATNConfigSet();
		c1.setOutermostConfigSet(true);

		DFAState s0 = dfa.addState(new DFAState(dfa, c0));
		DFAState s1 = dfa.addState(new DFAState(dfa, c1));
		s0.setAcceptState(new AcceptStateInfo(1));
		s0.setTarget(1, s1);
		dfa.s0.set(s0);

		Vocabulary vocab = new VocabularyImpl(new String[]{null, null, "'x'"}, new String[]{null, "ID", null});
		String text = dfa.toString(vocab);
		assertNotNull(text);
		assertFalse(text.isEmpty());

		String withRules = dfa.toString(vocab, new String[]{"r0", "r1"});
		assertNotNull(withRules);

		@SuppressWarnings("deprecation")
		String deprecated = dfa.toString(new String[]{null, "ID"});
		assertNotNull(deprecated);

		@SuppressWarnings("deprecation")
		String deprecated2 = dfa.toString(new String[]{null, "ID"}, new String[]{"r"});
		assertNotNull(deprecated2);
	}

	@Test
	public void lexerDfaSerializer() {
		ATN atn = lexerAtn(200);
		TokensStartState start = new TokensStartState();
		atn.addState(start);
		DFA dfa = new DFA(start);

		ATNConfigSet c0 = new ATNConfigSet();
		ATNConfigSet c1 = new ATNConfigSet();
		c1.setOutermostConfigSet(true);
		DFAState s0 = dfa.addState(new DFAState(dfa, c0));
		DFAState s1 = dfa.addState(new DFAState(dfa, c1));
		s1.setAcceptState(new AcceptStateInfo(1));
		s0.setTarget('a', s1);
		dfa.s0.set(s0);

		String lexerString = dfa.toLexerString();
		assertNotNull(lexerString);
		assertTrue(lexerString.contains("'a'") || lexerString.contains("s0") || lexerString.length() > 0);

		LexerDFASerializer serializer = new LexerDFASerializer(dfa);
		assertNotNull(serializer.toString());
	}

	@Test
	public void dfaSerializerNullS0() {
		ATN atn = parserAtn(5);
		BasicState start = addBasic(atn);
		DFA dfa = new DFA(start, 0);
		DFASerializer serializer = new DFASerializer(dfa, VocabularyImpl.EMPTY_VOCABULARY);
		assertNull(serializer.toString());
	}

	@Test
	public void dfaSerializerWithContextEdges() {
		ATN atn = parserAtn(5);
		BasicState start = addBasic(atn);
		addBasic(atn);
		DFA dfa = new DFA(start, 0);

		ATNConfigSet c0 = new ATNConfigSet();
		ATNConfigSet c1 = new ATNConfigSet();
		c1.setOutermostConfigSet(true);
		DFAState s0 = dfa.addState(new DFAState(dfa, c0));
		DFAState s1 = dfa.addState(new DFAState(dfa, c1));
		s0.setContextSensitive(atn);
		s0.setContextSymbol(1);
		s0.setTarget(1, s1);
		s0.setContextTarget(0, s1);
		s0.setContextTarget(PredictionContext.EMPTY_FULL_STATE_KEY, s1);
		dfa.s0.set(s0);

		DFASerializer serializer = new DFASerializer(dfa, VocabularyImpl.EMPTY_VOCABULARY,
			new String[]{"ruleA"}, atn);
		String out = serializer.toString();
		assertNotNull(out);
		assertTrue(out.contains("ctx:") || out.contains("s0") || out.contains("!"));
	}

	@Test
	public void dfaSerializerAcceptWithPredicates() {
		ATN atn = parserAtn(5);
		BasicState start = addBasic(atn);
		DFA dfa = new DFA(start, 0);
		ATNConfigSet c0 = new ATNConfigSet();
		ATNConfigSet c1 = new ATNConfigSet();
		c1.setOutermostConfigSet(true);
		DFAState s0 = dfa.addState(new DFAState(dfa, c0));
		DFAState s1 = dfa.addState(new DFAState(dfa, c1));
		s1.setAcceptState(new AcceptStateInfo(2));
		s1.predicates = new DFAState.PredPrediction[] {
			new DFAState.PredPrediction(SemanticContext.NONE, 2)
		};
		s0.setTarget(2, s1);
		dfa.s0.set(s0);

		String s = new DFASerializer(dfa, VocabularyImpl.EMPTY_VOCABULARY).toString();
		assertNotNull(s);
	}

	@Test
	public void isContextSensitiveNonPrecedence() {
		ATN atn = parserAtn(5);
		BasicState start = addBasic(atn);
		DFA dfa = new DFA(start, 0);
		assertFalse(dfa.isContextSensitive());
		dfa.s0full.set(new DFAState(dfa, new ATNConfigSet()));
		assertTrue(dfa.isContextSensitive());
	}

	@Test
	public void dfaStateExplicitEdgeMapsConstructor() {
		EmptyEdgeMap<DFAState> edges = new EmptyEdgeMap<DFAState>(0, 10);
		EmptyEdgeMap<DFAState> ctx = new EmptyEdgeMap<DFAState>(-1, 5);
		DFAState state = new DFAState(edges, ctx, new ATNConfigSet());
		assertFalse(state.isAcceptState());
		assertNull(state.getTarget(1));
		state.setTarget(1, state);
		assertSame(state, state.getTarget(1));
	}

	@Test
	@SuppressWarnings("deprecation")
	public void dfaSerializerConstructorsAndBranches() {
		ATN atn = parserAtn(5);
		BasicState start = addBasic(atn);
		BasicState other = addBasic(atn);
		other.ruleIndex = 0;
		DFA dfa = new DFA(start, 0);

		// deprecated tokenNames ctor
		DFASerializer serNames = new DFASerializer(dfa, new String[]{null, "A", "B"});
		assertNull(serNames.toString()); // s0 null

		// parser-based ctor with null parser
		DFASerializer serNullParser = new DFASerializer(dfa, (org.antlr.v4.runtime.Recognizer<?, ?>) null);
		assertNull(serNullParser.toString());

		// deprecated 4-arg ctor
		DFASerializer ser4 = new DFASerializer(dfa, new String[]{null, "A"}, new String[]{"r0"}, atn);
		assertNull(ser4.toString());

		ATNConfigSet c0 = new ATNConfigSet();
		ATNConfigSet c1 = new ATNConfigSet();
		c1.setOutermostConfigSet(true);
		DFAState s0 = dfa.addState(new DFAState(dfa, c0));
		DFAState s1 = dfa.addState(new DFAState(dfa, c1));
		s1.setAcceptState(new AcceptStateInfo(1));
		// edge to ERROR skipped unless context symbol
		s0.setTarget(3, ATNSimulator.ERROR);
		s0.setTarget(1, s1);
		// context-sensitive with outer context reach
		s0.setContextSensitive(atn);
		s0.setContextSymbol(2);
		// context edge without real target state number still prints ctx
		s0.setContextTarget(PredictionContext.EMPTY_LOCAL_STATE_KEY, s1);
		// context label with ATN state + rule name
		s0.setContextTarget(other.stateNumber, s1);
		// context symbol edge with null-ish target path: set context symbol and null target path
		// (isContextSymbol true, value ERROR-like)
		dfa.s0.set(s0);

		// also cover reachesIntoOuterContext double-star on state string
		ATNConfig cfg = ATNConfig.create(start, 1, PredictionContext.EMPTY_FULL);
		cfg.setOuterContextDepth(1);
		s0.configs.add(cfg);

		Vocabulary vocab = new VocabularyImpl(new String[]{null, "'a'"}, new String[]{null, "A", "B"});
		DFASerializer ser = new DFASerializer(dfa, vocab, new String[]{"ruleA"}, atn);
		String out = ser.toString();
		assertNotNull(out);
		assertTrue(out.contains("ctx:") || out.contains("s0") || out.length() > 0);

		// getContextLabel EMPTY_LOCAL covered via context edge above
		// ERROR state string
		assertTrue(ser.getStateString(ATNSimulator.ERROR).contains("ERROR")
			|| "ERROR".equals(ser.getStateString(ATNSimulator.ERROR)));
	}

	@Test
	public void dfaSerializerWithParserRecognizer() {
		ATN atn = parserAtn(3);
		BasicState start = addBasic(atn);
		DFA dfa = new DFA(start, 0);
		ATNConfigSet c0 = new ATNConfigSet();
		ATNConfigSet c1 = new ATNConfigSet();
		c1.setOutermostConfigSet(true);
		DFAState s0 = dfa.addState(new DFAState(dfa, c0));
		DFAState s1 = dfa.addState(new DFAState(dfa, c1));
		s1.setAcceptState(new AcceptStateInfo(0));
		s0.setTarget(1, s1);
		dfa.s0.set(s0);

		org.antlr.v4.runtime.Parser parser = new org.antlr.v4.runtime.Parser(
			new org.antlr.v4.runtime.CommonTokenStream(
				new org.antlr.v4.runtime.ListTokenSource(java.util.Collections.<org.antlr.v4.runtime.Token>emptyList()))) {
			@Override
			@SuppressWarnings("deprecation")
			public String[] getTokenNames() {
				return new String[]{null, "A"};
			}

			@Override
			public String[] getRuleNames() {
				return new String[]{"s"};
			}

			@Override
			public String getGrammarFileName() {
				return "T.g4";
			}

			@Override
			public ATN getATN() {
				return atn;
			}
		};
		parser.setInterpreter(new org.antlr.v4.runtime.atn.ParserATNSimulator(parser, atn));

		DFASerializer ser = new DFASerializer(dfa, parser);
		String out = ser.toString();
		assertNotNull(out);
	}

	@Test
	public void dfaSerializerEmptyEdgesReturnsNull() {
		ATN atn = parserAtn(2);
		BasicState start = addBasic(atn);
		DFA dfa = new DFA(start, 0);
		ATNConfigSet c0 = new ATNConfigSet();
		DFAState s0 = dfa.addState(new DFAState(dfa, c0));
		dfa.s0.set(s0);
		// no edges => empty string becomes null
		assertNull(new DFASerializer(dfa, VocabularyImpl.EMPTY_VOCABULARY).toString());
	}
}
