/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen.target;

import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.Target;
import org.antlr.v4.codegen.UnicodeEscapes;
import org.antlr.v4.misc.CharSupport;
import org.antlr.v4.tool.ast.GrammarAST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.StringRenderer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 *
 * @author Sam Harwell
 */
public class JavaTarget extends Target {

	/**
	 * The Java target can cache the code generation templates.
	 */
	private static final ThreadLocal<STGroup> targetTemplates = new ThreadLocal<STGroup>();

	protected static final String[] javaKeywords = {
		"abstract", "assert", "boolean", "break", "byte", "case", "catch",
		"char", "class", "const", "continue", "default", "do", "double", "else",
		"enum", "extends", "false", "final", "finally", "float", "for", "goto",
		"if", "implements", "import", "instanceof", "int", "interface",
		"long", "native", "new", "null", "package", "private", "protected",
		"public", "return", "short", "static", "strictfp", "super", "switch",
		"synchronized", "this", "throw", "throws", "transient", "true", "try",
		"void", "volatile", "while"
	};

	/** Avoid grammar symbols in this set to prevent conflicts in gen'd code. */
	protected final Set<String> badWords = new HashSet<String>();

	public JavaTarget(CodeGenerator gen) {
		super(gen, "Java");
	}

	public Set<String> getBadWords() {
		if (badWords.isEmpty()) {
			addBadWords();
		}

		return badWords;
	}

	protected void addBadWords() {
		badWords.addAll(Arrays.asList(javaKeywords));
		badWords.add("rule");
		badWords.add("parserRule");
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * For Java, this is the translation {@code 'a\n"'} &rarr; {@code "a\n\""}.
	 * Expect single quotes around the incoming literal. Just flip the quotes
	 * and replace double quotes with {@code \"}.
	 * </p>
	 * <p>
	 * Note that we have decided to allow people to use '\"' without penalty, so
	 * we must build the target string in a loop as {@link String#replace}
	 * cannot handle both {@code \"} and {@code "} without a lot of messing
	 * around.
	 * </p>
	 */
	@Override
	public String getTargetStringLiteralFromANTLRStringLiteral(
		CodeGenerator generator,
		String literal, boolean addQuotes)
	{
		StringBuilder sb = new StringBuilder();
		String is = literal;

		if ( addQuotes ) sb.append('"');

		for (int i = 1; i < is.length() -1; ) {
			int codePoint = is.codePointAt(i);
			int toAdvance = Character.charCount(codePoint);
			if  (codePoint == '\\') {
				// Anything escaped is what it is! We assume that
				// people know how to escape characters correctly. However
				// we catch anything that does not need an escape in Java (which
				// is what the default implementation is dealing with and remove
				// the escape. The C target does this for instance.
				//
				int escapedCodePoint = is.codePointAt(i+toAdvance);
				toAdvance++;
				switch (escapedCodePoint) {
					// Pass through any escapes that Java also needs
					//
					case    'n':
					case    'r':
					case    't':
					case    'b':
					case    'f':
					case    '\\':
						// Pass the escape through
						sb.append('\\');
						sb.appendCodePoint(escapedCodePoint);
						break;

					case    'u':    // Either unnnn or u{nnnnnn}
						int closer = is.length() - 1;
						boolean braced = i + toAdvance < closer && is.charAt(i + toAdvance) == '{';
						if ( braced ) {
							while ( i + toAdvance < closer && is.charAt(i + toAdvance) != '}' ) {
								toAdvance++;
							}
							if ( i + toAdvance < closer && is.charAt(i + toAdvance) == '}' ) {
								toAdvance++;
							}
						}
						else {
							int hex = 0;
							while ( hex < 4 && i + toAdvance < closer && isHexDigit(is.charAt(i + toAdvance)) ) {
								toAdvance++;
								hex++;
							}
						}
						if ( i + toAdvance <= closer ) {
							String fullEscape = is.substring(i, i + toAdvance);
							int decoded = CharSupport.getCharValueFromCharInGrammarLiteral(fullEscape);
							if ( decoded < 0 || decoded > Character.MAX_CODE_POINT ) {
								// Keep the grammar text. Each character is encoded
								// so a later quote or unicode escape stays data.
								appendJavaStringLiteralText(fullEscape, sb);
							}
							else {
								// javac rewrites U+000A, U+000D, U+0022, and U+005C
								// before it tokenizes the string, so those code
								// points must use the short escapes.
								appendJavaStringCodePoint(decoded, sb);
							}
						}
						break;
					default:
						if (shouldUseUnicodeEscapeForCodePointInDoubleQuotedString(escapedCodePoint)) {
							appendUnicodeEscapedCodePoint(escapedCodePoint, sb);
						} else {
							sb.appendCodePoint(escapedCodePoint);
						}
						break;
				}
			} else {
				if (codePoint == 0x22) {
					// ANTLR doesn't escape " in literal strings,
					// but every other language needs to do so.
					sb.append("\\\"");
				} else if (shouldUseUnicodeEscapeForCodePointInDoubleQuotedString(codePoint)) {
					appendUnicodeEscapedCodePoint(codePoint, sb);
				} else {
					sb.appendCodePoint(codePoint);
				}
			}
			i += toAdvance;
		}

		if ( addQuotes ) sb.append('"');

		return sb.toString();
	}

	@Override
	public String encodeIntAsCharEscape(int v) {
		if (v < Character.MIN_VALUE || v > Character.MAX_VALUE) {
			throw new IllegalArgumentException(String.format("Cannot encode the specified value: %d", v));
		}

		if (v >= 0 && v < targetCharValueEscape.length && targetCharValueEscape[v] != null) {
			return targetCharValueEscape[v];
		}

		if (v >= 0x20 && v < 127 && (!Character.isDigit(v) || v == '8' || v == '9')) {
			return String.valueOf((char)v);
		}

		if ( v>=0 && v<=127 ) {
			String oct = Integer.toOctalString(v);
			return "\\"+ oct;
		}

		String hex = Integer.toHexString(v|0x10000).substring(1,5);
		return "\\u"+hex;
	}

	@Override
	public int getSerializedATNSegmentLimit() {
		// 65535 is the class file format byte limit for a UTF-8 encoded string literal
		// 3 is the maximum number of bytes it takes to encode a value in the range 0-0xFFFF
		return 65535 / 3;
	}

	@Override
	protected boolean visibleGrammarSymbolCausesIssueInGeneratedCode(GrammarAST idNode) {
		return getBadWords().contains(idNode.getText());
	}

	@Override
	protected STGroup loadTemplates() {
		STGroup result = targetTemplates.get();
		if (result == null) {
			result = super.loadTemplates();
			result.registerRenderer(String.class, new JavaStringRenderer(), true);
			targetTemplates.set(result);
		}

		return result;
	}

	protected static class JavaStringRenderer extends StringRenderer {

		@Override
		public String toString(Object o, String formatString, Locale locale) {
			if ("java-escape".equals(formatString)) {
				// 5C is the hex code for the \ itself
				return ((String)o).replace("\\u", "\\u005Cu");
			}

			return super.toString(o, formatString, locale);
		}

	}

	/**
	 * Append grammar text that is not a Unicode scalar. Backslash and quote
	 * are escaped so javac does not treat a later quote or unicode escape as syntax.
	 */
	private void appendJavaStringLiteralText(String text, StringBuilder sb) {
		for (int k = 0; k < text.length(); ) {
			int cp = text.codePointAt(k);
			appendJavaStringCodePoint(cp, sb);
			k += Character.charCount(cp);
		}
	}

	private static boolean isHexDigit(char c) {
		return (c >= '0' && c <= '9')
			|| (c >= 'a' && c <= 'f')
			|| (c >= 'A' && c <= 'F');
	}

	/**
	 * Append one code point inside a Java double-quoted literal.
	 * Code points that javac treats as raw characters even inside a {@code \\u}
	 * escape use a short escape instead.
	 *
	 * @param codePoint a Unicode scalar value, {@code 0..Character.MAX_CODE_POINT}
	 * @throws IllegalArgumentException when {@code codePoint} is not a scalar.
	 *   Callers preserve an undecodable grammar escape as source text instead.
	 */
	private void appendJavaStringCodePoint(int codePoint, StringBuilder sb) {
		if (codePoint < 0 || codePoint > Character.MAX_CODE_POINT) {
			throw new IllegalArgumentException("invalid Java string code point " + codePoint);
		}
		switch (codePoint) {
			case '\n':
				sb.append("\\n");
				return;
			case '\r':
				sb.append("\\r");
				return;
			case '"':
				sb.append("\\\"");
				return;
			case '\\':
				sb.append("\\\\");
				return;
			default:
				break;
		}
		if (shouldUseUnicodeEscapeForCodePointInDoubleQuotedString(codePoint)) {
			appendUnicodeEscapedCodePoint(codePoint, sb);
		}
		else {
			sb.appendCodePoint(codePoint);
		}
	}

	@Override
	protected void appendUnicodeEscapedCodePoint(int codePoint, StringBuilder sb) {
		UnicodeEscapes.appendJavaStyleEscapedCodePoint(codePoint, sb);
	}
}
