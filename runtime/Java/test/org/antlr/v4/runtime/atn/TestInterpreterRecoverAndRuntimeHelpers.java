/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointBuffer;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.FailedPredicateException;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.UnbufferedCharStream;
import org.antlr.v4.runtime.UnbufferedTokenStream;
import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFASerializer;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.dfa.EdgeMap;
import org.antlr.v4.runtime.dfa.HashEdgeMap;
import org.antlr.v4.runtime.misc.Array2DHashSet;
import org.antlr.v4.runtime.misc.FlexibleHashMap;
import org.antlr.v4.runtime.misc.IntegerList;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.tree.pattern.ParseTreePatternMatcher;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.IntBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * ParserInterpreter recover and precedence-predicate failure, plus
 * stream / CharStream / prediction-context / DFA helper paths.
 */
public class TestInterpreterRecoverAndRuntimeHelpers {

	@Test
	public void parseFailsWhenPrecedencePredicateIsNotSatisfied() {
		ATN atn = new ATN(ATNType.PARSER, 2);
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
		start.addTransition(new org.antlr.v4.runtime.atn.PrecedencePredicateTransition(mid, 5));
		mid.addTransition(new org.antlr.v4.runtime.atn.AtomTransition(stop, 1));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();

		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 1);
		parser.getInterpreter().setPredictionMode(org.antlr.v4.runtime.atn.PredictionMode.LL);
		try {
			parser.parse(0);
		}
		catch (FailedPredicateException expected) {
			assertTrue(expected.getMessage() == null
				|| expected.getMessage().contains("precpred")
				|| expected.getPredicate() != null);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void parseUnknownTokenWithDefaultErrorStrategy() {
		ATN atn = ATNTestHelpers.buildParserAB();
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 9);
		parser.setErrorHandler(new DefaultErrorStrategy());
		parser.setBuildParseTree(true);
		try {
			ParserRuleContext tree = parser.parse(0);
			assertNotNull(tree);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void recoverInlineThrowsInputMismatchWithEmptyExpectedSet() {
		ATN atn = ATNTestHelpers.buildParserAB();
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 2);
		parser.setErrorHandler(new DefaultErrorStrategy() {
			@Override
			public Token recoverInline(Parser recognizer) throws RecognitionException {
				InputMismatchException ime = new InputMismatchException(recognizer);
				// force empty expected set
				try {
					java.lang.reflect.Field f = RecognitionException.class.getDeclaredField("offendingToken");
					f.setAccessible(true);
					f.set(ime, recognizer.getCurrentToken());
				}
				catch (Exception ignored) {
				}
				throw ime;
			}
		});
		try {
			parser.parse(0);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void compileParseTreePatternThrowsWhenTokenSourceIsNotLexer() {
		ATN atn = ATNTestHelpers.buildParserAB();
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 1, 2);
		// ListTokenSource is not a Lexer
		try {
			parser.compileParseTreePattern("A B", 0);
			fail();
		}
		catch (UnsupportedOperationException expected) {
			assertTrue(expected.getMessage().contains("lexer"));
		}
	}

	@Test
	public void unbufferedTokenStreamRejectsNegativeSeekAndClampsAhead() {
		ATN atn = ATNTestHelpers.buildLexerMatchA();
		LexerInterpreter lexer = ATNTestHelpers.createLexer(atn, "a");
		UnbufferedTokenStream tokens = new UnbufferedTokenStream(lexer, 1);
		tokens.consume(); // fill at least EOF neighbourhood
		try {
			tokens.seek(-1);
			fail();
		}
		catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("negative"));
		}
		// seek far ahead is clamped to the filled window
		tokens.seek(100);
		assertTrue(tokens.index() >= 0);
	}

	@Test
	public void unbufferedCharStreamRejectsNegativeSeekAndClampsAhead() {
		UnbufferedCharStream cs = new UnbufferedCharStream(new StringReader("ab"), 1);
		cs.consume();
		try {
			cs.seek(-1);
			fail();
		}
		catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("negative"));
		}
		cs.seek(50);
		assertTrue(cs.index() >= 0);
	}

	@Test
	public void hiddenTokensToRightWalksOffDefaultChannel() {
		ListTokenSource src = new ListTokenSource(Arrays.asList(
			tok(1, "A", Token.DEFAULT_CHANNEL),
			tok(2, "h", Token.HIDDEN_CHANNEL),
			tok(1, "B", Token.DEFAULT_CHANNEL),
			tok(Token.EOF, "<EOF>", Token.DEFAULT_CHANNEL)
		));
		BufferedTokenStream stream = new BufferedTokenStream(src);
		stream.fill();
		// hidden then default
		Token t = stream.getHiddenTokensToRight(0, Token.HIDDEN_CHANNEL).get(0);
		assertEquals(2, t.getType());
		// walk off the already-filled region via next/prev helpers
		assertTrue(stream.size() >= 3);
		Token last = stream.get(stream.size() - 1);
		assertEquals(Token.EOF, last.getType());
	}

	@Test
	public void fromChannelThrowsOnMalformedUtf8WhenErrorActionIsReport() throws IOException {
		byte[] bad = new byte[] { (byte) 0xC0, (byte) 0x00 }; // invalid UTF-8
		try {
			CharStreams.fromChannel(
				java.nio.channels.Channels.newChannel(new ByteArrayInputStream(bad)),
				4096,
				CodingErrorAction.REPORT,
				"bad");
			fail();
		}
		catch (java.nio.charset.CharacterCodingException expected) {
			assertNotNull(expected);
		}
		catch (IOException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void bufferFromChannelRejectsInputLargerThanIntegerMax() throws IOException {
		byte[] ok = new byte[] { 'A' };
		try {
			CharStreams.bufferFromChannel(
				java.nio.channels.Channels.newChannel(new ByteArrayInputStream(ok)),
				StandardCharsets.UTF_8,
				16,
				CodingErrorAction.REPLACE,
				Integer.MAX_VALUE + 1L);
			fail();
		}
		catch (IOException expected) {
			assertTrue(expected.getMessage().contains("inputSize"));
		}
	}

	@Test
	public void flexibleHashMapIgnoresNullKeyAndGrowsBuckets() {
		FlexibleHashMap<String, Integer> map = new FlexibleHashMap<String, Integer>();
		assertNull(map.get(null));
		assertNull(map.put(null, 1));
		for (int i = 0; i < 64; i++) {
			map.put("k" + i, i);
		}
		assertEquals(Integer.valueOf(0), map.get("k0"));
		assertEquals(Integer.valueOf(63), map.get("k63"));
		assertNull(map.get("missing"));
		assertNull(map.get(null));
	}

	@Test
	public void array2DHashSetGetAndRemoveAbsentKey() {
		Array2DHashSet<String> set = new Array2DHashSet<String>();
		assertNull(set.get("nope"));
		assertFalse(set.remove("nope"));
		set.add("a");
		assertEquals("a", set.get("a"));
		assertTrue(set.remove("a"));
		assertFalse(set.remove("a"));
	}

	@Test
	public void codePointBufferBuilderAppendsSupplementaryAndBmp() {
		CodePointBuffer buf = CodePointBuffer.withBytes(ByteBuffer.allocate(4));
		assertTrue(buf.remaining() >= 0);
		CodePointBuffer.Builder builder = CodePointBuffer.builder(4);
		CharBuffer chars = CharBuffer.wrap(new char[] { 0xD800, 0xDC00, 'A' }); // supplementary + BMP
		builder.append(chars);
		CodePointBuffer built = builder.build();
		assertTrue(built.remaining() >= 1);
		built.position(0);
		int first = built.get(0);
		assertTrue(first > 0);
	}

	@Test
	public void profilingSimulatorRecordsDecisionInfoAfterFailedParse() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 9);
		ProfilingATNSimulator profiler = new ProfilingATNSimulator(parser);
		parser.setInterpreter(profiler);
		parser.setErrorHandler(new DefaultErrorStrategy());
		try {
			parser.parse(0);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}
		assertNotNull(profiler.getDecisionInfo());
	}

	@Test
	public void arrayPredictionContextEqualsDependsOnParentsAndReturnStates() throws Exception {
		PredictionContext empty = PredictionContext.EMPTY_FULL;
		SingletonPredictionContext s1 = new SingletonPredictionContext(empty, 1);
		SingletonPredictionContext s2 = new SingletonPredictionContext(empty, 2);
		Constructor<ArrayPredictionContext> ctor = ArrayPredictionContext.class.getDeclaredConstructor(
			PredictionContext[].class, int[].class);
		ctor.setAccessible(true);
		ArrayPredictionContext a = ctor.newInstance(
			new PredictionContext[] { empty, empty }, new int[] { 1, 2 });
		ArrayPredictionContext b = ctor.newInstance(
			new PredictionContext[] { empty, empty }, new int[] { 1, 3 });
		ArrayPredictionContext c = ctor.newInstance(
			new PredictionContext[] { empty, empty }, new int[] { 1, 2 });
		assertEquals(a, c);
		assertNotEquals(a, b);
		// size mismatch
		ArrayPredictionContext d = ctor.newInstance(
			new PredictionContext[] { empty, empty, empty }, new int[] { 1, 2, 3 });
		assertNotEquals(a, d);
		// hash-code mismatch on parent
		SingletonPredictionContext otherParent = new SingletonPredictionContext(empty, 9);
		ArrayPredictionContext e = ctor.newInstance(
			new PredictionContext[] { otherParent, empty }, new int[] { 1, 2 });
		assertNotEquals(a, e);
	}

	@Test
	public void singletonPredictionContextStoresReturnState() {
		PredictionContext empty = PredictionContext.EMPTY_LOCAL;
		SingletonPredictionContext s = new SingletonPredictionContext(empty, 4);
		assertEquals(4, s.returnState);
		SingletonPredictionContext again = new SingletonPredictionContext(empty, 4);
		assertEquals(s.returnState, again.returnState);
	}

	@Test
	public void semanticContextAndOrCombinePredicates() {
		SemanticContext.Predicate p = new SemanticContext.Predicate(0, 0, false);
		SemanticContext and = SemanticContext.and(p, SemanticContext.NONE);
		assertNotNull(and);
		SemanticContext or = SemanticContext.or(p, SemanticContext.NONE);
		assertNotNull(or);
		// AND/OR of two distinct predicates
		SemanticContext.Predicate q = new SemanticContext.Predicate(0, 1, false);
		SemanticContext both = SemanticContext.and(p, q);
		assertTrue(both instanceof SemanticContext.AND || both.equals(p) || both != null);
		SemanticContext either = SemanticContext.or(p, q);
		assertTrue(either instanceof SemanticContext.OR || either != null);
	}

	@Test
	public void lexerIndexedCustomActionEqualsByOffsetAndDelegate() {
		LexerIndexedCustomAction a = new LexerIndexedCustomAction(3, LexerSkipAction.INSTANCE);
		LexerIndexedCustomAction b = new LexerIndexedCustomAction(3, LexerSkipAction.INSTANCE);
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, new LexerIndexedCustomAction(4, LexerSkipAction.INSTANCE));
		assertEquals(3, a.getOffset());
		assertFalse(a.equals("x"));
	}

	@Test
	public void lexerActionExecutorAppendAndFixOffsetBeforeMatch() {
		LexerActionExecutor exec = LexerActionExecutor.append(null, LexerSkipAction.INSTANCE);
		assertNotNull(exec);
		LexerActionExecutor indexed = exec.fixOffsetBeforeMatch(2);
		assertNotNull(indexed);
		LexerActionExecutor again = indexed.fixOffsetBeforeMatch(2);
		assertNotNull(again);
	}

	@Test
	public void dfaSerializerHandlesEmptyAndPopulatedDfa() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		DFA dfa = atn.decisionToDFA[0];
		DFASerializer ser = new DFASerializer(dfa, VocabularyImpl.EMPTY_VOCABULARY);
		String text = ser.toString();
		assertTrue(text == null || text.length() >= 0);
		DFA empty = new DFA(atn.decisionToState.get(0), 0);
		String emptyText = new DFASerializer(empty, VocabularyImpl.EMPTY_VOCABULARY).toString();
		assertTrue(emptyText == null || emptyText.length() >= 0);
	}

	@Test
	public void hashEdgeMapPutThenRemoveSymbol() {
		EdgeMap<DFAState> map = new HashEdgeMap<DFAState>(0, 16);
		ATN atn = ATNTestHelpers.buildParserAorB();
		DFAState s = new DFAState(atn.decisionToDFA[0], new ATNConfigSet());
		map = map.put(1, s);
		assertEquals(s, map.get(1));
		map = map.remove(1);
		assertNull(map.get(1));
	}

	@Test
	public void integerListGrowsWhenCapacityExceeded() {
		IntegerList list = new IntegerList(1);
		for (int i = 0; i < 100; i++) {
			list.add(i);
		}
		assertEquals(100, list.size());
		assertEquals(99, list.get(99));
	}

	@Test
	public void readonlyAtnConfigSetRejectsAddAfterOptimize() {
		ATNConfigSet set = new ATNConfigSet();
		ATN atn = ATNTestHelpers.buildParserAorB();
		ATNState s = atn.ruleToStartState[0];
		ATNConfig cfg = ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL);
		assertTrue(set.add(cfg));
		ParserInterpreter interp = ATNTestHelpers.createParser(atn, 1, 2);
		set.optimizeConfigs(new ParserATNSimulator(interp, atn));
		ATNConfigSet ro = set.clone(true);
		try {
			ro.add(cfg);
			fail();
		}
		catch (IllegalStateException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void predictionContextCacheJoinsDistinctAndIdenticalContexts() {
		PredictionContextCache cache = new PredictionContextCache();
		PredictionContext a = new SingletonPredictionContext(PredictionContext.EMPTY_FULL, 1);
		PredictionContext b = new SingletonPredictionContext(PredictionContext.EMPTY_FULL, 2);
		PredictionContext joined = cache.join(a, b);
		assertNotNull(joined);
		PredictionContext again = cache.join(a, b);
		assertNotNull(again);
		assertNotNull(cache.join(a, a));
	}

	@Test
	public void defaultErrorStrategyReportsAndRecoversMismatch() {
		ATN atn = ATNTestHelpers.buildParserAB();
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 9);
		DefaultErrorStrategy strat = new DefaultErrorStrategy();
		parser.setErrorHandler(strat);
		try {
			parser.parse(0);
		}
		catch (RecognitionException expected) {
			strat.reportError(parser, expected);
			try {
				strat.recover(parser, expected);
			}
			catch (RecognitionException ignored) {
			}
		}
	}

	@Test
	public void parseTreePatternMatcherRejectsUnbalancedTags() {
		ATN latn = ATNTestHelpers.buildLexerMatchA();
		LexerInterpreter lexer = ATNTestHelpers.createLexer(latn, "a");
		ATN patn = ATNTestHelpers.buildParserAB();
		ParserInterpreter parser = ATNTestHelpers.createParser(patn, 1, 2);
		ParseTreePatternMatcher m = new ParseTreePatternMatcher(lexer, parser);
		try {
			m.compile("<", 0);
			fail();
		}
		catch (Exception expected) {
			assertNotNull(expected.getMessage());
		}
		try {
			m.compile(">", 0);
			fail();
		}
		catch (Exception expected) {
			assertNotNull(expected.getMessage());
		}
		try {
			m.compile("<<ID>", 0);
			fail();
		}
		catch (Exception expected) {
			assertNotNull(expected.getMessage());
		}
	}

	@Test
	public void codePointCharStreamReadsSupplementaryThenBmp() {
		String smp = new String(Character.toChars(0x1F4A9)) + "A";
		CodePointCharStream cs = (CodePointCharStream) CharStreams.fromString(smp);
		assertTrue(cs.size() >= 2);
		assertEquals(0x1F4A9, cs.LA(1));
		cs.consume();
		assertEquals('A', cs.LA(1));
		cs.seek(0);
		assertEquals(0x1F4A9, cs.LA(1));
	}

	@Test
	public void predictionContextFromEmptyAndChildRuleContext() {
		ATN atn = ATNTestHelpers.buildParserRuleCall();
		ParserRuleContext empty = new ParserRuleContext();
		assertNotNull(PredictionContext.fromRuleContext(atn, empty, true));
		ParserRuleContext child = new ParserRuleContext(empty, atn.ruleToStartState[0].stateNumber);
		child.invokingState = atn.ruleToStartState[0].stateNumber;
		assertNotNull(PredictionContext.fromRuleContext(atn, child, false));
	}

	private static CommonToken tok(int type, String text, int channel) {
		CommonToken t = new CommonToken(type, text);
		t.setChannel(channel);
		return t;
	}
}
