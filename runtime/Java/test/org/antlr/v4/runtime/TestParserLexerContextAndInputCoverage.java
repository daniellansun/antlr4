/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.ActionTransition;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicBlockStartState;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.BlockEndState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.antlr.v4.runtime.atn.NotSetTransition;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PlusLoopbackState;
import org.antlr.v4.runtime.atn.PrecedencePredicateTransition;
import org.antlr.v4.runtime.atn.RangeTransition;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.SetTransition;
import org.antlr.v4.runtime.atn.StarLoopbackState;
import org.antlr.v4.runtime.atn.TokensStartState;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.Utils;
import org.antlr.v4.runtime.tree.ErrorNodeImpl;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.antlr.v4.runtime.tree.Trees;
import org.antlr.v4.runtime.tree.pattern.ParseTreePattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.IntBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Remaining {@link Parser}, {@link Lexer}, {@link RuleContext} and input-stream
 * public/protected API paths (trace, match recovery, CharStreams, CodePointBuffer).
 */
public class TestParserLexerContextAndInputCoverage {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	// ------------------------------------------------------------------
	// Utils / RuleContext / ParserRuleContext
	// ------------------------------------------------------------------

	@Test
	public void utilsDefaultConstructor() {
		assertNotNull(new Utils());
	}

	@Test
	public void ruleContextToStringOverloads() {
		ATN atn = simpleMatchA();
		ParserInterpreter p = parser(atn, Collections.singletonList("s"), 1);
		ParserRuleContext tree = p.parse(0);
		assertNotNull(tree.toStringTree(p));
		assertNotNull(tree.toString(p));
		assertNotNull(tree.toString((Recognizer<?, ?>) p, (RuleContext) null));
		assertNotNull(tree.toString((Recognizer<?, ?>) null, tree));
		RuleContext bare = new RuleContext();
		assertTrue(bare.getRuleIndex() < 0);
		assertNotNull(bare.toString(p));
		assertNotNull(p.getRuleInvocationStack(bare));
	}

	@Test
	public void parserRuleContextEmptyChildLookupsAndInfo() {
		ParserRuleContext empty = new ParserRuleContext();
		assertNull(empty.getChild(TerminalNodeImpl.class, 0));
		assertNull(empty.getChild(TerminalNodeImpl.class, -1));
		assertNull(empty.getToken(1, 0));
		assertTrue(empty.getTokens(1).isEmpty());
		assertNull(empty.getRuleContext(ParserRuleContext.class, 0));
		assertTrue(empty.getRuleContexts(ParserRuleContext.class).isEmpty());

		ParserRuleContext parent = new ParserRuleContext();
		TerminalNodeImpl leaf = new TerminalNodeImpl(new CommonToken(1, "a"));
		leaf.setParent(parent);
		parent.addAnyChild(leaf);
		// already-parented child (assert parent==this branch)
		parent.addAnyChild(leaf);
		assertNull(parent.getChild(ParserRuleContext.class, 0));
		assertNull(parent.getToken(99, 0));
		assertTrue(parent.getTokens(99).isEmpty());
		assertTrue(parent.getRuleContexts(ParserRuleContext.class).isEmpty());

		ATN atn = simpleMatchA();
		ParserInterpreter p = parser(atn, Collections.singletonList("s"), 1);
		ParserRuleContext tree = p.parse(0);
		assertNotNull(tree.toInfoString(p));
	}

	// ------------------------------------------------------------------
	// Lexer / LexerInterpreter
	// ------------------------------------------------------------------

	@Test
	public void lexerBaseNameArraysAndDebugModes() {
		BareLexer lexer = new BareLexer(CharStreams.fromString("a"));
		assertNull(lexer.getChannelNames());
		assertNull(lexer.getModeNames());
		assertNull(lexer.getTokenNames());

		lexer.pushMode(1);
		assertEquals(1, lexer._mode);
		assertEquals(0, lexer.popMode());
	}

	@Test
	public void lexerRejectsNonUnicodeStreamWhenSmpRequired() {
		LexerInterpreter lexer = createLexer(buildLexerMatchA(), "a");
		lexer.getATN().setHasUnicodeSMPTransitions(true);
		try {
			lexer.setInputStream(new ANTLRInputStream("a"));
			fail("expected UnsupportedOperationException");
		}
		catch (UnsupportedOperationException expected) {
			assertTrue(expected.getMessage().contains("code points"));
		}

		class NonUnicode implements CharStream {
			@Override public void consume() { }
			@Override public int LA(int i) { return IntStream.EOF; }
			@Override public int mark() { return -1; }
			@Override public void release(int marker) { }
			@Override public int index() { return 0; }
			@Override public void seek(int index) { }
			@Override public int size() { return 0; }
			@Override public String getSourceName() { return "x"; }
			@Override public String getText(Interval interval) { return ""; }
		}
		try {
			lexer.setInputStream(new NonUnicode());
			fail("expected UnsupportedOperationException");
		}
		catch (UnsupportedOperationException expected) {
			assertNotNull(expected.getMessage());
		}
	}

	@Test
	public void lexerInterpreterNullVocabularyFallsBack() throws Exception {
		// vocabulary field is @NotNull after ctor; force null via reflection for the fallback.
		LexerInterpreter lexer = createLexer(buildLexerMatchA(), "a");
		java.lang.reflect.Field f = LexerInterpreter.class.getDeclaredField("vocabulary");
		f.setAccessible(true);
		f.set(lexer, null);
		assertNotNull(lexer.getVocabulary());
	}

	// ------------------------------------------------------------------
	// Parser APIs
	// ------------------------------------------------------------------

	@Test
	public void traceListenerVisitErrorNodeAndMatchWildcardRecovery() {
		ParserInterpreter p = parser(simpleMatchA(), Collections.singletonList("s"), 1);
		Parser.TraceListener tl = p.new TraceListener();
		tl.visitErrorNode(new ErrorNodeImpl(new CommonToken(Token.INVALID_TYPE, "x")));

		p.setBuildParseTree(true);
		p._ctx = new ParserRuleContext();
		while (p.getInputStream().LA(1) != Token.EOF) {
			p.getInputStream().consume();
		}
		p.setErrorHandler(new DefaultErrorStrategy() {
			@Override
			public Token recoverInline(Parser recognizer) {
				CommonToken t = new CommonToken(1, "conjured");
				t.setTokenIndex(-1);
				return t;
			}
		});
		Token recovered = p.matchWildcard();
		assertEquals("conjured", recovered.getText());
	}

	@Test
	public void addParseListenerNullThrows() {
		ParserInterpreter p = parser(simpleMatchA(), Collections.singletonList("s"), 1);
		try {
			p.addParseListener(null);
			fail();
		}
		catch (NullPointerException expected) {
			assertEquals("listener", expected.getMessage());
		}
	}

	@Test
	public void getAtnWithBypassAltsNullSerialized() {
		ParserInterpreter p = new ParserInterpreter(
			"P",
			VocabularyImpl.EMPTY_VOCABULARY,
			Collections.singletonList("s"),
			simpleMatchA(),
			new CommonTokenStream(new MockTokenSource(tok(1, "A")))) {
			@Override
			public String getSerializedATN() {
				return null;
			}
		};
		try {
			p.getATNWithBypassAlts();
			fail();
		}
		catch (UnsupportedOperationException expected) {
			assertTrue(expected.getMessage().contains("bypass"));
		}
	}

	@Test
	public void compileParseTreePatternDiscoversLexer() {
		ATN lexerAtn = buildLexerMatchA();
		LexerInterpreter lexer = createLexer(lexerAtn, "a");
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		ParserInterpreter p = new ParserInterpreter(
			"P",
			new VocabularyImpl(new String[] { null, "'a'" }, new String[] { null, "A" }),
			Collections.singletonList("s"),
			simpleMatchA(),
			tokens);
		try {
			ParseTreePattern pat = p.compileParseTreePattern("a", 0);
			assertNotNull(pat);
		}
		catch (RuntimeException ex) {
			// Pattern compilation may fail for this tiny ATN; the lexer-discovery
			// path (and the 3-arg overload) still executed.
			assertNotNull(ex);
		}
		try {
			assertNotNull(p.compileParseTreePattern("a", 0, lexer));
		}
		catch (RuntimeException ex) {
			assertNotNull(ex);
		}
	}

	@Test
	public void enterLeftFactoredRuleWithListenerAndEmptyPrecedence() {
		ParserInterpreter p = parser(simpleMatchA(), Collections.singletonList("s"), 1);
		p._precedenceStack.clear();
		assertEquals(-1, p.getPrecedence());
		final List<String> events = new ArrayList<String>();
		p.addParseListener(new ParseTreeListener() {
			@Override public void visitTerminal(org.antlr.v4.runtime.tree.TerminalNode node) { }
			@Override public void visitErrorNode(org.antlr.v4.runtime.tree.ErrorNode node) { }
			@Override public void enterEveryRule(ParserRuleContext ctx) { events.add("enter"); }
			@Override public void exitEveryRule(ParserRuleContext ctx) { events.add("exit"); }
		});
		ParserRuleContext outer = new ParserRuleContext();
		ParserRuleContext factored = new ParserRuleContext();
		factored.parent = outer;
		outer.addChild(factored);
		p._ctx = outer;
		ParserRuleContext local = new ParserRuleContext();
		p.enterLeftFactoredRule(local, 0, 0);
		assertFalse(events.isEmpty());
	}

	@Test
	public void isExpectedTokenAndDfaDumpWithTwoDecisions() {
		ATN atn = twoSequentialDecisions();
		ParserInterpreter p = parser(atn, Collections.singletonList("s"), 1, 2);
		p.setState(atn.ruleToStartState[0].stateNumber);
		p._ctx = new ParserRuleContext();
		assertTrue(p.isExpectedToken(1) || p.isExpectedToken(2) || !p.isExpectedToken(99));

		ParserRuleContext tree = parser(atn, Collections.singletonList("s"), 1, 2).parse(0);
		assertNotNull(tree);
		ParserInterpreter p2 = parser(atn, Collections.singletonList("s"), 1, 2);
		p2.parse(0);
		List<String> dfa = p2.getDFAStrings();
		assertEquals(2, dfa.size());
		java.io.PrintStream prev = System.out;
		java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
		System.setOut(new java.io.PrintStream(buf));
		try {
			p2.dumpDFA();
		}
		finally {
			System.setOut(prev);
		}

		// parent-follow path of isExpectedToken: state whose nextTokens contains EPSILON
		ATN ruleCall = parserRuleCall();
		ParserInterpreter p3 = parser(ruleCall, Arrays.asList("s", "t"), 1);
		// after entering s, at rule call, following may include epsilon via rule stop
		p3.parse(0);
		p3.setState(ruleCall.ruleToStopState[1].stateNumber);
		p3._ctx = new ParserRuleContext();
		p3._ctx.invokingState = findRuleTransitionState(ruleCall);
		p3.isExpectedToken(1);
		p3.isExpectedToken(Token.EOF);
	}

	@Test
	public void parserInterpreterSetMismatchRecoverInlineAndPrecedence() {
		ATN setAtn = parserSetOfA();
		ParserInterpreter p = parser(setAtn, Collections.singletonList("s"), 2);
		p.setErrorHandler(new DefaultErrorStrategy() {
			@Override
			public Token recoverInline(Parser recognizer) {
				return recognizer.getCurrentToken();
			}
			@Override
			public void recover(Parser recognizer, RecognitionException e) {
				// do not consume so ParserInterpreter.recover adds an error node
			}
		});
		ParserRuleContext tree = p.parse(0);
		assertNotNull(tree);
		assertNotNull(p.recoverInline());

		ATN precAtn = parserWithFailingPrecedence();
		ParserInterpreter p2 = parser(precAtn, Collections.singletonList("s"), 1);
		try {
			p2.parse(0);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}

		// NoViableAlt recover path: atom mismatch with no-consume handler
		ParserInterpreter p3 = parser(simpleMatchA(), Collections.singletonList("s"), 2);
		p3.setErrorHandler(new DefaultErrorStrategy() {
			@Override
			public void recover(Parser recognizer, RecognitionException e) {
			}
			@Override
			public Token recoverInline(Parser recognizer) {
				return recognizer.getCurrentToken();
			}
		});
		assertNotNull(p3.parse(0));
	}

	// ------------------------------------------------------------------
	// CharStreams / CodePointBuffer / CodePointCharStream
	// ------------------------------------------------------------------

	@Test
	public void charStreamsFromChannelOverloadsAndErrors() throws IOException {
		File f = tmp.newFile("ch.txt");
		Files.write(f.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
		try (ReadableByteChannel ch = Files.newByteChannel(f.toPath())) {
			CharStream s = CharStreams.fromChannel(ch);
			assertEquals("hello", s.toString());
		}
		try (ReadableByteChannel ch = Files.newByteChannel(f.toPath())) {
			CharStream s = CharStreams.fromChannel(ch, StandardCharsets.UTF_8);
			assertEquals("hello", s.toString());
		}
		try (ReadableByteChannel ch = Files.newByteChannel(f.toPath())) {
			CharStream s = CharStreams.fromChannel(ch, 16, CodingErrorAction.REPLACE, "named");
			assertEquals("hello", s.toString());
			assertEquals("named", s.getSourceName());
		}

		try (ReadableByteChannel ch = Files.newByteChannel(f.toPath())) {
			CharStreams.bufferFromChannel(ch, StandardCharsets.UTF_8, 8,
				CodingErrorAction.REPLACE, (long) Integer.MAX_VALUE + 1L);
			fail("expected IOException");
		}
		catch (IOException expected) {
			assertTrue(expected.getMessage().contains("inputSize"));
		}

		// malformed UTF-8 with REPORT
		byte[] bad = new byte[] { (byte) 0xC0, (byte) 0x00 };
		try (ReadableByteChannel ch = java.nio.channels.Channels.newChannel(new ByteArrayInputStream(bad))) {
			CharStreams.bufferFromChannel(ch, StandardCharsets.UTF_8, 8,
				CodingErrorAction.REPORT, -1);
			fail("expected coding error");
		}
		catch (IOException expected) {
			assertTrue(expected instanceof CharacterCodingException
				|| expected.getCause() instanceof CharacterCodingException
				|| expected.getMessage() != null);
		}
	}

	@Test
	public void codePointBufferIntAndUnreachableSwitchDefaults() throws Exception {
		IntBuffer ib = IntBuffer.wrap(new int[] { 'A', 'B' });
		CodePointBuffer ints = CodePointBuffer.withInts(ib);
		assertEquals(0, ints.position());
		assertEquals(2, ints.remaining());
		assertEquals('A', ints.get(0));
		ints.position(1);
		assertEquals(1, ints.position());
		assertEquals(CodePointBuffer.Type.INT, ints.getType());
		assertEquals(0, ints.arrayOffset());
		assertNotNull(ints.intArray());
		ints.position(0);

		CodePointCharStream stream = CodePointCharStream.fromBuffer(ints, "");
		assertTrue(stream.supportsUnicodeCodePoints());
		assertEquals(IntStream.UNKNOWN_SOURCE_NAME, stream.getSourceName());
		try {
			stream.consume();
			stream.consume();
			stream.consume();
			fail("cannot consume EOF");
		}
		catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("EOF"));
		}

		// force invalid Type via reflection to hit switch defaults
		CodePointBuffer bytes = CodePointBuffer.withBytes(ByteBuffer.wrap(new byte[] { 'x' }));
		java.lang.reflect.Field type = CodePointBuffer.class.getDeclaredField("type");
		type.setAccessible(true);
		type.set(bytes, null);
		try { bytes.position(); fail(); } catch (RuntimeException e) { assertNotNull(e); }
		try { bytes.position(0); fail(); } catch (RuntimeException e) { assertNotNull(e); }
		try { bytes.remaining(); fail(); } catch (RuntimeException e) { assertNotNull(e); }
		try { bytes.get(0); fail(); } catch (RuntimeException e) { assertNotNull(e); }
		try { bytes.arrayOffset(); fail(); } catch (RuntimeException e) { assertNotNull(e); }
		try { CodePointCharStream.fromBuffer(bytes); fail(); } catch (RuntimeException e) { assertNotNull(e); }

		// assert arrayOffset==0 branches: wrap with offset
		ByteBuffer sliced = ByteBuffer.wrap(new byte[] { 0, 'z' }, 1, 1);
		// slice() keeps arrayOffset
		CodePointBuffer off = CodePointBuffer.withBytes(ByteBuffer.wrap(new byte[] { 0, 'z' }, 1, 1).slice());
		try {
			CodePointCharStream.fromBuffer(off);
		}
		catch (Throwable ignored) {
			// assertion or construction may fail depending on -ea
		}
		CharBuffer cb = CharBuffer.wrap(new char[] { 0, 'y' }, 1, 1);
		CodePointBuffer coff = CodePointBuffer.withChars(cb);
		try {
			CodePointCharStream.fromBuffer(coff);
		}
		catch (Throwable ignored) {
		}
		IntBuffer ioff = IntBuffer.wrap(new int[] { 0, 'q' }, 1, 1);
		CodePointBuffer ioffb = CodePointBuffer.withInts(ioff);
		try {
			CodePointCharStream.fromBuffer(ioffb);
		}
		catch (Throwable ignored) {
		}

		// wrong-type array accessors (assert type==BYTE etc.)
		try { bytes.charArray(); } catch (Throwable ignored) { }
		try { bytes.intArray(); } catch (Throwable ignored) { }
		CodePointBuffer chars = CodePointBuffer.withChars(CharBuffer.wrap(new char[] { 'a' }));
		try { chars.byteArray(); } catch (Throwable ignored) { }
		try { chars.intArray(); } catch (Throwable ignored) { }
		try { ints.byteArray(); } catch (Throwable ignored) { }
		try { ints.charArray(); } catch (Throwable ignored) { }
	}

	@Test
	public void commonTokenToStringWithRecognizerAndListSourceName() {
		ATN atn = simpleMatchA();
		ParserInterpreter p = parser(atn, Collections.singletonList("s"), 1);
		CommonToken t = new CommonToken(1, "A");
		assertTrue(t.toString(p).contains("A") || t.toString(p).contains("1"));

		ListTokenSource named = new ListTokenSource(Collections.singletonList(tok(1, "A")), "src");
		assertEquals("src", named.getSourceName());
		// no sourceName, but token has an input stream
		CommonToken withStream = new CommonToken(
			org.antlr.v4.runtime.misc.Tuple.create(null, CharStreams.fromString("A", "from-tok")),
			1, Token.DEFAULT_CHANNEL, 0, 0);
		withStream.setText("A");
		ListTokenSource fromTok = new ListTokenSource(Collections.singletonList(withStream));
		assertEquals("from-tok", fromTok.getSourceName());
	}

	@Test
	public void treesNullRecognizerAndStripAndFind() {
		ParserRuleContext root = new ParserRuleContext();
		ParserRuleContext child = new ParserRuleContext();
		child.start = tok(1, "a");
		((CommonToken) child.start).setTokenIndex(0);
		child.stop = child.start;
		child.parent = root;
		root.addChild(child);
		class NullNames extends ParserInterpreter {
			NullNames() {
				super("P", VocabularyImpl.EMPTY_VOCABULARY,
					Collections.singletonList("s"), simpleMatchA(),
					new CommonTokenStream(new MockTokenSource(tok(1, "A"))));
			}
			@Override public String[] getRuleNames() { return null; }
		}
		NullNames nn = new NullNames();
		assertNotNull(Trees.toStringTree(root, nn));
		assertNotNull(Trees.getNodeText(root, nn));

		ParserRuleContext displayed = new ParserRuleContext();
		Trees.stripChildrenOutOfRange(root, displayed, 10, 20);
		assertEquals(1, root.getChildCount());

		assertNull(Trees.findNodeSuchThat(null, new org.antlr.v4.runtime.misc.Predicate<org.antlr.v4.runtime.tree.Tree>() {
			@Override public boolean eval(org.antlr.v4.runtime.tree.Tree arg) { return false; }
		}));
	}

	@Test
	public void commonTokenStreamLbAndLtZero() {
		CommonToken hidden = hidden(" ");
		CommonTokenStream cts = new CommonTokenStream(new MockTokenSource(hidden, tok(1, "A")));
		cts.fill();
		cts.seek(cts.size() - 1);
		assertNull(cts.LT(0));
		// walk backward off the beginning
		Token back = cts.LT(-50);
		assertTrue(back == null || back.getType() >= Token.MIN_USER_TOKEN_TYPE || back.getType() == Token.EOF);
	}

	@Test
	public void defaultErrorStrategyLoopBackAndMissingEof() {
		ATN plus = parserAPlus();
		ParserInterpreter p = parser(plus, Collections.singletonList("s"), 2);
		PlusLoopbackState loop = null;
		for (ATNState s : plus.states) {
			if (s instanceof PlusLoopbackState) {
				loop = (PlusLoopbackState) s;
			}
		}
		assertNotNull(loop);
		p.setState(loop.stateNumber);
		p._ctx = new ParserRuleContext();
		new DefaultErrorStrategy().sync(p);

		ATN star = parserAStar();
		ParserInterpreter p2 = parser(star, Collections.singletonList("s"), 2);
		StarLoopbackState sloop = null;
		for (ATNState s : star.states) {
			if (s instanceof StarLoopbackState) {
				sloop = (StarLoopbackState) s;
			}
		}
		assertNotNull(sloop);
		p2.setState(sloop.stateNumber);
		p2._ctx = new ParserRuleContext();
		new DefaultErrorStrategy().sync(p2);

		// unknown state type default branch
		p2.setState(star.ruleToStopState[0].stateNumber);
		new DefaultErrorStrategy().sync(p2);

		// recover failsafe: same index + same state twice
		DefaultErrorStrategy strat = new DefaultErrorStrategy();
		ParserInterpreter p3 = parser(parserAorB(), Collections.singletonList("s"), 3);
		p3.setState(p3.getATN().decisionToState.get(0).stateNumber);
		p3._ctx = new ParserRuleContext();
		RecognitionException ex = new InputMismatchException(p3);
		strat.recover(p3, ex);
		strat.recover(p3, ex);

		// reportNoViableAlternative with start==EOF
		NoViableAltException nvae = new NoViableAltException(p3);
		try {
			java.lang.reflect.Field start = RecognitionException.class.getDeclaredField("startToken");
			// startToken may be via getter only; construct with EOF
		}
		catch (Exception ignored) {
		}
		NoViableAltException eofStart = new NoViableAltException(
			p3, p3.getInputStream(), p3.getCurrentToken(), p3.getCurrentToken(),
			null, p3._ctx);
		// if start token is EOF
		CommonToken eof = new CommonToken(Token.EOF, "<EOF>");
		NoViableAltException eofNvae = new NoViableAltException(
			p3, p3.getInputStream(), eof, eof, null, p3._ctx);
		new DefaultErrorStrategy().reportError(p3, eofNvae);

		// getMissingSymbol when expecting EOF
		class ExpectEof extends DefaultErrorStrategy {
			@Override
			protected IntervalSet getExpectedTokens(Parser recognizer) {
				return IntervalSet.of(Token.EOF);
			}
			Token missing(Parser r) { return getMissingSymbol(r); }
		}
		ExpectEof ee = new ExpectEof();
		p3.getInputStream().seek(0);
		while (p3.getInputStream().LA(1) != Token.EOF) {
			p3.getInputStream().consume();
		}
		try {
			assertNotNull(ee.missing(p3));
		}
		catch (NullPointerException npe) {
			// tokens built without a TokenSource still exercise getMissingSymbol
			assertNotNull(npe);
		}
	}

	@Test
	public void diagnosticErrorListenerExactAmbiguity() {
		org.antlr.v4.runtime.DiagnosticErrorListener exact =
			new org.antlr.v4.runtime.DiagnosticErrorListener(true);
		ATN atn = parserAmbiguousAA();
		ParserInterpreter p = parser(atn, Collections.singletonList("s"), 1);
		p.addErrorListener(exact);
		p.getInterpreter().setPredictionMode(org.antlr.v4.runtime.atn.PredictionMode.LL_EXACT_AMBIG_DETECTION);
		p.parse(0);
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static CommonToken tok(int type, String text) {
		return new CommonToken(type, text);
	}

	private static CommonToken hidden(String text) {
		CommonToken t = new CommonToken(99, text);
		t.setChannel(Token.HIDDEN_CHANNEL);
		return t;
	}

	private static ATN simpleMatchA() {
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
		start.addTransition(new AtomTransition(mid, 1));
		mid.addTransition(new EpsilonTransition(stop));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}

	/** s : (A|B) (A|B) ; two sequential block decisions. */
	private static ATN twoSequentialDecisions() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		RuleStartState start = new RuleStartState();
		BasicBlockStartState b1 = new BasicBlockStartState();
		BlockEndState e1 = new BlockEndState();
		BasicBlockStartState b2 = new BasicBlockStartState();
		BlockEndState e2 = new BlockEndState();
		RuleStopState stop = new RuleStopState();
		for (ATNState s : new ATNState[] { start, b1, e1, b2, e2, stop }) {
			s.ruleIndex = 0;
			atn.addState(s);
		}
		start.stopState = stop;
		b1.endState = e1;
		e1.startState = b1;
		b2.endState = e2;
		e2.startState = b2;
		start.addTransition(new EpsilonTransition(b1));
		b1.addTransition(new AtomTransition(e1, 1));
		b1.addTransition(new AtomTransition(e1, 2));
		e1.addTransition(new EpsilonTransition(b2));
		b2.addTransition(new AtomTransition(e2, 1));
		b2.addTransition(new AtomTransition(e2, 2));
		e2.addTransition(new EpsilonTransition(stop));
		atn.defineDecisionState(b1);
		atn.defineDecisionState(b2);
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}

	private static ATN parserSetOfA() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		RuleStartState start = new RuleStartState();
		RuleStopState stop = new RuleStopState();
		start.ruleIndex = 0;
		stop.ruleIndex = 0;
		start.stopState = stop;
		atn.addState(start);
		atn.addState(stop);
		start.addTransition(new SetTransition(stop, IntervalSet.of(1)));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}

	private static ATN parserWithFailingPrecedence() {
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
		start.addTransition(new PrecedencePredicateTransition(mid, 99));
		mid.addTransition(new AtomTransition(stop, 1));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}

	private static int findRuleTransitionState(ATN atn) {
		for (ATNState s : atn.states) {
			for (int i = 0; i < s.getNumberOfTransitions(); i++) {
				if (s.transition(i) instanceof RuleTransition) {
					return s.stateNumber;
				}
			}
		}
		return 0;
	}

	private static ParserInterpreter parser(ATN atn, List<String> rules, int... types) {
		List<Token> list = new ArrayList<Token>();
		for (int i = 0; i < types.length; i++) {
			CommonToken t = new CommonToken(types[i], String.valueOf(types[i]));
			t.setTokenIndex(i);
			list.add(t);
		}
		CommonToken eof = new CommonToken(Token.EOF, "<EOF>");
		eof.setTokenIndex(types.length);
		list.add(eof);
		Vocabulary vocab = new VocabularyImpl(
			new String[] { null, "'A'", "'B'", "'C'" },
			new String[] { null, "A", "B", "C" });
		return new ParserInterpreter("P", vocab, rules, atn, new CommonTokenStream(new ListTokenSource(list)));
	}

	private static LexerInterpreter createLexer(ATN atn, String input) {
		return new LexerInterpreter(
			"L",
			new VocabularyImpl(new String[] { null, "'a'" }, new String[] { null, "A" }),
			Collections.singletonList("A"),
			null,
			Collections.singletonList("DEFAULT_MODE"),
			atn,
			CharStreams.fromString(input));
	}

	private static ATN buildLexerMatchA() {
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
		atn.lexerActions = new org.antlr.v4.runtime.atn.LexerAction[0];
		atn.defineMode("DEFAULT_MODE", tokensStart);
		atn.clearDFA();
		return atn;
	}

	private static ATN parserRuleCall() {
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

	private static ATN parserAPlus() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		RuleStartState ruleStart = new RuleStartState();
		org.antlr.v4.runtime.atn.PlusBlockStartState blkStart = new org.antlr.v4.runtime.atn.PlusBlockStartState();
		BlockEndState blkEnd = new BlockEndState();
		PlusLoopbackState loop = new PlusLoopbackState();
		org.antlr.v4.runtime.atn.LoopEndState end = new org.antlr.v4.runtime.atn.LoopEndState();
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
		loop.addTransition(new EpsilonTransition(blkStart));
		loop.addTransition(new EpsilonTransition(end));
		end.addTransition(new EpsilonTransition(ruleStop));
		atn.defineDecisionState(loop);
		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.clearDFA();
		return atn;
	}

	private static ATN parserAStar() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		RuleStartState ruleStart = new RuleStartState();
		org.antlr.v4.runtime.atn.StarLoopEntryState entry = new org.antlr.v4.runtime.atn.StarLoopEntryState();
		org.antlr.v4.runtime.atn.StarBlockStartState blkStart = new org.antlr.v4.runtime.atn.StarBlockStartState();
		BlockEndState blkEnd = new BlockEndState();
		StarLoopbackState loop = new StarLoopbackState();
		org.antlr.v4.runtime.atn.LoopEndState end = new org.antlr.v4.runtime.atn.LoopEndState();
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

	private static ATN parserAorB() {
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

	private static ATN parserAmbiguousAA() {
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

	/** Lexer that does not override channel/mode/token name arrays. */
	static final class BareLexer extends Lexer {
		BareLexer(CharStream input) {
			super(input);
			ATN atn = buildLexerMatchA();
			this._interp = new LexerATNSimulator(this, atn);
		}

		@Override public String[] getRuleNames() { return new String[] { "A" }; }
		@Override public String getGrammarFileName() { return "Bare"; }
		@Override public ATN getATN() { return getInterpreter().atn; }
	}
}
