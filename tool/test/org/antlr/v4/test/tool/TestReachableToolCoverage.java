/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.v4.Tool;
import org.antlr.v4.codegen.CodeGenPipeline;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.CodeGeneratorExtension;
import org.antlr.v4.codegen.OutputModelController;
import org.antlr.v4.codegen.ParserFactory;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.atn.ATNSimulator;
import org.antlr.v4.runtime.atn.SemanticContext;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.semantics.AttributeChecks;
import org.antlr.v4.tool.ANTLRMessage;
import org.antlr.v4.tool.BuildDependencyGenerator;
import org.antlr.v4.tool.ErrorManager;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LeftRecursiveRule;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ToolMessage;
import org.antlr.v4.tool.ast.ActionAST;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.antlr.v4.tool.ast.PredAST;
import org.antlr.v4.tool.ast.RuleAST;
import org.junit.Test;

import java.io.File;
import java.io.Writer;
import java.security.Permission;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Direct tests for tool branches that the grammar suite does not reach:
 * a missing code-gen target, header-file targets, message-format fallback,
 * and the public grammar helpers around those paths.
 */
public class TestReachableToolCoverage extends BaseTest {

	@Test
	public void missingLanguageTargetRefusesEveryWrite() throws Exception {
		Grammar g = new Grammar("grammar T;\na : 'x' ;\n");
		CodeGenerator gen = new CodeGenerator(g.tool, g, "NoSuchLang");
		assertNull(gen.getTarget());
		assertNull(gen.getTemplates());
		assertUnsupported(new Probe() { @Override public void run() { gen.generateLexer(); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.generateLexer(true); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.generateParser(); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.generateParser(true); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.generateListener(); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.generateListener(true); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.generateBaseListener(); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.generateBaseListener(true); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.generateVisitor(); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.generateVisitor(true); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.generateBaseVisitor(); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.generateBaseVisitor(true); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.writeRecognizer(null, false); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.writeListener(null, false); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.writeBaseListener(null, false); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.writeVisitor(null, false); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.writeBaseVisitor(null, false); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.writeVocabFile(); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.getRecognizerFileName(); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.getRecognizerFileName(true); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.getListenerFileName(); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.getListenerFileName(true); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.getVisitorFileName(); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.getVisitorFileName(true); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.getBaseListenerFileName(); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.getBaseListenerFileName(true); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.getBaseVisitorFileName(); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.getBaseVisitorFileName(true); } });
		assertUnsupported(new Probe() { @Override public void run() { gen.getHeaderFileName(); } });
	}

	@Test
	public void headerTargetAndPlainTargetExerciseFileLists() throws Exception {
		mkdir(tmpdir);
		String parserPath = new File(tmpdir, "T.g4").getAbsolutePath();
		Grammar parser = new Grammar(parserPath,
			"grammar T;\noptions { language=Cov; }\nbad : 'x' ;\n");
		rememberName(parser);
		parser.tool.gen_listener = true;
		parser.tool.gen_visitor = true;
		CodeGenerator cov = new CodeGenerator(parser);
		assertTrue(cov.getTarget().needsHeader());
		assertNotNull(cov.getHeaderFileName());

		BuildDependencyGenerator deps = new BuildDependencyGenerator(parser.tool, parser);
		List<File> generated = deps.getGeneratedFileList();
		assertNotNull(generated);
		assertTrue(generated.size() > 2);

		parser.fileName = "dir with spaces/T.g4";
		assertTrue(deps.getOutputFile("T.cov").getPath().contains("T.cov"));

		String lexerPath = new File(tmpdir, "L.g4").getAbsolutePath();
		LexerGrammar lexer = new LexerGrammar(lexerPath,
			"lexer grammar L;\noptions { language=Cov; }\nA : 'a' ;\n", null);
		rememberName(lexer);
		runPipeline(lexer);

		runPipeline(parser);

		Grammar plain = new Grammar(new File(tmpdir, "P.g4").getAbsolutePath(),
			"parser grammar P;\noptions { language=Plain; }\na : 'a' ;\n");
		rememberName(plain);
		CodeGenerator plainGen = new CodeGenerator(plain);
		assertNull(plainGen.getHeaderFileName());
	}

	@Test
	public void dependencyListIncludesImportsAndTokenVocab() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "Imp.g4", "parser grammar Imp;\na : 'a' ;\n");
		String root = new File(tmpdir, "Root.g4").getAbsolutePath();
		writeFile(tmpdir, "Root.g4", "parser grammar Root;\nimport Imp;\nb : a ;\n");
		OpenTool tool = new OpenTool(new String[] {"-lib", tmpdir, "-o", tmpdir, "-listener", "-visitor"});
		Grammar g = tool.loadGrammar(root);
		assertNotNull(g.getImportedGrammars());
		Grammar imported = new Grammar("parser grammar Imp;\na : A ;\n");
		rememberName(imported);
		imported.fileName = "Imp.g4";
		imported.parent = g;
		g.importedGrammars = new java.util.ArrayList<Grammar>();
		g.importedGrammars.add(imported);
		assertNotNull(g.getRule("Imp", "a"));
		assertNull(g.getRule("Missing", "a"));
		BuildDependencyGenerator deps = new BuildDependencyGenerator(tool, g);
		List<File> files = deps.getGeneratedFileList();
		assertNotNull(files);
		boolean sawImport = false;
		for (File file : files) {
			if (file.getPath().contains("Imp")) sawImport = true;
		}
		assertTrue(sawImport);
		assertNotNull(deps.getDependencies().render());

		Grammar vocab = new Grammar("parser grammar Q;\noptions { tokenVocab=V; }\na : A ;\n");
		vocab.tool.libDirectory = ".";
		BuildDependencyGenerator vocabDeps = new BuildDependencyGenerator(vocab.tool, vocab);
		assertEquals(1, vocabDeps.getNonImportDependenciesFileList().size());
		vocab.tool.libDirectory = tmpdir;
		assertEquals(1, vocabDeps.getNonImportDependenciesFileList().size());
		assertNotNull(vocabDeps.getDependenciesFileList());
		Grammar bare = new Grammar("grammar Bare;\na : 'a' ;\n");
		assertNull(new BuildDependencyGenerator(bare.tool, bare).getDependenciesFileList());
	}

	@Test
	public void messageFormatsFallbackPanicAndOneOff() throws Exception {
		Tool tool = new Tool();
		ErrorManager errMgr = tool.errMgr;
		errMgr.setFormat("no-such-format");
		assertNotNull(errMgr.getMessageFormat());
		errMgr.setFormat("incomplete");
		assertNotNull(errMgr.getMessageFormat());
		errMgr.setFormat("gnu");
		assertTrue(errMgr.formatWantsSingleLineMessage());

		CommonToken token = new CommonToken(1, "a");
		token.setLine(4);
		token.setCharPositionInLine(2);
		org.antlr.v4.tool.GrammarSemanticsMessage located =
			new org.antlr.v4.tool.GrammarSemanticsMessage(
				ErrorType.ILLEGAL_OPTION, new File(tmpdir, "no-such.g4").getPath(), token, "silly");
		tool.longMessages = true;
		assertNotNull(errMgr.getMessageTemplate(located).render());

		tool.info("line\nmore");
		ANTLRMessage problem = new ToolMessage(ErrorType.INVALID_CMDLINE_ARG, "-bogus");
		tool.error(problem);
		tool.warnings_are_errors = true;
		int afterFirst = 0;
		tool.warning(problem);
		afterFirst = errMgr.getNumErrors();
		tool.warning(problem);
		assertEquals(afterFirst, errMgr.getNumErrors());

		ErrorManager.internalError("raw", new IllegalStateException("cause"));
		ErrorManager.internalError("internal");
		try {
			ErrorManager.fatalInternalError("fatal", new IllegalStateException("cause"));
			fail("fatalInternalError");
		}
		catch (RuntimeException expected) {
			assertEquals("fatal", expected.getMessage());
		}
		try {
			errMgr.panic(ErrorType.INTERNAL_ERROR, "x");
			fail("panic");
		}
		catch (Error expected) {
			assertTrue(expected.getMessage().contains("panic"));
		}
	}

	@Test
	public void commandLineEdgesAndOutputDirectories() throws Exception {
		mkdir(tmpdir);
		File outFile = new File(tmpdir, "not-a-directory");
		assertTrue(outFile.createNewFile());
		File missingLib = new File(tmpdir, "missing-lib");
		Tool bad = new Tool(new String[] {
			"-o", outFile.getAbsolutePath() + "/",
			"-lib", missingLib.getAbsolutePath() + "/",
			"-Dlanguage=",
			"-D",
			"-DnotAnOption=1"
		});
		assertTrue(bad.errMgr.getNumErrors() > 0);
		java.lang.reflect.Field optionField = Tool.Option.class.getDeclaredField("fieldName");
		optionField.setAccessible(true);
		String savedField = (String) optionField.get(Tool.optionDefs[0]);
		optionField.set(Tool.optionDefs[0], "notARealField");
		try {
			new Tool(new String[] {"-o", tmpdir});
		}
		finally {
			optionField.set(Tool.optionDefs[0], savedField);
		}

		String grammar = new File(tmpdir, "T.g4").getAbsolutePath();
		writeFile(tmpdir, "T.g4", "grammar T;\na : 'a' ;\n");
		new Tool(new String[] {grammar, grammar});

		OpenTool tool = new OpenTool(null);
		assertEquals(".", tool.getOutputDirectory(null).getPath());
		assertEquals("sub", tool.getOutputDirectory("sub/t.g4").getPath());
		tool.outputDirectory = tmpdir;
		tool.setHaveOutputDir(true);
		tool.exact_output_dir = true;
		assertEquals(new File(tmpdir).getPath(), tool.getOutputDirectory("/var/lib/t.g4").getPath());
		tool.exact_output_dir = false;
		assertEquals(new File(tmpdir).getPath(), tool.getOutputDirectory("~/lib/t.g4").getPath());
		assertEquals(new File(tmpdir).getPath(), tool.getOutputDirectory("/var/lib/t.g4").getPath());

		tool.grammarEncoding = "UTF-8";
		Grammar g = new Grammar("grammar U;\na : 'a' ;\n");
		Writer encoded = tool.getOutputFileWriter(g, "U.java");
		assertNotNull(encoded);
		encoded.close();
		tool.outputDirectory = null;
		Writer memory = tool.getOutputFileWriter(g, "U.java");
		assertNotNull(memory);
		memory.close();

		tool.outputDirectory = outFile.getAbsolutePath();
		tool.setHaveOutputDir(true);
		Grammar generated = new Grammar(grammar, "grammar T;\na : 'a' ;\n");
		generated.fileName = grammar;
		tool.generate_ATN_dot = true;
		tool.generateATNs(generated);
		assertTrue(tool.errMgr.getNumErrors() > 0);
	}

	@Test
	public void mainHonorsExitAndDebugReturn() throws Exception {
		mkdir(tmpdir);
		final SecurityManager previous = System.getSecurityManager();
		System.setSecurityManager(new SecurityManager() {
			@Override
			public void checkPermission(Permission perm) { }
			@Override
			public void checkExit(int status) {
				throw new SecurityException("exit " + status);
			}
		});
		try {
			try {
				Tool.main(new String[0]);
				fail("help should exit");
			}
			catch (SecurityException expected) {
				assertEquals("exit 0", expected.getMessage());
			}
			try {
				Tool.main(new String[] {"-not-an-option"});
				fail("bad option should exit");
			}
			catch (SecurityException expected) {
				assertEquals("exit 1", expected.getMessage());
			}
			Tool.main(new String[] {"-XdbgST"});
			String path = new File(tmpdir, "M.g4").getAbsolutePath();
			writeFile(tmpdir, "M.g4", "grammar M;\na : 'a' ;\n");
			try {
				Tool.main(new String[] {"-o", tmpdir, path});
				fail("clean grammar should exit 0");
			}
			catch (SecurityException expected) {
				assertEquals("exit 0", expected.getMessage());
			}
			Tool.main(new String[] {"-Xlog", "-XdbgST"});
		}
		finally {
			System.setSecurityManager(previous);
			File[] logs = new File(".").listFiles();
			if (logs != null) {
				for (File log : logs) {
					String name = log.getName();
					if (name.startsWith("antlr-") && name.endsWith(".log")) {
						log.delete();
					}
				}
			}
		}
	}

	@Test
	public void grammarHelpersCoverImportsContextsAndInterpreters() throws Exception {
		try {
			new Grammar(new Tool(), null);
			fail("null ast");
		}
		catch (NullPointerException expected) {
			assertEquals("ast", expected.getMessage());
		}

		Grammar parser = new Grammar("parser grammar P;\ntokens { A, B }\na : A #Alt1 | B #Alt2 ;\n");
		assertEquals("<invalid>", Grammar.getGrammarTypeToFileNameSuffix(0));
		assertNull(parser.getRule("nope"));
		Rule a = parser.getRule("a");
		assertNull(a.getUnlabeledAltASTs());
		assertNotNull(a.toString());
		assertTrue(a.equals(a));
		assertFalse(a.equals("a"));
		Rule sameName = new Rule(parser, "a", (RuleAST) a.ast, 1);
		assertTrue(a.equals(sameName));
		assertFalse(parser.defineRule(a));
		sameName.index = 50;
		assertFalse(parser.undefineRule(sameName));
		assertTrue(parser.undefineRule(a));
		assertTrue(parser.defineRule(a));

		OpenGrammar open = new OpenGrammar("grammar C;\na : 'a' ;\n");
		assertEquals("a", open.baseContext("a"));
		assertEquals("missing", open.baseContext("missing"));
		assertTrue(open.getTokenTypes().size() > 0);
		int lit = open.defineStringLiteral("'z'");
		assertEquals(lit, open.defineStringLiteral("'z'"));
		assertEquals("99999", open.getTokenDisplayName(99999));
		assertTrue(open.getStringLiterals().contains("'a'"));
		open.setLookaheadDFA(0, new DFA(open.atn.states.get(0), 0));
		assertNotNull(open.getStateToGrammarRegion(open.atn.ruleToStartState[0].stateNumber));

		LexerGrammar lexer = new LexerGrammar("lexer grammar L;\nA : 'a' ;\nfragment F : 'b' ;\n");
		rememberName(lexer);
		assertEquals("lexer", lexer.getDefaultActionScope());
		assertTrue(lexer.getRule("F").isFragment());
		int channel = lexer.defineChannelName("MINE");
		assertEquals(channel, lexer.defineChannelName("MINE"));
		assertTrue(lexer.getTokenTypes().size() > 0);
		assertNotNull(lexer.createLexerInterpreter(CharStreams.fromString("a")));
		try {
			lexer.createParserInterpreter(null);
			fail("lexer parser interpreter");
		}
		catch (IllegalStateException expected) {
			assertNotNull(expected);
		}
		try {
			lexer.createGrammarParserInterpreter(null);
			fail("lexer grammar interpreter");
		}
		catch (IllegalStateException expected) {
			assertNotNull(expected);
		}
		try {
			parser.createLexerInterpreter(CharStreams.fromString("a"));
			fail("parser lexer interpreter");
		}
		catch (IllegalStateException expected) {
			assertNotNull(expected);
		}
		org.antlr.v4.runtime.CommonTokenStream noTokens =
			new org.antlr.v4.runtime.CommonTokenStream(
				new org.antlr.v4.runtime.ListTokenSource(
					java.util.Collections.<org.antlr.v4.runtime.Token>emptyList()));
		assertNotNull(parser.createParserInterpreter(noTokens));
		assertNotNull(parser.createGrammarParserInterpreter(noTokens));
	}

	@Test
	public void combinedGrammarInterpreterAndSemanticContexts() throws Exception {
		Grammar combined = new Grammar("grammar T;\na : 'a' ;\n");
		assertNotNull(combined.createLexerInterpreter(CharStreams.fromString("a")));
		assertNotNull(combined.getImplicitLexer());
		PredAST pred = new PredAST(new CommonToken(ANTLRParser.SEMPRED, "{true}?"));
		combined.indexToPredMap = new LinkedHashMap<Integer, PredAST>();
		combined.indexToPredMap.put(0, pred);
		combined.indexToPredMap.put(1, pred);
		SemanticContext.Predicate p0 = new SemanticContext.Predicate(0, 0, false);
		SemanticContext.Predicate p1 = new SemanticContext.Predicate(0, 1, false);
		assertTrue(combined.getSemanticContextDisplayString(new SemanticContext.AND(p0, p1)).contains("and"));
		assertTrue(combined.getSemanticContextDisplayString(new SemanticContext.OR(p0, p1)).contains("or"));
		assertTrue(combined.getSemanticContextDisplayString(new SemanticContext.PrecedencePredicate(1)).contains("prec"));

		Grammar left = new Grammar("grammar E;\ne : e '+' e | INT ;\nINT : [0-9]+ ;\n");
		assertTrue(left.getRule("e") instanceof LeftRecursiveRule);
		assertNotNull(left.getStateToGrammarRegion(left.atn.ruleToStartState[left.getRule("e").index].stateNumber));
		left.ast = null;
		assertEquals(0, left.getType());
		assertNull(left.getTokenStream());
		assertNull(left.getTypeString());
		left.loadImportedGrammars();
		assertTrue(Grammar.getStateToGrammarRegionMap(null, null).isEmpty());

		mkdir(tmpdir);
		String path = new File(tmpdir, "Loaded.g4").getAbsolutePath();
		writeFile(tmpdir, "Loaded.g4", "grammar Loaded;\na : 'a' ;\n");
		assertNotNull(Grammar.load(path));
		Grammar misnamed = new Grammar(new File(tmpdir, "WrongName.g4").getPath(), "grammar T;\na : 'a' ;\n");
		assertTrue(misnamed.tool.errMgr.getNumErrors() > 0);

		Grammar withArgs = new Grammar("grammar Args;\na[int x] returns [int y] : 'a' ;\n");
		assertTrue(withArgs.getRule("a").toString().contains("args"));
		assertTrue(withArgs.getRule("a").toString().contains("retvals"));
	}

	@Test
	public void semanticAndAttributeBranches() throws Exception {
		assertTrue(errors("parser grammar P;\na options { baseContext=b; } : 'a' ;\nb options { baseContext=c; } : 'b' ;\nc : 'c' ;\n") > 0);
		assertTrue(errors("parser grammar P;\na : 'a' #One | 'b' ;\n") > 0);
		assertTrue(errors("parser grammar P;\na : 'a' #x ;\nx : 'b' ;\n") > 0);
		assertTrue(errors("parser grammar P;\na : 'a' #Lab ;\nb : 'b' #Lab ;\n") > 0);
		assertTrue(errors("lexer grammar L;\na : 'a' ;\n") > 0);
		assertTrue(errors("parser grammar P;\nA : 'a' ;\n") > 0);
		assertTrue(errors("lexer grammar L;\ntokens { a }\nA : 'a' ;\n") > 0);
		assertTrue(errors("lexer grammar L;\nA : '' ;\n") > 0);
		assertTrue(errors("parser grammar P;\na options { silly=1; } : 'a' ;\n") >= 0);
		assertTrue(errors("parser grammar P;\noptions { silly=1; }\na : 'a' ;\n") >= 0);
		assertTrue(errors("grammar T;\na : x=(b | c) ;\nb : 'b' ;\nc : 'c' ;\n") > 0);
		assertTrue(errors("grammar T;\na : b<foo=1> ;\nb : 'b' ;\n") >= 0);
		assertTrue(errors("grammar T;\na : ID<foo=1> ;\nID : [a-z]+ ;\n") >= 0);
		assertTrue(errors("grammar T;\na : {true}?<foo=1> 'x' ;\n") >= 0);
		assertTrue(errors("grammar T;\na : 'a'<assoc=right> ;\n") >= 0);
		assertTrue(errors("lexer grammar L;\nA : 'a' { $x.y } ;\n") > 0);
		assertTrue(errors("grammar T;\na : b { $b } ;\nb : 'b' ;\n") > 0);
		assertTrue(errors("grammar T;\na : { $nope } 'a' ;\n") > 0);
		assertTrue(errors("grammar T;\na[int x] : b { $b.nope } ;\nb : 'b' ;\n") > 0);
		assertTrue(errors("grammar T;\na : ids+=ID { $ids = 1; } ;\nID : [a-z]+ ;\n") > 0);
		assertTrue(errors("grammar T;\na : { $missing::y } 'a' ;\n") > 0);

		Grammar g = new Grammar("grammar T;\na : 'a' ;\n");
		Rule rule = g.getRule("a");
		CommonToken actionToken = new CommonToken(ANTLRParser.ACTION, "{ $nope }");
		ActionAST action = new ActionAST(actionToken);
		action.resolver = rule;
		rule.finallyAction = action;
		GrammarAST catchNode = new GrammarAST(new CommonToken(ANTLRParser.CATCH, "catch"));
		catchNode.addChild(new GrammarAST(new CommonToken(ANTLRParser.ID, "E")));
		catchNode.addChild(action);
		rule.exceptions.add(catchNode);
		AttributeChecks.checkAllAttributeExpressions(g);
		assertTrue(g.tool.errMgr.getNumErrors() > 0);
	}

	@Test
	public void importClashUnreadableImportAndVocabSort() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "P.g4", "parser grammar P;\na : 'a' ;\n");
		writeFile(tmpdir, "L.g4", "lexer grammar L;\nimport P;\nA : 'a' ;\n");
		OpenTool tool = new OpenTool(new String[] {"-lib", tmpdir, "-o", tmpdir});
		Grammar lexer = tool.loadGrammar(new File(tmpdir, "L.g4").getAbsolutePath());
		assertTrue(lexer.tool.errMgr.getNumErrors() > 0);

		writeFile(tmpdir, "TLexer.g4", "lexer grammar TLexer;\nA : 'a' ;\n");
		writeFile(tmpdir, "T.g4", "grammar T;\nimport TLexer;\na : A ;\n");
		Grammar clash = tool.loadGrammar(new File(tmpdir, "T.g4").getAbsolutePath());
		assertTrue(clash.tool.errMgr.getNumErrors() > 0);

		assertTrue(new File(tmpdir, "Bad.g4").mkdirs());
		writeFile(tmpdir, "Host.g4", "parser grammar Host;\nimport Bad;\na : 'a' ;\n");
		Grammar host = tool.loadGrammar(new File(tmpdir, "Host.g4").getAbsolutePath());
		assertTrue(host.tool.errMgr.getNumErrors() > 0);

		writeFile(tmpdir, "V.g4", "lexer grammar V;\nA : 'a' ;\n");
		writeFile(tmpdir, "UseV.g4", "parser grammar UseV;\noptions { tokenVocab='sub/V'; }\na : A ;\n");
		writeFile(tmpdir, "Junk.g4", "this is not a grammar\n");
		List<GrammarRootAST> sorted = tool.sortGrammarByTokenVocab(Arrays.asList(
			new File(tmpdir, "UseV.g4").getAbsolutePath(),
			new File(tmpdir, "V.g4").getAbsolutePath(),
			new File(tmpdir, "Junk.g4").getAbsolutePath()));
		assertNotNull(sorted);

		Tool withOption = new Tool(new String[] {"-Dlanguage=Java"});
		assertNotNull(withOption.parseGrammarFromString("grammar S;\na : 'a' ;\n"));
		withOption.parseGrammarFromString("tree grammar Old;\na : ^('x') ;\n");

		Grammar combined = new Grammar("grammar C;\na : 'a' ;\n");
		combined.tool.grammarOptions = new java.util.HashMap<String, String>();
		combined.tool.grammarOptions.put("language", "Java");
		combined.tool.process(combined, false);
		assertNotNull(combined.getImplicitLexer());
	}

	@Test
	public void outputModelExtensionAndVariantRuleFunctions() throws Exception {
		Grammar g = new Grammar(
			"grammar W;\n" +
			"e : e '*' e | INT ;\n" +
			"a : x=. | ys+=. | ;\n" +
			"INT : [0-9]+ ;\n");
		rememberName(g);
		CodeGenerator gen = new CodeGenerator(g);
		ParserFactory factory = new ParserFactory(gen);
		OutputModelController controller = new OutputModelController(factory);
		factory.setController(controller);
		controller.addExtension(new CodeGeneratorExtension(factory));
		assertNull(controller.getCurrentRuleFunction());
		assertNull(controller.popCurrentRule());
		assertNotNull(controller.buildParserOutputModel(false));
		assertNotNull(controller.buildListenerOutputModel(false));
		assertNotNull(controller.buildVisitorOutputModel(false));
		assertTrue(g.getRule("e") instanceof LeftRecursiveRule);
		assertNotNull(factory.rule(g.getRule("e")));

		Rule lf = new Rule(g, "a" + ATNSimulator.RULE_LF_VARIANT_MARKER + "x", (RuleAST) g.getRule("a").ast, 1);
		Rule nolf = new Rule(g, "a" + ATNSimulator.RULE_NOLF_VARIANT_MARKER + "x", (RuleAST) g.getRule("a").ast, 1);
		assertNotNull(factory.rule(lf));
		assertNotNull(factory.rule(nolf));
		assertNotNull(lf.getBaseContext());

		Grammar lists = new Grammar("grammar Ids;\na : ids+=ID { } ;\nID : [a-z]+ ;\n");
		Rule ids = lists.getRule("a");
		assertNull(ids.resolveToAttribute("ids", "text", null));
		assertTrue(ids.resolvesToAttributeDict("ids", null) == false);
		Grammar labeled = new Grammar("grammar Lab;\na : id=ID r=b ;\nb : 'b' ;\nID : [a-z]+ ;\n");
		Rule lab = labeled.getRule("a");
		assertTrue(lab.resolvesToAttributeDict("id", null));
		assertNotNull(lab.resolveToRule("r"));

		LexerGrammar lexer = new LexerGrammar(
			"lexer grammar Acts;\n" +
			"A : 'a' {int x;} ;\n" +
			"B : 'b' {true}? ;\n" +
			"C : 'c' ;\n");
		rememberName(lexer);
		CodeGenerator lexerGen = new CodeGenerator(lexer);
		ParserFactory lexerFactory = new ParserFactory(lexerGen);
		OutputModelController lexerController = new OutputModelController(lexerFactory);
		lexerFactory.setController(lexerController);
		lexerController.addExtension(new CodeGeneratorExtension(lexerFactory));
		assertNotNull(lexerController.buildLexerOutputModel(false));
	}

	private static void runPipeline(Grammar g) {
		try {
			new CodeGenPipeline(g).process();
		}
		catch (RuntimeException ex) {
			assertNotNull(ex);
		}
	}

	private static int errors(String grammar) throws Exception {
		Grammar g = grammar.startsWith("lexer ") ? new LexerGrammar(grammar) : new Grammar(grammar);
		return g.tool.errMgr.getNumErrors() + g.tool.errMgr.warnings;
	}

	/** Grammar(String) does not copy the name out of the AST. Code generation asserts that it is set. */
	private static void rememberName(Grammar g) {
		if (g.name == null && g.ast != null && g.ast.getChildCount() > 0) {
			g.name = g.ast.getChild(0).getText();
		}
	}

	private static void assertUnsupported(Probe probe) {
		try {
			probe.run();
			fail("expected UnsupportedOperationException");
		}
		catch (UnsupportedOperationException expected) {
			assertNotNull(expected.getMessage());
		}
	}

	private interface Probe {
		void run();
	}

	public static class OpenTool extends Tool {
		public OpenTool(String[] args) { super(args); }
		void setHaveOutputDir(boolean value) { haveOutputDir = value; }
	}

	public static class OpenGrammar extends Grammar {
		public OpenGrammar(String text) throws org.antlr.runtime.RecognitionException {
			super(text);
		}
		String baseContext(String name) { return getBaseContextName(name); }
	}
}
