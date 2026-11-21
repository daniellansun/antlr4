/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATNSimulator;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.antlr.v4.runtime.atn.LexerActionExecutor;
import org.antlr.v4.runtime.dfa.AcceptStateInfo;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.NotNull;

/**
 * Package-private fast DFA runner for {@link CodePointCharStream} invoked
 * exclusively from {@link Lexer#nextTokenCodePoint}.
 *
 * <p>Lives in {@code org.antlr.v4.runtime} so it can read the package-private
 * {@code byteArray}/{@code charArray}/{@code intArray}, {@code position}, and
 * {@code size} fields of {@link CodePointCharStream} subclasses directly without
 * exposing mutable internal arrays in any {@code public} or {@code protected}
 * API.</p>
 *
 * <p>Uses two thin register-resident loops ({@link #exec8Bit} for Latin-1
 * {@code byte[]}, {@link #exec16Bit} for BMP {@code char[]}) plus a compact
 * 32-bit fallback ({@link #exec32Bit}), all sharing a single accept and
 * zero-reflection {@code -> skip} fusion tail ({@link #finishToken}).</p>
 */
final class CodePointLexerRunner {

	/** Internal sentinel returned by {@link #finishToken} when an in-place skip fused. */
	private static final int CONTINUE_SKIP = Integer.MIN_VALUE;

	private CodePointLexerRunner() {
	}

	/**
	 * Matches the next token starting at {@code input.position}, dispatching
	 * directly into the concrete 8/16/32-bit register-resident loop when the
	 * simulator is fast-DFA eligible and the mode's start state is warm, and
	 * falling back cleanly to {@link LexerATNSimulator#match} otherwise.
	 */
	static int match(@NotNull Lexer recog,
					 @NotNull CodePointCharStream input,
					 @NotNull LexerATNSimulator interp,
					 int mode)
	{
		DFAState ds0;
		if (interp.isFastDFAEligible() && !LexerATNSimulator.debug
			&& (ds0 = interp.getDFA(mode).s0.get()) != null) {
			Class<?> inputClass = input.getClass();
			if (inputClass == CodePointCharStream.CodePoint8BitCharStream.class) {
				return exec8Bit(recog, input, ((CodePointCharStream.CodePoint8BitCharStream) input).byteArray, interp, mode, ds0);
			}
			if (inputClass == CodePointCharStream.CodePoint16BitCharStream.class) {
				return exec16Bit(recog, input, ((CodePointCharStream.CodePoint16BitCharStream) input).charArray, interp, mode, ds0);
			}
			if (inputClass == CodePointCharStream.CodePoint32BitCharStream.class) {
				return exec32Bit(recog, input, ((CodePointCharStream.CodePoint32BitCharStream) input).intArray, interp, mode, ds0);
			}
		}
		return interp.match(input, mode);
	}

	private static int exec8Bit(@NotNull Lexer recog,
								@NotNull CodePointCharStream input,
								@NotNull byte[] data,
								@NotNull LexerATNSimulator interp,
								int mode,
								@NotNull DFAState ds0)
	{
		final int limit = input.size;
		int pos = input.position;
		int startIndex = pos;
		int curLine = interp.getLine();
		int curCharPos = interp.getCharPositionInLine();

		while (true) {
			int acceptIndex = -1;
			int acceptLine = 0;
			int acceptCharPos = -1;
			DFAState acceptState = null;

			if (ds0.isAcceptState()) {
				acceptIndex = pos;
				acceptLine = curLine;
				acceptCharPos = curCharPos;
				acceptState = ds0;
			}

			int t = pos < limit ? (data[pos] & 0xFF) : IntStream.EOF;
			DFAState s = ds0;

			while (true) {
				DFAState target = s.getLexerTarget(t);
				if (target == null) {
					input.position = pos;
					interp.syncFastState(mode, startIndex, curLine, curCharPos);
					target = interp.resolveFastTarget(input, ds0, s, t);
					pos = input.position;
					curLine = interp.getLine();
					curCharPos = interp.getCharPositionInLine();
				}

				if (target == ATNSimulator.ERROR) {
					break;
				}

				if (t > '\r') {
					curCharPos++;
					pos++;
					if (target.isAcceptState()) {
						acceptIndex = pos;
						acceptLine = curLine;
						acceptCharPos = curCharPos;
						acceptState = target;
					}
				}
				else if (t != IntStream.EOF) {
					if (t == '\n' || (t == '\r' && (pos + 1 >= limit || (data[pos + 1] & 0xFF) != '\n'))) {
						curLine++;
						curCharPos = 0;
					}
					else {
						curCharPos++;
					}
					pos++;
					if (target.isAcceptState()) {
						acceptIndex = pos;
						acceptLine = curLine;
						acceptCharPos = curCharPos;
						acceptState = target;
					}
				}
				else if (target.isAcceptState()) {
					acceptIndex = pos;
					acceptLine = curLine;
					acceptCharPos = curCharPos;
					acceptState = target;
					break;
				}

				t = pos < limit ? (data[pos] & 0xFF) : IntStream.EOF;
				s = target;
			}

			if (acceptState != null) {
				int res = finishToken(recog, input, interp, mode, startIndex,
					acceptIndex, acceptLine, acceptCharPos, acceptState, limit);
				if (res == CONTINUE_SKIP) {
					pos = acceptIndex;
					startIndex = acceptIndex;
					curLine = acceptLine;
					curCharPos = acceptCharPos;
					continue;
				}
				return res;
			}

			input.position = pos;
			return interp.handleFastNoViableAlt(input, mode, startIndex, s, t, curLine, curCharPos);
		}
	}

	private static int exec16Bit(@NotNull Lexer recog,
								 @NotNull CodePointCharStream input,
								 @NotNull char[] data,
								 @NotNull LexerATNSimulator interp,
								 int mode,
								 @NotNull DFAState ds0)
	{
		final int limit = input.size;
		int pos = input.position;
		int startIndex = pos;
		int curLine = interp.getLine();
		int curCharPos = interp.getCharPositionInLine();

		while (true) {
			int acceptIndex = -1;
			int acceptLine = 0;
			int acceptCharPos = -1;
			DFAState acceptState = null;

			if (ds0.isAcceptState()) {
				acceptIndex = pos;
				acceptLine = curLine;
				acceptCharPos = curCharPos;
				acceptState = ds0;
			}

			int t = pos < limit ? (data[pos] & 0xFFFF) : IntStream.EOF;
			DFAState s = ds0;

			while (true) {
				DFAState target = s.getLexerTarget(t);
				if (target == null) {
					input.position = pos;
					interp.syncFastState(mode, startIndex, curLine, curCharPos);
					target = interp.resolveFastTarget(input, ds0, s, t);
					pos = input.position;
					curLine = interp.getLine();
					curCharPos = interp.getCharPositionInLine();
				}

				if (target == ATNSimulator.ERROR) {
					break;
				}

				if (t > '\r') {
					curCharPos++;
					pos++;
					if (target.isAcceptState()) {
						acceptIndex = pos;
						acceptLine = curLine;
						acceptCharPos = curCharPos;
						acceptState = target;
					}
				}
				else if (t != IntStream.EOF) {
					if (t == '\n' || (t == '\r' && (pos + 1 >= limit || data[pos + 1] != '\n'))) {
						curLine++;
						curCharPos = 0;
					}
					else {
						curCharPos++;
					}
					pos++;
					if (target.isAcceptState()) {
						acceptIndex = pos;
						acceptLine = curLine;
						acceptCharPos = curCharPos;
						acceptState = target;
					}
				}
				else if (target.isAcceptState()) {
					acceptIndex = pos;
					acceptLine = curLine;
					acceptCharPos = curCharPos;
					acceptState = target;
					break;
				}

				t = pos < limit ? (data[pos] & 0xFFFF) : IntStream.EOF;
				s = target;
			}

			if (acceptState != null) {
				int res = finishToken(recog, input, interp, mode, startIndex,
					acceptIndex, acceptLine, acceptCharPos, acceptState, limit);
				if (res == CONTINUE_SKIP) {
					pos = acceptIndex;
					startIndex = acceptIndex;
					curLine = acceptLine;
					curCharPos = acceptCharPos;
					continue;
				}
				return res;
			}

			input.position = pos;
			return interp.handleFastNoViableAlt(input, mode, startIndex, s, t, curLine, curCharPos);
		}
	}

	private static int exec32Bit(@NotNull Lexer recog,
								 @NotNull CodePointCharStream input,
								 @NotNull int[] data,
								 @NotNull LexerATNSimulator interp,
								 int mode,
								 @NotNull DFAState ds0)
	{
		final int limit = input.size;
		int pos = input.position;
		int startIndex = pos;
		int curLine = interp.getLine();
		int curCharPos = interp.getCharPositionInLine();

		while (true) {
			int acceptIndex = -1;
			int acceptLine = 0;
			int acceptCharPos = -1;
			DFAState acceptState = null;

			if (ds0.isAcceptState()) {
				acceptIndex = pos;
				acceptLine = curLine;
				acceptCharPos = curCharPos;
				acceptState = ds0;
			}

			int t = pos < limit ? data[pos] : IntStream.EOF;
			DFAState s = ds0;

			while (true) {
				DFAState target = s.getLexerTarget(t);
				if (target == null) {
					input.position = pos;
					interp.syncFastState(mode, startIndex, curLine, curCharPos);
					target = interp.resolveFastTarget(input, ds0, s, t);
					pos = input.position;
					curLine = interp.getLine();
					curCharPos = interp.getCharPositionInLine();
				}

				if (target == ATNSimulator.ERROR) {
					break;
				}

				if (t > '\r') {
					curCharPos++;
					pos++;
					if (target.isAcceptState()) {
						acceptIndex = pos;
						acceptLine = curLine;
						acceptCharPos = curCharPos;
						acceptState = target;
					}
				}
				else if (t != IntStream.EOF) {
					if (t == '\n' || (t == '\r' && (pos + 1 >= limit || data[pos + 1] != '\n'))) {
						curLine++;
						curCharPos = 0;
					}
					else {
						curCharPos++;
					}
					pos++;
					if (target.isAcceptState()) {
						acceptIndex = pos;
						acceptLine = curLine;
						acceptCharPos = curCharPos;
						acceptState = target;
					}
				}
				else if (target.isAcceptState()) {
					acceptIndex = pos;
					acceptLine = curLine;
					acceptCharPos = curCharPos;
					acceptState = target;
					break;
				}

				t = pos < limit ? data[pos] : IntStream.EOF;
				s = target;
			}

			if (acceptState != null) {
				int res = finishToken(recog, input, interp, mode, startIndex,
					acceptIndex, acceptLine, acceptCharPos, acceptState, limit);
				if (res == CONTINUE_SKIP) {
					pos = acceptIndex;
					startIndex = acceptIndex;
					curLine = acceptLine;
					curCharPos = acceptCharPos;
					continue;
				}
				return res;
			}

			input.position = pos;
			return interp.handleFastNoViableAlt(input, mode, startIndex, s, t, curLine, curCharPos);
		}
	}

	/**
	 * Shared accept completion and {@code -> skip} fusion tail.
	 *
	 * <p>When {@link Lexer#isDefaultSkipEligible()} is {@code true} (un-overridden
	 * {@link Lexer#skip()}), built-in single-action {@code -> skip} rules fuse
	 * directly in registers without flushing stream/simulator coordinates to
	 * heap or re-validating post-skip fields. Custom {@link Lexer#skip()}
	 * overrides fall through to the outlined {@link #finishCustomSkip} path,
	 * preserving full observability.</p>
	 */
	private static int finishToken(@NotNull Lexer recog,
								   @NotNull CodePointCharStream input,
								   @NotNull LexerATNSimulator interp,
								   int mode,
								   int startIndex,
								   int acceptIndex,
								   int acceptLine,
								   int acceptCharPos,
								   @NotNull DFAState acceptState,
								   int limit)
	{
		AcceptStateInfo acceptInfo = acceptState.getAcceptStateInfo();
		LexerActionExecutor exec = acceptInfo.getLexerActionExecutor();
		if (exec == null) {
			input.position = acceptIndex;
			interp.syncFastState(mode, startIndex, acceptLine, acceptCharPos);
			return acceptInfo.getPrediction();
		}
		if (exec.isSingleSkip()
			&& acceptIndex > startIndex
			&& acceptIndex < limit
			&& !recog._hitEOF
			&& recog._type == Token.INVALID_TYPE
			&& recog._token == null
			&& recog._channel == Token.DEFAULT_CHANNEL
			&& recog._text == null
			&& recog._tokenStartCharIndex == startIndex) {
			if (recog.isDefaultSkipEligible()) {
				recog._tokenStartCharIndex = acceptIndex;
				recog._tokenStartLine = acceptLine;
				recog._tokenStartCharPositionInLine = acceptCharPos;
				return CONTINUE_SKIP;
			}
			return finishCustomSkip(recog, input, interp, mode, startIndex,
				acceptIndex, acceptLine, acceptCharPos, acceptInfo);
		}
		input.position = acceptIndex;
		interp.syncFastState(mode, startIndex, acceptLine, acceptCharPos);
		exec.execute(recog, input, startIndex);
		return acceptInfo.getPrediction();
	}

	private static int finishCustomSkip(@NotNull Lexer recog,
										@NotNull CodePointCharStream input,
										@NotNull LexerATNSimulator interp,
										int mode,
										int startIndex,
										int acceptIndex,
										int acceptLine,
										int acceptCharPos,
										@NotNull AcceptStateInfo acceptInfo)
	{
		input.position = acceptIndex;
		interp.syncFastState(mode, startIndex, acceptLine, acceptCharPos);
		recog.skip();
		if (recog._type == Lexer.SKIP
			&& recog._input == input
			&& !recog._hitEOF
			&& recog._mode == mode
			&& recog._token == null
			&& recog._channel == Token.DEFAULT_CHANNEL
			&& recog._text == null
			&& input.position == acceptIndex
			&& interp.getLine() == acceptLine
			&& interp.getCharPositionInLine() == acceptCharPos) {
			recog._tokenStartCharIndex = acceptIndex;
			recog._tokenStartLine = acceptLine;
			recog._tokenStartCharPositionInLine = acceptCharPos;
			recog._type = Token.INVALID_TYPE;
			return CONTINUE_SKIP;
		}
		return acceptInfo.getPrediction();
	}
}
