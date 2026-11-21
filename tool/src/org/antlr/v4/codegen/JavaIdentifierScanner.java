/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen;

/**
 * Single-pass, zero-allocation lexical identifier scanner for Java 8 action
 * and semantic predicate source fragments.
 *
 * <p>Skips {@code // ...} line comments, {@code /* ... *}{@code /} block
 * comments, double-quoted string literals, and single-quoted character
 * literals (honoring backslash escapes inside literals). Any raw backslash
 * encountered outside comments and literals (e.g., a JLS &sect;3.3 Unicode
 * escape such as {@code \u005flocalctx}) causes {@link #containsIdentifier}
 * to return {@code true} conservatively so callers never misclassify an
 * obfuscated identifier.</p>
 *
 * @since 4.13.2.20
 */
public final class JavaIdentifierScanner {

	private JavaIdentifierScanner() {
	}

	/**
	 * Returns {@code true} if {@code src} contains {@code ident} as a whole
	 * Java identifier token outside comments and string/character literals,
	 * or if {@code src} contains a backslash outside comments/literals (JLS
	 * &sect;3.3 conservative guard).
	 */
	public static boolean containsIdentifier(String src, String ident) {
		if (src == null || ident == null || src.isEmpty() || ident.isEmpty()) {
			return false;
		}
		final int len = src.length();
		final int identLen = ident.length();
		int i = 0;
		while (i < len) {
			char c = src.charAt(i);
			if (c == '/') {
				if (i + 1 < len) {
					char next = src.charAt(i + 1);
					if (next == '/') {
						i += 2;
						while (i < len) {
							char ch = src.charAt(i++);
							if (ch == '\\' && i < len && src.charAt(i) == 'u') {
								// JLS §3.3: Unicode escapes (U+000A / U+000D) can terminate a line comment early.
								return true;
							}
							if (ch == '\n' || ch == '\r') {
								break;
							}
						}
						continue;
					}
					if (next == '*') {
						i += 2;
						while (i < len) {
							char ch = src.charAt(i);
							if (ch == '\\' && i + 1 < len && src.charAt(i + 1) == 'u') {
								// JLS §3.3: Unicode escapes (U+002A / U+002F) can terminate a block comment early.
								return true;
							}
							if (ch == '*' && i + 1 < len && src.charAt(i + 1) == '/') {
								i += 2;
								break;
							}
							i++;
						}
						continue;
					}
				}
				i++;
				continue;
			}
			if (c == '"' || c == '\'') {
				if (c == '"' && i + 2 < len && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
					// Java 15+ text block: conservatively return true rather than mis-scanning.
					return true;
				}
				final char quote = c;
				i++;
				while (i < len) {
					char ch = src.charAt(i++);
					if (ch == '\\') {
						if (i < len) {
							if (src.charAt(i) == 'u' && isLiteralDelimiterUnicodeEscape(src, i + 1, len)) {
								return true;
							}
							i++;
						}
					}
					else if (ch == quote) {
						break;
					}
				}
				continue;
			}
			if (c == '\\') {
				// JLS §3.3 Unicode escapes are translated prior to lexical
				// tokenization; return true conservatively so an escaped
				// identifier (e.g., \u005flocalctx) is never missed.
				return true;
			}
			int cp = src.codePointAt(i);
			if (Character.isJavaIdentifierStart(cp)) {
				int start = i;
				i += Character.charCount(cp);
				while (i < len) {
					int part = src.codePointAt(i);
					if (!Character.isJavaIdentifierPart(part)) {
						break;
					}
					i += Character.charCount(part);
				}
				if (i - start == identLen && src.regionMatches(start, ident, 0, identLen)) {
					return true;
				}
				continue;
			}
			i += Character.charCount(cp);
		}
		return false;
	}

	/**
	 * Checks whether a JLS &sect;3.3 Unicode escape inside a string or
	 * character literal decodes to a quote, backslash, or line terminator
	 * ({@code "}, {@code '}, {@code \}, {@code \n}, {@code \r}) or is malformed,
	 * which could close or shift the literal prior to lexical tokenization.
	 */
	private static boolean isLiteralDelimiterUnicodeEscape(String src, int pos, int len) {
		while (pos < len && src.charAt(pos) == 'u') {
			pos++;
		}
		if (pos + 4 > len) {
			return true;
		}
		int code = 0;
		for (int k = 0; k < 4; k++) {
			int d = Character.digit(src.charAt(pos + k), 16);
			if (d < 0) {
				return true;
			}
			code = (code << 4) | d;
		}
		return code == '"' || code == '\'' || code == '\\' || code == '\n' || code == '\r';
	}
}
