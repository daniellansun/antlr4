/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.v4.codegen.ActionTranslator;
import org.antlr.v4.codegen.CodeGenPipeline;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.OutputModelFactory;
import org.antlr.v4.codegen.ParserFactory;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.ast.ActionAST;

import org.junit.Test;

import static org.junit.Assert.*;

public class TestCodeGenPipelineAndTranslatorCoverage {

	@Test
	public void testCodeGenPipeline() throws Exception {
		String grammarStr = "grammar T;\n a : 'x' ;\n";
		Grammar g = new Grammar(grammarStr);
		g.tool.process(g, false);

		CodeGenPipeline pipeline = new CodeGenPipeline(g);
		assertNotNull(pipeline);
	}

	@Test
	public void testActionTranslator() throws Exception {
		String grammarStr = "grammar T;\n a : x='x' { $x.text; } ;\n";
		Grammar g = new Grammar(grammarStr);
		g.tool.process(g, false);

		ActionAST actionAST = new ActionAST(new CommonToken(1, "{ $x.text; }"));
		actionAST.g = g;

		CodeGenerator gen = new CodeGenerator(g);
		OutputModelFactory factory = new ParserFactory(gen);

		ActionTranslator translator = new ActionTranslator(factory, actionAST);
		assertNotNull(translator);
	}
}
