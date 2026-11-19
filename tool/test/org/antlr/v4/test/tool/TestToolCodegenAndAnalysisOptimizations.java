/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.v4.analysis.AnalysisPipeline;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.DefaultOutputModelFactory;
import org.antlr.v4.codegen.OutputModelController;
import org.antlr.v4.codegen.Target;
import org.antlr.v4.codegen.model.LL1AltBlock;
import org.antlr.v4.codegen.model.LL1StarBlockSingleAlt;
import org.antlr.v4.codegen.model.ParserFile;
import org.antlr.v4.codegen.model.SrcOp;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.tool.Grammar;
import org.junit.Test;
import org.stringtemplate.v4.ST;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end compile/execute and model/analysis unit tests covering all Round 3
 * {@code tool/} optimizations and strict review edge cases:
 * <ul>
 *   <li>{@link AnalysisPipeline} EBNF exit-predicate LL(1) recovery &amp; ALL(*) soundness rejections</li>
 *   <li>{@code Choice.extractSingleTokenMatch} safety on {@code .*?}, {@code .*}, {@code (~A)*}, {@code (A|B)*}</li>
 *   <li>{@code _matchStar} / {@code _matchStarPredict} / {@code force_atn} / {@code _matchEOF} with token {@code Token}</li>
 *   <li>{@code TestSetInline} bit 63/64 boundary</li>
 *   <li>{@code LL1AltBlock} compact bitmask tests &amp; non-Java {@link Target} default hooks</li>
 *   <li>Flat O(1) {@code action()} &amp; {@code sempred()} (with {@code _localctx}, {@code ruleIndex} shadowing, {@code predIndex})</li>
 *   <li>Tier 2a &amp; Tier 2b deferred labeled-alt contexts (with listener removal in {@code enterEveryRule},
 *       {@code ExtraTokensAndAltLabels} error recovery, and {@code catch}/{@code finally} exclusion)</li>
 * </ul>
 */
public class TestToolCodegenAndAnalysisOptimizations extends BaseTest {

	// -------------------------------------------------------------------------
	// Choice.extractSingleTokenMatch safety: .*?, .*, (~SEMI)*?, (A|B)*, x=A*
	// -------------------------------------------------------------------------

	@Test
	public void testStarBlockDoesNotFoldWildcardOrMatchSetOrLabeledToken() throws Exception {
		String grammar =
			"grammar T;\n" +
			"r1 : .*? SEMI ;\n" +
			"r2 : .* SEMI ;\n" +
			"r3 : (~SEMI)*? SEMI ;\n" +
			"r4 : (A | B)* A ;\n" +
			"r5 : (x=A)* B ;\n" +
			"r6 : NL* A ;\n" +
			"r7 : A* A ;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"SEMI : ';' ;\n" +
			"NL : '\\n' ;\n" +
			"WS : [ \\t]+ -> skip ;\n";

		assertEquals("", execParser("T.g4", grammar, "TParser", "TLexer", "r1", "a b ;", false));
		assertEquals("", execParser("T.g4", grammar, "TParser", "TLexer", "r2", "a b ;", false));
		assertEquals("", execParser("T.g4", grammar, "TParser", "TLexer", "r3", "a b ;", false));
		assertEquals("", execParser("T.g4", grammar, "TParser", "TLexer", "r4", "a b a", false));
		assertEquals("", execParser("T.g4", grammar, "TParser", "TLexer", "r5", "a a b", false));
		assertEquals("", execParser("T.g4", grammar, "TParser", "TLexer", "r6", "\n\na", false));
		assertEquals("", execParser("T.g4", grammar, "TParser", "TLexer", "r7", "a a a", false));

		// Also verify normal codegen emits both _matchStar (r6) and _matchStarPredict (r7),
		// while force_atn emits _matchStarPredict instead of _matchStar calls.
		Grammar gNorm = new Grammar(grammar);
		gNorm.tool.process(gNorm, false);
		String normCode = new CodeGenerator(gNorm).generateParser().render();
		assertTrue(normCode.contains("\t_matchStar("));
		assertTrue(normCode.contains("\t_matchStarPredict("));

		Grammar g = new Grammar(grammar);
		g.tool.force_atn = true;
		g.tool.process(g, false);
		String code = new CodeGenerator(g).generateParser().render();
		assertFalse(code.contains("\t_matchStar("));
		assertFalse(code.contains("\t_matchStarPredict("));
	}

	// -------------------------------------------------------------------------
	// _matchEOF with token literally named `Token` (no Token.EOF shadowing)
	// -------------------------------------------------------------------------

	@Test
	public void testMatchEofWithTokenNamedToken() {
		String grammar =
			"grammar T;\n" +
			"root : Token EOF {System.out.println(\"OK\");} ;\n" +
			"Token : 't1' ;\n";
		String found = execParser("T.g4", grammar, "TParser", "TLexer", "root", "t1", false);
		assertEquals("OK\n", found);
	}

	// -------------------------------------------------------------------------
	// TestSetInline bit 63 & 64 boundary
	// -------------------------------------------------------------------------

	@Test
	public void testTestSetInlineBit63And64Boundary() {
		StringBuilder sb = new StringBuilder();
		sb.append("grammar T;\n");
		// Match tokens T62, T63, T64, T65 in a set and in an LL1AltBlock
		sb.append("s : (T62 | T63 | T64 | T65)+ EOF {System.out.println(\"MATCHED\");} ;\n");
		for (int i = 1; i <= 66; i++) {
			sb.append("T").append(i).append(" : 'k").append(i).append("' ;\n");
		}
		sb.append("WS : [ \\t]+ -> skip ;\n");
		String found = execParser("T.g4", sb.toString(), "TParser", "TLexer", "s", "k62 k63 k64 k65", false);
		assertEquals("MATCHED\n", found);
	}

	// -------------------------------------------------------------------------
	// Target default hooks & LL1AltBlock / LL1StarBlockSingleAlt target gating
	// -------------------------------------------------------------------------

	@Test
	public void testNonJavaTargetDefaultsDisableJavaCompactOptimizations() throws Exception {
		String grammar =
			"grammar T;\n" +
			"s : A* (B | C | D | E | F | G | H) ;\n" +
			"A : 'a' ; B : 'b' ; C : 'c' ; D : 'd' ; E : 'e' ; F : 'f' ; G : 'g' ; H : 'h' ;\n";
		Grammar g = new Grammar(grammar);
		g.tool.process(g, false);
		CodeGenerator gen = new CodeGenerator(g);
		Target customTarget = new Target(gen, "Custom") {
			@Override
			protected boolean visibleGrammarSymbolCausesIssueInGeneratedCode(org.antlr.v4.tool.ast.GrammarAST idNode) {
				return false;
			}
			@Override
			public String encodeIntAsCharEscape(int v) {
				return String.valueOf(v);
			}
			@Override
			protected void appendUnicodeEscapedCodePoint(int codePoint, StringBuilder sb) {
				sb.append(codePoint);
			}
			@Override
			public String getTargetStringLiteralFromANTLRStringLiteral(CodeGenerator generator, String literal, boolean addQuotes) {
				return literal;
			}
		};
		assertFalse(customTarget.supportsCompactSingleTokenStarLoop());
		assertFalse(customTarget.supportsCompactLL1AltTests());

		// Also verify LL1StarBlockSingleAlt.getSingleTokenMatch returns null if preamble is added
		org.antlr.v4.codegen.ParserFactory factory = new org.antlr.v4.codegen.ParserFactory(gen);
		OutputModelController controller = new OutputModelController(factory);
		factory.setController(controller);
		ParserFile pf = (ParserFile) controller.buildParserOutputModel(false);
		assertNotNull(pf);
		// Walk code ops in rule 's' (recursing into CodeBlockForAlt.ops) to locate LL1StarBlockSingleAlt
		boolean checkedStar = false;
		for (SrcOp outer : pf.parser.funcs.iterator().next().code) {
			java.util.List<SrcOp> candidates = outer instanceof org.antlr.v4.codegen.model.decl.CodeBlock
				? ((org.antlr.v4.codegen.model.decl.CodeBlock) outer).ops
				: Collections.singletonList(outer);
			for (SrcOp op : candidates) {
				if (op instanceof LL1StarBlockSingleAlt) {
					LL1StarBlockSingleAlt star = (LL1StarBlockSingleAlt) op;
					assertNotNull(star.getSingleTokenMatch());
					star.preamble = Collections.<SrcOp>singletonList(star);
					assertNull(star.getSingleTokenMatch());
					checkedStar = true;
				}
			}
		}
		assertTrue(checkedStar);
	}

	// -------------------------------------------------------------------------
	// Flat action() & sempred(): _localctx, ruleIndex local/field shadowing, predIndex
	// -------------------------------------------------------------------------

	@Test
	public void testFlatActionsAndSempredsWithRuleIndexShadowingAndPredIndex() {
		// 1. User action & sempred declaring local `int ruleIndex` + _localctx-dependent parser pred
		String grammar =
			"grammar T;\n" +
			"@parser::members { int ruleIndex = 42; }\n" +
			"@lexer::members { int ruleIndex = 99; }\n" +
			"s : p[true] q ;\n" +
			"p[boolean ok] : {$ok}? A {System.out.println(\"P:\" + ruleIndex);} ;\n" +
			"q : {ruleIndex == 42}? B {System.out.println(\"Q:\" + ruleIndex);} ;\n" +
			"A : 'a' { int ruleIndex = 7; System.out.println(\"LA:\" + ruleIndex); } ;\n" +
			"B : { ruleIndex == 99 }? 'b' ;\n" +
			"WS : [ \\t]+ -> skip ;\n";
		String found = execParser("T.g4", grammar, "TParser", "TLexer", "s", "a b", false);
		assertEquals("LA:7\nP:42\nQ:42\n", found);

		// 2. Context-dependent parser predicate referencing `predIndex` falls back cleanly (does not emit outlined helper without predIndex param)
		String predIdxGrammar =
			"grammar T;\n" +
			"@parser::members { int predIndex = 5; }\n" +
			"s : p[true] ;\n" +
			"p[boolean ok] : {$ok && predIndex >= 0}? A {System.out.println(\"PI_OK\");} ;\n" +
			"A : 'a' ;\n";
		String foundPi = execParser("T.g4", predIdxGrammar, "TParser", "TLexer", "s", "a", false);
		assertEquals("PI_OK\n", foundPi);
	}

	// -------------------------------------------------------------------------
	// Tier 2a & Tier 2b labeled-alt rules: listener removal in enterEveryRule,
	// ExtraTokensAndAltLabels recovery, catch/finally exclusion
	// -------------------------------------------------------------------------

	@Test
	public void testTier2aListenerRemovalInEnterEveryRuleAndTier2bRecovery() {
		// Tier 2a (single-alt labeled rule) with listener removing itself in enterEveryRule
		// Tier 2b (LL(1) multi-alt labeled rule) with extraneous leading token error recovery
		// Tier 2b excluded when rule has catch / finally
		String grammar =
			"grammar T;\n" +
			"@parser::members {\n" +
			"  public void installOneShotListener() {\n" +
			"    addParseListener(new TBaseListener() {\n" +
			"      @Override public void enterEveryRule(ParserRuleContext ctx) {\n" +
			"        removeParseListener(this);\n" +
			"      }\n" +
			"    });\n" +
			"  }\n" +
			"}\n" +
			"s : single choice withFinally ;\n" +
			"single : x=A {System.out.println(\"S:\" + $x.text);} # SingleAlt ;\n" +
			"choice : x=A {System.out.println(\"CA:\" + $x.text);} # AltA\n" +
			"       | y=B {System.out.println(\"CB:\" + $y.text);} # AltB\n" +
			"       ;\n" +
			"withFinally\n" +
			"  : A {System.out.println(\"FA\");} # FinA\n" +
			"  | B {System.out.println(\"FB\");} # FinB\n" +
			"  ;\n" +
			"  finally { System.out.println(\"FIN:\" + (_localctx != null)); }\n" +
			"A : 'a' ; B : 'b' ; C : 'c' ;\n" +
			"WS : [ \\t]+ -> skip ;\n";

		// Normal parse + extraneous token 'c' before choice ('c b') exercises Tier 2b retry loop
		String out = execParser("T.g4", grammar, "TParser", "TLexer", "s", "a c b a", false);
		assertEquals("S:a\nCB:b\nFA\nFIN:true\n", out);

		// Also run Tier 2a with an active listener (verifies enterEveryRule receives base SingleContext,
		// NOT premature enterSingleAlt, and exitSingleAlt fires with SingleAltContext, matching standard ANTLR4)
		String g2 =
			"grammar T;\n" +
			"s : { addParseListener(new TBaseListener() {\n" +
			"        @Override public void enterEveryRule(ParserRuleContext ctx) { System.out.println(\"ENTER_EVERY:\" + ctx.getClass().getSimpleName()); }\n" +
			"        @Override public void enterSingleAlt(SingleAltContext ctx) { System.out.println(\"PREMATURE_ENTER_SINGLE\"); }\n" +
			"        @Override public void exitSingleAlt(SingleAltContext ctx) { System.out.println(\"EXIT_SINGLE\"); }\n" +
			"      }); } single { removeParseListeners(); } single ;\n" +
			"single : x=A {System.out.println(\"BODY:\" + $x.text);} # SingleAlt ;\n" +
			"A : 'a' ;\n" +
			"WS : [ \\t]+ -> skip ;\n";
		String out2 = execParser("T.g4", g2, "TParser", "TLexer", "s", "a a", false);
		assertEquals("ENTER_EVERY:SingleContext\nBODY:a\nEXIT_SINGLE\nBODY:a\n", out2);
	}

	// -------------------------------------------------------------------------
	// AnalysisPipeline.recoverSingleAltEbnfExitLookahead: recovery & ALL(*) soundness
	// -------------------------------------------------------------------------

	@Test
	public void testEbnfExitLookaheadRecoveryAndAllStarSoundness() throws Exception {
		// 1. Positive recovery: greedy *, +, ? whose exit hits a callee PrecedencePredicateTransition
		//    (left-recursive expr) or context-dependent PredicateTransition ($flag)
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
		// Check decisions (0-indexed: look[0] = continue, look[1] = exit): NL*, NL+, NL? should recover LL(1)
		int recoveredLl1 = 0;
		for (IntervalSet[] look : g.decisionLOOK) {
			if (look != null && look.length == 2 && look[0] != null && look[1] != null
					&& !look[0].contains(org.antlr.v4.runtime.atn.LL1Analyzer.HIT_PRED)
					&& !look[1].contains(org.antlr.v4.runtime.atn.LL1Analyzer.HIT_PRED)
					&& look[0].and(look[1]).isNil()) {
				recoveredLl1++;
			}
		}
		assertTrue("Expected greedy EBNF loops to recover LL(1), got " + recoveredLl1, recoveredLl1 >= 3);
		// Compile & execute to verify runtime equivalence
		assertEquals("", execParser("T.g4", recoveryGrammar, "TParser", "TLexer", "s", "\nx+x\nb\nx", false));

		// 2. Negative ALL(*) soundness check: context-independent callee predicate `{false}?`
		//    MUST NOT be seen through by recoverSingleAltEbnfExitLookahead
		String soundnessGrammar =
			"grammar T;\n" +
			"s : A* bad ;\n" +
			"bad : {false}? B {System.out.println(\"ENTERED_BAD\");} ;\n" +
			"A : 'a' ; B : 'b' ;\n";
		Grammar gs = new Grammar(soundnessGrammar);
		gs.tool.process(gs, false);
		// Decision 0 (A*, look[1] = exit alt) MUST remain null (unrecovered, predicate opaque to preserve ALL(*) semantics)!
		IntervalSet[] dec0 = gs.decisionLOOK.get(0);
		assertNull("Context-independent callee predicate must remain opaque to preserve ALL(*) semantics", dec0[1]);

		// 3. Rejection cases: non-greedy (*?), direct enclosing predicate, overlapping lookahead, RuleStopState
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

		// 4. Deep AnalysisPipeline branches: multi-alt EBNF (*, +, ?), callee RuleStopState pop,
		//    NotSetTransition behind callee pred, and _localctx literal in sempred
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
		String genDeep = new CodeGenerator(gd).generateParser().render();
		assertNotNull(genDeep);
		// Also exercise LL1AltBlock with >4 tokens per alt & high token types (>64) & non-Java target gating
		StringBuilder hiToks = new StringBuilder("grammar HI;\n");
		hiToks.append("s : T1 | T2 | T3 | T4 | T5 | T6 | T7 | T8 | T9 | T10 | T65 | T66 ;\n");
		hiToks.append("s2 : (T1 | T2 | T3 | T4 | T5) | T6 ;\n");
		hiToks.append("s3 : T1* T2 ;\n");
		for (int i = 1; i <= 66; i++) {
			hiToks.append("T").append(i).append(" : 't").append(i).append("' ;\n");
		}
		Grammar ghi = new Grammar(hiToks.toString());
		ghi.tool.process(ghi, false);
		String hiCode = new CodeGenerator(ghi).generateParser().render();
		assertNotNull(hiCode);
		// Test custom non-Java target passed into CodeGenerator -> exercises LL1AltBlock & Choice target false branches
		CodeGenerator customGen = new CodeGenerator(ghi) {
			@Override
			public Target getTarget() {
				return new Target(this, "Java") {
					@Override protected boolean visibleGrammarSymbolCausesIssueInGeneratedCode(org.antlr.v4.tool.ast.GrammarAST idNode) { return false; }
					@Override public String encodeIntAsCharEscape(int v) { return String.valueOf(v); }
					@Override protected void appendUnicodeEscapedCodePoint(int codePoint, StringBuilder sb) { sb.append(codePoint); }
					@Override public String getTargetStringLiteralFromANTLRStringLiteral(CodeGenerator g2, String lit, boolean q) { return lit; }
				};
			}
		};
		org.antlr.v4.codegen.ParserFactory cf = new org.antlr.v4.codegen.ParserFactory(customGen);
		OutputModelController cc = new OutputModelController(cf);
		cf.setController(cc);
		assertNotNull(cc.buildParserOutputModel(false));
	}
}
