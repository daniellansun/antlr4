/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.test.tool;

import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.target.JavaTarget;
import org.antlr.v4.misc.CharSupport;
import org.antlr.v4.parse.ScopeParser;
import org.antlr.v4.runtime.misc.Tuple2;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Generated lexer/parser defects found by the tool-module review. */
public class TestToolReviewFixes extends BaseTest {

	@Test
	public void hyphenAfterRangeIsLiteral() {
		String grammar =
			"lexer grammar L;\n" +
			"ID : [a-zA-Z0-9-_]+ ;\n";
		String found = execLexer("L.g4", grammar, "L", "a-Z_9!");
		assertTrue(found, found.startsWith("[@0,0:4='a-Z_9',<1>,1:0]"));
		assertTrue(stderrDuringParse, stderrDuringParse != null && stderrDuringParse.contains("token recognition error at: '!'"));
	}

	@Test
	public void typeCommandOnTheOnlyAltStillEmitsTheNamedType() {
		String grammar =
			"lexer grammar L;\n" +
			"FOO : 'a' -> type(BAR) ;\n" +
			"BAR : 'c' ;\n";
		String found = execLexer("L.g4", grammar, "L", "a");
		assertTrue(found, found.contains("='a',<1>"));
		assertFalse(found, found.contains("='a',<0>"));
	}

	@Test
	public void parserRuleArgumentsFollowALexerRule() {
		String grammar =
			"grammar T;\n" +
			"A : 'a' ;\n" +
			"b[int x] returns [int y] locals [int z] : A ;\n";
		ErrorQueue errors = antlr("T.g4", grammar, false);
		assertEquals("", errors.toString(true));
		assertTrue(rawGenerateAndBuildRecognizer("T.g4", grammar, "TParser", "TLexer"));
	}

	@Test
	public void localsKeepADeclarationAfterATrailingBackslash() throws Exception {
		String grammar =
			"grammar T;\n" +
			"a locals [String s=\"\\\\\", int x] : ID ;\n" +
			"ID : [a-z]+ ;\n";
		ErrorQueue errors = antlr("T.g4", grammar, false);
		assertEquals("", errors.toString(true));
		assertTrue(rawGenerateAndBuildRecognizer("T.g4", grammar, "TParser", "TLexer"));
		String source = new String(org.antlr.v4.runtime.misc.Utils.readFile(tmpdir + "/TParser.java"));
		assertTrue(source, source.contains("int x"));
		assertTrue(source, source.contains("String s"));
	}

	@Test
	public void partialAltLabelIsReported() {
		String grammar =
			"grammar T;\n" +
			"a : A | B #lab ;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n";
		ErrorQueue errors = antlr("T.g4", grammar, false);
		String rendered = errors.toString(true);
		assertTrue(rendered, rendered.contains("error(" + ErrorType.RULE_WITH_TOO_FEW_ALT_LABELS.code + ")"));
	}

	@Test
	public void actionValuedOptionIsNotAnInternalError() {
		String grammar =
			"grammar T;\n" +
			"options { foo={bar}; }\n" +
			"s : 'a' ;\n";
		ErrorQueue errors = antlr("T.g4", grammar, false);
		String rendered = errors.toString(true);
		assertFalse(rendered, rendered.contains("internal error"));
		assertTrue(rendered, rendered.contains("unsupported option"));
	}

	@Test
	public void listLabelOnWildcardCompiles() {
		String grammar =
			"grammar T;\n" +
			"s : ys+=. ;\n" +
			"A : 'a' ;\n";
		assertTrue(rawGenerateAndBuildRecognizer("T.g4", grammar, "TParser", "TLexer"));
	}

	@Test
	public void unicodeEscapesUseTheCodePoint() throws Exception {
		Grammar g = new Grammar("grammar T; s : 'a' ;");
		CodeGenerator gen = new CodeGenerator(g);
		JavaTarget target = (JavaTarget) gen.getTarget();
		assertEquals("\"\\u00C9\"", target.getTargetStringLiteralFromString("\u00C9"));
		String newline = target.getTargetStringLiteralFromANTLRStringLiteral(gen, "'\\u000A'", true);
		assertTrue(newline, newline.contains("\\n"));
		assertFalse(newline, newline.contains("\\u000A"));
		String quote = target.getTargetStringLiteralFromANTLRStringLiteral(gen, "'\\u0022'", true);
		assertTrue(quote, quote.contains("\\\""));
		assertFalse(quote, quote.contains("\\u0022"));
	}

	@Test
	public void codePointPastUnicodeIsRejected() {
		assertEquals(-1, CharSupport.getCharValueFromCharInGrammarLiteral("\\u{110000}"));
		assertEquals(-1, CharSupport.getCharValueFromCharInGrammarLiteral("\\\u00FF"));
		String shown = CharSupport.getANTLRCharLiteralForChar(0x10041);
		assertTrue(shown, shown.contains("\\u{010041}"));
		assertFalse(shown, shown.contains("'A'"));
	}

	@Test
	public void attributeListKeepsEscapedTrailingBackslash() {
		List<Tuple2<String, Integer>> decls = ScopeParser.splitDecls("String s=\"\\\\\", int x", ',');
		assertEquals(2, decls.size());
		assertTrue(decls.get(0).getItem1().contains("String s"));
		assertEquals("int x", decls.get(1).getItem1());
	}

	@Test
	public void circularImportDoesNotOverflow() {
		mkdir(tmpdir);
		writeFile(tmpdir, "A.g4", "grammar A;\nimport B;\na : 'a' ;\n");
		writeFile(tmpdir, "B.g4", "grammar B;\nimport A;\nb : 'b' ;\n");
		org.antlr.v4.Tool tool = newTool(new String[] {"-o", tmpdir, "-lib", tmpdir, tmpdir + "/A.g4"});
		tool.processGrammarsOnCommandLine();
		assertTrue(tool.errMgr.getNumErrors() >= 0);
	}

	@Test
	public void parserOnlyCombinedGrammarRefusesALexerInterpreter() throws Exception {
		try {
			Grammar g = new Grammar("grammar T; s : EOF ;");
			g.createLexerInterpreter(org.antlr.v4.runtime.CharStreams.fromString(""));
			fail("combined grammar without a lexer");
		}
		catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("lexer"));
		}
	}

	@Test
	public void missingOptionValueIsACommandLineError() {
		org.antlr.v4.Tool tool = newTool(new String[] {"-o"});
		assertTrue(tool.errMgr.getNumErrors() > 0);
	}
}
