/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.MurmurHash;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;

import java.util.Arrays;

/**
 * Represents an executor for a sequence of lexer actions which traversed during
 * the matching operation of a lexer rule (token).
 *
 * <p>The executor tracks position information for position-dependent lexer actions
 * efficiently, ensuring that actions appearing only at the end of the rule do
 * not cause bloating of the {@link DFA} created for the lexer.</p>
 *
 * @author Sam Harwell
 * @since 4.2
 */
public class LexerActionExecutor {
	private static final byte MODE_SLOW = 0;
	private static final byte MODE_SINGLE = 1;
	private static final byte MODE_MULTI = 2;
	private static final byte MODE_CUSTOM_OPT_BUILTIN = 3;

	private static final byte KIND_NONE = 0;
	private static final byte KIND_SKIP = 1;
	private static final byte KIND_MORE = 2;
	private static final byte KIND_TYPE = 3;
	private static final byte KIND_CHANNEL = 4;
	private static final byte KIND_MODE = 5;
	private static final byte KIND_PUSH_MODE = 6;
	private static final byte KIND_POP_MODE = 7;
	private static final byte KIND_CUSTOM = 8;

	@NotNull
	private final LexerAction[] lexerActions;
	/**
	 * Caches the result of {@link #hashCode} since the hash code is an element
	 * of the performance-critical {@link LexerATNConfig#hashCode} operation.
	 */
	private final int hashCode;

	/**
	 * {@code true} iff at least one action in {@link #lexerActions} is
	 * position-dependent and not already wrapped in a
	 * {@link LexerIndexedCustomAction}. Allows {@link #fixOffsetBeforeMatch}
	 * to return {@code this} in O(1) without scanning or megamorphic dispatch.
	 */
	private final boolean hasUnfixedPositionDependent;

	/**
	 * Compiled execution mode for built-in non-indexed {@link LexerAction}
	 * sequences. Avoids megamorphic {@code invokeinterface} dispatch on
	 * {@link LexerAction#isPositionDependent()} and {@link LexerAction#execute}
	 * as well as redundant {@link IntStream#seek} / {@code try-finally} work
	 * when no {@link LexerIndexedCustomAction} is present.
	 *
	 * <p>In {@link #MODE_CUSTOM_OPT_BUILTIN}, {@link #singleArg1} and
	 * {@link #singleArg2} hold {@code (ruleIndex, actionIndex)} of the leading
	 * {@link LexerCustomAction}, and {@link #tailKind} / {@link #tailArg} hold
	 * an optional trailing built-in command (e.g. {@code pushMode}, {@code popMode},
	 * {@code type}, {@code skip}). Keeping literally one {@code lexer.action}
	 * call site in this class avoids HotSpot C2 {@code NodeCountInliningCutoff}
	 * bimodal regressions on parallel lexing.</p>
	 */
	private final byte fastMode;
	private final byte singleKind;
	private final int singleArg1;
	private final int singleArg2;
	private final byte tailKind;
	private final int tailArg;
	private final byte[] actionKinds;
	private final int[] actionArg1;

	/**
	 * Constructs an executor for a sequence of {@link LexerAction} actions.
	 * @param lexerActions The lexer actions to execute.
	 */
	public LexerActionExecutor(@NotNull LexerAction[] lexerActions) {
		this.lexerActions = lexerActions;

		int hash = MurmurHash.initialize();
		boolean unfixedPosDep = false;
		boolean allBuiltInNonIndexed = true;
		int customCount = 0;
		final int len = lexerActions.length;
		for (int i = 0; i < len; i++) {
			LexerAction lexerAction = lexerActions[i];
			hash = MurmurHash.update(hash, lexerAction);
			if (lexerAction.isPositionDependent() && !(lexerAction instanceof LexerIndexedCustomAction)) {
				unfixedPosDep = true;
			}
			byte kind = classifyBuiltIn(lexerAction);
			if (kind == KIND_NONE) {
				allBuiltInNonIndexed = false;
			}
			else if (kind == KIND_CUSTOM) {
				customCount++;
			}
		}

		this.hashCode = MurmurHash.finish(hash, len);
		this.hasUnfixedPositionDependent = unfixedPosDep;

		if (len == 0) {
			this.fastMode = MODE_SINGLE;
			this.singleKind = KIND_NONE;
			this.singleArg1 = 0;
			this.singleArg2 = 0;
			this.tailKind = KIND_NONE;
			this.tailArg = 0;
			this.actionKinds = null;
			this.actionArg1 = null;
		}
		else if (allBuiltInNonIndexed && len == 1) {
			LexerAction a = lexerActions[0];
			byte k = classifyBuiltIn(a);
			if (k == KIND_CUSTOM) {
				this.fastMode = MODE_CUSTOM_OPT_BUILTIN;
				this.singleKind = KIND_CUSTOM;
				this.singleArg1 = extractArg1(a, k);
				this.singleArg2 = extractArg2(a, k);
				this.tailKind = KIND_NONE;
				this.tailArg = 0;
			}
			else {
				this.fastMode = MODE_SINGLE;
				this.singleKind = k;
				this.singleArg1 = extractArg1(a, k);
				this.singleArg2 = 0;
				this.tailKind = KIND_NONE;
				this.tailArg = 0;
			}
			this.actionKinds = null;
			this.actionArg1 = null;
		}
		else if (allBuiltInNonIndexed && len == 2 && customCount == 1
			&& classifyBuiltIn(lexerActions[0]) == KIND_CUSTOM) {
			LexerAction a0 = lexerActions[0];
			LexerAction a1 = lexerActions[1];
			byte k1 = classifyBuiltIn(a1);
			this.fastMode = MODE_CUSTOM_OPT_BUILTIN;
			this.singleKind = KIND_CUSTOM;
			this.singleArg1 = extractArg1(a0, KIND_CUSTOM);
			this.singleArg2 = extractArg2(a0, KIND_CUSTOM);
			this.tailKind = k1;
			this.tailArg = extractArg1(a1, k1);
			this.actionKinds = null;
			this.actionArg1 = null;
		}
		else if (allBuiltInNonIndexed && customCount == 0) {
			byte[] kinds = new byte[len];
			int[] a1 = new int[len];
			for (int i = 0; i < len; i++) {
				LexerAction a = lexerActions[i];
				byte k = classifyBuiltIn(a);
				kinds[i] = k;
				a1[i] = extractArg1(a, k);
			}
			this.fastMode = MODE_MULTI;
			this.singleKind = KIND_NONE;
			this.singleArg1 = 0;
			this.singleArg2 = 0;
			this.tailKind = KIND_NONE;
			this.tailArg = 0;
			this.actionKinds = kinds;
			this.actionArg1 = a1;
		}
		else {
			// Covers [builtIn, CUSTOM], len >= 3 containing custom, >= 2 custom,
			// LexerIndexedCustomAction, and custom LexerAction subclasses.
			this.fastMode = MODE_SLOW;
			this.singleKind = KIND_NONE;
			this.singleArg1 = 0;
			this.singleArg2 = 0;
			this.tailKind = KIND_NONE;
			this.tailArg = 0;
			this.actionKinds = null;
			this.actionArg1 = null;
		}
	}

	private static byte classifyBuiltIn(LexerAction action) {
		if (action == LexerSkipAction.INSTANCE) {
			return KIND_SKIP;
		}
		if (action == LexerMoreAction.INSTANCE) {
			return KIND_MORE;
		}
		if (action == LexerPopModeAction.INSTANCE) {
			return KIND_POP_MODE;
		}
		Class<?> cls = action.getClass();
		if (cls == LexerCustomAction.class) {
			return KIND_CUSTOM;
		}
		if (cls == LexerTypeAction.class) {
			return KIND_TYPE;
		}
		if (cls == LexerPushModeAction.class) {
			return KIND_PUSH_MODE;
		}
		if (cls == LexerChannelAction.class) {
			return KIND_CHANNEL;
		}
		if (cls == LexerModeAction.class) {
			return KIND_MODE;
		}
		return KIND_NONE;
	}

	private static int extractArg1(LexerAction action, byte kind) {
		switch (kind) {
		case KIND_TYPE:
			return ((LexerTypeAction) action).getType();
		case KIND_CHANNEL:
			return ((LexerChannelAction) action).getChannel();
		case KIND_MODE:
			return ((LexerModeAction) action).getMode();
		case KIND_PUSH_MODE:
			return ((LexerPushModeAction) action).getMode();
		case KIND_CUSTOM:
			return ((LexerCustomAction) action).getRuleIndex();
		default:
			return 0;
		}
	}

	private static int extractArg2(LexerAction action, byte kind) {
		return kind == KIND_CUSTOM ? ((LexerCustomAction) action).getActionIndex() : 0;
	}

	/**
	 * Creates a {@link LexerActionExecutor} which executes the actions for
	 * the input {@code lexerActionExecutor} followed by a specified
	 * {@code lexerAction}.
	 *
	 * @param lexerActionExecutor The executor for actions already traversed by
	 * the lexer while matching a token within a particular
	 * {@link ATNConfig}. If this is {@code null}, the method behaves as though
	 * it were an empty executor.
	 * @param lexerAction The lexer action to execute after the actions
	 * specified in {@code lexerActionExecutor}.
	 *
	 * @return A {@link LexerActionExecutor} for executing the combine actions
	 * of {@code lexerActionExecutor} and {@code lexerAction}.
	 */
	@NotNull
	public static LexerActionExecutor append(@Nullable LexerActionExecutor lexerActionExecutor, @NotNull LexerAction lexerAction) {
		if (lexerActionExecutor == null) {
			return new LexerActionExecutor(new LexerAction[] { lexerAction });
		}

		LexerAction[] lexerActions = Arrays.copyOf(lexerActionExecutor.lexerActions, lexerActionExecutor.lexerActions.length + 1);
		lexerActions[lexerActions.length - 1] = lexerAction;
		return new LexerActionExecutor(lexerActions);
	}

	/**
	 * Creates a {@link LexerActionExecutor} which encodes the current offset
	 * for position-dependent lexer actions.
	 *
	 * <p>Normally, when the executor encounters lexer actions where
	 * {@link LexerAction#isPositionDependent} returns {@code true}, it calls
	 * {@link IntStream#seek} on the input {@link CharStream} to set the input
	 * position to the <em>end</em> of the current token. This behavior provides
	 * for efficient DFA representation of lexer actions which appear at the end
	 * of a lexer rule, even when the lexer rule matches a variable number of
	 * characters.</p>
	 *
	 * <p>Prior to traversing a match transition in the ATN, the current offset
	 * from the token start index is assigned to all position-dependent lexer
	 * actions which have not already been assigned a fixed offset. By storing
	 * the offsets relative to the token start index, the DFA representation of
	 * lexer actions which appear in the middle of tokens remains efficient due
	 * to sharing among tokens of the same length, regardless of their absolute
	 * position in the input stream.</p>
	 *
	 * <p>If the current executor already has offsets assigned to all
	 * position-dependent lexer actions, the method returns {@code this}.</p>
	 *
	 * @param offset The current offset to assign to all position-dependent
	 * lexer actions which do not already have offsets assigned.
	 *
	 * @return A {@link LexerActionExecutor} which stores input stream offsets
	 * for all position-dependent lexer actions.
	 */
	public LexerActionExecutor fixOffsetBeforeMatch(int offset) {
		if (!hasUnfixedPositionDependent) {
			return this;
		}
		LexerAction[] updatedLexerActions = null;
		for (int i = 0; i < lexerActions.length; i++) {
			if (lexerActions[i].isPositionDependent() && !(lexerActions[i] instanceof LexerIndexedCustomAction)) {
				if (updatedLexerActions == null) {
					updatedLexerActions = lexerActions.clone();
				}

				updatedLexerActions[i] = new LexerIndexedCustomAction(offset, lexerActions[i]);
			}
		}

		if (updatedLexerActions == null) {
			return this;
		}

		return new LexerActionExecutor(updatedLexerActions);
	}

	/**
	 * Gets the lexer actions to be executed by this executor.
	 * @return The lexer actions to be executed by this executor.
	 */
	@NotNull
	public LexerAction[] getLexerActions() {
		return lexerActions;
	}

	/**
	 * Returns {@code true} iff this executor consists of a single built-in
	 * {@link LexerSkipAction} (no custom or position-dependent actions).
	 *
	 * @since 4.13.2.20
	 */
	public final boolean isSingleSkip() {
		return fastMode == MODE_SINGLE && singleKind == KIND_SKIP;
	}

	/**
	 * Execute the actions encapsulated by this executor within the context of a
	 * particular {@link Lexer}.
	 *
	 * <p>This method calls {@link IntStream#seek} to set the position of the
	 * {@code input} {@link CharStream} prior to calling
	 * {@link LexerAction#execute} on a position-dependent action. Before the
	 * method returns, the input position will be restored to the same position
	 * it was in when the method was invoked.</p>
	 *
	 * @param lexer The lexer instance.
	 * @param input The input stream which is the source for the current token.
	 * When this method is called, the current {@link IntStream#index} for
	 * {@code input} should be the start of the following token, i.e. 1
	 * character past the end of the current token.
	 * @param startIndex The token start index. This value may be passed to
	 * {@link IntStream#seek} to set the {@code input} position to the beginning
	 * of the token.
	 */
	public void execute(@NotNull Lexer lexer, CharStream input, int startIndex) {
		final byte mode = fastMode;
		byte kind;
		int arg;
		if (mode == MODE_CUSTOM_OPT_BUILTIN) {
			// Literally the only lexer.action(...) call site in this class.
			lexer.action(null, singleArg1, singleArg2);
			kind = tailKind;
			if (kind == KIND_NONE) {
				return;
			}
			arg = tailArg;
		}
		else if (mode == MODE_SINGLE) {
			kind = singleKind;
			if (kind == KIND_SKIP) {
				lexer.skip();
				return;
			}
			arg = singleArg1;
		}
		else if (mode == MODE_MULTI) {
			executeMultiBuiltIn(lexer);
			return;
		}
		else {
			executeSlow(lexer, input, startIndex);
			return;
		}
		executeBuiltIn(lexer, kind, arg);
	}

	private static void executeBuiltIn(@NotNull Lexer lexer, byte kind, int arg1) {
		switch (kind) {
		case KIND_SKIP:
			lexer.skip();
			break;
		case KIND_MORE:
			lexer.more();
			break;
		case KIND_TYPE:
			lexer.setType(arg1);
			break;
		case KIND_CHANNEL:
			lexer.setChannel(arg1);
			break;
		case KIND_MODE:
			lexer.mode(arg1);
			break;
		case KIND_PUSH_MODE:
			lexer.pushMode(arg1);
			break;
		case KIND_POP_MODE:
			lexer.popMode();
			break;
		default:
			break;
		}
	}

	private void executeMultiBuiltIn(@NotNull Lexer lexer) {
		final byte[] kinds = this.actionKinds;
		final int[] a1 = this.actionArg1;
		for (int i = 0, len = kinds.length; i < len; i++) {
			executeBuiltIn(lexer, kinds[i], a1[i]);
		}
	}

	private void executeSlow(@NotNull Lexer lexer, CharStream input, int startIndex) {
		boolean requiresSeek = false;
		int stopIndex = input.index();
		try {
			for (LexerAction lexerAction : lexerActions) {
				if (lexerAction instanceof LexerIndexedCustomAction) {
					int offset = ((LexerIndexedCustomAction)lexerAction).getOffset();
					input.seek(startIndex + offset);
					lexerAction = ((LexerIndexedCustomAction)lexerAction).getAction();
					requiresSeek = (startIndex + offset) != stopIndex;
				}
				else if (lexerAction.isPositionDependent()) {
					input.seek(stopIndex);
					requiresSeek = false;
				}

				lexerAction.execute(lexer);
			}
		}
		finally {
			if (requiresSeek) {
				input.seek(stopIndex);
			}
		}
	}

	@Override
	public int hashCode() {
		return this.hashCode;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this) {
			return true;
		}
		else if (!(obj instanceof LexerActionExecutor)) {
			return false;
		}

		LexerActionExecutor other = (LexerActionExecutor)obj;
		return hashCode == other.hashCode
			&& Arrays.equals(lexerActions, other.lexerActions);
	}
}
