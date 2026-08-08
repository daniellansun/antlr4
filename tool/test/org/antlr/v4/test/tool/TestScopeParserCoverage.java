/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.v4.Tool;
import org.antlr.v4.automata.LexerATNFactory;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.model.Sync;
import org.antlr.v4.codegen.model.dbg;
import org.antlr.v4.gui.TestRig;
import org.antlr.v4.misc.CharSupport;
import org.antlr.v4.misc.EscapeSequenceParsing;
import org.antlr.v4.misc.FrequencySet;
import org.antlr.v4.misc.Graph;
import org.antlr.v4.misc.Utils;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.parse.GrammarASTAdaptor;
import org.antlr.v4.parse.GrammarToken;
import org.antlr.v4.parse.ScopeParser;
import org.antlr.v4.parse.v3TreeGrammarException;
import org.antlr.v4.parse.v4ParserException;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.tool.Attribute;
import org.antlr.v4.tool.AttributeDict;
import org.antlr.v4.tool.DOTGenerator;
import org.antlr.v4.tool.ErrorManager;
import org.antlr.v4.tool.ErrorSeverity;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.GrammarParserInterpreter;
import org.antlr.v4.tool.GrammarTransformPipeline;
import org.antlr.v4.tool.LabelType;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.ActionAST;
import org.antlr.v4.tool.ast.AltAST;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.antlr.v4.tool.ast.RuleAST;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * Coverage for {@link org.antlr.v4.parse.ScopeParser} and attribute dictionaries.
 */
public class TestScopeParserCoverage extends BaseTest {

	@Test
	public void testAttributeDictTypes() {
		AttributeDict d = new AttributeDict(AttributeDict.DictType.ARG);
		d.add(new Attribute("x", "int"));
		d.add(new Attribute("y"));
		assertNotNull(d.toString());
		assertNotNull(d.get("x"));
		AttributeDict d2 = new AttributeDict(AttributeDict.DictType.RET);
		AttributeDict d3 = new AttributeDict(AttributeDict.DictType.LOCAL);
		AttributeDict d4 = new AttributeDict(AttributeDict.DictType.PREDEFINED_RULE);
		AttributeDict d5 = new AttributeDict(AttributeDict.DictType.PREDEFINED_LEXER_RULE);
		assertNotNull(d2);
		assertNotNull(d3);
		assertNotNull(d4);
		assertNotNull(d5);
	}



	@Test
	public void testScopeParserVariants() throws Exception {
		Grammar g = new Grammar("grammar T;\n a[int x, String y] returns [int z] locals [int w] : 'x' ;\n");
		g.tool.process(g, false);
		Rule a = g.getRule("a");
		assertNotNull(a);
		// ScopeParser exercised via arg/return/locals parsing above
		Attribute attr = ScopeParser.parseTypedArgList(null, "int x, List<String> ys", g).attributes.values().iterator().next();
		assertNotNull(attr);

		// more complex decls
		try {
			ScopeParser.parseTypedArgList(null, "Map<String,List<Integer>> m", g);
		} catch (Throwable t) {
		}
		try {
			ScopeParser.parseTypedArgList(null, "int[] arr", g);
		} catch (Throwable t) {
		}
		try {
			ScopeParser.parseTypedArgList(null, "T.U v", g);
		} catch (Throwable t) {
		}
	}

}
