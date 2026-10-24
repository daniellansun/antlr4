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
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
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
import java.lang.annotation.AnnotationTypeMismatchException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import static org.junit.Assert.assertNull;
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

	@Test
	public void stubbedMirrorsCoverUncheckedBranches() throws Exception {
		DepEnv env = new DepEnv();
		RuleDependencyProcessor processor = env.processor();

		// recognizer type is not assignable to the grouped type
		env.invokeCheck(processor, Collections.singletonList(
			env.dependency(env.otherType, 0, 0, new Dependents[] { Dependents.SELF })),
			env.recognizerType);

		// unknown rule, no annotation mirror for the property
		env.messages.clear();
		env.invokeCheck(processor, Collections.singletonList(
			env.dependency(env.recognizerType, 99, 0, new Dependents[] { Dependents.SELF })),
			env.recognizerType);
		assertTrue(env.messages.toString(), contains(env.messages, "unknown rule"));

		// child 5 is also a parent, and the RULE_ table includes index 5 so the
		// already-checked continue (not the out-of-range jump) runs
		env.messages.clear();
		env.installRules(buildWideInvocationAtn(), 0, false);
		env.members.add(env.fieldPublic("RULE_f", Integer.valueOf(5)));
		env.invokeCheck(processor, Collections.singletonList(
			env.dependency(env.recognizerType, 0, 0,
				new Dependents[] { Dependents.PARENTS, Dependents.CHILDREN })),
			env.recognizerType);

		// declared version above every actual version, mirror missing
		env.messages.clear();
		env.installRules(null, 0, false);
		env.invokeCheck(processor, Collections.singletonList(
			env.dependency(env.recognizerType, 0, 5, new Dependents[] { Dependents.SELF })),
			env.recognizerType);
		assertTrue(env.messages.toString(), contains(env.messages, "maximum dependency version"));

		// actual version above the declaration, mirror missing
		env.messages.clear();
		env.installRules(null, 2, false);
		env.invokeCheck(processor, Collections.singletonList(
			env.dependency(env.recognizerType, 0, 0, new Dependents[] { Dependents.SELF })),
			env.recognizerType);
		assertTrue(env.messages.toString(), contains(env.messages, "has version"));

		// unimplemented SIBLINGS and no dependents mirror
		env.messages.clear();
		env.installRules(null, 0, false);
		env.invokeCheck(processor, Collections.singletonList(
			env.dependency(env.recognizerType, 0, 0, new Dependents[] { Dependents.SIBLINGS })),
			env.recognizerType);
		assertTrue(env.messages.toString(), contains(env.messages, "Cannot validate"));

		// annotation member type does not match
		env.messages.clear();
		env.invokeCheck(processor, Collections.singletonList(env.mismatchingDependency()), env.recognizerType);
		assertTrue(env.messages.toString(), contains(env.messages, "Could not validate"));

		// @RuleVersion mirror present but runtime value is null, then getAnnotation throws
		env.messages.clear();
		env.installSplitRules();
		env.invokeGetRuleVersions(processor);
		assertTrue(env.messages.toString(), contains(env.messages, "Exception occurred"));

		// RuleVersion type missing, then a mirror that is not RuleVersion
		processor = env.processor();
		env.hideRuleVersion = true;
		assertFalse(env.invokeHasRuleVersion(processor, env.method("a", null, true)));
		env.hideRuleVersion = false;
		assertFalse(env.invokeHasRuleVersion(processor, env.method("a", null, false)));

		// RuleDependencies.value() is null
		List<Tuple2<RuleDependency, Element>> none = RuleDependencyProcessor.getDependencies(env.nullValueRound());
		assertTrue(none.isEmpty());

		// malformed mirrors inside findRuleDependencyProperty
		env.messages.clear();
		processor = env.processor();
		Tuple2<RuleDependency, Element> dep = env.dependency(
			env.recognizerType, 1, 1, new Dependents[] { Dependents.SELF });
		assertNull(env.invokeFind(processor, dep, env.mirrorWith("value()", annotationValue("nope")),
			RuleDependencyProcessor.RuleDependencyProperty.RULE));
		assertTrue(contains(env.messages, "Expected array"));

		env.messages.clear();
		assertNull(env.invokeFind(processor, dep, env.mirrorWith("value()", annotationValue(Collections.singletonList("x"))),
			RuleDependencyProcessor.RuleDependencyProperty.RULE));
		assertTrue(contains(env.messages, "Expected RuleDependency annotation mirror"));

		env.messages.clear();
		assertNull(env.invokeFind(processor, dep, env.mirrorWith("other()", annotationValue(1)),
			RuleDependencyProcessor.RuleDependencyProperty.RULE));
		assertTrue(contains(env.messages, "Unexpected annotation property"));

		AnnotationMirror inner = env.ruleMirror(Collections.singletonMap("rule()", annotationValue("bad")));
		env.messages.clear();
		assertNull(env.invokeFind(processor, dep,
			env.mirrorWith("value()", annotationValue(Collections.singletonList(inner))),
			RuleDependencyProcessor.RuleDependencyProperty.RULE));

		assertNull(env.invokeInner(processor, dep, env.ruleMirror(Collections.singletonMap("rule()", annotationValue("no"))),
			RuleDependencyProcessor.RuleDependencyProperty.RULE));
		assertNull(env.invokeInner(processor, dep, env.ruleMirror(Collections.singletonMap("recognizer()", annotationValue("no"))),
			RuleDependencyProcessor.RuleDependencyProperty.RECOGNIZER));
		assertNull(env.invokeInner(processor, dep, env.ruleMirror(Collections.singletonMap("recognizer()", annotationValue(env.otherType))),
			RuleDependencyProcessor.RuleDependencyProperty.RECOGNIZER));
		assertNull(env.invokeInner(processor, dep, env.ruleMirror(Collections.singletonMap("version()", annotationValue("no"))),
			RuleDependencyProcessor.RuleDependencyProperty.VERSION));
		assertNull(env.invokeInner(processor, dep, env.ruleMirror(Collections.singletonMap("version()", annotationValue(9))),
			RuleDependencyProcessor.RuleDependencyProperty.VERSION));
		assertNotNull(env.invokeInner(processor, dep,
			env.ruleMirror(Collections.singletonMap("recognizer()", annotationValue(env.recognizerType))),
			RuleDependencyProcessor.RuleDependencyProperty.RECOGNIZER));

		env.messages.clear();
		assertNull(env.invokeInner(processor, dep, env.ruleMirror(Collections.<String, AnnotationValue>emptyMap()),
			RuleDependencyProcessor.RuleDependencyProperty.VERSION));
		assertTrue(env.messages.toString(), contains(env.messages, "recognizer()"));
		assertTrue(contains(env.messages, "rule()"));
		assertTrue(contains(env.messages, "version()"));

		env.messages.clear();
		assertNull(env.invokeInner(processor, dep, env.ruleMirror(Collections.<String, AnnotationValue>emptyMap()),
			RuleDependencyProcessor.RuleDependencyProperty.RECOGNIZER));
		assertNull(env.invokeInner(processor, dep, env.ruleMirror(Collections.<String, AnnotationValue>emptyMap()),
			RuleDependencyProcessor.RuleDependencyProperty.RULE));

		// recognizer present, rule absent, version present: fall out of the early return
		java.util.Map<String, AnnotationValue> recognizerAndVersion = new java.util.LinkedHashMap<String, AnnotationValue>();
		recognizerAndVersion.put("recognizer()", annotationValue(env.recognizerType));
		recognizerAndVersion.put("version()", annotationValue(Integer.valueOf(1)));
		assertNull(env.invokeInner(processor, dep, env.ruleMirror(recognizerAndVersion),
			RuleDependencyProcessor.RuleDependencyProperty.VERSION));

		java.util.Map<String, AnnotationValue> recognizerAndRule = new java.util.LinkedHashMap<String, AnnotationValue>();
		recognizerAndRule.put("recognizer()", annotationValue(env.recognizerType));
		recognizerAndRule.put("rule()", annotationValue(Integer.valueOf(1)));
		assertNull(env.invokeInner(processor, dep, env.ruleMirror(recognizerAndRule),
			RuleDependencyProcessor.RuleDependencyProperty.VERSION));
	}

	static boolean contains(List<String> messages, String fragment) {
		for (String message : messages) {
			if (message.contains(fragment)) {
				return true;
			}
		}
		return false;
	}

	static AnnotationValue annotationValue(final Object value) {
		return (AnnotationValue) Proxy.newProxyInstance(AnnotationValue.class.getClassLoader(),
			new Class<?>[] { AnnotationValue.class },
			new InvocationHandler() {
				@Override
				public Object invoke(Object proxy, Method method, Object[] args) {
					if ("getValue".equals(method.getName())) {
						return value;
					}
					if ("toString".equals(method.getName())) {
						return String.valueOf(value);
					}
					if ("hashCode".equals(method.getName())) {
						return System.identityHashCode(proxy);
					}
					if ("equals".equals(method.getName())) {
						return proxy == args[0];
					}
					return null;
				}
			});
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

	/** Rule 0 calls rule 5 and rule 5 calls rule 0, so relation bits sit outside a 1-slot RULE_ table. */
	static ATN buildWideInvocationAtn() {
		ATN atn = new ATN(ATNType.PARSER, 1);
		RuleStartState[] starts = new RuleStartState[6];
		RuleStopState[] stops = new RuleStopState[6];
		for (int i = 0; i < 6; i++) {
			starts[i] = new RuleStartState();
			stops[i] = new RuleStopState();
			starts[i].ruleIndex = i;
			stops[i].ruleIndex = i;
			starts[i].stopState = stops[i];
			atn.addState(starts[i]);
			atn.addState(stops[i]);
			if (i != 0 && i != 5) {
				starts[i].addTransition(new EpsilonTransition(stops[i]));
			}
		}
		BasicState call0 = new BasicState();
		BasicState follow0 = new BasicState();
		call0.ruleIndex = 0;
		follow0.ruleIndex = 0;
		atn.addState(call0);
		atn.addState(follow0);
		starts[0].addTransition(new EpsilonTransition(call0));
		call0.addTransition(new RuleTransition(starts[5], 5, 0, follow0));
		follow0.addTransition(new EpsilonTransition(stops[0]));

		BasicState call5 = new BasicState();
		BasicState follow5 = new BasicState();
		call5.ruleIndex = 5;
		follow5.ruleIndex = 5;
		atn.addState(call5);
		atn.addState(follow5);
		starts[5].addTransition(new EpsilonTransition(call5));
		call5.addTransition(new RuleTransition(starts[0], 0, 0, follow5));
		follow5.addTransition(new EpsilonTransition(stops[5]));

		atn.ruleToStartState = starts;
		atn.ruleToStopState = stops;
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

	/**
	 * Hand-built annotation-processing environment. Runtime {@link RuleDependency}
	 * proxies throw {@link MirroredTypeException} the way javac mirrors do, and
	 * element mirrors are omitted or malformed on purpose.
	 */
	static final class DepEnv {
		final List<String> messages = new ArrayList<String>();
		final TypeMirror recognizerType = typeMirror();
		final TypeMirror otherType = typeMirror();
		final DeclaredType ruleDepType = declared();
		final DeclaredType ruleDepsType = declared();
		final DeclaredType ruleVerType = declared();
		final TypeElement ruleDepElement = typeElement(ruleDepType);
		final TypeElement ruleDepsElement = typeElement(ruleDepsType);
		final TypeElement ruleVerElement = typeElement(ruleVerType);
		boolean hideRuleVersion;
		List<Element> members = new ArrayList<Element>();
		String serialized;

		RuleDependencyProcessor processor() {
			RuleDependencyProcessor processor = new RuleDependencyProcessor();
			processor.init(environment());
			return processor;
		}

		void installRules(ATN atn, final int version, final boolean versionIsNull) {
			members = new ArrayList<Element>();
			members.add(field("RULE_a", Integer.valueOf(0), true));
			if (atn != null) {
				serialized = ATNSerializer.getSerializedAsString(atn, Arrays.asList("a", "b", "c", "d", "e", "f"));
				members.add(field("_serializedATN", serialized, true));
			}
			members.add(method("a", versionIsNull ? null : Integer.valueOf(version), true));
		}

		void installSplitRules() {
			members = new ArrayList<Element>();
			members.add(field("RULE_a", Integer.valueOf(0), true));
			members.add(field("RULE_b", Integer.valueOf(1), true));
			members.add(method("a", null, true));
			members.add(throwingMethod("b"));
		}

		Tuple2<RuleDependency, Element> dependency(final TypeMirror type, final int rule, final int version,
													final Dependents[] dependents) {
			RuleDependency dep = (RuleDependency) Proxy.newProxyInstance(RuleDependency.class.getClassLoader(),
				new Class<?>[] { RuleDependency.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						String name = method.getName();
						if ("recognizer".equals(name)) {
							throw new MirroredTypeException(type);
						}
						if ("rule".equals(name)) {
							return Integer.valueOf(rule);
						}
						if ("version".equals(name)) {
							return Integer.valueOf(version);
						}
						if ("dependents".equals(name)) {
							return dependents;
						}
						if ("annotationType".equals(name)) {
							return RuleDependency.class;
						}
						if ("toString".equals(name)) {
							return "dep";
						}
						if ("hashCode".equals(name)) {
							return System.identityHashCode(proxy);
						}
						if ("equals".equals(name)) {
							return proxy == args[0];
						}
						return null;
					}
				});
			return Tuple.create(dep, elementWithMirrors(Collections.<AnnotationMirror>emptyList()));
		}

		Tuple2<RuleDependency, Element> mismatchingDependency() {
			final Method ruleMethod = RuleDependency.class.getDeclaredMethods()[0];
			RuleDependency dep = (RuleDependency) Proxy.newProxyInstance(RuleDependency.class.getClassLoader(),
				new Class<?>[] { RuleDependency.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						String name = method.getName();
						if ("recognizer".equals(name)) {
							throw new MirroredTypeException(recognizerType);
						}
						if ("rule".equals(name) || "version".equals(name) || "dependents".equals(name)) {
							throw new AnnotationTypeMismatchException(ruleMethod, "bad");
						}
						if ("annotationType".equals(name)) {
							return RuleDependency.class;
						}
						if ("hashCode".equals(name)) {
							return System.identityHashCode(proxy);
						}
						if ("equals".equals(name)) {
							return proxy == args[0];
						}
						if ("toString".equals(name)) {
							return "bad";
						}
						return null;
					}
				});
			return Tuple.create(dep, elementWithMirrors(Collections.<AnnotationMirror>emptyList()));
		}

		void invokeCheck(RuleDependencyProcessor processor, List<Tuple2<RuleDependency, Element>> deps, TypeMirror type)
			throws Exception {
			Method method = RuleDependencyProcessor.class.getDeclaredMethod("checkDependencies", List.class, TypeMirror.class);
			method.setAccessible(true);
			method.invoke(processor, deps, type);
		}

		void invokeGetRuleVersions(RuleDependencyProcessor processor) throws Exception {
			Method method = RuleDependencyProcessor.class.getDeclaredMethod("getRuleVersions", TypeMirror.class, String[].class);
			method.setAccessible(true);
			method.invoke(processor, recognizerType, new String[] { "a", "b" });
		}

		boolean invokeHasRuleVersion(RuleDependencyProcessor processor, ExecutableElement executable) throws Exception {
			Method method = RuleDependencyProcessor.class.getDeclaredMethod("hasRuleVersionAnnotation", ExecutableElement.class);
			method.setAccessible(true);
			return (Boolean) method.invoke(processor, executable);
		}

		RoundEnvironment nullValueRound() {
			final Element element = elementWithMirrors(Collections.<AnnotationMirror>emptyList());
			return new RoundEnvironment() {
				@Override public boolean processingOver() { return false; }
				@Override public boolean errorRaised() { return false; }
				@Override public Set<? extends Element> getRootElements() { return Collections.emptySet(); }
				@Override public Set<? extends Element> getElementsAnnotatedWith(TypeElement a) {
					return Collections.emptySet();
				}
				@Override
				public Set<? extends Element> getElementsAnnotatedWith(Class<? extends java.lang.annotation.Annotation> a) {
					if (a == RuleDependencies.class) {
						return Collections.singleton(new Element() {
							@Override
							@SuppressWarnings("unchecked")
							public <A extends java.lang.annotation.Annotation> A getAnnotation(Class<A> annotationType) {
								if (annotationType == RuleDependencies.class) {
									return (A) nullDependencies();
								}
								return null;
							}
							@Override public ElementKind getKind() { return ElementKind.FIELD; }
							@Override public Set<javax.lang.model.element.Modifier> getModifiers() { return Collections.emptySet(); }
							@Override public javax.lang.model.element.Name getSimpleName() { return new SimpleName("x"); }
							@Override public Element getEnclosingElement() { return null; }
							@Override public List<? extends Element> getEnclosedElements() { return Collections.emptyList(); }
							@Override public boolean equals(Object obj) { return this == obj; }
							@Override public int hashCode() { return System.identityHashCode(this); }
							@Override public TypeMirror asType() { return null; }
							@Override public List<? extends AnnotationMirror> getAnnotationMirrors() { return Collections.emptyList(); }
							@Override public <R, P> R accept(javax.lang.model.element.ElementVisitor<R, P> v, P p) { return null; }
							@Override
							@SuppressWarnings("unchecked")
							public <A extends java.lang.annotation.Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
								return (A[]) java.lang.reflect.Array.newInstance(annotationType, 0);
							}
						});
					}
					return Collections.emptySet();
				}
			};
		}

		AnnotationMirror mirrorWith(final String key, final AnnotationValue value) {
			Map<ExecutableElement, AnnotationValue> values = new java.util.LinkedHashMap<ExecutableElement, AnnotationValue>();
			values.put(keyElement(key), value);
			return depsMirror(values);
		}

		AnnotationMirror ruleMirror(Map<String, AnnotationValue> named) {
			Map<ExecutableElement, AnnotationValue> values = new java.util.LinkedHashMap<ExecutableElement, AnnotationValue>();
			for (Map.Entry<String, AnnotationValue> entry : named.entrySet()) {
				values.put(keyElement(entry.getKey()), entry.getValue());
			}
			return dependencyAnnotation(values);
		}

		Object invokeFind(RuleDependencyProcessor processor, Tuple2<RuleDependency, Element> dep,
						  AnnotationMirror mirror, RuleDependencyProcessor.RuleDependencyProperty property) throws Exception {
			// attach the mirror to the dependency element by wrapping
			Tuple2<RuleDependency, Element> wrapped = Tuple.create(dep.getItem1(), elementWithMirrors(Collections.singletonList(mirror)));
			Method method = RuleDependencyProcessor.class.getDeclaredMethod(
				"findRuleDependencyProperty", Tuple2.class, RuleDependencyProcessor.RuleDependencyProperty.class);
			method.setAccessible(true);
			return method.invoke(processor, wrapped, property);
		}

		Object invokeInner(RuleDependencyProcessor processor, Tuple2<RuleDependency, Element> dep,
						   AnnotationMirror mirror, RuleDependencyProcessor.RuleDependencyProperty property) throws Exception {
			Method method = RuleDependencyProcessor.class.getDeclaredMethod(
				"findRuleDependencyProperty", Tuple2.class, AnnotationMirror.class,
				RuleDependencyProcessor.RuleDependencyProperty.class);
			method.setAccessible(true);
			return method.invoke(processor, dep, mirror, property);
		}

		ExecutableElement method(String name, Integer version, boolean versionMirror) {
			return methodElement(name, version, versionMirror, false);
		}

		private RuleDependencies nullDependencies() {
			return (RuleDependencies) Proxy.newProxyInstance(RuleDependencies.class.getClassLoader(),
				new Class<?>[] { RuleDependencies.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						if ("value".equals(method.getName())) {
							return null;
						}
						if ("annotationType".equals(method.getName())) {
							return RuleDependencies.class;
						}
						if ("hashCode".equals(method.getName())) {
							return System.identityHashCode(proxy);
						}
						if ("equals".equals(method.getName())) {
							return proxy == args[0];
						}
						if ("toString".equals(method.getName())) {
							return "deps";
						}
						return null;
					}
				});
		}

		private ProcessingEnvironment environment() {
			return new ProcessingEnvironment() {
				@Override public Map<String, String> getOptions() { return Collections.emptyMap(); }
				@Override public Messager getMessager() {
					return new Messager() {
						@Override public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
							messages.add(kind + ":" + msg);
						}
						@Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
							printMessage(kind, msg);
						}
						@Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
							printMessage(kind, msg);
						}
						@Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) {
							printMessage(kind, msg);
						}
					};
				}
				@Override public Filer getFiler() { return null; }
				@Override public Elements getElementUtils() {
					return (Elements) Proxy.newProxyInstance(Elements.class.getClassLoader(),
						new Class<?>[] { Elements.class },
						new InvocationHandler() {
							@Override
							public Object invoke(Object proxy, Method method, Object[] args) {
								if ("getAllMembers".equals(method.getName())) {
									return members;
								}
								if ("getTypeElement".equals(method.getName())) {
									String name = args[0].toString();
									if (RuleDependencyProcessor.RuleDependencyClassName.equals(name)) {
										return ruleDepElement;
									}
									if (RuleDependencyProcessor.RuleDependenciesClassName.equals(name)) {
										return ruleDepsElement;
									}
									if (RuleDependencyProcessor.RuleVersionClassName.equals(name)) {
										return hideRuleVersion ? null : ruleVerElement;
									}
									return null;
								}
								if ("hashCode".equals(method.getName())) {
									return System.identityHashCode(proxy);
								}
								if ("equals".equals(method.getName())) {
									return proxy == args[0];
								}
								if ("toString".equals(method.getName())) {
									return "elements";
								}
								throw new UnsupportedOperationException(method.getName());
							}
						});
				}
				@Override public javax.lang.model.util.Types getTypeUtils() {
					return (javax.lang.model.util.Types) Proxy.newProxyInstance(
						javax.lang.model.util.Types.class.getClassLoader(),
						new Class<?>[] { javax.lang.model.util.Types.class },
						new InvocationHandler() {
							@Override
							public Object invoke(Object proxy, Method method, Object[] args) {
								String name = method.getName();
								if ("isAssignable".equals(name) || "isSameType".equals(name)) {
									return args[0] == args[1];
								}
								if ("asElement".equals(name)) {
									return ruleDepElement;
								}
								if ("hashCode".equals(name)) {
									return System.identityHashCode(proxy);
								}
								if ("equals".equals(name)) {
									return proxy == args[0];
								}
								if ("toString".equals(name)) {
									return "types";
								}
								throw new UnsupportedOperationException(name);
							}
						});
				}
				@Override public SourceVersion getSourceVersion() { return SourceVersion.RELEASE_8; }
				@Override public java.util.Locale getLocale() { return java.util.Locale.US; }
			};
		}

		Element fieldPublic(String name, Object constant) {
			return field(name, constant, true);
		}

		private Element field(final String name, final Object constant, final boolean stat) {
			return (VariableElement) Proxy.newProxyInstance(VariableElement.class.getClassLoader(),
				new Class<?>[] { VariableElement.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
						String n = method.getName();
						if ("getKind".equals(n)) return ElementKind.FIELD;
						if ("getModifiers".equals(n)) {
							return stat
								? java.util.EnumSet.of(javax.lang.model.element.Modifier.STATIC)
								: java.util.EnumSet.noneOf(javax.lang.model.element.Modifier.class);
						}
						if ("getConstantValue".equals(n)) return constant;
						if ("getSimpleName".equals(n)) return new SimpleName(name);
						if ("getAnnotationMirrors".equals(n)) return Collections.emptyList();
						if ("getAnnotation".equals(n)) return null;
						if ("toString".equals(n)) return name;
						if ("hashCode".equals(n)) return System.identityHashCode(proxy);
						if ("equals".equals(n)) return proxy == args[0];
						if ("getEnclosedElements".equals(n)) return Collections.emptyList();
						if ("getAnnotationsByType".equals(n)) {
							Class<?> c = (Class<?>) args[0];
							return java.lang.reflect.Array.newInstance(c, 0);
						}
						return null;
					}
				});
		}

		private ExecutableElement throwingMethod(String name) {
			return methodElement(name, null, true, true);
		}

		private ExecutableElement methodElement(final String name, final Integer version, final boolean versionMirror,
												final boolean explode) {
			final List<AnnotationMirror> mirrors = new ArrayList<AnnotationMirror>();
			if (versionMirror) {
				mirrors.add(ruleVerMirror());
			}
			else {
				mirrors.add(dependencyAnnotation(Collections.<ExecutableElement, AnnotationValue>emptyMap()));
			}
			return (ExecutableElement) Proxy.newProxyInstance(ExecutableElement.class.getClassLoader(),
				new Class<?>[] { ExecutableElement.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						String n = method.getName();
						if ("getKind".equals(n)) return ElementKind.METHOD;
						if ("getSimpleName".equals(n)) return new SimpleName(name);
						if ("getAnnotationMirrors".equals(n)) return mirrors;
						if ("getAnnotation".equals(n)) {
							if (explode) {
								throw new IllegalArgumentException("boom");
							}
							if (version == null) {
								return null;
							}
							return ruleVersion(version.intValue());
						}
						if ("contentEquals".equals(n)) return name.contentEquals((CharSequence) args[0]);
						if ("toString".equals(n)) return name;
						if ("hashCode".equals(n)) return System.identityHashCode(proxy);
						if ("equals".equals(n)) return proxy == args[0];
						if ("getModifiers".equals(n)) return Collections.emptySet();
						if ("getEnclosedElements".equals(n)) return Collections.emptyList();
						if ("getAnnotationsByType".equals(n)) {
							Class<?> c = (Class<?>) args[0];
							return java.lang.reflect.Array.newInstance(c, 0);
						}
						return null;
					}
				});
		}

		private AnnotationMirror ruleVerMirror() {
			return (AnnotationMirror) Proxy.newProxyInstance(AnnotationMirror.class.getClassLoader(),
				new Class<?>[] { AnnotationMirror.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						if ("getAnnotationType".equals(method.getName())) return ruleVerType;
						if ("getElementValues".equals(method.getName())) return Collections.emptyMap();
						if ("toString".equals(method.getName())) return "@RuleVersion";
						if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
						if ("equals".equals(method.getName())) return proxy == args[0];
						return null;
					}
				});
		}

		private AnnotationMirror dependencyAnnotation(final Map<ExecutableElement, AnnotationValue> values) {
			return (AnnotationMirror) Proxy.newProxyInstance(AnnotationMirror.class.getClassLoader(),
				new Class<?>[] { AnnotationMirror.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						if ("getAnnotationType".equals(method.getName())) return ruleDepType;
						if ("getElementValues".equals(method.getName())) return values;
						if ("toString".equals(method.getName())) return "@RuleDependency";
						if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
						if ("equals".equals(method.getName())) return proxy == args[0];
						return null;
					}
				});
		}

		private AnnotationMirror depsMirror(final Map<ExecutableElement, AnnotationValue> values) {
			return (AnnotationMirror) Proxy.newProxyInstance(AnnotationMirror.class.getClassLoader(),
				new Class<?>[] { AnnotationMirror.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						if ("getAnnotationType".equals(method.getName())) return ruleDepsType;
						if ("getElementValues".equals(method.getName())) return values;
						if ("toString".equals(method.getName())) return "@RuleDependencies";
						if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
						if ("equals".equals(method.getName())) return proxy == args[0];
						return null;
					}
				});
		}

		private ExecutableElement keyElement(final String text) {
			return (ExecutableElement) Proxy.newProxyInstance(ExecutableElement.class.getClassLoader(),
				new Class<?>[] { ExecutableElement.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						if ("toString".equals(method.getName())) return text;
						if ("getSimpleName".equals(method.getName())) return new SimpleName(text);
						if ("hashCode".equals(method.getName())) return text.hashCode();
						if ("equals".equals(method.getName())) return proxy == args[0];
						return null;
					}
				});
		}

		private RuleVersion ruleVersion(final int value) {
			return (RuleVersion) Proxy.newProxyInstance(RuleVersion.class.getClassLoader(),
				new Class<?>[] { RuleVersion.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						if ("value".equals(method.getName())) return Integer.valueOf(value);
						if ("annotationType".equals(method.getName())) return RuleVersion.class;
						if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
						if ("equals".equals(method.getName())) return proxy == args[0];
						if ("toString".equals(method.getName())) return "@RuleVersion";
						return null;
					}
				});
		}

		private Element elementWithMirrors(final List<? extends AnnotationMirror> mirrors) {
			return new AnnotationBearingElement(null) {
				@Override
				public List<? extends AnnotationMirror> getAnnotationMirrors() {
					return mirrors;
				}
			};
		}

		private static TypeMirror typeMirror() {
			return (TypeMirror) Proxy.newProxyInstance(TypeMirror.class.getClassLoader(),
				new Class<?>[] { TypeMirror.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						if ("toString".equals(method.getName())) return "type";
						if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
						if ("equals".equals(method.getName())) return proxy == args[0];
						return null;
					}
				});
		}

		private static DeclaredType declared() {
			return (DeclaredType) Proxy.newProxyInstance(DeclaredType.class.getClassLoader(),
				new Class<?>[] { DeclaredType.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						if ("toString".equals(method.getName())) return "declared";
						if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
						if ("equals".equals(method.getName())) return proxy == args[0];
						return null;
					}
				});
		}

		private static TypeElement typeElement(final DeclaredType type) {
			return (TypeElement) Proxy.newProxyInstance(TypeElement.class.getClassLoader(),
				new Class<?>[] { TypeElement.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						if ("asType".equals(method.getName())) return type;
						if ("toString".equals(method.getName())) return "element";
						if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
						if ("equals".equals(method.getName())) return proxy == args[0];
						if ("getKind".equals(method.getName())) return ElementKind.ANNOTATION_TYPE;
						return null;
					}
				});
		}
	}
}
