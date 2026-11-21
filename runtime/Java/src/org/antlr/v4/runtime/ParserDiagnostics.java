/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNDeserializationOptions;
import org.antlr.v4.runtime.atn.ATNDeserializer;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.ParseInfo;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.ProfilingATNSimulator;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.pattern.ParseTreePattern;
import org.antlr.v4.runtime.tree.pattern.ParseTreePatternMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Package-private cold diagnostics, pattern compilation, expected-token query,
 * and slow-path helpers extracted from {@link Parser} so {@link Parser} stays
 * focused on hot parsing state and rule/match execution.
 */
final class ParserDiagnostics {

	/**
	 * Maps a serialized ATN string to the lazily deserialized {@link ATN} with
	 * rule-bypass transitions enabled.
	 */
	private static final Map<String, ATN> bypassAltsAtnCache =
		new WeakHashMap<String, ATN>();

	private ParserDiagnostics() {
	}

	@NotNull
	static ATN getATNWithBypassAlts(@NotNull Parser parser) {
		String serializedAtn = parser.getSerializedATN();
		if (serializedAtn == null) {
			throw new UnsupportedOperationException("The current parser does not support an ATN with bypass alternatives.");
		}

		synchronized (bypassAltsAtnCache) {
			ATN result = bypassAltsAtnCache.get(serializedAtn);
			if (result == null) {
				ATNDeserializationOptions deserializationOptions = new ATNDeserializationOptions();
				deserializationOptions.setGenerateRuleBypassTransitions(true);
				result = new ATNDeserializer(deserializationOptions).deserialize(serializedAtn);
				bypassAltsAtnCache.put(serializedAtn, result);
			}
			return result;
		}
	}

	static ParseTreePattern compileParseTreePattern(@NotNull Parser parser, String pattern, int patternRuleIndex) {
		if (parser.getInputStream() != null) {
			TokenSource tokenSource = parser.getInputStream().getTokenSource();
			if (tokenSource instanceof Lexer) {
				Lexer lexer = (Lexer) tokenSource;
				// Preserve virtual dispatch for subclasses overriding the 3-arg overload.
				return parser.compileParseTreePattern(pattern, patternRuleIndex, lexer);
			}
		}
		throw new UnsupportedOperationException("Parser can't discover a lexer to use");
	}

	static ParseTreePattern compileParseTreePattern(@NotNull Parser parser, String pattern, int patternRuleIndex, Lexer lexer) {
		ParseTreePatternMatcher m = new ParseTreePatternMatcher(lexer, parser);
		return m.compile(pattern, patternRuleIndex);
	}

	static void notifyErrorListeners(@NotNull Parser parser,
									 @NotNull Token offendingToken,
									 @NotNull String msg,
									 @Nullable RecognitionException e)
	{
		parser._syntaxErrors++;
		int line = -1;
		int charPositionInLine = -1;
		if (offendingToken != null) {
			line = offendingToken.getLine();
			charPositionInLine = offendingToken.getCharPositionInLine();
		}

		ANTLRErrorListener<? super Token> listener = parser.getErrorListenerDispatch();
		listener.syntaxError(parser, offendingToken, line, charPositionInLine, msg, e);
	}

	static void consumeBuildTreeSlow(@NotNull Parser parser,
									 @NotNull Token o,
									 @Nullable List<ParseTreeListener> listeners)
	{
		boolean recovering = parser.reportMatchRequired
			? parser._errHandler.inErrorRecoveryMode(parser)
			: parser.errorRecoveryMode;
		if (recovering) {
			ErrorNode node = parser._ctx.addErrorNode(parser.createErrorNode(parser._ctx, o));
			if (listeners != null) {
				for (int i = 0, n = listeners.size(); i < n; i++) {
					listeners.get(i).visitErrorNode(node);
				}
			}
		}
		else {
			TerminalNode node = parser.createTerminalNode(parser._ctx, o);
			parser._ctx.addChild(node);
			if (listeners != null) {
				for (int i = 0, n = listeners.size(); i < n; i++) {
					listeners.get(i).visitTerminal(node);
				}
			}
		}
	}

	static void enterLeftFactoredRule(@NotNull Parser parser, ParserRuleContext localctx, int state, int ruleIndex) {
		parser.setState(state);
		if (parser._buildParseTrees) {
			ParserRuleContext factoredContext = (ParserRuleContext) parser._ctx.getChild(parser._ctx.getChildCount() - 1);
			parser._ctx.removeLastChild();
			factoredContext.parent = localctx;
			localctx.addChild(factoredContext);
		}

		parser._ctx = localctx;
		parser._ctx.start = parser._input.LT(1);
		if (parser._buildParseTrees) {
			parser.addContextToParseTree();
		}

		if (parser._parseListeners != null) {
			parser.triggerEnterRuleEvent();
		}
	}

	static void exitRuleSlow(@NotNull Parser parser) {
		if (parser.matchedEOF) {
			// if we have matched EOF, it cannot consume past EOF so we use LT(1) here
			parser._ctx.stop = parser._input.LT(1);
		}
		else if (parser.lastConsumed != null && parser.lastConsumed.getType() != Token.EOF) {
			parser._ctx.stop = parser.lastConsumed;
		}
		else {
			parser._ctx.stop = parser._input.LT(-1);
		}
		// trigger event on _ctx, before it reverts to parent
		if (parser._parseListeners != null) {
			parser.triggerExitRuleEvent();
		}
		parser.setState(parser._ctx.invokingState);
		parser._ctx = (ParserRuleContext) parser._ctx.parent;
	}

	static ParserRuleContext getInvokingContext(@NotNull Parser parser, int ruleIndex) {
		ParserRuleContext p = parser._ctx;
		while (p != null) {
			if (p.getRuleIndex() == ruleIndex) {
				return p;
			}
			p = (ParserRuleContext) p.parent;
		}
		return null;
	}

	static boolean isExpectedToken(@NotNull Parser parser, int symbol) {
		ATN atn = parser.getInterpreter().atn;
		ParserRuleContext ctx = parser._ctx;
		ATNState s = atn.states.get(parser.getState());
		IntervalSet following = atn.nextTokens(s);
		if (following.contains(symbol)) {
			return true;
		}
		if (!following.contains(Token.EPSILON)) {
			return false;
		}

		while (ctx != null && ctx.invokingState >= 0 && following.contains(Token.EPSILON)) {
			ATNState invokingState = atn.states.get(ctx.invokingState);
			RuleTransition rt = (RuleTransition) invokingState.transition(0);
			following = atn.nextTokens(rt.followState);
			if (following.contains(symbol)) {
				return true;
			}

			ctx = (ParserRuleContext) ctx.parent;
		}

		return following.contains(Token.EPSILON) && symbol == Token.EOF;
	}

	@NotNull
	static IntervalSet getExpectedTokens(@NotNull Parser parser) {
		return parser.getATN().getExpectedTokens(parser.getState(), parser.getContext());
	}

	@NotNull
	static IntervalSet getExpectedTokensWithinCurrentRule(@NotNull Parser parser) {
		ATN atn = parser.getInterpreter().atn;
		ATNState s = atn.states.get(parser.getState());
		return atn.nextTokens(s);
	}

	static int getRuleIndex(@NotNull Parser parser, String ruleName) {
		Integer ruleIndex = parser.getRuleIndexMap().get(ruleName);
		return ruleIndex != null ? ruleIndex : -1;
	}

	static List<String> getRuleInvocationStack(@NotNull Parser parser) {
		// Preserve virtual dispatch for subclasses overriding getRuleInvocationStack(RuleContext).
		return parser.getRuleInvocationStack(parser._ctx);
	}

	static List<String> getRuleInvocationStack(@NotNull Parser parser, RuleContext p) {
		String[] ruleNames = parser.getRuleNames();
		List<String> stack = new ArrayList<String>();
		while (p != null) {
			int ruleIndex = p.getRuleIndex();
			if (ruleIndex < 0) {
				stack.add("n/a");
			}
			else {
				stack.add(ruleNames[ruleIndex]);
			}
			p = p.parent;
		}
		return stack;
	}

	static List<String> getDFAStrings(@NotNull Parser parser) {
		List<String> s = new ArrayList<String>();
		for (int d = 0; d < parser._interp.atn.decisionToDFA.length; d++) {
			DFA dfa = parser._interp.atn.decisionToDFA[d];
			s.add(dfa.toString(parser.getVocabulary(), parser.getRuleNames()));
		}
		return s;
	}

	static void dumpDFA(@NotNull Parser parser) {
		boolean seenOne = false;
		for (int d = 0; d < parser._interp.atn.decisionToDFA.length; d++) {
			DFA dfa = parser._interp.atn.decisionToDFA[d];
			if (!dfa.isEmpty()) {
				if (seenOne) {
					System.out.println();
				}
				System.out.println("Decision " + dfa.decision + ":");
				System.out.print(dfa.toString(parser.getVocabulary(), parser.getRuleNames()));
				seenOne = true;
			}
		}
	}

	@Nullable
	static ParseInfo getParseInfo(@NotNull Parser parser) {
		ParserATNSimulator interp = parser.getInterpreter();
		if (interp instanceof ProfilingATNSimulator) {
			return new ParseInfo((ProfilingATNSimulator) interp);
		}
		return null;
	}

	static void setProfile(@NotNull Parser parser, boolean profile) {
		ParserATNSimulator interp = parser.getInterpreter();
		if (profile) {
			if (!(interp instanceof ProfilingATNSimulator)) {
				parser.setInterpreter(new ProfilingATNSimulator(parser));
			}
		}
		else if (interp instanceof ProfilingATNSimulator) {
			parser.setInterpreter(new ParserATNSimulator(parser, parser.getATN()));
		}
		parser.getInterpreter().setPredictionMode(interp.getPredictionMode());
	}

	static void setTrace(@NotNull Parser parser, boolean trace) {
		if (!trace) {
			parser.removeParseListener(parser._tracer);
			parser._tracer = null;
		}
		else {
			if (parser._tracer != null) {
				parser.removeParseListener(parser._tracer);
			}
			else {
				parser._tracer = parser.new TraceListener();
			}
			parser.addParseListener(parser._tracer);
		}
	}

	static boolean isTrace(@NotNull Parser parser) {
		return parser._tracer != null;
	}
}
