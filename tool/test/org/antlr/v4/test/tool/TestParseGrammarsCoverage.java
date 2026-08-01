/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.antlr.v4.automata.ParserATNFactory;
import org.antlr.v4.parse.ATNBuilder;
import org.antlr.v4.parse.BlockSetTransformer;
import org.antlr.v4.parse.GrammarTreeVisitor;
import org.antlr.v4.tool.Grammar;

import org.junit.Test;

import static org.junit.Assert.*;

public class TestParseGrammarsCoverage {

	@Test
	public void testBlockSetTransformerAndATNBuilder() throws Exception {
		String grammarStr = "grammar T;\n a : 'x' | 'y' ;\n";
		Grammar g = new Grammar(grammarStr);
		g.tool.process(g, false);

		if (g.ast != null) {
			CommonTreeNodeStream nodes = new CommonTreeNodeStream(g.ast);
			BlockSetTransformer setTransformer = new BlockSetTransformer(nodes, g);
			assertNotNull(setTransformer);

			ParserATNFactory factory = new ParserATNFactory(g);
			ATNBuilder builder = new ATNBuilder(nodes, factory);
			assertNotNull(builder);

			GrammarTreeVisitor visitor = new GrammarTreeVisitor(nodes);
			assertNotNull(visitor);
		}
	}
}
