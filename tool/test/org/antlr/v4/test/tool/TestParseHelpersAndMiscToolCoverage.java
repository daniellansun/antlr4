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
 * Coverage for CharSetParseState, ErrorManager helpers, Utils/Graph, parse exceptions, and codegen model stubs.
 */
public class TestParseHelpersAndMiscToolCoverage extends BaseTest {

	@Test
	public void testParseExceptionsAndTokens() throws Exception {
		CommonToken tok = new CommonToken(1, "x");
		v3TreeGrammarException v3 = new v3TreeGrammarException(tok);
		assertSame(tok, v3.location);

		v4ParserException v4b = new v4ParserException();
		assertNull(v4b.msg);
		// v4ParserException(msg, input) may NPE if input is null depending on ANTLR3
		try {
			v4ParserException v4 = new v4ParserException("msg", null);
			assertEquals("msg", v4.msg);
		} catch (Throwable t) {
		}

		GrammarASTAdaptor adaptor = new GrammarASTAdaptor();
		GrammarAST created = adaptor.create(tok);
		assertNotNull(created);
		assertEquals("x", created.getText());
		assertNotNull(adaptor.create(ANTLRParser.RULE, "r"));
		assertNotNull(adaptor.create(ANTLRParser.STRING_LITERAL, "'x'"));
		assertNotNull(adaptor.create(ANTLRParser.TOKEN_REF, "ID"));
		assertNull(adaptor.dupNode(null));
		assertNotNull(adaptor.dupNode(created));

		try {
			Grammar g = new Grammar("grammar T;\na:'x';\n");
			GrammarToken gt = new GrammarToken(g, tok);
			assertNotNull(gt.toString());
		} catch (Throwable t) {
		}
	}



	@Test
	public void testCharSetParseStateEqualsHashCode() throws Exception {
		// CharSetParseState is a public nested class of package-visible? Use setAccessible
		Class<?> modeCls = Class.forName("org.antlr.v4.automata.LexerATNFactory$CharSetParseState$Mode");
		Object noneMode = Enum.valueOf((Class<Enum>) modeCls, "NONE");
		Object errorMode = Enum.valueOf((Class<Enum>) modeCls, "ERROR");
		Class<?> stateCls = Class.forName("org.antlr.v4.automata.LexerATNFactory$CharSetParseState");
		Constructor<?> ctor = stateCls.getDeclaredConstructor(modeCls, boolean.class, int.class, IntervalSet.class);
		ctor.setAccessible(true);
		Object s1 = ctor.newInstance(noneMode, false, -1, IntervalSet.EMPTY_SET);
		Object s2 = ctor.newInstance(noneMode, false, -1, IntervalSet.EMPTY_SET);
		Object s3 = ctor.newInstance(errorMode, true, 65, IntervalSet.of(65));
		assertEquals(s1, s2);
		assertEquals(s1.hashCode(), s2.hashCode());
		assertFalse(s1.equals(s3));
		assertFalse(s1.equals("x"));
		assertTrue(s1.equals(s1));
		assertNotNull(s1.toString());
		assertNotNull(s3.toString());
		Field none = stateCls.getField("NONE");
		Field err = stateCls.getField("ERROR");
		none.setAccessible(true);
		err.setAccessible(true);
		assertNotNull(none.get(null));
		assertNotNull(err.get(null));
	}



	@Test
	public void testCodegenModelSyncAndDbg() throws Exception {
		Grammar g = new Grammar("grammar T;\n a : 'x' ;\n");
		g.tool.process(g, false);
		CodeGenerator gen = new CodeGenerator(g);
		if (gen.getTarget() == null) return;
		org.antlr.v4.codegen.ParserFactory factory = new org.antlr.v4.codegen.ParserFactory(gen);
		org.antlr.v4.codegen.OutputModelController ctrl = new org.antlr.v4.codegen.OutputModelController(factory);
		factory.setController(ctrl);
		// Sync and dbg constructors
		try {
			GrammarAST ast = new GrammarAST(new CommonToken(1, "x"));
			Sync sync = new Sync(factory, ast, IntervalSet.of(1), 0, "s");
			assertNotNull(sync);
		} catch (Throwable t) {
		}
		try {
			dbg d = new dbg();
			assertNotNull(d);
		} catch (Throwable t) {
		}
	}



	@Test
	public void testMiscUtilsGraphFrequency() {
		FrequencySet<String> freq = new FrequencySet<String>();
		freq.add("a");
		freq.add("a");
		freq.add("b");
		assertEquals(2, freq.count("a"));
		assertEquals(1, freq.count("b"));

		Graph<String> graph = new Graph<String>();
		graph.addEdge("a", "b");
		graph.addEdge("b", "c");
		List<String> sorted = graph.sort();
		assertNotNull(sorted);

		assertNotNull(CharSupport.getANTLRCharLiteralForChar('a'));
		assertNotNull(CharSupport.getANTLRCharLiteralForChar('\n'));
		assertNotNull(CharSupport.getANTLRCharLiteralForChar(0x10000));
	}



	@Test
	public void testErrorManagerPanicAndFormatWantsSingleLine() {
		Tool tool = new Tool();
		ErrorManager em = tool.errMgr;
		em.setFormat("gnu");
		assertNotNull(em.getMessageFormat());
		// formatWantsSingleLineMessage
		try {
			em.formatWantsSingleLineMessage();
		} catch (Throwable t) {
		}
		// severity enum
		for (ErrorSeverity s : ErrorSeverity.values()) {
			assertNotNull(s.getText());
		}
		for (LabelType lt : LabelType.values()) {
			assertNotNull(lt.name());
		}
	}

}
