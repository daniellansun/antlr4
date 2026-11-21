/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime;

import org.antlr.v4.runtime.misc.NotNull;

/**
 * This class extends {@link BufferedTokenStream} with functionality to filter
 * token streams to tokens on a particular channel (tokens where
 * {@link Token#getChannel} returns a particular value).
 *
 * <p>
 * This token stream provides access to all tokens by index or when calling
 * methods like {@link #getText}. The channel filtering is only used for code
 * accessing tokens via the lookahead methods {@link #LA}, {@link #LT}, and
 * {@link #LB}.</p>
 *
 * <p>
 * By default, tokens are placed on the default channel
 * ({@link Token#DEFAULT_CHANNEL}), but may be reassigned by using the
 * {@code ->channel(HIDDEN)} lexer command, or by using an embedded action to
 * call {@link Lexer#setChannel}.
 * </p>
 *
 * <p>
 * Note: lexer rules which use the {@code ->skip} lexer command or call
 * {@link Lexer#skip} do not produce tokens at all, so input text matched by
 * such a rule will not be available as part of the token stream, regardless of
 * channel.</p>
 */
public class CommonTokenStream extends BufferedTokenStream {
	/**
	 * Specifies the channel to use for filtering tokens.
	 *
	 * <p>
	 * The default value is {@link Token#DEFAULT_CHANNEL}, which matches the
	 * default channel assigned to tokens created by the lexer.</p>
	 */
    protected int channel = Token.DEFAULT_CHANNEL;

	/**
	 * Constructs a new {@link CommonTokenStream} using the specified token
	 * source and the default token channel ({@link Token#DEFAULT_CHANNEL}).
	 *
	 * @param tokenSource The token source.
	 */
    public CommonTokenStream(@NotNull TokenSource tokenSource) {
        super(tokenSource);
    }

	/**
	 * Constructs a new {@link CommonTokenStream} using the specified token
	 * source and filtering tokens to the specified channel. Only tokens whose
	 * {@link Token#getChannel} matches {@code channel} or have the
	 * {@link Token#getType} equal to {@link Token#EOF} will be returned by the
	 * token stream lookahead methods.
	 *
	 * @param tokenSource The token source.
	 * @param channel The channel to use for filtering tokens.
	 */
    public CommonTokenStream(@NotNull TokenSource tokenSource, int channel) {
        this(tokenSource);
        this.channel = channel;
    }

	@Override
	public void seek(int index) {
		if (getClass() == CommonTokenStream.class && tokens == fastTokens && p >= 0 && index >= 0 && index < fastTokens.size) {
			if (index == p) {
				if (cachedLT1 == null) {
					cachedLT1 = fastTokens.getFast(p);
				}
				return;
			}
			Token t = fastTokens.getFast(index);
			if (t.getChannel() == channel || t.getType() == EOF) {
				p = index;
				cachedLT1 = t;
				cachedLTMinus1 = null;
				return;
			}
		}
		super.seek(index);
	}

	@Override
	public void consume() {
		if (getClass() == CommonTokenStream.class && tokens == fastTokens) {
			Token current = cachedLT1;
			if (current != null) {
				if (current.getType() == EOF) {
					throw new IllegalStateException("cannot consume EOF");
				}
				final TokenList list = fastTokens;
				final int next = p + 1;
				final int sz = list.size;
				if (next < sz) {
					Token t = list.getFast(next);
					if (t.getChannel() == channel || t.getType() == EOF) {
						p = next;
						cachedLTMinus1 = current;
						cachedLT1 = t;
						return;
					}
				}
				else if (next == sz && !fetchedEOF) {
					Token t = fetchNextOnChannel(p, list, sz);
					p = list.size - 1;
					cachedLTMinus1 = current;
					cachedLT1 = t;
					return;
				}
			}
		}
		super.consume();
	}

	@Override
	protected int adjustSeekIndex(int i) {
		return nextTokenOnChannel(i, channel);
	}

    @Override
    protected Token LB(int k) {
        if ( k==0 || (p-k)<0 ) return null;

        int i = p;
        int n = 1;
        // find k good tokens looking backwards
        while ( n<=k && i>0 ) {
            // skip off-channel tokens
            i = previousTokenOnChannel(i - 1, channel);
            n++;
        }
        if ( i<0 ) return null;
        return tokenAt(i);
    }

    @Override
    public Token LT(int k) {
		// PERF: Keep LT(1) <= 35B (HotSpot MaxInlineSize) without chaining
		// through super.LT(1), and split the rare k==1 cache-miss path
		// (ltOneSlow) from k!=1 (ltCommonSlow) so constant LT(1) call sites
		// dead-code-eliminate ltCommonSlow and never inline 160B+ of slow IR.
		if (k == 1) {
			Token t = cachedLT1;
			if (t != null) {
				return t;
			}
			return ltOneSlow();
		}
		return ltCommonSlow(k);
	}

	private Token ltCommonSlow(int k) {
		if (getClass() == CommonTokenStream.class) {
			if (k == -1 && cachedLTMinus1 != null) {
				return cachedLTMinus1;
			}
			if (k > 1 && p >= 0 && tokens == fastTokens && p < fastTokens.size) {
				return ltForwardFast(k);
			}
		}
        //System.out.println("enter LT("+k+")");
        lazyInit();
        if ( k == 0 ) return null;
        if ( k < 0 ) return LB(-k);
        int i = p;
        int n = 1; // we know tokens[p] is a good one
        // find k good tokens
        while ( n<k ) {
            // skip off-channel tokens, but make sure to not look past EOF
			if (sync(i + 1)) {
				i = nextTokenOnChannel(i + 1, channel);
			}
            n++;
        }
//		if ( i>range ) range = i;
        return tokenAt(i);
    }

	/**
	 * Returns the first token at raw buffer index {@code > afterIndex} on
	 * {@link #channel} (or {@link Token#EOF}) without mutating the stream
	 * cursor ({@link #p}), {@link #cachedLT1}, or {@link #cachedLTMinus1}.
	 *
	 * <p>Passing {@code afterIndex == -1} returns the first on-channel (or EOF)
	 * token. Passing an index at or past EOF (including
	 * {@link Integer#MAX_VALUE}) returns the EOF token.</p>
	 *
	 * @param afterIndex raw token buffer index ({@code >= -1})
	 * @return the next on-channel or EOF token after {@code afterIndex}
	 * @throws IllegalArgumentException if {@code afterIndex < -1}
	 * @since 4.13.2.20
	 */
	@NotNull
	public Token nextOnChannel(int afterIndex) {
		if (getClass() == CommonTokenStream.class && tokens == fastTokens) {
			final TokenList list = fastTokens;
			final int next = afterIndex + 1;
			if (next >= 0) {
				final int sz = list.size;
				if (next < sz) {
					Token t = list.getFast(next);
					if (t.getChannel() == channel || t.getType() == EOF) {
						return t;
					}
				}
				else if (next == sz && !fetchedEOF) {
					return fetchNextOnChannel(afterIndex, list, sz);
				}
			}
		}
		return nextOnChannelSlow(afterIndex);
	}

	/**
	 * Fast frontier fetch for base {@link CommonTokenStream#nextOnChannel(int)}
	 * when {@code afterIndex + 1 >= fastTokens.size} and EOF has not yet been
	 * reached. Pulls directly from {@link #tokenSource} without redundant
	 * validation or buffered-prefix scans.
	 */
	@NotNull
	private Token fetchNextOnChannel(int afterIndex, @NotNull TokenList list, int sz) {
		final int ch = this.channel;
		final TokenSource src = tokenSource;
		while (true) {
			Token t = src.nextToken();
			int type;
			int tokCh;
			if (t.getClass() == CommonToken.class) {
				CommonToken ct = (CommonToken) t;
				ct.index = sz;
				type = ct.type;
				tokCh = ct.channel;
			}
			else {
				if (t instanceof WritableToken) {
					((WritableToken) t).setTokenIndex(sz);
				}
				type = t.getType();
				tokCh = t.getChannel();
			}
			list.appendFast(t);
			int idx = sz++;
			if (type == EOF) {
				fetchedEOF = true;
				return t;
			}
			if (idx > afterIndex && tokCh == ch) {
				return t;
			}
		}
	}

	@NotNull
	private Token nextOnChannelSlow(int afterIndex) {
		if (afterIndex < -1) {
			throw new IllegalArgumentException("afterIndex must be >= -1: " + afterIndex);
		}
		if (afterIndex == Integer.MAX_VALUE) {
			while (!fetchedEOF && fetch(1000) > 0) {
			}
			return tokenAt(tokenCount() - 1);
		}
		final int next = afterIndex + 1;
		if (getClass() == CommonTokenStream.class && tokens == fastTokens) {
			final TokenList list = fastTokens;
			final int sz = list.size;
			final int ch = this.channel;
			int j = next;
			while (j < sz) {
				Token t = list.getFast(j);
				if (t.getChannel() == ch || t.getType() == EOF) {
					return t;
				}
				j++;
			}
			if (fetchedEOF) {
				return list.getFast(sz - 1);
			}
			return fetchNextOnChannel(afterIndex, list, sz);
		}
		// Subclass or custom backing list: nextTokenOnChannel calls sync(...)
		// directly without invoking lazyInit(), preserving p / cachedLT1 / cachedLTMinus1.
		int idx = nextTokenOnChannel(next, channel);
		return tokenAt(idx);
	}

	/**
	 * Fast forward lookahead ({@code k > 1}) for base {@link CommonTokenStream}:
	 * walks already-buffered tokens in {@link #fastTokens} directly without
	 * {@code lazyInit}/{@code sync}/{@code nextTokenOnChannel} frames, and fuses
	 * unbuffered frontier fetches directly from {@link #tokenSource}.
	 */
	private Token ltForwardFast(int k) {
		final TokenList list = fastTokens;
		final int ch = this.channel;
		int sz = list.size;
		int i = p;
		int n = 1;
		while (n < k) {
			int j = i + 1;
			while (j < sz) {
				Token t = list.getFast(j);
				int type = t.getType();
				if (type == EOF) {
					return t;
				}
				if (t.getChannel() == ch) {
					i = j;
					n++;
					if (n == k) {
						return t;
					}
					break;
				}
				j++;
			}
			if (j >= sz) {
				if (fetchedEOF) {
					return list.getFast(i);
				}
				final TokenSource src = tokenSource;
				while (n < k) {
					Token t = src.nextToken();
					int type;
					int tokCh;
					if (t.getClass() == CommonToken.class) {
						CommonToken ct = (CommonToken) t;
						ct.index = sz;
						type = ct.type;
						tokCh = ct.channel;
					}
					else {
						if (t instanceof WritableToken) {
							((WritableToken) t).setTokenIndex(sz);
						}
						type = t.getType();
						tokCh = t.getChannel();
					}
					list.appendFast(t);
					sz++;
					if (type == EOF) {
						fetchedEOF = true;
						return t;
					}
					if (tokCh == ch) {
						i = sz - 1;
						n++;
						if (n == k) {
							return t;
						}
					}
				}
			}
		}
		return list.getFast(i);
	}

	/** Count EOF just once. */
	public int getNumberOfOnChannelTokens() {
		int n = 0;
		fill();
		for (Token t : tokens) {
			if (t.getChannel() == channel) n++;
			if (t.getType() == Token.EOF) break;
		}
		return n;
	}
}
