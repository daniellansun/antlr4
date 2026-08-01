/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.v4.automata.ATNFactory;
import org.antlr.v4.automata.ATNOptimizer;
import org.antlr.v4.automata.LexerATNFactory;
import org.antlr.v4.automata.ParserATNFactory;
import org.antlr.v4.automata.TailEpsilonRemover;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.semantics.BasicSemanticChecks;
import org.antlr.v4.semantics.BlankActionSplitterListener;
import org.antlr.v4.semantics.RuleCollector;
import org.antlr.v4.semantics.SymbolChecks;
import org.antlr.v4.semantics.SymbolCollector;
import org.antlr.v4.semantics.UseDefAnalyzer;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;

import org.junit.Test;

import static org.junit.Assert.*;

public class TestAutomataAndSemanticsCoverage {

	@Test
	public void testATNFactoryHandle() {
		ATNState left = new BasicState();
		ATNState right = new BasicState();
		ATNFactory.Handle handle = new ATNFactory.Handle(left, right);
		assertEquals(left, handle.left);
		assertEquals(right, handle.right);
		assertNotNull(handle.toString());
	}

	@Test
	public void testTailEpsilonRemoverAndATNOptimizer() throws Exception {
		String grammarStr = "grammar T;\n a : 'x' | 'y' ;\n";
		Grammar g = new Grammar(grammarStr);
		g.tool.process(g, false);

		if (g.atn != null && !g.atn.states.isEmpty()) {
			TailEpsilonRemover remover = new TailEpsilonRemover(g.atn);
			remover.visitState(g.atn.states.get(0));

			ATNOptimizer.optimize(g, g.atn);
		}
	}

	@Test
	public void testParserAndLexerATNFactory() throws Exception {
		String lexerGrammarStr = "lexer grammar L;\n A : 'a' ;\n";
		LexerGrammar lg = new LexerGrammar(lexerGrammarStr);
		LexerATNFactory lexerFactory = new LexerATNFactory(lg);
		assertNotNull(lexerFactory);

		String parserGrammarStr = "grammar P;\n a : 'a' ;\n";
		Grammar pg = new Grammar(parserGrammarStr);
		ParserATNFactory parserFactory = new ParserATNFactory(pg);
		assertNotNull(parserFactory);
	}

	@Test
	public void testSemanticsClasses() throws Exception {
		String grammarStr = "grammar T;\n a : 'x' ;\n";
		Grammar g = new Grammar(grammarStr);
		g.tool.process(g, false);

		RuleCollector collector = new RuleCollector(g);
		collector.process(g.ast);

		SymbolCollector symCollector = new SymbolCollector(g);
		symCollector.process(g.ast);

		SymbolChecks symChecks = new SymbolChecks(g, symCollector);
		assertNotNull(symChecks);

		BasicSemanticChecks basicChecks = new BasicSemanticChecks(g, collector);
		basicChecks.process();

		UseDefAnalyzer.trackTokenRuleRefsInActions(g);

		BlankActionSplitterListener listener = new BlankActionSplitterListener();
		listener.attr("expr", new CommonToken(1, "x"));
		listener.qualifiedAttr("expr", new CommonToken(1, "x"), new CommonToken(2, "y"));
		listener.nonLocalAttr("expr", new CommonToken(1, "x"), new CommonToken(2, "y"));
		listener.setAttr("expr", new CommonToken(1, "x"), new CommonToken(2, "y"));
		listener.setNonLocalAttr("expr", new CommonToken(1, "x"), new CommonToken(2, "y"), new CommonToken(3, "z"));
		listener.text("text");
	}
}
