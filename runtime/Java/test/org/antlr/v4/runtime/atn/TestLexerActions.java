/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.VocabularyImpl;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestLexerActions {

	@Test
	public void testLexerActionTypeEnum() {
		assertTrue(LexerActionType.values().length >= 8);
		assertSame(LexerActionType.CHANNEL, LexerActionType.valueOf("CHANNEL"));
		assertSame(LexerActionType.TYPE, LexerActionType.valueOf("TYPE"));
	}

	@Test
	public void testLexerSkipMorePopMode() {
		assertSame(LexerActionType.SKIP, LexerSkipAction.INSTANCE.getActionType());
		assertFalse(LexerSkipAction.INSTANCE.isPositionDependent());
		assertEquals(LexerSkipAction.INSTANCE, LexerSkipAction.INSTANCE);
		assertNotEquals(LexerSkipAction.INSTANCE, null);
		assertEquals("skip", LexerSkipAction.INSTANCE.toString());
		assertEquals(LexerSkipAction.INSTANCE.hashCode(), LexerSkipAction.INSTANCE.hashCode());

		assertSame(LexerActionType.MORE, LexerMoreAction.INSTANCE.getActionType());
		assertFalse(LexerMoreAction.INSTANCE.isPositionDependent());
		assertEquals("more", LexerMoreAction.INSTANCE.toString());
		assertEquals(LexerMoreAction.INSTANCE, LexerMoreAction.INSTANCE);

		assertSame(LexerActionType.POP_MODE, LexerPopModeAction.INSTANCE.getActionType());
		assertFalse(LexerPopModeAction.INSTANCE.isPositionDependent());
		assertEquals("popMode", LexerPopModeAction.INSTANCE.toString());
		assertEquals(LexerPopModeAction.INSTANCE, LexerPopModeAction.INSTANCE);
	}

	@Test
	public void testLexerTypeChannelModePush() {
		LexerTypeAction type = new LexerTypeAction(5);
		assertEquals(5, type.getType());
		assertEquals(LexerActionType.TYPE, type.getActionType());
		assertFalse(type.isPositionDependent());
		assertEquals(type, new LexerTypeAction(5));
		assertNotEquals(type, new LexerTypeAction(6));
		assertEquals(type.hashCode(), new LexerTypeAction(5).hashCode());
		assertTrue(type.toString().contains("5"));

		LexerChannelAction ch = new LexerChannelAction(2);
		assertEquals(2, ch.getChannel());
		assertEquals(LexerActionType.CHANNEL, ch.getActionType());
		assertFalse(ch.isPositionDependent());
		assertEquals(ch, new LexerChannelAction(2));
		assertNotEquals(ch, new LexerChannelAction(3));
		assertTrue(ch.toString().contains("2"));

		LexerModeAction mode = new LexerModeAction(1);
		assertEquals(1, mode.getMode());
		assertEquals(LexerActionType.MODE, mode.getActionType());
		assertFalse(mode.isPositionDependent());
		assertEquals(mode, new LexerModeAction(1));
		assertNotEquals(mode, new LexerModeAction(2));
		assertTrue(mode.toString().contains("1"));

		LexerPushModeAction push = new LexerPushModeAction(3);
		assertEquals(3, push.getMode());
		assertEquals(LexerActionType.PUSH_MODE, push.getActionType());
		assertFalse(push.isPositionDependent());
		assertEquals(push, new LexerPushModeAction(3));
		assertNotEquals(push, new LexerPushModeAction(4));
		assertTrue(push.toString().contains("3"));
	}

	@Test
	public void testLexerCustomAndIndexed() {
		LexerCustomAction custom = new LexerCustomAction(1, 2);
		assertEquals(1, custom.getRuleIndex());
		assertEquals(2, custom.getActionIndex());
		assertEquals(LexerActionType.CUSTOM, custom.getActionType());
		assertTrue(custom.isPositionDependent());
		assertEquals(custom, new LexerCustomAction(1, 2));
		assertNotEquals(custom, new LexerCustomAction(1, 3));
		assertEquals(custom.hashCode(), new LexerCustomAction(1, 2).hashCode());

		LexerIndexedCustomAction indexed = new LexerIndexedCustomAction(4, custom);
		assertEquals(4, indexed.getOffset());
		assertSame(custom, indexed.getAction());
		assertEquals(LexerActionType.CUSTOM, indexed.getActionType());
		assertTrue(indexed.isPositionDependent());
		assertEquals(indexed, new LexerIndexedCustomAction(4, custom));
		assertNotEquals(indexed, new LexerIndexedCustomAction(5, custom));
		assertEquals(indexed.hashCode(), new LexerIndexedCustomAction(4, custom).hashCode());
	}

	@Test
	public void testLexerActionExecutor() {
		LexerActionExecutor empty = new LexerActionExecutor(new LexerAction[0]);
		assertEquals(0, empty.getLexerActions().length);
		assertEquals(empty, new LexerActionExecutor(new LexerAction[0]));
		assertNotEquals(empty, null);
		assertNotEquals(empty, "x");

		LexerActionExecutor a = LexerActionExecutor.append(null, LexerSkipAction.INSTANCE);
		assertEquals(1, a.getLexerActions().length);
		assertSame(LexerSkipAction.INSTANCE, a.getLexerActions()[0]);

		LexerActionExecutor b = LexerActionExecutor.append(a, LexerMoreAction.INSTANCE);
		assertEquals(2, b.getLexerActions().length);
		assertEquals(b, LexerActionExecutor.append(a, LexerMoreAction.INSTANCE));
		assertEquals(b.hashCode(), LexerActionExecutor.append(a, LexerMoreAction.INSTANCE).hashCode());

		// fixOffsetBeforeMatch with position-dependent action
		LexerCustomAction custom = new LexerCustomAction(0, 0);
		LexerActionExecutor withCustom = new LexerActionExecutor(new LexerAction[] { custom, LexerSkipAction.INSTANCE });
		LexerActionExecutor fixed = withCustom.fixOffsetBeforeMatch(3);
		assertTrue(fixed.getLexerActions()[0] instanceof LexerIndexedCustomAction);
		assertEquals(3, ((LexerIndexedCustomAction) fixed.getLexerActions()[0]).getOffset());
		// second call with already-indexed should return same when no unindexed position-dependent remain
		LexerActionExecutor fixed2 = fixed.fixOffsetBeforeMatch(9);
		assertSame(fixed, fixed2);

		// no position-dependent => same instance
		LexerActionExecutor onlySkip = new LexerActionExecutor(new LexerAction[] { LexerSkipAction.INSTANCE });
		assertSame(onlySkip, onlySkip.fixOffsetBeforeMatch(1));
	}

	@Test
	public void testLexerActionExecuteOnLexer() {
		ATN atn = ATNTestHelpers.buildLexerMatchA();
		LexerInterpreter lexer = ATNTestHelpers.createLexer(atn, "a");

		new LexerTypeAction(TokenType.A).execute(lexer);
		assertEquals(TokenType.A, lexer.getType());

		new LexerChannelAction(2).execute(lexer);
		assertEquals(2, lexer.getChannel());

		// mode/pushMode/popMode
		// Need a second mode for push/pop - just push mode 0 again is fine for coverage
		new LexerPushModeAction(0).execute(lexer);
		LexerPopModeAction.INSTANCE.execute(lexer);

		new LexerModeAction(0).execute(lexer);
		assertEquals(0, lexer._mode);

		LexerSkipAction.INSTANCE.execute(lexer);
		LexerMoreAction.INSTANCE.execute(lexer);

		// custom action path (no-op default in Recognizer)
		new LexerCustomAction(0, 0).execute(lexer);

		// executor execute with stream seek; last action wins for type
		CharStream input = CharStreams.fromString("abc");
		input.seek(3);
		LexerActionExecutor exec = new LexerActionExecutor(new LexerAction[] {
			new LexerIndexedCustomAction(1, new LexerTypeAction(9))
		});
		exec.execute(lexer, input, 0);
		assertEquals(9, lexer.getType());

		// skip after type leaves type as SKIP (-3)
		LexerActionExecutor exec2 = new LexerActionExecutor(new LexerAction[] {
			new LexerTypeAction(9),
			LexerSkipAction.INSTANCE
		});
		exec2.execute(lexer, input, 0);
		assertEquals(org.antlr.v4.runtime.Lexer.SKIP, lexer.getType());
	}

	@Test
	public void testEqualsHashCodeEdgeCases() {
		LexerModeAction mode = new LexerModeAction(1);
		assertEquals(mode.hashCode(), new LexerModeAction(1).hashCode());
		assertNotEquals(mode, null);
		assertNotEquals(mode, "mode");
		assertNotEquals(mode, new LexerModeAction(2));
		assertEquals(mode, mode);

		LexerPushModeAction push = new LexerPushModeAction(3);
		assertEquals(push.hashCode(), new LexerPushModeAction(3).hashCode());
		assertNotEquals(push, null);
		assertNotEquals(push, new LexerPushModeAction(4));
		assertEquals(push, push);

		LexerChannelAction ch = new LexerChannelAction(2);
		assertEquals(ch.hashCode(), new LexerChannelAction(2).hashCode());
		assertNotEquals(ch, null);
		assertNotEquals(ch, new LexerTypeAction(2));

		LexerTypeAction type = new LexerTypeAction(5);
		assertEquals(type.hashCode(), new LexerTypeAction(5).hashCode());
		assertNotEquals(type, null);
		assertNotEquals(type, new LexerChannelAction(5));

		assertEquals(LexerPopModeAction.INSTANCE.hashCode(), LexerPopModeAction.INSTANCE.hashCode());
		assertNotEquals(LexerPopModeAction.INSTANCE, null);
		assertNotEquals(LexerPopModeAction.INSTANCE, LexerSkipAction.INSTANCE);
		assertEquals(LexerPopModeAction.INSTANCE, LexerPopModeAction.INSTANCE);

		LexerCustomAction custom = new LexerCustomAction(1, 2);
		LexerIndexedCustomAction indexed = new LexerIndexedCustomAction(4, custom);
		assertNotEquals(indexed, null);
		assertNotEquals(indexed, custom);
		assertNotEquals(indexed, new LexerIndexedCustomAction(4, new LexerCustomAction(9, 9)));

		// execute indexed custom action
		ATN atn = ATNTestHelpers.buildLexerMatchA();
		LexerInterpreter lexer = ATNTestHelpers.createLexer(atn, "a");
		indexed.execute(lexer);
	}

	/** Tiny local constant helper for token type values in tests. */
	private static final class TokenType {
		static final int A = 1;
	}
}
