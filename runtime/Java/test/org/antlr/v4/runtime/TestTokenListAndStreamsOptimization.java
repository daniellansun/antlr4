/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.antlr.v4.runtime.atn.LexerAction;
import org.antlr.v4.runtime.atn.LexerCustomAction;
import org.antlr.v4.runtime.atn.LexerMoreAction;
import org.antlr.v4.runtime.atn.LexerSkipAction;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.TokensStartState;
import org.antlr.v4.runtime.atn.ActionTransition;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.Tuple;
import org.antlr.v4.runtime.tree.AbstractParseTreeVisitor;
import org.antlr.v4.runtime.tree.ErrorNodeImpl;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;
import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * High-coverage unit tests for Round 1–3 runtime stream, token list, lexer,
 * parser, parse-tree, interval set, and recognizer optimizations and edge cases.
 */
public class TestTokenListAndStreamsOptimization {

	private static CommonToken tok(int type, int channel, String text) {
		CommonToken t = new CommonToken(type, text);
		t.setChannel(channel);
		return t;
	}

	// -------------------------------------------------------------------------
	// TokenList full JDK List<Token> + RandomAccess contract
	// -------------------------------------------------------------------------

	@Test
	public void testTokenListListContractAndChunkBoundaries() {
		TokenList list = new TokenList();
		assertTrue(list.isEmpty());
		assertEquals(0, list.size());
		// clear() on empty list (chunks == null)
		list.clear();
		assertTrue(list.isEmpty());

		// Out-of-bounds get/set/add/remove on empty list
		try { list.get(0); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.get(-1); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.set(0, tok(1, 0, "x")); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.set(-1, tok(1, 0, "x")); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.add(-1, tok(1, 0, "x")); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.add(1, tok(1, 0, "x")); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.remove(-1); fail(); } catch (IndexOutOfBoundsException expected) {}
		try { list.remove(0); fail(); } catch (IndexOutOfBoundsException expected) {}

		// Append across >4 chunks (>1024 elements) to exercise directory growth
		int total = 1100;
		for (int i = 0; i < total; i++) {
			list.add(tok(i + 1, 0, "t" + i));
		}
		assertFalse(list.isEmpty());
		assertEquals(total, list.size());
		assertEquals("t0", list.get(0).getText());
		assertEquals("t255", list.get(255).getText());
		assertEquals("t256", list.get(256).getText());
		assertEquals("t1099", list.get(1099).getText());

		// set() in place
		Token old = list.set(256, tok(999, 0, "replaced"));
		assertEquals("t256", old.getText());
		assertEquals("replaced", list.get(256).getText());

		// add(index, element) at end, start, and middle across chunk boundaries
		list.add(list.size(), tok(2000, 0, "tail"));
		assertEquals("tail", list.get(list.size() - 1).getText());
		list.add(0, tok(2001, 0, "head"));
		assertEquals("head", list.get(0).getText());
		assertEquals("t0", list.get(1).getText());
		list.add(257, tok(2002, 0, "mid"));
		assertEquals("mid", list.get(257).getText());
		assertEquals("replaced", list.get(258).getText());

		// remove(index) at start, middle, and tail
		assertEquals("head", list.remove(0).getText());
		assertEquals("mid", list.remove(256).getText());
		assertEquals("tail", list.remove(list.size() - 1).getText());
		assertEquals(total, list.size());

		// clear() when directory grew past INITIAL_CHUNK_DIR releases chunks
		list.clear();
		assertTrue(list.isEmpty());
		assertEquals(0, list.size());

		// Small multi-chunk clear (<= INITIAL_CHUNK_DIR chunks, >1 chunk)
		for (int i = 0; i < 300; i++) {
			list.appendFast(tok(1, 0, "s" + i));
		}
		assertEquals(300, list.size());
		list.clear();
		assertTrue(list.isEmpty());

		// Clear on single non-empty chunk, then clear again while size==0 & chunks!=null
		list.appendFast(tok(1, 0, "only"));
		list.clear();
		list.clear();
		assertTrue(list.isEmpty());

		// Remove down to empty list (hits size == 0 branch in remove)
		list.appendFast(tok(1, 0, "one"));
		assertEquals("one", list.remove(0).getText());
		assertTrue(list.isEmpty());

		// Fail-fast iterator detects appendFast structural growth
		list.appendFast(tok(1, 0, "a"));
		Iterator<Token> it = list.iterator();
		assertEquals("a", it.next().getText());
		list.appendFast(tok(2, 0, "b"));
		try {
			it.next();
			fail("Expected ConcurrentModificationException");
		}
		catch (ConcurrentModificationException expected) {
		}
	}

	// -------------------------------------------------------------------------
	// CodePointCharStream & CodePointBuffer edge cases (8/16/32-bit + overflow)
	// -------------------------------------------------------------------------

	@Test
	public void testCodePointCharStreamFromStringAndGetTextEdgeCases() {
		// Empty string
		CodePointCharStream empty = CharStreams.fromString("");
		assertEquals(0, empty.size());
		assertEquals("", empty.getText(0, -1));
		assertEquals(IntStream.EOF, empty.LA(1));

		// 8-bit Latin-1 (1-char ASCII, 1-char high Latin-1, CRLF, 4-way unrolled + tail)
		CodePointCharStream s8 = CharStreams.fromString("abcd\r\n\u00e9xyz");
		assertEquals("a", s8.getText(0, 0));
		assertEquals("\u00e9", s8.getText(6, 6));
		assertEquals("\r\n", s8.getText(4, 5));
		assertEquals("", s8.getText(2, 1));
		assertEquals("abcd\r\n\u00e9xyz", s8.getText(0, Integer.MAX_VALUE));
		try { s8.getText(-1, 2); fail(); } catch (StringIndexOutOfBoundsException expected) {}
		try { s8.getText(2, Integer.MIN_VALUE); fail(); } catch (StringIndexOutOfBoundsException expected) {}

		// 16-bit BMP (ASCII prefix before BMP char triggers fromStringNonLatin1)
		CodePointCharStream s16 = CharStreams.fromString("a\r\n\u4e2d\u6587b");
		assertEquals("a", s16.getText(0, 0));
		assertEquals("\r\n", s16.getText(1, 2));
		assertEquals("\u4e2d\u6587", s16.getText(3, 4));
		assertEquals("", s16.getText(2, 1));
		assertEquals("a\r\n\u4e2d\u6587b", s16.getText(0, Integer.MAX_VALUE));
		try { s16.getText(2, Integer.MIN_VALUE); fail(); } catch (StringIndexOutOfBoundsException expected) {}

		// 32-bit SMP: valid surrogate pair, lone high surrogate, lone low surrogate,
		// consecutive high surrogates, and trailing lone high surrogate
		String smpStr = "x\uD83D\uDE00\uD800a\uDC00\uD800\uD801\uDE00\uD83D";
		CodePointCharStream s32 = CharStreams.fromString(smpStr);
		assertEquals(smpStr, s32.getText(0, Integer.MAX_VALUE));
		assertEquals(0x1F600, s32.LA(2));
		assertEquals(0xD800, s32.LA(3));
		assertEquals('a', s32.LA(4));
		assertEquals(0xDC00, s32.LA(5));
		assertEquals(0xD800, s32.LA(6));
		assertEquals(Character.toCodePoint('\uD801', '\uDE00'), s32.LA(7));
		assertEquals(0xD83D, s32.LA(8));

		// Also test string starting with supplementary surrogate pair & ending with lone high
		CodePointCharStream s32b = CharStreams.fromString("\uD83D\uDE00");
		assertEquals(1, s32b.size());
		assertEquals("\uD83D\uDE00", s32b.getText(0, 0));
		try { s32b.getText(0, Integer.MIN_VALUE); fail(); } catch (StringIndexOutOfBoundsException expected) {}

		// CodePointBuffer.Builder.append(char[], int, int) across 8->16->32 widening
		CodePointBuffer.Builder builder = CodePointBuffer.builder(4);
		builder.append("abc".toCharArray(), 0, 3);
		builder.append(new char[] { '\u4e2d', '\uD83D', '\uDE00', '\uD800' }, 0, 4);
		CodePointBuffer buf = builder.build();
		assertEquals(CodePointBuffer.Type.INT, buf.getType());
		CodePointCharStream fromBuf = CodePointCharStream.fromBuffer(buf);
		assertEquals("abc\u4e2d\uD83D\uDE00\uD800", fromBuf.toString());
	}

	// -------------------------------------------------------------------------
	// BufferedTokenStream & CommonTokenStream edge paths
	// -------------------------------------------------------------------------

	private static TokenSource sourceOf(final Token... toks) {
		return new TokenSource() {
			int i = 0;
			@Override
			public Token nextToken() {
				return i < toks.length ? toks[i++] : tok(Token.EOF, 0, "<EOF>");
			}
			@Override public int getLine() { return 1; }
			@Override public int getCharPositionInLine() { return 0; }
			@Override public CharStream getInputStream() { return null; }
			@Override public String getSourceName() { return "mock"; }
			@Override public void setTokenFactory(TokenFactory factory) {}
			@Override public TokenFactory getTokenFactory() { return CommonTokenFactory.DEFAULT; }
		};
	}

	/** Plain non-WritableToken implementation to exercise non-CommonToken paths. */
	private static final class ReadOnlyToken implements Token {
		final int type;
		final int channel;
		final String text;
		ReadOnlyToken(int type, int channel, String text) {
			this.type = type;
			this.channel = channel;
			this.text = text;
		}
		@Override public String getText() { return text; }
		@Override public int getType() { return type; }
		@Override public int getLine() { return 1; }
		@Override public int getCharPositionInLine() { return 0; }
		@Override public int getChannel() { return channel; }
		@Override public int getTokenIndex() { return -1; }
		@Override public int getStartIndex() { return 0; }
		@Override public int getStopIndex() { return 0; }
		@Override public TokenSource getTokenSource() { return null; }
		@Override public CharStream getInputStream() { return null; }
	}

	/** WritableToken that is NOT CommonToken.class. */
	private static final class CustomWritableToken extends CommonToken {
		CustomWritableToken(int type, int channel, String text) {
			super(type, text);
			setChannel(channel);
		}
	}

	@Test
	public void testBufferedTokenStreamSlowAndBoundaryPaths() {
		// LA(1) slow path (uninitialized and after cache clear), LT(1) seek past EOF,
		// LT(Integer.MAX_VALUE) overflow guard, LB on shortened buffer, negative nextTokenOnChannel
		BufferedTokenStream bts = new BufferedTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 1, "ws"), tok(3, 0, "b"), tok(Token.EOF, 0, "<EOF>")
		));
		assertEquals(1, bts.LA(1));
		bts.clearCachedLT1();
		assertEquals(1, bts.LA(1));
		bts.clearCachedLT1();
		assertEquals("a", bts.LT(1).getText());
		assertNull(bts.LT(0));
		bts.consume();
		assertEquals("a", bts.LT(-1).getText());
		// Overflow guard: p + Integer.MAX_VALUE - 1 would overflow 32-bit int
		assertEquals(Token.EOF, bts.LT(Integer.MAX_VALUE).getType());

		// Seek past fetched buffer triggers ltOneSlow clamping to EOF
		bts.seek(99);
		assertEquals(Token.EOF, bts.LT(1).getType());
		// Consume at EOF throws
		try {
			bts.consume();
			fail("Expected IllegalStateException");
		}
		catch (IllegalStateException expected) {
		}

		// Subclass overriding LB(1) must not be bypassed by cachedLTMinus1
		final boolean[] lbCalled = new boolean[1];
		BufferedTokenStream subBts = new BufferedTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 0, "b"), tok(Token.EOF, 0, "<EOF>")
		)) {
			@Override
			protected Token LB(int k) {
				lbCalled[0] = true;
				return super.LB(k);
			}
		};
		subBts.LT(1);
		subBts.consume();
		assertEquals("a", subBts.LT(-1).getText());
		assertTrue(lbCalled[0]);

		// Subclass replacing tokens with custom ArrayList triggers fetchSlow & non-fastTokens paths
		BufferedTokenStream customListStream = new BufferedTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 1, "h"), tok(3, 0, "b"), tok(Token.EOF, 0, "<EOF>")
		)) {
			{
				this.tokens = new ArrayList<Token>();
			}
		};
		assertEquals(1, customListStream.LA(1));
		customListStream.consume();
		assertEquals(2, customListStream.nextTokenOnChannel(1, 0));
		assertEquals(3, customListStream.LA(2));
		customListStream.seek(50);
		assertEquals(Token.EOF, customListStream.LT(1).getType());

		// nextTokenOnChannel with out-of-range high index clamps to EOF; negative index rejects via assert/IOOBE
		assertEquals(bts.size() - 1, bts.nextTokenOnChannel(999, 0));
		try {
			bts.nextTokenOnChannel(-1, 0);
			fail("Expected AssertionError or IndexOutOfBoundsException");
		}
		catch (AssertionError expected) {
		}
		catch (IndexOutOfBoundsException expected) {
		}

		// LB when tokens externally shortened throws IndexOutOfBoundsException instead of returning null
		BufferedTokenStream shortStream = new BufferedTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 0, "b"), tok(3, 0, "c"), tok(Token.EOF, 0, "<EOF>")
		));
		shortStream.fill();
		shortStream.seek(2);
		shortStream.getTokens().clear();
		try {
			shortStream.LB(1);
			fail("Expected IndexOutOfBoundsException");
		}
		catch (IndexOutOfBoundsException expected) {
		}
	}

	@Test
	public void testCommonTokenStreamSeekConsumeAndLtForwardFastEdgeCases() {
		// Mixed CommonToken, CustomWritableToken, ReadOnlyToken, off-channel, and EOF
		CommonTokenStream cts = new CommonTokenStream(sourceOf(
			tok(1, 1, "h0"),
			new CustomWritableToken(2, 0, "w1"),
			new ReadOnlyToken(3, 1, "rh2"),
			new ReadOnlyToken(4, 0, "r3"),
			tok(5, 0, "c4"),
			new ReadOnlyToken(Token.EOF, 0, "<EOF>")
		));

		// Initial LT(1) skips hidden token 0 -> lands on w1 (index 1)
		assertEquals("w1", cts.LT(1).getText());
		// LT(k > 1) via ltForwardFast fused frontier fetch (exercises CustomWritableToken & ReadOnlyToken)
		assertEquals("r3", cts.LT(2).getText());
		assertEquals("c4", cts.LT(3).getText());
		assertEquals(Token.EOF, cts.LT(4).getType());
		// Already fetchedEOF == true: LT(k) beyond EOF still returns EOF
		assertEquals(Token.EOF, cts.LT(10).getType());

		// Fast seek(p) with null cachedLT1, fast seek to on-channel index, and fallback seek to off-channel
		cts.clearCachedLT1();
		cts.seek(cts.index());
		assertEquals("w1", cts.LT(1).getText());
		cts.seek(4);
		assertEquals("c4", cts.LT(1).getText());
		cts.seek(0); // off-channel -> falls back to super.seek -> adjusts to 1
		assertEquals(1, cts.index());

		// Streaming consume() fused frontier fetch across CustomWritableToken & ReadOnlyToken & EOF
		CommonTokenStream stream2 = new CommonTokenStream(sourceOf(
			tok(1, 0, "a"),
			new CustomWritableToken(2, 1, "h"),
			new ReadOnlyToken(3, 0, "b"),
			new CustomWritableToken(Token.EOF, 0, "<EOF>")
		));
		assertEquals("a", stream2.LT(1).getText());
		stream2.consume(); // frontier fetch skips CustomWritableToken hidden -> lands on ReadOnlyToken b
		assertEquals("b", stream2.LT(1).getText());
		assertEquals("a", stream2.LT(-1).getText());
		stream2.consume(); // frontier fetch hits EOF
		assertEquals(Token.EOF, stream2.LT(1).getType());
		try {
			stream2.consume();
			fail("Expected IllegalStateException");
		}
		catch (IllegalStateException expected) {
		}

		// Subclass overriding LB(1) must not use cachedLTMinus1 shortcut
		final boolean[] subLb = new boolean[1];
		CommonTokenStream subCts = new CommonTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 0, "b"), tok(Token.EOF, 0, "<EOF>")
		)) {
			@Override
			protected Token LB(int k) {
				subLb[0] = true;
				return super.LB(k);
			}
		};
		subCts.LT(1);
		subCts.consume();
		assertEquals("a", subCts.LT(-1).getText());
		assertTrue(subLb[0]);

		// External truncation of fastTokens falls back safely on seek(p) (does NOT return stale cachedLT1)
		CommonTokenStream trunc = new CommonTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 0, "b"), tok(Token.EOF, 0, "<EOF>")
		));
		trunc.fill();
		trunc.seek(1);
		trunc.getTokens().clear();
		try {
			trunc.seek(1);
			fail("Expected IllegalStateException on truncated empty token stream");
		}
		catch (IllegalStateException expected) {
		}
	}

	// -------------------------------------------------------------------------
	// Lexer: mid-match _input swap, finishMoreGeneric, nextTokenGeneric, overrides
	// -------------------------------------------------------------------------

	private static ATN buildMultiActionLexerATN() {
		// Rules:
		//   rule 0 ('s'): action(0) -> custom action 0 (swaps stream + skip)
		//   rule 1 ('m'): action(1) -> custom action 1 (swaps stream + more)
		//   rule 2 ('a'): token type 1
		//   rule 3 ('b'): token type 2
		//   rule 4 (' '): skip
		//   rule 5 ('+'): more
		ATN atn = new ATN(ATNType.LEXER, 4);
		TokensStartState start = new TokensStartState();
		start.ruleIndex = -1;
		atn.addState(start);

		RuleStartState[] starts = new RuleStartState[6];
		RuleStopState[] stops = new RuleStopState[6];
		char[] chars = { 's', 'm', 'a', 'b', ' ', '+' };
		int[] types = { 3, 4, 1, 2, 5, 6 };

		for (int r = 0; r < 6; r++) {
			RuleStartState rs = new RuleStartState();
			BasicState mid = new BasicState();
			BasicState act = new BasicState();
			RuleStopState stop = new RuleStopState();
			rs.ruleIndex = r; mid.ruleIndex = r; act.ruleIndex = r; stop.ruleIndex = r;
			rs.stopState = stop;
			atn.addState(rs); atn.addState(mid); atn.addState(act); atn.addState(stop);
			start.addTransition(new EpsilonTransition(rs));
			rs.addTransition(new AtomTransition(mid, chars[r]));
			if (r == 0) {
				mid.addTransition(new ActionTransition(act, 0, 0, false));
				act.addTransition(new EpsilonTransition(stop));
			}
			else if (r == 1) {
				mid.addTransition(new ActionTransition(act, 1, 1, false));
				act.addTransition(new EpsilonTransition(stop));
			}
			else if (r == 4) {
				mid.addTransition(new ActionTransition(act, 4, 2, false));
				act.addTransition(new EpsilonTransition(stop));
			}
			else if (r == 5) {
				mid.addTransition(new ActionTransition(act, 5, 3, false));
				act.addTransition(new EpsilonTransition(stop));
			}
			else {
				mid.addTransition(new EpsilonTransition(stop));
			}
			starts[r] = rs;
			stops[r] = stop;
		}
		atn.ruleToStartState = starts;
		atn.ruleToStopState = stops;
		atn.ruleToTokenType = types;
		atn.lexerActions = new LexerAction[] {
			new LexerCustomAction(0, 0),
			new LexerCustomAction(1, 1),
			LexerSkipAction.INSTANCE,
			LexerMoreAction.INSTANCE
		};
		atn.defineMode("DEFAULT_MODE", start);
		atn.clearDFA();
		return atn;
	}

	@SuppressWarnings("deprecation")
	@Test
	public void testLexerInputSwapGenericMoreAndOverrides() {
		final ATN atn = buildMultiActionLexerATN();

		// 1. Mid-match _input swap: CodePointCharStream -> CodePointCharStream on SKIP and MORE
		final CharStream[] swapTargets = new CharStream[2];
		swapTargets[0] = CharStreams.fromString("a");
		swapTargets[1] = CharStreams.fromString("b");
		Lexer swapCpLexer = new Lexer(CharStreams.fromString("s")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "WS", "PLUS" }; }
			@Override public String getGrammarFileName() { return "Swap.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public void action(RuleContext ctx, int ruleIndex, int actionIndex) {
				if (actionIndex == 0) {
					_input = swapTargets[0];
					skip();
				}
				else if (actionIndex == 1) {
					_input = swapTargets[1];
					more();
				}
			}
		};
		assertEquals(1, swapCpLexer.nextToken().getType()); // swapped to "a" and skipped 's'
		assertEquals(Token.EOF, swapCpLexer.nextToken().getType());

		swapCpLexer.setInputStream(CharStreams.fromString("m"));
		swapTargets[1] = CharStreams.fromString("b");
		assertEquals(2, swapCpLexer.nextToken().getType()); // swapped to "b" on MORE

		// 2. Mid-match _input swap: CodePointCharStream -> ANTLRInputStream on SKIP & MORE (finishMoreGeneric)
		final CharStream[] genericTargets = new CharStream[2];
		Lexer swapGenericLexer = new Lexer(CharStreams.fromString("s")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "WS", "PLUS" }; }
			@Override public String getGrammarFileName() { return "SwapG.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public void action(RuleContext ctx, int ruleIndex, int actionIndex) {
				if (actionIndex == 0) {
					_input = genericTargets[0];
					skip();
				}
				else if (actionIndex == 1) {
					_input = genericTargets[1];
					more();
				}
			}
		};
		swapGenericLexer.removeErrorListeners();
		genericTargets[0] = new ANTLRInputStream(" a");
		assertEquals(1, swapGenericLexer.nextToken().getType());
		assertEquals(Token.EOF, swapGenericLexer.nextToken().getType());

		// Swap into ANTLRInputStream during MORE -> exercises finishMoreGeneric (+, !, skip, normal, EOF)
		swapGenericLexer.setInputStream(CharStreams.fromString("m"));
		genericTargets[1] = new ANTLRInputStream("+!b"); // '+' MORE, '!' NVA->SKIP, then 'b'
		assertEquals(2, swapGenericLexer.nextToken().getType());

		swapGenericLexer.setInputStream(CharStreams.fromString("m"));
		genericTargets[1] = new ANTLRInputStream(" b"); // ' ' SKIP inside finishMoreGeneric -> nextToken()
		assertEquals(2, swapGenericLexer.nextToken().getType());

		// 3. Pure nextTokenGeneric with ANTLRInputStream (SKIP, MORE, NVA, normal, EOF)
		Lexer genericLexer = new Lexer(new ANTLRInputStream(" +a!b")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "WS", "PLUS" }; }
			@Override public String getGrammarFileName() { return "Gen.g4"; }
			@Override public ATN getATN() { return atn; }
		};
		genericLexer.removeErrorListeners();
		assertEquals(1, genericLexer.nextToken().getType());
		assertEquals(2, genericLexer.nextToken().getType());
		assertEquals(Token.EOF, genericLexer.nextToken().getType());

		// 4. Custom emit(Token) wrapping EOF token + custom TokenFactory + overridden getCharIndex()
		final Token wrappedEof = tok(Token.EOF, 0, "WRAPPED_EOF");
		Lexer customEmitLexer = new Lexer(CharStreams.fromString("a")) {
			{
				_interp = new LexerATNSimulator(this, atn);
				setTokenFactory(new CommonTokenFactory(true));
			}
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "WS", "PLUS" }; }
			@Override public String getGrammarFileName() { return "Cust.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public void emit(Token token) {
				super.emit(token.getType() == Token.EOF ? wrappedEof : token);
			}
		};
		Token t1 = customEmitLexer.nextToken();
		assertEquals("a", t1.getText());
		assertSame(wrappedEof, customEmitLexer.nextToken());

		// Overridden getCharIndex() honored by default CommonTokenFactory.DEFAULT fast path
		Lexer customIdxLexer = new Lexer(CharStreams.fromString("a")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "WS", "PLUS" }; }
			@Override public String getGrammarFileName() { return "Idx.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override public int getCharIndex() { return super.getCharIndex() + 10; }
		};
		Token shifted = customIdxLexer.nextToken();
		assertEquals(10, shifted.getStopIndex());
	}

	// -------------------------------------------------------------------------
	// Recognizer lock-free CAS error listeners
	// -------------------------------------------------------------------------

	@Test
	public void testRecognizerLockFreeErrorListeners() throws Exception {
		final ATN atn = buildMultiActionLexerATN();
		final Lexer lexer = new Lexer(CharStreams.fromString("a")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "S" }; }
			@Override public String getGrammarFileName() { return "R.g4"; }
			@Override public ATN getATN() { return atn; }
		};
		ANTLRErrorListener<Object> l1 = new ANTLRErrorListener<Object>() {
			@Override public <T extends Object> void syntaxError(Recognizer<T, ?> r, T sym, int l, int c, String m, RecognitionException e) {}
		};
		ANTLRErrorListener<Object> l2 = new ANTLRErrorListener<Object>() {
			@Override public <T extends Object> void syntaxError(Recognizer<T, ?> r, T sym, int l, int c, String m, RecognitionException e) {}
		};
		ANTLRErrorListener<Object> l3 = new ANTLRErrorListener<Object>() {
			@Override public <T extends Object> void syntaxError(Recognizer<T, ?> r, T sym, int l, int c, String m, RecognitionException e) {}
		};

		lexer.removeErrorListeners();
		lexer.removeErrorListeners(); // idempotent when already EMPTY_LISTENERS
		assertTrue(lexer.getErrorListeners().isEmpty());

		lexer.addErrorListener(l1);
		lexer.addErrorListener(l2);
		lexer.addErrorListener(l3);
		assertEquals(3, lexer.getErrorListeners().size());

		// Remove missing & null -> no-op
		lexer.removeErrorListener(new ANTLRErrorListener<Object>() {
			@Override public <T extends Object> void syntaxError(Recognizer<T, ?> r, T sym, int l, int c, String m, RecognitionException e) {}
		});
		lexer.removeErrorListener(null);
		assertEquals(3, lexer.getErrorListeners().size());

		// Remove middle, first, then last (drops back to EMPTY_LISTENERS)
		lexer.removeErrorListener(l2);
		assertEquals(Arrays.asList(l1, l3), lexer.getErrorListeners());
		lexer.removeErrorListener(l1);
		assertEquals(Collections.singletonList(l3), lexer.getErrorListeners());
		lexer.removeErrorListener(l3);
		assertTrue(lexer.getErrorListeners().isEmpty());

		// Multi-threaded CAS contention check
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			final CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> futures = new ArrayList<Future<?>>();
			for (int t = 0; t < 4; t++) {
				futures.add(pool.submit(new Runnable() {
					@Override
					public void run() {
						try { start.await(); } catch (InterruptedException ignored) {}
						ANTLRErrorListener<Object> local = new ANTLRErrorListener<Object>() {
							@Override public <T extends Object> void syntaxError(Recognizer<T, ?> r, T sym, int l, int c, String m, RecognitionException e) {}
						};
						for (int i = 0; i < 200; i++) {
							lexer.addErrorListener(local);
							lexer.removeErrorListener(local);
						}
					}
				}));
			}
			start.countDown();
			for (Future<?> f : futures) f.get();
			assertTrue(lexer.getErrorListeners().isEmpty());
		}
		finally {
			pool.shutdownNow();
		}
	}

	// -------------------------------------------------------------------------
	// Parser, ParserRuleContext, RuleContext, TerminalNodeImpl, AbstractParseTreeVisitor
	// -------------------------------------------------------------------------

	@Test
	public void testParserRuleContextRuleContextTerminalAndVisitorEdgeCases() {
		// TerminalNodeImpl(Token, RuleNode)
		ParserRuleContext root = new ParserRuleContext();
		Token tA = tok(1, 0, "A");
		TerminalNodeImpl leaf = new TerminalNodeImpl(tA, root);
		assertSame(tA, leaf.getSymbol());
		assertSame(root, leaf.getParent());

		// replaceLastChild when children == null -> adds child
		root.replaceLastChild(leaf);
		assertEquals(1, root.getChildCount());
		assertSame(leaf, root.getChild(0));
		// replaceLastChild when non-empty -> replaces in place
		TerminalNodeImpl leaf2 = new TerminalNodeImpl(tok(2, 0, "B"), root);
		root.replaceLastChild(leaf2);
		assertEquals(1, root.getChildCount());
		assertSame(leaf2, root.getChild(0));

		// RuleContext.getText(): 0, 1 (normal), 1 (null text -> "null"), 2+ children
		RuleContext emptyRc = new ParserRuleContext();
		assertEquals("", emptyRc.getText());

		ParserRuleContext singleNullText = new ParserRuleContext();
		singleNullText.addChild(new TerminalNodeImpl(new CommonToken(1), singleNullText));
		assertEquals("null", singleNullText.getText());
		assertEquals("B", root.getText());
		root.addChild(new TerminalNodeImpl(tok(3, 0, "C"), root));
		assertEquals("BC", root.getText());

		// copyFrom with ErrorNodeImpl + self-copy safety
		ParserRuleContext src = new ParserRuleContext();
		ErrorNodeImpl err = new ErrorNodeImpl(tok(9, 0, "err"));
		src.addErrorNode(err);
		ParserRuleContext dst = new ParserRuleContext();
		dst.copyFrom(src);
		assertEquals(1, dst.getChildCount());
		assertSame(dst, err.getParent());
		dst.copyFrom(dst); // self-copy must not throw
		assertEquals(1, dst.getChildCount());

		// AbstractParseTreeVisitor.visitChildren: null children, shouldVisitNextChild=false, custom RuleNode
		AbstractParseTreeVisitor<String> visitor = new AbstractParseTreeVisitor<String>() {
			@Override protected String defaultResult() { return ""; }
			@Override protected String aggregateResult(String agg, String next) { return agg + next; }
			@Override public String visitTerminal(TerminalNode node) { return node.getText(); }
			@Override
			protected boolean shouldVisitNextChild(RuleNode node, String currentResult) {
				return currentResult.length() < 1;
			}
		};
		assertEquals("", visitor.visitChildren(new ParserRuleContext())); // children == null
		assertEquals("B", visitor.visitChildren(root)); // stops after first child due to shouldVisitNextChild

		// Custom RuleNode that does NOT extend ParserRuleContext
		final TerminalNodeImpl customChild = new TerminalNodeImpl(tok(1, 0, "Z"));
		RuleNode nonPrcRuleNode = new RuleNode() {
			@Override public RuleContext getRuleContext() { return null; }
			@Override public RuleNode getParent() { return null; }
			@Override public Object getPayload() { return null; }
			@Override public ParseTree getChild(int i) { return i == 0 ? customChild : null; }
			@Override public int getChildCount() { return 1; }
			@Override public String toStringTree() { return "Z"; }
			@Override public <T> T accept(ParseTreeVisitor<? extends T> v) { return v.visitChildren(this); }
			@Override public String getText() { return "Z"; }
			@Override public String toStringTree(Parser parser) { return "Z"; }
			@Override public Interval getSourceInterval() { return Interval.INVALID; }
		};
		assertEquals("Z", visitor.visitChildren(nonPrcRuleNode));

		// Parser.consume() with _buildParseTrees == false and an active ParseTreeListener
		CommonTokenStream ts = new CommonTokenStream(sourceOf(tok(1, 0, "a"), tok(Token.EOF, 0, "<EOF>")));
		Parser miniParser = new Parser(ts) {
			@Override public String[] getTokenNames() { return new String[] { "<INVALID>", "A" }; }
			@Override public String[] getRuleNames() { return new String[] { "r" }; }
			@Override public String getGrammarFileName() { return "M.g4"; }
			@Override public ATN getATN() { return new ATN(ATNType.PARSER, 1); }
		};
		miniParser.setBuildParseTree(false);
		final List<String> visited = new ArrayList<String>();
		miniParser.addParseListener(new ParseTreeListener() {
			@Override public void visitTerminal(TerminalNode node) { visited.add(node.getText()); }
			@Override public void visitErrorNode(org.antlr.v4.runtime.tree.ErrorNode node) {}
			@Override public void enterEveryRule(ParserRuleContext ctx) {}
			@Override public void exitEveryRule(ParserRuleContext ctx) {}
		});
		miniParser.enterRule(new ParserRuleContext(), 0, 0);
		miniParser.consume();
		assertEquals(Collections.singletonList("a"), visited);
		// Exit rule after consuming EOF without matchedEOF -> does not assign EOF via lastConsumed fast path
		miniParser.consume(); // consumes EOF into lastConsumed
		miniParser.exitRule();
	}

	// -------------------------------------------------------------------------
	// IntervalSet.buildFastBits valid & malformed/boundary cases
	// -------------------------------------------------------------------------

	@Test
	public void testIntervalSetFastBitsValidAndDefensiveBounds() {
		// >2 intervals within [-2..2048] builds fastBits
		IntervalSet s = new IntervalSet();
		s.add(Token.EOF);
		s.add(1, 5);
		s.add(10, 12);
		s.add(100);
		s.setReadonly(true);
		assertTrue(s.contains(Token.EOF));
		assertTrue(s.contains(3));
		assertFalse(s.contains(7));
		assertTrue(s.contains(100));
		assertFalse(s.contains(-3));
		assertFalse(s.contains(2049));

		// min < -2 -> skips fastBits cleanly
		IntervalSet lowMin = new IntervalSet();
		lowMin.add(-5);
		lowMin.add(1);
		lowMin.add(5);
		lowMin.setReadonly(true);
		assertTrue(lowMin.contains(-5));
		assertTrue(lowMin.contains(5));

		// max > 2048 -> skips fastBits cleanly
		IntervalSet highMax = new IntervalSet();
		highMax.add(1);
		highMax.add(5);
		highMax.add(3000);
		highMax.setReadonly(true);
		assertTrue(highMax.contains(3000));

		// Malformed unsorted IntervalSet(List<Interval>) where max < min -> no OOM/AIOOBE
		List<Interval> malformed1 = new ArrayList<Interval>();
		malformed1.add(Interval.of(10, 20));
		malformed1.add(Interval.of(5, 6));
		malformed1.add(Interval.of(-10, -5));
		IntervalSet bad1 = new IntervalSet(malformed1);
		bad1.setReadonly(true);
		assertFalse(bad1.contains(999));

		// Malformed intermediate interval exceeding max or < -2 -> aborts buildFastBits cleanly
		List<Interval> malformed2 = new ArrayList<Interval>();
		malformed2.add(Interval.of(1, 2));
		malformed2.add(Interval.of(10, 50));
		malformed2.add(Interval.of(5, 8));
		IntervalSet bad2 = new IntervalSet(malformed2);
		bad2.setReadonly(true);
		assertNotNull(bad2.getIntervals());
	}

	@Test
	public void testStreamsLexerAndBufferRemainingEdgePaths() {
		// 1. CodePointBuffer.Builder.append(CharBuffer) with backing char[] directly into CHAR and INT buffers
		CodePointBuffer.Builder cbChar = CodePointBuffer.builder(4);
		cbChar.append(java.nio.CharBuffer.wrap("\u4e2d".toCharArray()));
		cbChar.append(java.nio.CharBuffer.wrap("ab".toCharArray()));
		assertEquals(CodePointBuffer.Type.CHAR, cbChar.build().getType());

		CodePointBuffer.Builder cbInt = CodePointBuffer.builder(4);
		cbInt.append(java.nio.CharBuffer.wrap("\uD83D\uDE00".toCharArray()));
		cbInt.append(java.nio.CharBuffer.wrap("x".toCharArray()));
		assertEquals(CodePointBuffer.Type.INT, cbInt.build().getType());

		// 2. CodePointCharStream.consume() at EOF on 8-bit, 16-bit, and 32-bit streams
		for (String sample : new String[] { "a", "\u4e2d", "\uD83D\uDE00" }) {
			CodePointCharStream cs = CharStreams.fromString(sample);
			cs.consume();
			try {
				cs.consume();
				fail("Expected IllegalStateException at EOF");
			}
			catch (IllegalStateException expected) {
			}
		}

		// 3. BufferedTokenStream: consume() at p < sz - 1 vs EOF, seek() with custom ArrayList,
		//    LA(1)/LT(1) when p >= sz (without clamping first!), nextTokenOnChannel hitting EOF
		BufferedTokenStream bts = new BufferedTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 1, "h"), tok(Token.EOF, 0, "<EOF>")
		));
		bts.fill();
		bts.seek(0);
		bts.consume(); // p < sz - 1 fast branch
		assertEquals(1, bts.index());
		assertEquals(2, bts.nextTokenOnChannel(1, 0));

		// Plain BufferedTokenStream: ltOneSlow() when p >= sz (lines 430-434) and consume() when fetchedEOF==false
		BufferedTokenStream freshBts = new BufferedTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 0, "b"), tok(Token.EOF, 0, "<EOF>")
		));
		assertEquals(1, freshBts.LA(1));
		freshBts.seek(50);
		freshBts.clearCachedLT1();
		assertEquals(Token.EOF, freshBts.LT(1).getType()); // directly hits ltOneSlow p >= sz -> clamp!
		freshBts.seek(0);
		freshBts.consume();
		freshBts.consume();
		assertEquals(Token.EOF, freshBts.LT(1).getType());

		BufferedTokenStream customArr = new BufferedTokenStream(sourceOf(
			tok(1, 0, "a"), tok(Token.EOF, 0, "<EOF>")
		)) {
			{ this.tokens = new ArrayList<Token>(); }
		};
		customArr.fill();
		customArr.seek(0);
		customArr.clearCachedLT1();
		customArr.seek(0);
		assertEquals(1, customArr.LA(1));
		customArr.seek(99);
		customArr.clearCachedLT1();
		assertEquals(Token.EOF, customArr.LA(1));
		customArr.seek(99);
		customArr.clearCachedLT1();
		assertEquals(Token.EOF, customArr.LT(1).getType());
		customArr.seek(0);
		customArr.consume();
		assertEquals(Token.EOF, customArr.LT(1).getType());

		// 4. CommonTokenStream with custom ArrayList + ltForwardFast with CustomWritableToken
		CommonTokenStream customCts = new CommonTokenStream(sourceOf(
			tok(1, 0, "a"), tok(2, 1, "h"), tok(3, 0, "b"), tok(Token.EOF, 0, "<EOF>")
		)) {
			{ this.tokens = new ArrayList<Token>(); }
		};
		assertEquals("a", customCts.LT(1).getText());
		assertEquals("b", customCts.LT(2).getText());
		assertEquals("a", customCts.LT(-1) == null ? "a" : "x");
		customCts.consume();
		assertEquals("b", customCts.LT(1).getText());

		CommonTokenStream ctsFastFrontier = new CommonTokenStream(sourceOf(
			new CustomWritableToken(1, 0, "w0"),
			new CustomWritableToken(2, 1, "wh"),
			new CustomWritableToken(3, 0, "w2"),
			new CustomWritableToken(Token.EOF, 0, "<EOF>")
		));
		assertEquals("w0", ctsFastFrontier.LT(1).getText());
		assertEquals("w2", ctsFastFrontier.LT(2).getText());

		// 5. Lexer: mid-match _input swap to empty stream (line 165), swap without skip/more (line 168),
		//    and finishMoreGeneric emitting a normal token reaching EOF (lines 213, 222-225)
		final ATN lexAtn = buildMultiActionLexerATN();
		final int[] swapMode = new int[] { 0 };
		Lexer swapLex = new Lexer(CharStreams.fromString("s")) {
			{ _interp = new LexerATNSimulator(this, lexAtn); }
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "SP", "PLUS" }; }
			@Override public String getGrammarFileName() { return "T.g4"; }
			@Override public ATN getATN() { return lexAtn; }
			@Override
			public void action(RuleContext _localctx, int ruleIndex, int actionIndex) {
				if (actionIndex == 0 && swapMode[0] == 0) {
					// Swap to empty stream without skip/more -> hits lines 165 & 168!
					_input = CharStreams.fromString("");
				}
				else if (actionIndex == 1 && swapMode[0] == 1) {
					// Swap to generic stream ("a") with more() -> hits finishMoreGeneric normal emit + EOF (lines 213, 222-225)!
					_input = new ANTLRInputStream("a");
					more();
				}
			}
		};
		assertEquals(3, swapLex.nextToken().getType()); // emits token 3 after swapping to empty stream
		swapMode[0] = 1;
		swapLex.setInputStream(CharStreams.fromString("m"));
		assertEquals(1, swapLex.nextToken().getType()); // finishMoreGeneric matches 'a' (type 1) & reaches EOF!

		Lexer cpSkipToEof = new Lexer(CharStreams.fromString("   ")) {
			{ _interp = new LexerATNSimulator(this, lexAtn); }
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "SP", "PLUS" }; }
			@Override public String getGrammarFileName() { return "T.g4"; }
			@Override public ATN getATN() { return lexAtn; }
		};
		assertEquals(Token.EOF, cpSkipToEof.nextToken().getType());

		Lexer genericLex = new Lexer(new ANTLRInputStream("a ")) {
			{ _interp = new LexerATNSimulator(this, lexAtn); }
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "SP", "PLUS" }; }
			@Override public String getGrammarFileName() { return "T.g4"; }
			@Override public ATN getATN() { return lexAtn; }
		};
		genericLex.setText("customText");
		assertEquals("customText", genericLex.emit().getText());
		genericLex.setInputStream(new ANTLRInputStream("a "));
		assertEquals(1, genericLex.nextToken().getType());
		assertEquals(Token.EOF, genericLex.nextToken().getType());
	}
}
