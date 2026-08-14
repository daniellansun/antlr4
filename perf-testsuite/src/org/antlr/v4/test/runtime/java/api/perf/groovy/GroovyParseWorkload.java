/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.runtime.java.api.perf.groovy;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.test.runtime.java.api.GroovyLikeLexer;
import org.antlr.v4.test.runtime.java.api.GroovyLikeParser;
import org.antlr.v4.test.runtime.java.api.perf.jmh.CorpusLoader;
import org.antlr.v4.test.runtime.java.api.perf.jmh.ParseWorkload;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Groovy-like serial/parallel parse helpers. The compact
 * {@code GroovyLike.g4} front end shares {@link ParseWorkload}'s thread pool
 * and two-stage driver; each worker still owns its lexer/parser while DFA
 * lives on the static ATN.
 */
public final class GroovyParseWorkload {

	public static final ParseWorkload.FrontEnd GROOVY_LIKE = new ParseWorkload.FrontEnd() {
		@Override
		public void clearSharedDfa() {
			new GroovyLikeLexer(CharStreams.fromString("")).getInterpreter().clearDFA();
			new GroovyLikeParser(new CommonTokenStream(
				new GroovyLikeLexer(CharStreams.fromString("")))).getInterpreter().clearDFA();
		}

		@Override
		public Lexer newLexer(String text) {
			return new GroovyLikeLexer(CharStreams.fromString(text));
		}

		@Override
		public Parser newParser(CommonTokenStream tokens) {
			return new GroovyLikeParser(tokens);
		}

		@Override
		public void parseStartRule(Parser parser) {
			((GroovyLikeParser) parser).compilationUnit();
		}
	};

	private GroovyParseWorkload() {
	}

	public static void clearSharedDfa() {
		GROOVY_LIKE.clearSharedDfa();
	}

	public static int lexFile(String text, boolean clearDfa) {
		return ParseWorkload.lexFile(GROOVY_LIKE, text, clearDfa);
	}

	public static int parseFile(String text, ParseWorkload.PredictionStrategy strategy,
								boolean clearDfa, boolean buildTree) {
		return ParseWorkload.parseFile(GROOVY_LIKE, text, strategy, clearDfa, buildTree);
	}

	public static ParseWorkload.Stats parseSerial(List<CorpusLoader.SourceFile> files,
												  ParseWorkload.PredictionStrategy strategy,
												  boolean clearDfaPerFile,
												  boolean buildTree) {
		return ParseWorkload.parseSerial(GROOVY_LIKE, files, strategy, clearDfaPerFile, buildTree);
	}

	public static ParseWorkload.Stats parseParallel(List<CorpusLoader.SourceFile> files,
													ParseWorkload.PredictionStrategy strategy,
													boolean clearDfaAtStart,
													boolean buildTree,
													int threads) throws InterruptedException, ExecutionException {
		return ParseWorkload.parseParallel(GROOVY_LIKE, files, strategy, clearDfaAtStart, buildTree, threads);
	}
}
