/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.antlr.v4.codegen.BlankOutputModelFactory;
import org.antlr.v4.codegen.CodeGeneratorExtension;
import org.antlr.v4.codegen.OutputModelController;
import org.antlr.v4.codegen.Target;
import org.antlr.v4.codegen.model.*;
import org.antlr.v4.codegen.model.decl.CodeBlock;
import org.antlr.v4.codegen.model.decl.Decl;
import org.antlr.v4.codegen.model.decl.ElementListDecl;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.tool.Alternative;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.ActionAST;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.RuleAST;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class TestCodegenModelCoverage {

	static class TestBlankFactory extends BlankOutputModelFactory {
		private OutputModelController controller;
		private Grammar grammar;

		public TestBlankFactory(Grammar grammar) {
			this.grammar = grammar;
		}

		@Override
		public void setController(OutputModelController controller) { this.controller = controller; }

		@Override
		public OutputModelController getController() { return controller; }

		@Override
		public Target getTarget() { return null; }

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
	public void testCodeGeneratorExtensionAllMethods() {
		TestBlankFactory factory = new TestBlankFactory(null);
		CodeGeneratorExtension ext = new CodeGeneratorExtension(factory);

		assertNull(ext.parserFile(null));
		assertNull(ext.parser(null));
		assertNull(ext.lexerFile(null));
		assertNull(ext.lexer(null));
		assertNull(ext.rule(null));

		List<SrcOp> ops = new ArrayList<SrcOp>();
		assertSame(ops, ext.rulePostamble(ops));
		assertSame(ops, ext.ruleRef(ops));
		assertSame(ops, ext.tokenRef(ops));
		assertSame(ops, ext.set(ops));
		assertSame(ops, ext.stringRef(ops));
		assertSame(ops, ext.wildcard(ops));
		assertSame(ops, ext.action(ops));
		assertSame(ops, ext.sempred(ops));

		assertNull(ext.alternative(null, true));
		assertNull(ext.finishAlternative(null, true));
		assertNull(ext.epsilon(null));
		assertNull(ext.getChoiceBlock(null));
		assertNull(ext.getEBNFBlock(null));

		assertFalse(ext.needsImplicitLabel(null, null));
	}

	@Test
	public void testBlankOutputModelFactoryAllMethods() {
		TestBlankFactory factory = new TestBlankFactory(null);

		assertNull(factory.parserFile("foo"));
		assertNull(factory.parser(null));
		assertNull(factory.rule(null));
		assertNull(factory.rulePostamble(null, null));
		assertNull(factory.lexerFile("foo"));
		assertNull(factory.lexer(null));
		assertNull(factory.alternative(null, true));
		assertNull(factory.finishAlternative(null, null));
		assertNull(factory.epsilon(null, true));
		assertNull(factory.ruleRef(null, null, null));
		assertNull(factory.tokenRef(null, null, null));
		assertNull(factory.stringRef(null, null));
		assertNull(factory.set(null, null, false));
		assertNull(factory.wildcard(null, null));
		assertNull(factory.action(null));
		assertNull(factory.sempred(null));
		assertNull(factory.getChoiceBlock(null, null, null));
		assertNull(factory.getEBNFBlock(null, null));
		assertNull(factory.getLL1ChoiceBlock(null, null));
		assertNull(factory.getComplexChoiceBlock(null, null));
		assertNull(factory.getLL1EBNFBlock(null, null));
		assertNull(factory.getComplexEBNFBlock(null, null));
		assertNull(factory.getLL1Test(null, null));
		assertFalse(factory.needsImplicitLabel(null, null));
		assertNull(factory.getRoot());
		assertNull(factory.getCurrentRuleFunction());
		assertNull(factory.getCurrentOuterMostAlt());
		assertNull(factory.getCurrentBlock());
		assertNull(factory.getCurrentOuterMostAlternativeBlock());
		assertEquals(0, factory.getCodeBlockLevel());
		assertEquals(0, factory.getTreeLevel());
		assertNull(factory.getGenerator());
		assertNull(factory.getTarget());
		assertNull(factory.getGrammar());
		assertNull(factory.getController());
	}

	@Test
	public void testCodegenModelClasses() throws Exception {
		Grammar g = new Grammar("grammar T;\n r : 'a' ; \n");
		g.atn = new ATN(ATNType.PARSER, 100);
		g.atn.ruleToStartState = new RuleStartState[10];

		TestBlankFactory factory = new TestBlankFactory(g);

		// ExceptionClause
		ActionAST catchAST = new ActionAST(new CommonToken(1, "CATCH"));
		ActionAST exceptionAST = new ActionAST(new CommonToken(2, "e"));
		ExceptionClause excClause = new ExceptionClause(factory, catchAST, exceptionAST);
		assertNotNull(excClause);

		// ThrowEarlyExitException
		GrammarAST ast = new GrammarAST(new CommonToken(1, "STAR"));
		ThrowEarlyExitException earlyExit = new ThrowEarlyExitException(factory, ast, null);
		assertNotNull(earlyExit);

		// CaptureNextToken
		CaptureNextToken cnt = new CaptureNextToken(factory, "tok");
		assertNotNull(cnt);

		// ArgAction
		ArgAction argAct = new ArgAction(factory, actionAST("x = 1;"), "MyRule");
		assertNotNull(argAct);

		// ElementListDecl
		ElementListDecl listDecl = new ElementListDecl(factory, "myList");
		assertNotNull(listDecl);

		// Rule & LeftFactoredRuleFunction & LeftUnfactoredRuleFunction
		RuleAST rAST = new RuleAST(new CommonToken(1, "r"));
		rAST.setOption("baseContext", new GrammarAST(new CommonToken(1, "base")));
		Rule r = new Rule(g, "r", rAST, 0);

		LeftFactoredRuleFunction lfFunc = new LeftFactoredRuleFunction(factory, r);
		assertNotNull(lfFunc);

		LeftUnfactoredRuleFunction luFunc = new LeftUnfactoredRuleFunction(factory, r);
		assertNotNull(luFunc);

		// CodeBlock
		CodeBlock cb = new CodeBlock(factory);
		assertNotNull(cb);

		// Action
		Action action = new Action(factory, actionAST("foo();"));
		assertNotNull(action);

		// Decl
		Decl decl = new Decl(factory, "myDecl");
		assertNotNull(decl);
	}

	@Test
	public void testElementFrequenciesVisitor() {
		CommonTreeNodeStream input = new CommonTreeNodeStream(new GrammarAST(new CommonToken(1, "ROOT")));
		ElementFrequenciesVisitor visitor = new ElementFrequenciesVisitor(null, input);
		assertNotNull(visitor);
	}

	private ActionAST actionAST(String text) {
		ActionAST ast = new ActionAST(new CommonToken(1, text));
		return ast;
	}
}
