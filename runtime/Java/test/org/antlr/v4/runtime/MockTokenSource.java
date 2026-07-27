/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

/**
 * Simple {@link TokenSource} for unit tests that returns a fixed sequence of
 * tokens and then EOF forever.
 */
final class MockTokenSource implements TokenSource {
	private final Token[] tokens;
	private int i;
	private TokenFactory factory = CommonTokenFactory.DEFAULT;
	private final String sourceName;

	MockTokenSource(Token... tokens) {
		this(null, tokens);
	}

	MockTokenSource(String sourceName, Token... tokens) {
		this.tokens = tokens;
		this.sourceName = sourceName != null ? sourceName : "mock";
	}

	@Override
	public Token nextToken() {
		if (i < tokens.length) {
			return tokens[i++];
		}
		return factory.create(Token.EOF, "EOF");
	}

	@Override
	public int getLine() {
		return 1;
	}

	@Override
	public int getCharPositionInLine() {
		return 0;
	}

	@Override
	public CharStream getInputStream() {
		return null;
	}

	@Override
	public String getSourceName() {
		return sourceName;
	}

	@Override
	public TokenFactory getTokenFactory() {
		return factory;
	}

	@Override
	public void setTokenFactory(TokenFactory factory) {
		this.factory = factory;
	}
}
