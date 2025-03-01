/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

/**
 *
 * @author Sam Harwell
 */
public class ConsoleErrorListener implements ANTLRErrorListener<Object> {
	/**
	 * Provides a default instance of {@link ConsoleErrorListener}.
	 */
	public static final ConsoleErrorListener INSTANCE = new ConsoleErrorListener();

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * This implementation prints messages to {@link System#err} containing the
	 * values of {@code line}, {@code charPositionInLine}, and {@code msg} using
	 * the following format.</p>
	 *
	 * <pre>
	 * line <em>line</em>:<em>charPositionInLine</em> <em>msg</em>
	 * </pre>
	 */
	@Override
	public <T> void syntaxError(Recognizer<T, ?> recognizer,
								T offendingSymbol,
								int line,
								int charPositionInLine,
								String msg,
								RecognitionException e)
	{
		System.err.println("line " + line + ":" + charPositionInLine + " " + msg);
	}

}
