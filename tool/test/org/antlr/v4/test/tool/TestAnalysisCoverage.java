/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.v4.analysis.LeftFactoringRuleTransformer;
import org.antlr.v4.analysis.LeftRecursionDetector;
import org.antlr.v4.analysis.LeftRecursiveRuleAltInfo;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.ast.AltAST;
import org.junit.Test;

import static org.junit.Assert.*;

public class TestAnalysisCoverage {

	@Test
	public void testLeftFactoringRuleTransformer() throws Exception {
		String grammarStr =
				"grammar T;\n" +
				"a @leftfactor{b} : b 'c' | b 'd' ;\n" +
				"b : 'x' ;\n";
		Grammar g = new Grammar(grammarStr);

		LeftFactoringRuleTransformer transformer = new LeftFactoringRuleTransformer(g.ast, g.rules, g);
		transformer.translateLeftFactoredRules();
		assertNotNull(transformer);
	}

	@Test
	public void testLeftRecursionDetector() throws Exception {
		String grammarStr =
				"grammar T;\n" +
				"a : b ;\n" +
				"b : a ;\n";
		Grammar g = new Grammar(grammarStr);
		g.tool.process(g, false);
		if (g.atn != null) {
			LeftRecursionDetector detector = new LeftRecursionDetector(g, g.atn);
			detector.check();
			assertNotNull(detector.listOfRecursiveCycles);
		}
	}

	@Test
	public void testLeftRecursiveRuleAltInfo() {
		AltAST altAST = new AltAST(new org.antlr.runtime.CommonToken(1, "ALT"));
		LeftRecursiveRuleAltInfo info1 = new LeftRecursiveRuleAltInfo(1, "x");
		assertEquals(1, info1.altNum);
		assertEquals("x", info1.altText);

		LeftRecursiveRuleAltInfo info2 = new LeftRecursiveRuleAltInfo(1, "x", "a", "b", false, altAST);
		assertEquals("a", info2.leftRecursiveRuleRefLabel);
		assertEquals("b", info2.altLabel);
		assertFalse(info2.isListLabel);
		assertEquals(altAST, info2.originalAltAST);
	}
}
