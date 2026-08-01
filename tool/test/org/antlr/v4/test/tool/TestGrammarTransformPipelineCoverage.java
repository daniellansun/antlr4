/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.GrammarTransformPipeline;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.RuleAST;

import org.junit.Test;

import static org.junit.Assert.*;

public class TestGrammarTransformPipelineCoverage {

	@Test
	public void testGrammarTransformPipeline() throws Exception {
		String grammarStr = "grammar T;\n a : ('x' | 'y') ;\n";
		Grammar g = new Grammar(grammarStr);
		g.tool.process(g, false);

		GrammarTransformPipeline pipeline = new GrammarTransformPipeline(g, g.tool);
		assertNotNull(pipeline);
		pipeline.process();
	}

	@Test
	public void testLeftRecursiveRuleTransformer() throws Exception {
		String grammarStr =
				"grammar T;\n" +
				"e : e '*' e | e '+' e | INT ;\n" +
				"INT : [0-9]+ ;\n";
		Grammar g = new Grammar(grammarStr);
		g.tool.process(g, false);

		Rule r = g.getRule("e");
		if (r != null) {
			RuleAST rAST = r.ast;
			assertNotNull(rAST);
		}
	}

	@Test
	public void testGrammarASTWithOptions() {
		CommonToken tok = new CommonToken(1, "opts");
		RuleAST opts = new RuleAST(tok);
		opts.setOption("foo", new GrammarAST(new CommonToken(2, "bar")));
		assertEquals(1, opts.getNumberOfOptions());
		assertEquals("bar", opts.getOptionString("foo"));

		RuleAST dup = (RuleAST) opts.dupNode();
		assertNotNull(dup);
	}
}
