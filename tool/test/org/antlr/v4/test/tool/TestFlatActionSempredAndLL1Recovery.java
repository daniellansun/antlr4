/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.JavaIdentifierScanner;
import org.antlr.v4.runtime.atn.LL1Analyzer;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.tool.Grammar;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Focused tests for {@link JavaIdentifierScanner}, {@link OutputModelController}'s
 * flat {@code action()}/{@code sempred()} chunk analysis (including
 * {@link SetNonLocalAttr} before {@link SetAttr} ordering and {@code rhsChunks}
 * {@code predIndex} recursion), and {@link org.antlr.v4.analysis.AnalysisPipeline}
 * EBNF exit lookahead recovery via {@link LL1Analyzer.PredicatePolicy}.
 */
public class TestFlatActionSempredAndLL1Recovery extends BaseTest {

	@Test
	public void testJavaIdentifierScannerAllBranchesAndUnicodeGuard() {
		// Null / empty guards
		assertFalse(JavaIdentifierScanner.containsIdentifier(null, "_localctx"));
		assertFalse(JavaIdentifierScanner.containsIdentifier("x", null));
		assertFalse(JavaIdentifierScanner.containsIdentifier("", "_localctx"));
		assertFalse(JavaIdentifierScanner.containsIdentifier("x", ""));

		// Exact identifier vs prefix/suffix non-match
		assertTrue(JavaIdentifierScanner.containsIdentifier("_localctx", "_localctx"));
		assertTrue(JavaIdentifierScanner.containsIdentifier("return _localctx.x;", "_localctx"));
		assertFalse(JavaIdentifierScanner.containsIdentifier("my_localctx + _localctx2", "_localctx"));

		// Comments: // line comments (LF, CR, EOF), /* */ block comments (closed and unclosed), lone '/',
		// plus JLS §3.3 Unicode escapes inside comments returning true conservatively
		assertFalse(JavaIdentifierScanner.containsIdentifier("// _localctx\nreturn true;", "_localctx"));
		assertFalse(JavaIdentifierScanner.containsIdentifier("// _localctx\rreturn true;", "_localctx"));
		assertFalse(JavaIdentifierScanner.containsIdentifier("// _localctx", "_localctx"));
		assertFalse(JavaIdentifierScanner.containsIdentifier("/* _localctx */ return 1 / 2;", "_localctx"));
		assertFalse(JavaIdentifierScanner.containsIdentifier("/* unterminated _localctx", "_localctx"));
		assertFalse(JavaIdentifierScanner.containsIdentifier("a / b", "_localctx"));
		assertFalse(JavaIdentifierScanner.containsIdentifier("a /", "_localctx"));
		assertTrue(JavaIdentifierScanner.containsIdentifier("/* nope */ _localctx", "_localctx"));
		assertTrue(JavaIdentifierScanner.containsIdentifier("// \\u000a _localctx.x > 0", "_localctx"));
		assertTrue(JavaIdentifierScanner.containsIdentifier("/* \\u002a/ _localctx.x > 0 /* */", "_localctx"));

		// String/char literals: normal escapes, benign Unicode escapes, delimiter Unicode escapes, and Java 15+ text blocks
		assertFalse(JavaIdentifierScanner.containsIdentifier("\"_localctx\" + \"escaped \\\" _localctx\"", "_localctx"));
		assertFalse(JavaIdentifierScanner.containsIdentifier("'x' + '\\'' + \"_localctx\"", "_localctx"));
		assertFalse(JavaIdentifierScanner.containsIdentifier("\"\\u4e2d _localctx\"", "_localctx"));
		assertFalse(JavaIdentifierScanner.containsIdentifier("\"unterminated\\", "_localctx"));
		assertTrue(JavaIdentifierScanner.containsIdentifier("\"\\u0022 + _localctx.x + \\u0022\"", "_localctx"));
		assertTrue(JavaIdentifierScanner.containsIdentifier("\"\\uu005c\" _localctx", "_localctx"));
		assertTrue(JavaIdentifierScanner.containsIdentifier("\"\\u00\"", "_localctx"));
		assertTrue(JavaIdentifierScanner.containsIdentifier("\"\\uZZZZ\"", "_localctx"));
		assertTrue(JavaIdentifierScanner.containsIdentifier("\"\"\"\nhello \" world\n\"\"\" + _localctx.x != null", "_localctx"));
		assertTrue(JavaIdentifierScanner.containsIdentifier("\"_localctx\" + _localctx", "_localctx"));

		// JLS §3.3 raw backslash outside comments/literals returns true conservatively
		assertTrue(JavaIdentifierScanner.containsIdentifier("\\u005flocalctx", "_localctx"));
		assertTrue(JavaIdentifierScanner.containsIdentifier("a \\ b", "anything"));

		// Non-ASCII / supplementary code points inside identifiers and punctuation
		assertTrue(JavaIdentifierScanner.containsIdentifier("int \u03b1 = 1; predIndex++;", "predIndex"));
		assertFalse(JavaIdentifierScanner.containsIdentifier("\uD83D\uDE00 + 123", "predIndex"));
	}

	@Test
	public void testFlatActionAndSempredDispatchAndPredIndexFallback() throws Exception {
		// 1. Flat-eligible parser & lexer sempreds/actions emit flat switch(predIndex) & _sempred_ helper
		String flatGrammar =
			"grammar T;\n" +
			"@parser::members { int ruleIndex = 42; }\n" +
			"@lexer::members { int ruleIndex = 99; }\n" +
			"s : p[true] q ;\n" +
			"p[boolean ok] : {$ok}? A {System.out.println(\"P:\" + ruleIndex);} ;\n" +
			"q : {ruleIndex == 42}? B {System.out.println(\"Q:\" + ruleIndex);} ;\n" +
			"A : 'a' { int ruleIndex = 7; System.out.println(\"LA:\" + ruleIndex); } ;\n" +
			"B : { ruleIndex == 99 }? 'b' ;\n" +
			"WS : [ \\t]+ -> skip ;\n";
		assertEquals("LA:7\nP:42\nQ:42\n",
			execParser("T.g4", flatGrammar, "TParser", "TLexer", "s", "a b", false));

		Grammar gFlat = new Grammar(flatGrammar);
		gFlat.tool.process(gFlat, false);
		String parserCode = new CodeGenerator(gFlat).generateParser().render();
		assertTrue("Flat parser sempred must switch directly on predIndex", parserCode.contains("switch (predIndex)"));
		assertTrue("Typed _localctx parser pred must outline _sempred_ helper", parserCode.contains("private boolean _sempred_"));
		String lexCode = new CodeGenerator(gFlat.getImplicitLexer()).generateLexer().render();
		assertTrue("Flat lexer action must switch directly on actionIndex", lexCode.contains("switch (actionIndex)"));
		assertTrue("Flat lexer sempred must switch directly on predIndex", lexCode.contains("switch (predIndex)"));

		// 2. Context-dependent parser predicate referencing `predIndex` (including via SetAttr RHS)
		//    falls back cleanly to classic switch (ruleIndex) rather than outlining without predIndex.
		String predIdxFallbackGrammar =
			"grammar T;\n" +
			"@parser::members { int predIndex = 5; }\n" +
			"s : r[true] ;\n" +
			"r[boolean ok] locals [int v = 0]\n" +
			"  : {$ok && predIndex >= 0}? A {$v = predIndex;} {System.out.println(\"R:\" + $v);} ;\n" +
			"A : 'a' ;\n";
		assertEquals("R:5\n",
			execParser("T.g4", predIdxFallbackGrammar, "TParser", "TLexer", "s", "a", false));
		Grammar gFb = new Grammar(predIdxFallbackGrammar);
		gFb.tool.process(gFb, false);
		String fbCode = new CodeGenerator(gFb).generateParser().render();
		assertTrue(fbCode.contains("switch (ruleIndex)"));
		assertFalse(fbCode.contains("private boolean _sempred_"));
	}

	@Test
	public void testAnalysisPipelineEbnfExitLookaheadRecoveryAndAllStarSoundness() throws Exception {
		// 1. Positive recovery: greedy *, +, ? whose exit hits a callee PrecedencePredicateTransition
		//    or context-dependent PredicateTransition ($ok)
		String recoveryGrammar =
			"grammar T;\n" +
			"s : NL* expr NL+ ctxRule[true] NL? notNlTail EOF ;\n" +
			"expr : expr '+' expr | ID ;\n" +
			"ctxRule[boolean ok] : {$ok}? B ;\n" +
			"notNlTail : ~NL ;\n" +
			"wildRule : NL* w ;\n" +
			"w : {$text != null}? . ;\n" +
			"ID : 'x' ; B : 'b' ; NL : '\\n' ; PLUS : '+' ;\n";
		Grammar g = new Grammar(recoveryGrammar);
		g.tool.process(g, false);
		int recoveredLl1 = 0;
		for (IntervalSet[] look : g.decisionLOOK) {
			if (look != null && look.length == 2 && look[0] != null && look[1] != null
					&& !look[0].contains(LL1Analyzer.HIT_PRED)
					&& !look[1].contains(LL1Analyzer.HIT_PRED)
					&& look[0].and(look[1]).isNil()) {
				recoveredLl1++;
			}
		}
		assertTrue("Expected greedy EBNF loops to recover LL(1), got " + recoveredLl1, recoveredLl1 >= 3);
		assertEquals("", execParser("T.g4", recoveryGrammar, "TParser", "TLexer", "s", "\nx+x\nb\nx", false));

		// 2. Negative ALL(*) soundness check: context-independent callee predicate {false}?
		//    MUST remain opaque (dec0[1] == null)
		String soundnessGrammar =
			"grammar T;\n" +
			"s : A* bad ;\n" +
			"bad : {false}? B {System.out.println(\"ENTERED_BAD\");} ;\n" +
			"A : 'a' ; B : 'b' ;\n";
		Grammar gs = new Grammar(soundnessGrammar);
		gs.tool.process(gs, false);
		IntervalSet[] dec0 = gs.decisionLOOK.get(0);
		assertNull("Context-independent callee predicate must remain opaque", dec0[1]);

		// 3. Rejection cases: non-greedy (*?), depth-0 predicate, overlapping lookahead, RuleStopState
		String rejectGrammar =
			"grammar T;\n" +
			"s : A*? b1 | A* {true}? B | A* overlap | tail ;\n" +
			"b1 : B ;\n" +
			"overlap : A | B ;\n" +
			"tail : A* ;\n" +
			"A : 'a' ; B : 'b' ;\n";
		Grammar gr = new Grammar(rejectGrammar);
		gr.tool.process(gr, false);
		assertNotNull(gr.decisionLOOK);

		// 4. Multi-alt EBNF (*, +, ?), nullable callee RuleStopState pop, NotSet behind callee pred, _localctx in sempred
		String deepGrammar =
			"grammar T;\n" +
			"s : (A | B)* sub[true] (A | B)+ sub[true] (A | B)? notSub[true] EOF ;\n" +
			"s2 : A* (nullableSub[true] B | C) EOF ;\n" +
			"sub[boolean ok] : {$ok}? C ;\n" +
			"notSub[boolean ok] : {$ok}? ~A ;\n" +
			"nullableSub[boolean ok] : {$ok}? | D ;\n" +
			"s4 : A* { _localctx != null }? B ;\n" +
			"A : 'a' ; B : 'b' ; C : 'c' ; D : 'd' ;\n";
		Grammar gd = new Grammar(deepGrammar);
		gd.tool.process(gd, false);
		assertNotNull(new CodeGenerator(gd).generateParser().render());
		assertEquals("", execParser("T.g4", deepGrammar, "TParser", "TLexer", "s", "abcbac", false));
	}
}
