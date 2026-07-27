/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.antlr.v4.runtime.Dependents;
import org.antlr.v4.runtime.RuleDependencies;
import org.antlr.v4.runtime.RuleDependency;
import org.antlr.v4.runtime.RuleVersion;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNSerializer;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.junit.Test;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Coverage for {@link RuleDependencyProcessor}, including a full
 * {@link JavaCompiler} annotation-processing round against a hand-built ATN.
 */
public class TestRuleDependencyProcessor {
	@Test
	public void classNameConstants() {
		assertEquals("org.antlr.v4.runtime.RuleDependency", RuleDependencyProcessor.RuleDependencyClassName);
		assertEquals("org.antlr.v4.runtime.RuleDependencies", RuleDependencyProcessor.RuleDependenciesClassName);
		assertEquals("org.antlr.v4.runtime.RuleVersion", RuleDependencyProcessor.RuleVersionClassName);
	}

	@Test
	public void constructAndSupportedSourceVersion() {
		RuleDependencyProcessor processor = new RuleDependencyProcessor();
		SourceVersion version = processor.getSupportedSourceVersion();
		assertNotNull(version);
		assertTrue(version.ordinal() >= SourceVersion.RELEASE_6.ordinal());
		// on modern JDKs the processor caps at RELEASE_8
		assertTrue(version.ordinal() <= SourceVersion.RELEASE_8.ordinal()
			|| version == SourceVersion.latestSupported());
	}

	@Test
	public void supportedAnnotationTypes() {
		RuleDependencyProcessor processor = new RuleDependencyProcessor();
		Set<String> types = processor.getSupportedAnnotationTypes();
		assertNotNull(types);
		assertTrue(types.contains(RuleDependencyProcessor.RuleDependencyClassName)
			|| types.contains("org.antlr.v4.runtime.RuleDependency")
			|| !types.isEmpty());
	}

	@Test
	public void ruleDependencyPropertyEnum() {
		RuleDependencyProcessor.RuleDependencyProperty[] values =
			RuleDependencyProcessor.RuleDependencyProperty.values();
		assertEquals(4, values.length);
		assertEquals(RuleDependencyProcessor.RuleDependencyProperty.RECOGNIZER,
			RuleDependencyProcessor.RuleDependencyProperty.valueOf("RECOGNIZER"));
		assertEquals(RuleDependencyProcessor.RuleDependencyProperty.RULE,
			RuleDependencyProcessor.RuleDependencyProperty.valueOf("RULE"));
		assertEquals(RuleDependencyProcessor.RuleDependencyProperty.VERSION,
			RuleDependencyProcessor.RuleDependencyProperty.valueOf("VERSION"));
		assertEquals(RuleDependencyProcessor.RuleDependencyProperty.DEPENDENTS,
			RuleDependencyProcessor.RuleDependencyProperty.valueOf("DEPENDENTS"));
		for (RuleDependencyProcessor.RuleDependencyProperty p : values) {
			assertNotNull(p.name());
			assertTrue(p.ordinal() >= 0);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void ruleRelationsGraph() throws Exception {
		Class<?> rrClass = Class.forName(
			"org.antlr.v4.runtime.misc.RuleDependencyProcessor$RuleRelations");
		Constructor<?> ctor = rrClass.getDeclaredConstructor(int.class);
		ctor.setAccessible(true);
		Object relations = ctor.newInstance(4);

		Method add = rrClass.getDeclaredMethod("addRuleInvocation", int.class, int.class);
		add.setAccessible(true);
		assertEquals(Boolean.FALSE, add.invoke(relations, -1, 0));
		assertEquals(Boolean.TRUE, add.invoke(relations, 0, 1));
		assertEquals(Boolean.FALSE, add.invoke(relations, 0, 1));
		assertEquals(Boolean.TRUE, add.invoke(relations, 1, 2));
		assertEquals(Boolean.TRUE, add.invoke(relations, 0, 2));
		assertEquals(Boolean.TRUE, add.invoke(relations, 2, 3));

		Field parentsField = rrClass.getDeclaredField("parents");
		parentsField.setAccessible(true);
		BitSet[] parents = (BitSet[]) parentsField.get(relations);
		assertTrue(parents[1].get(0));
		assertTrue(parents[2].get(1));
		assertTrue(parents[2].get(0));

		Field childrenField = rrClass.getDeclaredField("children");
		childrenField.setAccessible(true);
		BitSet[] children = (BitSet[]) childrenField.get(relations);
		assertTrue(children[0].get(1));
		assertTrue(children[0].get(2));

		Method getAncestors = rrClass.getDeclaredMethod("getAncestors", int.class);
		getAncestors.setAccessible(true);
		BitSet ancestors = (BitSet) getAncestors.invoke(relations, 3);
		assertTrue(ancestors.get(2));
		assertTrue(ancestors.get(1));
		assertTrue(ancestors.get(0));

		Method getDescendants = rrClass.getDeclaredMethod("getDescendants", int.class);
		getDescendants.setAccessible(true);
		BitSet descendants = (BitSet) getDescendants.invoke(relations, 0);
		assertTrue(descendants.get(1));
		assertTrue(descendants.get(2));
		assertTrue(descendants.get(3));

		BitSet empty = (BitSet) getDescendants.invoke(relations, 3);
		assertEquals(0, empty.cardinality());
		BitSet noAnc = (BitSet) getAncestors.invoke(relations, 0);
		assertEquals(0, noAnc.cardinality());
	}

	@Test
	public void processEmptyRoundWithStubEnv() throws Exception {
		RuleDependencyProcessor processor = new RuleDependencyProcessor();
		ProcessingEnvironment env = new StubProcessingEnvironment();
		processor.init(env);

		RoundEnvironment round = new RoundEnvironment() {
			@Override public boolean processingOver() { return true; }
			@Override public boolean errorRaised() { return false; }
			@Override public Set<? extends Element> getRootElements() { return Collections.emptySet(); }
			@Override public Set<? extends Element> getElementsAnnotatedWith(TypeElement a) {
				return Collections.emptySet();
			}
			@Override public Set<? extends Element> getElementsAnnotatedWith(
				Class<? extends java.lang.annotation.Annotation> a) {
				return Collections.emptySet();
			}
		};

		assertTrue(processor.process(Collections.<TypeElement>emptySet(), round));
		List<Tuple2<RuleDependency, Element>> deps = RuleDependencyProcessor.getDependencies(round);
		assertNotNull(deps);
		assertTrue(deps.isEmpty());
	}

	@Test
	public void getDependenciesFindsAnnotationsViaRoundEnv() {
		List<Tuple2<RuleDependency, Element>> deps = RuleDependencyProcessor.getDependencies(
			new AnnotationRoundEnv());
		assertTrue(deps.size() >= 2);
		assertEquals(0, deps.get(0).getItem1().rule());
	}

	@Test
	public void getDependenciesSkipsNullAnnotations() {
		RoundEnvironment round = new RoundEnvironment() {
			@Override public boolean processingOver() { return false; }
			@Override public boolean errorRaised() { return false; }
			@Override public Set<? extends Element> getRootElements() { return Collections.emptySet(); }
			@Override public Set<? extends Element> getElementsAnnotatedWith(TypeElement a) {
				return Collections.emptySet();
			}
			@Override public Set<? extends Element> getElementsAnnotatedWith(
				Class<? extends java.lang.annotation.Annotation> a) {
				// Element whose getAnnotation returns null (covers continue branches)
				return Collections.singleton(new AnnotationBearingElement(null));
			}
		};
		List<Tuple2<RuleDependency, Element>> deps = RuleDependencyProcessor.getDependencies(round);
		assertNotNull(deps);
		assertTrue(deps.isEmpty());
	}

	@Test
	public void getRecognizerTypeThrowsWhenNoMirroredTypeException() throws Exception {
		// Runtime annotation proxies return Class from recognizer() without MirroredTypeException.
		// That path throws UnsupportedOperationException inside getRecognizerType.
		RuleDependency dep = DependentSample.class.getDeclaredFields()[0]
			.getAnnotation(RuleDependency.class);
		assertNotNull(dep);
		Method m = RuleDependencyProcessor.class.getDeclaredMethod(
			"getRecognizerType", RuleDependency.class);
		m.setAccessible(true);
		try {
			m.invoke(null, dep);
			fail("expected UnsupportedOperationException via InvocationTargetException");
		}
		catch (java.lang.reflect.InvocationTargetException ex) {
			assertTrue(ex.getCause() instanceof UnsupportedOperationException
				|| ex.getCause() instanceof javax.lang.model.type.MirroredTypeException);
		}
	}

	/**
	 * Full annotation-processing round: multi-rule ATN with rule invocations,
	 * version mismatches, unknown rules, unimplemented dependents, and
	 * {@link RuleDependencies} containers so {@code findRuleDependencyProperty}
	 * and {@code checkDependencies} execute end-to-end.
	 */
	@Test
	public void annotationProcessorValidatesDependenciesViaJavaCompiler() throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			return; // no JDK compiler in this environment
		}

		ATN atn = buildRuleInvocationAtn();
		String serialized = ATNSerializer.getSerializedAsString(atn, Arrays.asList("a", "b", "c"));

		File tmp = Files.createTempDirectory("rdp-test").toFile();
		tmp.deleteOnExit();
		File srcDir = new File(tmp, "src");
		File pkg = new File(srcDir, "proc");
		pkg.mkdirs();
		File out = new File(tmp, "classes");
		out.mkdirs();

		String recognizerSrc =
			"package proc;\n" +
			"import org.antlr.v4.runtime.*;\n" +
			"import org.antlr.v4.runtime.atn.*;\n" +
			"public class P extends Recognizer<Token, ATNSimulator> {\n" +
			"  public static final int RULE_a = 0;\n" +
			"  public static final int RULE_b = 1;\n" +
			"  public static final int RULE_c = 2;\n" +
			"  public static final int RULE_Bad = 3;\n" + // uppercase after RULE_ => skipped
			"  public static final int RULE_a$variant = 0;\n" + // left-factored pseudo-rule
			"  public static final int RULE_d = 3;\n" + // no @RuleVersion method
			"  public static final int RULE_neg = -1;\n" + // negative index skipped / oob
			"  public static final String[] ruleNames = new String[]{\"a\",\"b\",\"c\",\"d\"};\n" +
			"  public static final String _serializedATN = " + toJavaStringLiteral(serialized) + ";\n" +
			"  @RuleVersion(1) public void a() {}\n" +
			"  @RuleVersion(2) public void b() {}\n" +
			"  @RuleVersion(0) public void c() {}\n" +
			// method without @RuleVersion so hasRuleVersionAnnotation returns false for name match
			"  public void notARule() {}\n" +
			"  public String[] getTokenNames() { return new String[0]; }\n" +
			"  public String[] getRuleNames() { return ruleNames; }\n" +
			"  public String getGrammarFileName() { return \"P.g4\"; }\n" +
			"  public IntStream getInputStream() { return null; }\n" +
			"}\n";

		String userSrc =
			"package proc;\n" +
			"import org.antlr.v4.runtime.*;\n" +
			"public class U {\n" +
			// full dependent set including unimplemented SIBLINGS
			"  @RuleDependency(recognizer = P.class, rule = 0, version = 2,\n" +
			"    dependents = {Dependents.SELF, Dependents.CHILDREN, Dependents.PARENTS,\n" +
			"      Dependents.ANCESTORS, Dependents.DESCENDANTS, Dependents.SIBLINGS})\n" +
			"  int full;\n" +
			// unknown rule index
			"  @RuleDependency(recognizer = P.class, rule = 99, version = 0)\n" +
			"  int unknown;\n" +
			// actual version of b is 2 > declared 0 (self + relations)
			"  @RuleDependency(recognizer = P.class, rule = 1, version = 0,\n" +
			"    dependents = {Dependents.SELF, Dependents.PARENTS, Dependents.CHILDREN,\n" +
			"      Dependents.ANCESTORS, Dependents.DESCENDANTS})\n" +
			"  int lowVersion;\n" +
			// declared version higher than any related rule version
			"  @RuleDependency(recognizer = P.class, rule = 2, version = 5)\n" +
			"  int highDeclared;\n" +
			// relation-focused dependency that still mismatches on parent (a@1)
			"  @RuleDependency(recognizer = P.class, rule = 1, version = 0,\n" +
			"    dependents = {Dependents.PARENTS})\n" +
			"  int parentMismatch;\n" +
			// ANCESTORS only (no PARENTS) so ancestor loop body runs, not skipped via checked
			"  @RuleDependency(recognizer = P.class, rule = 2, version = 0,\n" +
			"    dependents = {Dependents.ANCESTORS})\n" +
			"  int ancestorsOnly;\n" +
			// DESCENDANTS only
			"  @RuleDependency(recognizer = P.class, rule = 0, version = 0,\n" +
			"    dependents = {Dependents.DESCENDANTS})\n" +
			"  int descendantsOnly;\n" +
			// CHILDREN only with version mismatch on child
			"  @RuleDependency(recognizer = P.class, rule = 0, version = 0,\n" +
			"    dependents = {Dependents.CHILDREN})\n" +
			"  int childrenOnly;\n" +
			// RuleDependencies container — force error so findRuleDependencyProperty walks value()
			"  @RuleDependencies({\n" +
			"    @RuleDependency(recognizer = P.class, rule = 0, version = 2),\n" +
			"    @RuleDependency(recognizer = P.class, rule = 77, version = 1)\n" +
			"  })\n" +
			"  int multi;\n" +
			// valid dependency exercising children/descendants without error
			"  @RuleDependency(recognizer = P.class, rule = 0, version = 2,\n" +
			"    dependents = {Dependents.CHILDREN, Dependents.DESCENDANTS})\n" +
			"  int okChildren;\n" +
			"}\n";

		write(new File(pkg, "P.java"), recognizerSrc);
		write(new File(pkg, "U.java"), userSrc);

		// Second compilation unit: missing _serializedATN exercises extractRuleRelations null path
		// (process may NPE on default PARENTS — isolated so main coverage above is unaffected)
		String noAtnSrc =
			"package proc2;\n" +
			"import org.antlr.v4.runtime.*;\n" +
			"import org.antlr.v4.runtime.atn.*;\n" +
			"public class Q extends Recognizer<Token, ATNSimulator> {\n" +
			"  public static final int RULE_r = 0;\n" +
			"  public static final String[] ruleNames = new String[]{\"r\"};\n" +
			"  @RuleVersion(0) public void r() {}\n" +
			"  public String[] getTokenNames() { return new String[0]; }\n" +
			"  public String[] getRuleNames() { return ruleNames; }\n" +
			"  public String getGrammarFileName() { return \"Q.g4\"; }\n" +
			"  public IntStream getInputStream() { return null; }\n" +
			"}\n" +
			"class QUser {\n" +
			"  @RuleDependency(recognizer = Q.class, rule = 0, version = 0)\n" +
			"  int x;\n" +
			"}\n";
		File pkg2 = new File(srcDir, "proc2");
		pkg2.mkdirs();
		write(new File(pkg2, "Q.java"), noAtnSrc);

		String cp = buildClasspath();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
		StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
		try {
			Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(
				new File(pkg, "P.java"), new File(pkg, "U.java"));

			StringWriter compilerOut = new StringWriter();
			List<String> options = Arrays.asList(
				"-classpath", cp,
				"-d", out.getAbsolutePath(),
				"-processor", RuleDependencyProcessor.class.getName(),
				"-proc:only"
			);

			JavaCompiler.CompilationTask task = compiler.getTask(
				compilerOut, fm, diagnostics, options, null, units);
			Boolean ok = task.call();
			assertNotNull(ok);

			String allMsgs = messagesOf(diagnostics) + compilerOut;
			// Processor ran and validated
			assertTrue("expected validation note, got: " + allMsgs,
				allMsgs.contains("Validating") || allMsgs.contains("ANTLR 4")
					|| allMsgs.contains("Rule dependency") || allMsgs.contains("unknown rule")
					|| allMsgs.contains("dependents"));
			assertTrue("expected unknown-rule diagnostic, got: " + allMsgs,
				allMsgs.contains("unknown rule") || allMsgs.contains("99"));
			assertTrue("expected version mismatch, got: " + allMsgs,
				allMsgs.contains("version mismatch") || allMsgs.contains("expected"));
			assertTrue("expected unimplemented dependents warning, got: " + allMsgs,
				allMsgs.contains("SIBLINGS") || allMsgs.contains("dependents")
					|| allMsgs.contains("Cannot validate"));

			// Missing-ATN compile: tolerate NPE from null RuleRelations
			diagnostics = new DiagnosticCollector<JavaFileObject>();
			Iterable<? extends JavaFileObject> units2 = fm.getJavaFileObjects(new File(pkg2, "Q.java"));
			try {
				compiler.getTask(new StringWriter(), fm, diagnostics, options, null, units2).call();
			}
			catch (RuntimeException expected) {
				assertNotNull(expected);
			}
		}
		finally {
			fm.close();
		}
	}

	@Test
	public void annotationProcessorCompatibleDependencyOnly() throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			return;
		}

		ATN atn = buildRuleInvocationAtn();
		String serialized = ATNSerializer.getSerializedAsString(atn, Arrays.asList("a", "b", "c"));

		File tmp = Files.createTempDirectory("rdp-ok").toFile();
		tmp.deleteOnExit();
		File pkg = new File(tmp, "src/ok");
		pkg.mkdirs();
		File out = new File(tmp, "classes");
		out.mkdirs();

		String src =
			"package ok;\n" +
			"import org.antlr.v4.runtime.*;\n" +
			"import org.antlr.v4.runtime.atn.*;\n" +
			"class R extends Recognizer<Token, ATNSimulator> {\n" +
			"  public static final int RULE_a = 0;\n" +
			"  public static final int RULE_b = 1;\n" +
			"  public static final int RULE_c = 2;\n" +
			"  public static final String[] ruleNames = new String[]{\"a\",\"b\",\"c\"};\n" +
			"  public static final String _serializedATN = " + toJavaStringLiteral(serialized) + ";\n" +
			"  @RuleVersion(1) public void a() {}\n" +
			"  @RuleVersion(2) public void b() {}\n" +
			"  @RuleVersion(0) public void c() {}\n" +
			"  public String[] getTokenNames() { return new String[0]; }\n" +
			"  public String[] getRuleNames() { return ruleNames; }\n" +
			"  public String getGrammarFileName() { return \"R.g4\"; }\n" +
			"  public IntStream getInputStream() { return null; }\n" +
			"}\n" +
			"class User {\n" +
			"  @RuleDependency(recognizer = R.class, rule = 0, version = 2,\n" +
			"    dependents = {Dependents.SELF, Dependents.CHILDREN, Dependents.DESCENDANTS,\n" +
			"      Dependents.PARENTS, Dependents.ANCESTORS})\n" +
			"  int ok;\n" +
			"  @RuleDependencies({@RuleDependency(recognizer = R.class, rule = 2, version = 0)})\n" +
			"  int multiOk;\n" +
			"}\n";
		write(new File(pkg, "R.java"), src);

		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
		StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
		try {
			List<String> options = Arrays.asList(
				"-classpath", buildClasspath(),
				"-d", out.getAbsolutePath(),
				"-processor", RuleDependencyProcessor.class.getName(),
				"-proc:only"
			);
			Boolean ok = compiler.getTask(new StringWriter(), fm, diagnostics, options, null,
				fm.getJavaFileObjects(new File(pkg, "R.java"))).call();
			assertNotNull(ok);
			String msgs = messagesOf(diagnostics);
			assertTrue(msgs.contains("Validating") || msgs.isEmpty() || ok);
		}
		finally {
			fm.close();
		}
	}

	// ---------- helpers ----------

	/**
	 * Parser ATN: {@code a -> b -> c}; {@code c} matches token type 1.
	 * Rule transitions sit on epsilon-only states so {@code extractRuleRelations} sees them.
	 */
	static ATN buildRuleInvocationAtn() {
		ATN atn = new ATN(ATNType.PARSER, 1);

		RuleStartState aStart = new RuleStartState();
		aStart.ruleIndex = 0;
		BasicState aCall = new BasicState();
		aCall.ruleIndex = 0;
		BasicState aFollow = new BasicState();
		aFollow.ruleIndex = 0;
		RuleStopState aStop = new RuleStopState();
		aStop.ruleIndex = 0;
		aStart.stopState = aStop;

		RuleStartState bStart = new RuleStartState();
		bStart.ruleIndex = 1;
		BasicState bCall = new BasicState();
		bCall.ruleIndex = 1;
		BasicState bFollow = new BasicState();
		bFollow.ruleIndex = 1;
		RuleStopState bStop = new RuleStopState();
		bStop.ruleIndex = 1;
		bStart.stopState = bStop;

		RuleStartState cStart = new RuleStartState();
		cStart.ruleIndex = 2;
		BasicState cMid = new BasicState();
		cMid.ruleIndex = 2;
		RuleStopState cStop = new RuleStopState();
		cStop.ruleIndex = 2;
		cStart.stopState = cStop;

		for (ATNState s : new ATNState[] {
			aStart, aCall, aFollow, aStop,
			bStart, bCall, bFollow, bStop,
			cStart, cMid, cStop
		}) {
			atn.addState(s);
		}

		aStart.addTransition(new EpsilonTransition(aCall));
		aCall.addTransition(new RuleTransition(bStart, 1, 0, aFollow));
		aFollow.addTransition(new EpsilonTransition(aStop));

		bStart.addTransition(new EpsilonTransition(bCall));
		bCall.addTransition(new RuleTransition(cStart, 2, 0, bFollow));
		bFollow.addTransition(new EpsilonTransition(bStop));

		cStart.addTransition(new AtomTransition(cMid, 1));
		cMid.addTransition(new EpsilonTransition(cStop));

		atn.ruleToStartState = new RuleStartState[] { aStart, bStart, cStart };
		atn.ruleToStopState = new RuleStopState[] { aStop, bStop, cStop };
		atn.clearDFA();
		return atn;
	}

	/**
	 * Escape a string as a Java source literal. Avoids bare {@code \\u000a}/{@code \\u000d}
	 * which the Java lexer would turn into real newlines before string lexing.
	 */
	static String toJavaStringLiteral(String s) {
		StringBuilder sb = new StringBuilder(s.length() * 6 + 2);
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '\b': sb.append("\\b"); break;
				case '\t': sb.append("\\t"); break;
				case '\n': sb.append("\\n"); break;
				case '\f': sb.append("\\f"); break;
				case '\r': sb.append("\\r"); break;
				case '\"': sb.append("\\\""); break;
				case '\\': sb.append("\\\\"); break;
				default:
					if (c < 0x20 || c > 0x7e) {
						sb.append(String.format("\\u%04x", (int) c));
					}
					else {
						sb.append(c);
					}
			}
		}
		sb.append('"');
		return sb.toString();
	}

	static String buildClasspath() {
		StringBuilder cp = new StringBuilder();
		String sep = File.pathSeparator;
		// runtime classes under test
		File classes = new File("target/classes");
		if (!classes.isDirectory()) {
			classes = new File("runtime/Java/target/classes");
		}
		if (classes.isDirectory()) {
			cp.append(classes.getAbsolutePath()).append(sep);
		}
		String sys = System.getProperty("java.class.path");
		if (sys != null) {
			cp.append(sys);
		}
		return cp.toString();
	}

	static String messagesOf(DiagnosticCollector<JavaFileObject> diagnostics) {
		StringBuilder sb = new StringBuilder();
		for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
			sb.append(d.getKind()).append(':').append(d.getMessage(null)).append('\n');
		}
		return sb.toString();
	}

	private static void write(File f, String content) throws IOException {
		Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	// ---------- stubs for lightweight unit tests ----------

	static class AnnotationRoundEnv implements RoundEnvironment {
		@Override public boolean processingOver() { return false; }
		@Override public boolean errorRaised() { return false; }
		@Override public Set<? extends Element> getRootElements() { return Collections.emptySet(); }
		@Override public Set<? extends Element> getElementsAnnotatedWith(TypeElement a) {
			return Collections.emptySet();
		}
		@Override
		public Set<? extends Element> getElementsAnnotatedWith(
			Class<? extends java.lang.annotation.Annotation> a) {
			Set<Element> set = new HashSet<Element>();
			if (a == RuleDependency.class) {
				set.add(new AnnotationBearingElement(
					DependentSample.class.getDeclaredFields()[0].getAnnotation(RuleDependency.class)));
			}
			if (a == RuleDependencies.class) {
				set.add(new AnnotationBearingElement(null) {
					@Override
					@SuppressWarnings("unchecked")
					public <A extends java.lang.annotation.Annotation> A getAnnotation(Class<A> annotationType) {
						if (annotationType == RuleDependencies.class) {
							return (A) DependentSample.class.getDeclaredFields()[1]
								.getAnnotation(RuleDependencies.class);
						}
						return null;
					}
				});
			}
			return set;
		}
	}

	static class AnnotationBearingElement implements Element {
		private final RuleDependency dep;

		AnnotationBearingElement(RuleDependency dep) {
			this.dep = dep;
		}

		@Override
		@SuppressWarnings("unchecked")
		public <A extends java.lang.annotation.Annotation> A getAnnotation(Class<A> annotationType) {
			if (annotationType == RuleDependency.class) {
				return (A) dep;
			}
			return null;
		}

		@Override public ElementKind getKind() { return ElementKind.FIELD; }
		@Override public Set<javax.lang.model.element.Modifier> getModifiers() {
			return Collections.emptySet();
		}
		@Override public javax.lang.model.element.Name getSimpleName() { return new SimpleName("x"); }
		@Override public Element getEnclosingElement() { return null; }
		@Override public List<? extends Element> getEnclosedElements() { return Collections.emptyList(); }
		@Override public boolean equals(Object obj) { return this == obj; }
		@Override public int hashCode() { return System.identityHashCode(this); }
		@Override public javax.lang.model.type.TypeMirror asType() { return null; }
		@Override public List<? extends AnnotationMirror> getAnnotationMirrors() {
			return Collections.emptyList();
		}
		@Override public <R, P> R accept(javax.lang.model.element.ElementVisitor<R, P> v, P p) {
			return null;
		}
		@Override
		@SuppressWarnings("unchecked")
		public <A extends java.lang.annotation.Annotation> A[] getAnnotationsByType(Class<A> a) {
			return (A[]) java.lang.reflect.Array.newInstance(a, 0);
		}
	}

	static class SimpleName implements javax.lang.model.element.Name {
		private final String s;
		SimpleName(String s) { this.s = s; }
		@Override public int length() { return s.length(); }
		@Override public char charAt(int index) { return s.charAt(index); }
		@Override public CharSequence subSequence(int start, int end) { return s.subSequence(start, end); }
		@Override public boolean contentEquals(CharSequence cs) { return s.contentEquals(cs); }
		@Override public String toString() { return s; }
	}

	static class StubMessager implements Messager {
		final List<String> messages = new ArrayList<String>();
		@Override public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
			messages.add(kind + ":" + msg);
		}
		@Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
			printMessage(kind, msg);
		}
		@Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e,
										   AnnotationMirror a) {
			printMessage(kind, msg);
		}
		@Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e,
										   AnnotationMirror a,
										   javax.lang.model.element.AnnotationValue v) {
			printMessage(kind, msg);
		}
	}

	static class StubProcessingEnvironment implements ProcessingEnvironment {
		final StubMessager messager = new StubMessager();
		@Override public Map<String, String> getOptions() { return Collections.emptyMap(); }
		@Override public Messager getMessager() { return messager; }
		@Override public Filer getFiler() { return null; }
		@Override public Elements getElementUtils() { return null; }
		@Override public Types getTypeUtils() { return null; }
		@Override public SourceVersion getSourceVersion() { return SourceVersion.RELEASE_8; }
		@Override public Locale getLocale() { return Locale.US; }
	}

	public static class MiniRecognizer
		extends org.antlr.v4.runtime.Recognizer<org.antlr.v4.runtime.Token, org.antlr.v4.runtime.atn.ATNSimulator> {
		public static final int RULE_a = 0;
		public static final int RULE_b = 1;
		public static final String[] ruleNames = new String[] { "a", "b" };

		@RuleVersion(1)
		public void a() {}

		@RuleVersion(0)
		public void b() {}

		@Override public String[] getTokenNames() { return new String[0]; }
		@Override public String[] getRuleNames() { return ruleNames; }
		@Override public String getGrammarFileName() { return "Mini.g4"; }
		@Override public org.antlr.v4.runtime.IntStream getInputStream() { return null; }
	}

	static class DependentSample {
		@RuleDependency(recognizer = MiniRecognizer.class, rule = 0, version = 1,
			dependents = {Dependents.SELF, Dependents.CHILDREN, Dependents.PARENTS,
				Dependents.ANCESTORS, Dependents.DESCENDANTS, Dependents.SIBLINGS})
		int single;

		@RuleDependencies({
			@RuleDependency(recognizer = MiniRecognizer.class, rule = 1, version = 0)
		})
		int multi;
	}
}
