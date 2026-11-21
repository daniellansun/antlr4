/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.RuleContext;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Focused unit tests for {@link LexerActionExecutor} compiled fast modes:
 * {@code MODE_SINGLE}, {@code MODE_CUSTOM_OPT_BUILTIN}, {@code MODE_MULTI},
 * {@code MODE_SLOW}, {@link LexerActionExecutor#isSingleSkip()}, and
 * position-dependent seek/restore semantics.
 */
public class TestLexerActionExecutorFastModes {

	@Test
	public void testAllModesCustomOptBuiltInAndSeekSemantics() {
		final ATN atn = ATNTestHelpers.buildLexerMatchA();
		final List<String> log = new ArrayList<String>();
		Lexer lexer = new Lexer(CharStreams.fromString("abcdef")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "R" }; }
			@Override public String getGrammarFileName() { return "E.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public void action(RuleContext ctx, int ruleIndex, int actionIndex) {
				log.add("act:" + ruleIndex + ":" + actionIndex + "@" + _input.index());
				if (actionIndex == 10) {
					// Deliberately move input stream to verify MODE_SLOW stopIndex restore
					_input.seek(1);
				}
			}
		};
		CharStream input = lexer.getInputStream();
		input.seek(4);

		// 1. Empty executor (len == 0 -> MODE_SINGLE with KIND_NONE)
		LexerActionExecutor empty = new LexerActionExecutor(new LexerAction[0]);
		assertFalse(empty.isSingleSkip());
		assertSame(empty, empty.fixOffsetBeforeMatch(2));
		empty.execute(lexer, input, 0);

		// 2. MODE_SINGLE for every built-in action kind
		LexerActionExecutor singleSkip = new LexerActionExecutor(new LexerAction[] { LexerSkipAction.INSTANCE });
		assertTrue(singleSkip.isSingleSkip());
		singleSkip.execute(lexer, input, 0);
		assertEquals(Lexer.SKIP, lexer.getType());

		LexerActionExecutor singleMore = new LexerActionExecutor(new LexerAction[] { LexerMoreAction.INSTANCE });
		assertFalse(singleMore.isSingleSkip());
		singleMore.execute(lexer, input, 0);
		assertEquals(Lexer.MORE, lexer.getType());

		new LexerActionExecutor(new LexerAction[] { new LexerTypeAction(42) }).execute(lexer, input, 0);
		assertEquals(42, lexer.getType());

		new LexerActionExecutor(new LexerAction[] { new LexerChannelAction(7) }).execute(lexer, input, 0);
		assertEquals(7, lexer.getChannel());

		new LexerActionExecutor(new LexerAction[] { new LexerPushModeAction(2) }).execute(lexer, input, 0);
		assertEquals(2, lexer._mode);

		new LexerActionExecutor(new LexerAction[] { new LexerModeAction(3) }).execute(lexer, input, 0);
		assertEquals(3, lexer._mode);

		new LexerActionExecutor(new LexerAction[] { LexerPopModeAction.INSTANCE }).execute(lexer, input, 0);
		assertEquals(Lexer.DEFAULT_MODE, lexer._mode);

		// 3. MODE_CUSTOM_OPT_BUILTIN: [CUSTOM] and [CUSTOM, trailingBuiltIn]
		log.clear();
		LexerActionExecutor singleCustom = new LexerActionExecutor(new LexerAction[] { new LexerCustomAction(1, 5) });
		assertFalse(singleCustom.isSingleSkip());
		singleCustom.execute(lexer, input, 0);
		assertEquals(Collections.singletonList("act:1:5@4"), log);

		log.clear();
		LexerActionExecutor customThenSkip = new LexerActionExecutor(new LexerAction[] {
			new LexerCustomAction(2, 6), LexerSkipAction.INSTANCE
		});
		assertFalse(customThenSkip.isSingleSkip());
		customThenSkip.execute(lexer, input, 0);
		assertEquals(Collections.singletonList("act:2:6@4"), log);
		assertEquals(Lexer.SKIP, lexer.getType());

		log.clear();
		LexerActionExecutor customThenPush = new LexerActionExecutor(new LexerAction[] {
			new LexerCustomAction(2, 7), new LexerPushModeAction(5)
		});
		customThenPush.execute(lexer, input, 0);
		assertEquals(Collections.singletonList("act:2:7@4"), log);
		assertEquals(5, lexer._mode);
		lexer.popMode();

		log.clear();
		LexerActionExecutor customThenType = new LexerActionExecutor(new LexerAction[] {
			new LexerCustomAction(2, 8), new LexerTypeAction(77)
		});
		customThenType.execute(lexer, input, 0);
		assertEquals(Collections.singletonList("act:2:8@4"), log);
		assertEquals(77, lexer.getType());

		// 4. MODE_MULTI: multiple pure built-in actions (customCount == 0)
		LexerActionExecutor multiNoCustom = new LexerActionExecutor(new LexerAction[] {
			new LexerTypeAction(11), new LexerChannelAction(2), LexerMoreAction.INSTANCE
		});
		assertFalse(multiNoCustom.isSingleSkip());
		multiNoCustom.execute(lexer, input, 0);
		assertEquals(Lexer.MORE, lexer.getType());
		assertEquals(2, lexer.getChannel());

		// 5. MODE_SLOW: [builtIn, CUSTOM], len >= 3 containing custom, >= 2 custom actions
		log.clear();
		input.seek(4);
		LexerActionExecutor builtInThenCustom = new LexerActionExecutor(new LexerAction[] {
			new LexerTypeAction(33), new LexerCustomAction(0, 11)
		});
		builtInThenCustom.execute(lexer, input, 0);
		assertEquals(33, lexer.getType());
		assertEquals(Collections.singletonList("act:0:11@4"), log);

		log.clear();
		input.seek(4);
		LexerActionExecutor multiTwoCustom = new LexerActionExecutor(new LexerAction[] {
			new LexerCustomAction(0, 10),
			new LexerCustomAction(0, 20),
			new LexerTypeAction(99)
		});
		multiTwoCustom.execute(lexer, input, 0);
		assertEquals(Arrays.asList("act:0:10@4", "act:0:20@4"), log);
		assertEquals(99, lexer.getType());

		// 6. MODE_SLOW via fixOffsetBeforeMatch (LexerIndexedCustomAction) + already-fixed identity return
		LexerActionExecutor indexed = multiTwoCustom.fixOffsetBeforeMatch(2);
		assertSame(indexed, indexed.fixOffsetBeforeMatch(3));
		log.clear();
		input.seek(4);
		indexed.execute(lexer, input, 0);
		assertEquals(Arrays.asList("act:0:10@2", "act:0:20@2"), log);
		assertEquals(4, input.index()); // finally restored stopIndex

		// Indexed offset == stopIndex (requiresSeek stays false)
		log.clear();
		input.seek(2);
		indexed.execute(lexer, input, 0);
		assertEquals(Arrays.asList("act:0:10@2", "act:0:20@2"), log);
		assertEquals(2, input.index());

		// 7. MODE_SLOW via custom non-built-in LexerAction + append/equals/hashCode
		final boolean[] customRan = new boolean[1];
		LexerAction customImpl = new LexerAction() {
			@Override public LexerActionType getActionType() { return LexerActionType.CUSTOM; }
			@Override public boolean isPositionDependent() { return true; }
			@Override public void execute(Lexer l) { customRan[0] = true; }
		};
		LexerActionExecutor slowExec = new LexerActionExecutor(new LexerAction[] { customImpl });
		slowExec.execute(lexer, input, 0);
		assertTrue(customRan[0]);

		LexerActionExecutor appended1 = LexerActionExecutor.append(null, LexerSkipAction.INSTANCE);
		assertTrue(appended1.isSingleSkip());
		assertEquals(singleSkip, appended1);
		assertEquals(singleSkip.hashCode(), appended1.hashCode());
		LexerActionExecutor appended2 = LexerActionExecutor.append(appended1, new LexerTypeAction(5));
		assertEquals(2, appended2.getLexerActions().length);
		assertNotEquals(singleSkip, appended2);
		assertFalse(singleSkip.equals("not-an-executor"));
		assertTrue(singleSkip.equals(singleSkip));
	}
}
