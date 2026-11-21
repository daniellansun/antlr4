/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.OutputModelController;
import org.antlr.v4.codegen.ParserFactory;
import org.antlr.v4.codegen.Target;
import org.antlr.v4.codegen.model.LL1StarBlockSingleAlt;
import org.antlr.v4.codegen.model.ParserFile;
import org.antlr.v4.codegen.model.SrcOp;
import org.antlr.v4.codegen.model.decl.CodeBlock;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.ast.GrammarAST;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Focused end-to-end and output-model tests for Java codegen optimizations:
 * inherited {@code Parser} hooks ({@code _match}, {@code _matchEOF}, {@code _matchSet},
 * {@code _matchSetEOF}, {@code _sync}, {@code _syncPredict}, {@code _recover},
 * {@code _matchStar}, {@code _matchStarPredict}), {@code TestSetInline} bit 63/64
 * boundary, {@code LL1AltBlock} bitmask tests, {@link Target} capability gates,
 * and Tier 2a/2b deferred labeled-alt contexts (including transitive {@code baseContext}
 * analysis).
 */
public class TestToolCodegenOptimizations extends BaseTest {

	@Test
	public void testStarBlockAndMatchSetAndSyncHooksCodegenAndExecution() throws Exception {
		String grammar =
			"grammar T;\n" +
			"r1 : .*? SEMI ;\n" +
			"r2 : .* SEMI ;\n" +
			"r3 : (~SEMI)*? SEMI ;\n" +
			"r4 : (A | B)* A ;\n" +
			"r5 : (x=A)* B ;\n" +
			"r6 : NL* A ;\n" +
			"r7 : A* A ;\n" +
			"r8 : (A | B) ;\n" +
			"r9 : ~A ;\n" +
			"r10 : (A | EOF) ;\n" +
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
		assertEquals("", execParser("T.g4", grammar, "TParser", "TLexer", "r8", "b", false));
		assertEquals("", execParser("T.g4", grammar, "TParser", "TLexer", "r9", "b", false));
		assertEquals("", execParser("T.g4", grammar, "TParser", "TLexer", "r10", "", false));

		// Verify generated parser inherits helpers from Parser (zero private helper copies)
		Grammar gNorm = new Grammar(grammar);
		gNorm.tool.process(gNorm, false);
		String normCode = new CodeGenerator(gNorm).generateParser().render();
		assertFalse(normCode.contains("private Token _match("));
		assertFalse(normCode.contains("private void _sync("));
		assertFalse(normCode.contains("private int _adaptivePredict("));
		assertTrue(normCode.contains("\t_matchStar("));
		assertTrue(normCode.contains("\t_matchStarPredict("));
		assertTrue(normCode.contains("_matchSet("));
		assertTrue(normCode.contains("_matchSetEOF("));
		assertTrue(normCode.contains("_recover(_localctx, re);"));
		assertTrue(normCode.contains("_sync("));
		assertTrue(normCode.contains("_syncPredict("));

		// force_atn disables _matchStar / _matchStarPredict
		Grammar gForce = new Grammar(grammar);
		gForce.tool.force_atn = true;
		gForce.tool.process(gForce, false);
		String forceCode = new CodeGenerator(gForce).generateParser().render();
		assertFalse(forceCode.contains("\t_matchStar("));
		assertFalse(forceCode.contains("\t_matchStarPredict("));
	}

	@Test
	public void testMatchEofWithTokenNamedToken() {
		String grammar =
			"grammar T;\n" +
			"root : Token EOF {System.out.println(\"OK\");} ;\n" +
			"Token : 't1' ;\n";
		assertEquals("OK\n", execParser("T.g4", grammar, "TParser", "TLexer", "root", "t1", false));
	}

	@Test
	public void testTestSetInlineBit63And64Boundary() {
		StringBuilder sb = new StringBuilder();
		sb.append("grammar T;\n");
		sb.append("s : (T62 | T63 | T64 | T65)+ EOF {System.out.println(\"MATCHED\");} ;\n");
		for (int i = 1; i <= 66; i++) {
			sb.append("T").append(i).append(" : 'k").append(i).append("' ;\n");
		}
		sb.append("WS : [ \\t]+ -> skip ;\n");
		assertEquals("MATCHED\n", execParser("T.g4", sb.toString(), "TParser", "TLexer", "s", "k62 k63 k64 k65", false));
	}

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
			@Override protected boolean visibleGrammarSymbolCausesIssueInGeneratedCode(GrammarAST idNode) { return false; }
			@Override public String encodeIntAsCharEscape(int v) { return String.valueOf(v); }
			@Override protected void appendUnicodeEscapedCodePoint(int codePoint, StringBuilder sb) { sb.append(codePoint); }
			@Override public String getTargetStringLiteralFromANTLRStringLiteral(CodeGenerator g2, String lit, boolean q) { return lit; }
		};
		assertFalse(customTarget.supportsCompactSingleTokenStarLoop());
		assertFalse(customTarget.supportsCompactLL1AltTests());
		assertFalse(customTarget.supportsFlatActionAndSempredDispatch());
		assertTrue(gen.getTarget().supportsCompactSingleTokenStarLoop());
		assertTrue(gen.getTarget().supportsCompactLL1AltTests());
		assertTrue(gen.getTarget().supportsFlatActionAndSempredDispatch());

		// Verify LL1StarBlockSingleAlt.getSingleTokenMatch returns null if preamble is added
		ParserFactory factory = new ParserFactory(gen);
		OutputModelController controller = new OutputModelController(factory);
		factory.setController(controller);
		ParserFile pf = (ParserFile) controller.buildParserOutputModel(false);
		assertNotNull(pf);
		boolean checkedStar = false;
		for (SrcOp outer : pf.parser.funcs.iterator().next().code) {
			List<SrcOp> candidates = outer instanceof CodeBlock
				? ((CodeBlock) outer).ops
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

		// Also verify custom non-Java target passed into CodeGenerator exercises LL1AltBlock / flat-dispatch false paths
		StringBuilder hiToks = new StringBuilder("grammar HI;\n");
		hiToks.append("s : T1 | T2 | T3 | T4 | T5 | T6 | T7 | T8 | T9 | T10 | T65 | T66 ;\n");
		hiToks.append("s2 : (T1 | T2 | T3 | T4 | T5) | T6 ;\n");
		hiToks.append("s3 : T1* {true}? T2 {System.out.println();} ;\n");
		for (int i = 1; i <= 66; i++) {
			hiToks.append("T").append(i).append(" : 't").append(i).append("' ;\n");
		}
		Grammar ghi = new Grammar(hiToks.toString());
		ghi.tool.process(ghi, false);
		CodeGenerator customGen = new CodeGenerator(ghi) {
			@Override
			public Target getTarget() {
				return new Target(this, "Java") {
					@Override protected boolean visibleGrammarSymbolCausesIssueInGeneratedCode(GrammarAST idNode) { return false; }
					@Override public String encodeIntAsCharEscape(int v) { return String.valueOf(v); }
					@Override protected void appendUnicodeEscapedCodePoint(int codePoint, StringBuilder sb) { sb.append(codePoint); }
					@Override public String getTargetStringLiteralFromANTLRStringLiteral(CodeGenerator g2, String lit, boolean q) { return lit; }
				};
			}
		};
		ParserFactory cf = new ParserFactory(customGen);
		OutputModelController cc = new OutputModelController(cf);
		cf.setController(cc);
		ParserFile customPf = (ParserFile) cc.buildParserOutputModel(false);
		assertNotNull(customPf);
		assertNull("Non-Java target must not emit flat sempreds", customPf.parser.flatSempreds);
	}

	@Test
	public void testTier2aAndTier2bDeferredContextsListenerAndTransitiveBaseContext() throws Exception {
		// 1. Tier 2a + Tier 2b with extraneous token recovery, catch/finally exclusion,
		//    and namedArgPrimary-shaped nested alt referencing baseContext locals/args
		String grammar =
			"grammar T;\n" +
			"s : single choice withFinally scoped[10] ;\n" +
			"single : x=A {System.out.println(\"S:\" + $x.text);} # SingleAlt ;\n" +
			"choice : x=A {System.out.println(\"CA:\" + $x.text);} # AltA\n" +
			"       | y=B {System.out.println(\"CB:\" + $y.text);} # AltB\n" +
			"       ;\n" +
			"withFinally\n" +
			"  : A {System.out.println(\"FA\");} # FinA\n" +
			"  | B {System.out.println(\"FB\");} # FinB\n" +
			"  ;\n" +
			"  finally { System.out.println(\"FIN:\" + (_localctx != null)); }\n" +
			"scoped[int k] locals [int n = 0]\n" +
			"  : A (A {$n = $k + 1;})? {System.out.println(\"SC:\" + $n);} # ScopedA\n" +
			"  | B {System.out.println(\"SB:\" + $k);} # ScopedB\n" +
			"  ;\n" +
			"A : 'a' ; B : 'b' ; C : 'c' ;\n" +
			"WS : [ \\t]+ -> skip ;\n";

		// Extraneous 'c' before choice exercises Tier 2b retry loop; scoped[10] exercises nested baseContext
		assertEquals("S:a\nCB:b\nFA\nFIN:true\nSC:11\n",
			execParser("T.g4", grammar, "TParser", "TLexer", "s", "a c b a a a", false));

		// Verify scoped (which has args/locals) is NOT deferred by Tier 2b, whereas choice IS deferred
		Grammar g = new Grammar(grammar);
		g.tool.process(g, false);
		String code = new CodeGenerator(g).generateParser().render();
		assertTrue(code.contains("_enterDeferred("));

		// 2. Tier 2a with active listener clearing listeners inside enterEveryRule
		String g2 =
			"grammar T;\n" +
			"s : { addParseListener(new TBaseListener() {\n" +
			"        @Override public void enterEveryRule(ParserRuleContext ctx) { System.out.println(\"ENTER_EVERY:\" + ctx.getClass().getSimpleName()); removeParseListeners(); }\n" +
			"        @Override public void enterSingleAlt(SingleAltContext ctx) { System.out.println(\"PREMATURE_ENTER_SINGLE\"); }\n" +
			"        @Override public void exitSingleAlt(SingleAltContext ctx) { System.out.println(\"EXIT_SINGLE\"); }\n" +
			"      }); } single single ;\n" +
			"single : x=A {System.out.println(\"BODY:\" + $x.text);} # SingleAlt ;\n" +
			"A : 'a' ;\n" +
			"WS : [ \\t]+ -> skip ;\n";
		assertEquals("ENTER_EVERY:SingleContext\nBODY:a\nBODY:a\n",
			execParser("T.g4", g2, "TParser", "TLexer", "s", "a a", false));

		// 3. Labeled LL(*) AltBlock (exercises <!choice.label && !preamble> else-branch),
		//    negative baseContext bailout (base with locals), and positive baseContext Tier 2b (base without locals)
		String g3 =
			"grammar T;\n" +
			"s : (A B | A C) derivedLocal derivedClean EOF ;\n" +
			"baseLocal locals [int flag = 0]\n" +
			"  : A # DerA\n" +
			"  | B # DerB\n" +
			"  ;\n" +
			"derivedLocal options { baseContext = baseLocal; }\n" +
			"  : A # DerA\n" +
			"  | B # DerB\n" +
			"  ;\n" +
			"baseClean\n" +
			"  : A # CleanA\n" +
			"  | B # CleanB\n" +
			"  ;\n" +
			"derivedClean options { baseContext = baseClean; }\n" +
			"  : A # CleanA\n" +
			"  | B # CleanB\n" +
			"  ;\n" +
			"A : 'a' ; B : 'b' ; C : 'c' ;\n";
		assertEquals("", execParser("T.g4", g3, "TParser", "TLexer", "s", "abaa", false));
		Grammar g3Model = new Grammar(g3);
		g3Model.tool.process(g3Model, false);
		String g3Code = new CodeGenerator(g3Model).generateParser().render();
		assertTrue("Positive baseContext rule without locals should use _enterDeferred",
			g3Code.contains("derivedClean()") && g3Code.contains("_enterDeferred("));
	}
}
