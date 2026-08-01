/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.v4.codegen.BlankOutputModelFactory;
import org.antlr.v4.codegen.CodeGenPipeline;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.LexerFactory;
import org.antlr.v4.codegen.OutputModelController;
import org.antlr.v4.codegen.OutputModelWalker;
import org.antlr.v4.codegen.Target;
import org.antlr.v4.codegen.model.CodeBlockForOuterMostAlt;
import org.antlr.v4.codegen.model.OutputModelObject;
import org.antlr.v4.codegen.model.RuleFunction;
import org.antlr.v4.codegen.model.decl.CodeBlock;
import org.antlr.v4.codegen.model.decl.StructDecl;
import org.antlr.v4.codegen.model.chunk.*;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.tool.Alternative;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.RuleAST;

import org.junit.Test;

import static org.junit.Assert.*;

public class TestChunkAndCodegenCoverage {

	static class TestBlankFactory extends BlankOutputModelFactory {
		private OutputModelController controller;
		private Grammar grammar;
		private Target target;

		public TestBlankFactory(Grammar grammar, Target target) {
			this.grammar = grammar;
			this.target = target;
		}

		@Override
		public void setController(OutputModelController controller) { this.controller = controller; }

		@Override
		public OutputModelController getController() { return controller; }

		@Override
		public Target getTarget() { return target; }

		@Override
		public Grammar getGrammar() { return grammar; }

		@Override
		public OutputModelObject getRoot() { return null; }

		@Override
		public RuleFunction getCurrentRuleFunction() { return null; }

		@Override
		public Alternative getCurrentOuterMostAlt() { return null; }

		@Override
		public CodeBlock getCurrentBlock() { return null; }

		@Override
		public CodeBlockForOuterMostAlt getCurrentOuterMostAlternativeBlock() { return null; }

		@Override
		public int getCodeBlockLevel() { return 0; }

		@Override
		public int getTreeLevel() { return 0; }

		@Override
		public org.antlr.v4.codegen.CodeGenerator getGenerator() { return null; }
	}

	@Test
	public void testChunkClasses() throws Exception {
		String lexerGrammarStr = "lexer grammar L;\n A : 'a' ;\n";
		LexerGrammar lg = new LexerGrammar(lexerGrammarStr);
		CodeGenerator gen = new CodeGenerator(lg);

		Grammar g = new Grammar("grammar T;\n r : 'a' ; \n");
		g.atn = new ATN(ATNType.PARSER, 100);
		g.atn.ruleToStartState = new RuleStartState[10];

		TestBlankFactory factory = new TestBlankFactory(g, gen.getTarget());

		RuleAST rAST = new RuleAST(new CommonToken(1, "r"));
		Rule r = new Rule(g, "r", rAST, 0);

		StructDecl ctx = new StructDecl(factory, r);

		NonLocalAttrRef nonLocalAttrRef = new NonLocalAttrRef(ctx, "x", "y", 1);
		assertNotNull(nonLocalAttrRef);

		SetNonLocalAttr setNonLocalAttr = new SetNonLocalAttr(ctx, "x", "y", 1, null);
		assertNotNull(setNonLocalAttr);

		RulePropertyRef_parser parserRef = new RulePropertyRef_parser(ctx, "r");
		assertNotNull(parserRef);

		ThisRulePropertyRef_stop stopRef = new ThisRulePropertyRef_stop(ctx, "r");
		assertNotNull(stopRef);

		TokenPropertyRef_channel channelRef = new TokenPropertyRef_channel(ctx, "x");
		assertNotNull(channelRef);

		TokenPropertyRef_index indexRef = new TokenPropertyRef_index(ctx, "x");
		assertNotNull(indexRef);

		TokenPropertyRef_pos posRef = new TokenPropertyRef_pos(ctx, "x");
		assertNotNull(posRef);
	}

	@Test
	public void testLexerFactoryAndPipeline() throws Exception {
		String lexerGrammarStr = "lexer grammar L;\n A : 'a' ;\n";
		LexerGrammar lg = new LexerGrammar(lexerGrammarStr);
		CodeGenerator gen = new CodeGenerator(lg);
		LexerFactory factory = new LexerFactory(gen);
		assertNotNull(factory);

		CodeGenPipeline pipeline = new CodeGenPipeline(lg);
		assertNotNull(pipeline);

		OutputModelWalker walker = new OutputModelWalker(lg.tool, gen.getTarget().getTemplates());
		assertNotNull(walker);
	}
}
