/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.test.tool;

import org.antlr.runtime.RecognitionException;
import org.antlr.v4.automata.ATNFactory;
import org.antlr.v4.automata.LexerATNFactory;
import org.antlr.v4.automata.ParserATNFactory;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.semantics.SemanticPipeline;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.junit.Test;
import org.stringtemplate.v4.AutoIndentWriter;
import org.stringtemplate.v4.InstanceScope;
import org.stringtemplate.v4.Interpreter;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.STWriter;
import org.stringtemplate.v4.misc.ErrorManager;
import org.stringtemplate.v4.misc.ErrorType;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class TestCodeGeneration extends BaseTest {
	@Test public void testArgDecl() throws Exception { // should use template not string
		ErrorQueue equeue = new ErrorQueue();
		String g =
				"grammar T;\n" +
				"a[int xyz] : 'a' ;\n";
		List<String> evals = getEvalInfoForString(g, "int xyz");
		System.out.println(evals);
		for (int i = 0; i < evals.size(); i++) {
			String eval = evals.get(i);
			assertFalse("eval should not be POJO: "+eval, eval.startsWith("<pojo:"));
		}
	}

	@Test public void AssignTokenNamesToStringLiteralsInGeneratedParserRuleContexts() throws Exception {
		String g =
			"grammar T;\n" +
			"root: 't1';\n" +
			"Token: 't1';";
		List<String> evals = getEvalInfoForString(g, "() { return getToken(");
		assertNotEquals(0, evals.size());
	}

	@Test public void AssignTokenNamesToStringLiteralArraysInGeneratedParserRuleContexts() throws Exception {
		String g =
			"grammar T;\n" +
				"root: 't1' 't1';\n" +
				"Token: 't1';";
		List<String> evals = getEvalInfoForString(g, "() { return getTokens(");
		assertNotEquals(0, evals.size());
	}

	/**
	 * Generated parsers must emit the private {@code _adaptivePredict} helper
	 * and call it from LL(*) decision sites instead of
	 * {@code getInterpreter().adaptivePredict(...)}.
	 */
	@Test public void generatedParserUsesAdaptivePredictHelper() throws Exception {
		// Non-LL(1) alternatives force LL(*) adaptivePredict codegen (not LA switch).
		String g =
			"grammar T;\n" +
			"s : A B | A C ;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n";
		String source = generateParserSource(g, false);
		assertTrue("missing _adaptivePredict helper",
			source.contains("private int _adaptivePredict(int decision)"));
		assertTrue("helper must bind _interp.adaptivePredict",
			source.contains("_interp.adaptivePredict(_input, decision, _ctx)"));
		assertTrue("decision sites must call _adaptivePredict",
			source.contains("switch ( _adaptivePredict(") || source.contains("= _adaptivePredict("));
		// Decision sites only (ignore any residual comment/doc text).
		assertFalse("must not call getInterpreter().adaptivePredict at decision sites",
			source.contains("getInterpreter().adaptivePredict(_input,"));
	}

	@Test public void generatedStarLoopComparesContinueAltWithoutInvalidConstant() throws Exception {
		// force_atn disables LL(1) specialization so StarBlock/PlusBlock templates
		// emit adaptivePredict loops. Comparing the continue-alt avoids
		// ATN.INVALID_ALT_NUMBER (a grammar token named ATN would shadow the type).
		String g =
			"grammar T;\n" +
			"s : A* B ;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n";
		String source = generateParserSource(g, true);
		assertTrue(source.contains("_adaptivePredict("));
		assertTrue("star loop must test the continue alternative",
			source.contains("while (_alt==1)"));
		assertFalse("must not mention INVALID_ALT_NUMBER (FQN or otherwise)",
			source.contains("INVALID_ALT_NUMBER"));
	}

	/**
	 * Generated parsers emit a private {@code _sync} helper so every decision
	 * site shares one monomorphic call to the error strategy.
	 */
	@Test public void generatedParserUsesSyncHelper() throws Exception {
		String g =
			"grammar T;\n" +
			"s : A* B ;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n";
		String source = generateParserSource(g, true);
		assertTrue("missing _sync helper",
			source.contains("private void _sync()"));
		assertTrue("helper must honor the cached errorSyncEnabled field",
			source.contains("if (errorSyncEnabled)"));
		assertTrue("helper must delegate to the error strategy",
			source.contains("_errHandler.sync(this)"));
		assertTrue("decision / loop sites must call _sync()",
			source.contains("_sync();"));
		int first = source.indexOf("_errHandler.sync(this)");
		int second = source.indexOf("_errHandler.sync(this)", first + 1);
		assertTrue("sync helper body must mention _errHandler.sync once", first >= 0);
		assertTrue("decision sites must not call _errHandler.sync directly",
			second < 0);
	}

	@Test public void generatedParserUsesGetStateForContext() throws Exception {
		String g =
			"grammar T;\n" +
			"s : A ;\n" +
			"A : 'a' ;\n";
		String source = generateParserSource(g, false);
		assertTrue("rule prologues use getState()",
			source.contains("new SContext(_ctx, getState())"));
		assertFalse("must not reach into _stateNumber",
			source.contains("new SContext(_ctx, _stateNumber)"));
	}

	@Test public void generatedSetMatchReusesLookaheadToken() throws Exception {
		String g =
			"grammar T;\n" +
			"s : (A | B) ;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n";
		String source = generateParserSource(g, false);
		assertTrue("set match must fetch LT(1) once",
			source.contains("Token _st = _input.LT(1)"));
		assertTrue("set match must consume the already-fetched token",
			source.contains("consume(_st)"));
		assertFalse("must not consume() without the looked-up token",
			source.contains("consume();"));
		assertTrue("success path must gate reportMatch on errorRecoveryMode",
			source.contains("if (errorRecoveryMode)"));
		assertFalse("set without EOF must not store matchedEOF",
			source.contains("matchedEOF = true") && source.contains("_st.getType() == Token.EOF"));
	}

	@Test public void generatedTokenMatchUsesPrivateHelper() throws Exception {
		String g =
			"grammar T;\n" +
			"s : A ;\n" +
			"A : 'a' ;\n";
		String source = generateParserSource(g, false);
		assertTrue("missing _match helper",
			source.contains("private Token _match(int ttype)"));
		assertTrue("token sites must call _match",
			source.contains("_match("));
	}

	@Test public void generatedNongreedyStarLoopUsesContinueAltTwo() throws Exception {
		String g =
			"grammar T;\n" +
			"s : A*? B ;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n";
		String source = generateParserSource(g, true);
		assertTrue("nongreedy star continues on alt 2",
			source.contains("while (_alt==2)"));
		assertFalse(source.contains("INVALID_ALT_NUMBER"));
	}

	@Test public void generatedPlusSingleAltOmitsSwitch() throws Exception {
		String g =
			"grammar T;\n" +
			"s : A+ B ;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n";
		String source = generateParserSource(g, true);
		assertTrue(source.contains("} while (_alt==1);"));
		int loop = source.indexOf("} while (_alt==1);");
		String plusBody = source.substring(Math.max(0, loop - 400), loop);
		assertFalse("single-alt plus must not switch on _alt",
			plusBody.contains("switch (_alt)"));
	}

	@Test public void generatedPlusMultiAltKeepsSwitch() throws Exception {
		String g =
			"grammar T;\n" +
			"s : (A B | C)+ D ;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n" +
			"D : 'd' ;\n";
		String source = generateParserSource(g, true);
		String compact = source.replaceAll("\\s+", " ");
		assertTrue(compact.contains("while (_alt==1)"));
		assertTrue("multi-alt plus body must keep both alternatives: " + compact,
			compact.contains("case 2:"));
	}

	@Test public void generatedSetMatchWithEofStoresMatchedEof() throws Exception {
		String g =
			"grammar T;\n" +
			"s : (A | EOF) ;\n" +
			"A : 'a' ;\n";
		String source = generateParserSource(g, false);
		assertTrue(source.contains("if (_la == Token.EOF)"));
		assertTrue(source.contains("matchedEOF = true"));
	}

	@Test public void generatedNotSetMatchInvertsLaTest() throws Exception {
		String g =
			"grammar T;\n" +
			"s : ~A B ;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n";
		String source = generateParserSource(g, false);
		assertTrue("MatchNotSet must reject non-positive lookahead",
			source.contains("_la <= 0 ||") || source.contains("_la <= 0||"));
	}

	@Test public void generatedLabeledSetMatchAssignsLookaheadToken() throws Exception {
		String g =
			"grammar T;\n" +
			"s : t=(A | B) ;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n";
		String source = generateParserSource(g, false);
		assertTrue(source.contains("t = _st;"));
		assertTrue(source.contains("Token _st = _input.LT(1)"));
	}

	@Test public void generatedLabeledRecAltDeclaresParentStateCtor() throws Exception {
		String g =
			"grammar T;\n" +
			"e : e '*' e # star\n" +
			"  | INT     # prim\n" +
			"  ;\n" +
			"INT : '0'..'9'+ ;\n";
		String source = generateParserSource(g, false);
		assertTrue(source.contains("public StarContext(ParserRuleContext parent, int invokingState)"));
		assertTrue(source.contains("super(parent, invokingState);"));
	}

	@Test public void generatedLeftRecursionUsesPrecHelperAndNoDummyContext() throws Exception {
		String g =
			"grammar T;\n" +
			"e : e '*' e # star\n" +
			"  | INT     # prim\n" +
			"  ;\n" +
			"INT : '0'..'9'+ ;\n";
		String source = generateParserSource(g, false);
		assertTrue("missing _prec helper",
			source.contains("private boolean _prec(int p)"));
		assertTrue("operator alts must call _prec",
			source.contains("_prec("));
		assertFalse("must not wrap a dummy parent context for labeled rec alts",
			source.contains("Context(new EContext("));
		assertTrue("labeled rec alt uses parent/state ctor",
			source.contains("new StarContext(_parentctx, _parentState)"));
	}

	@Test public void generatedRecognizerDeserializesAtnFromString() throws Exception {
		String g =
			"grammar T;\n" +
			"s : 'a' ;\n";
		String source = generateParserSource(g, false);
		assertTrue(source.contains("new ATNDeserializer().deserialize(_serializedATN)"));
		assertFalse(source.contains("deserialize(_serializedATN.toCharArray())"));
		assertFalse("unused Iterator import should be omitted",
			source.contains("import java.util.Iterator;"));
	}

	/** Render the full generated parser source for structural assertions. */
	private String generateParserSource(String grammarString, boolean forceAtn) throws RecognitionException {
		Grammar g = new Grammar(grammarString);
		if (g.ast == null || g.ast.hasErrors) {
			throw new IllegalStateException("grammar has errors");
		}
		g.tool.force_atn = forceAtn;
		SemanticPipeline sem = new SemanticPipeline(g);
		sem.process();
		ATNFactory factory = new ParserATNFactory(g);
		if (g.isLexer()) {
			factory = new LexerATNFactory((LexerGrammar) g);
		}
		g.atn = factory.createATN();
		// LL(1) analysis populates decisionLOOK used by the code generator.
		new org.antlr.v4.analysis.AnalysisPipeline(g).process();
		CodeGenerator gen = new CodeGenerator(g);
		ST outputFileST = gen.generateParser();
		return outputFileST.render();
	}

	/** Add tags around each attribute/template/value write */
	public static class DebugInterpreter extends Interpreter {
		List<String> evals = new ArrayList<String>();
		ErrorManager myErrMgrCopy;
		int tab = 0;
		public DebugInterpreter(STGroup group, ErrorManager errMgr, boolean debug) {
			super(group, errMgr, debug);
			myErrMgrCopy = errMgr;
		}

		@Override
		protected int writeObject(STWriter out, InstanceScope scope, Object o, String[] options) {
			if ( o instanceof ST ) {
				String name = ((ST)o).getName();
				name = name.substring(1);
				if ( !name.startsWith("_sub") ) {
					try {
						out.write("<ST:" + name + ">");
						evals.add("<ST:" + name + ">");
						int r = super.writeObject(out, scope, o, options);
						out.write("</ST:" + name + ">");
						evals.add("</ST:" + name + ">");
						return r;
					} catch (IOException ioe) {
						myErrMgrCopy.IOError(scope.st, ErrorType.WRITE_IO_ERROR, ioe);
					}
				}
			}
			return super.writeObject(out, scope, o, options);
		}

		@Override
		protected int writePOJO(STWriter out, InstanceScope scope, Object o, String[] options) throws IOException {
			Class<?> type = o.getClass();
			String name = type.getSimpleName();
			out.write("<pojo:"+name+">"+o.toString()+"</pojo:"+name+">");
			evals.add("<pojo:" + name + ">" + o.toString() + "</pojo:" + name + ">");
			return super.writePOJO(out, scope, o, options);
		}

		public void indent(STWriter out) throws IOException {
			for (int i=1; i<=tab; i++) {
				out.write("\t");
			}
		}
	}

	public List<String> getEvalInfoForString(String grammarString, String pattern) throws RecognitionException {
		ErrorQueue equeue = new ErrorQueue();
		Grammar g = new Grammar(grammarString);
		List<String> evals = new ArrayList<String>();
		if ( g.ast!=null && !g.ast.hasErrors ) {
			SemanticPipeline sem = new SemanticPipeline(g);
			sem.process();

			ATNFactory factory = new ParserATNFactory(g);
			if (g.isLexer()) factory = new LexerATNFactory((LexerGrammar) g);
			g.atn = factory.createATN();

			CodeGenerator gen = new CodeGenerator(g);
			ST outputFileST = gen.generateParser();

//			STViz viz = outputFileST.inspect();
//			try {
//				viz.waitForClose();
//			}
//			catch (Exception e) {
//				e.printStackTrace();
//			}

			boolean debug = false;
			DebugInterpreter interp =
					new DebugInterpreter(outputFileST.groupThatCreatedThisInstance,
							outputFileST.impl.nativeGroup.errMgr,
							debug);
			InstanceScope scope = new InstanceScope(null, outputFileST);
			StringWriter sw = new StringWriter();
			AutoIndentWriter out = new AutoIndentWriter(sw);
			interp.exec(out, scope);

			for (String e : interp.evals) {
				if (e.contains(pattern)) {
					evals.add(e);
				}
			}
		}
		if ( equeue.size()>0 ) {
			System.err.println(equeue.toString());
		}
		return evals;
	}
}
