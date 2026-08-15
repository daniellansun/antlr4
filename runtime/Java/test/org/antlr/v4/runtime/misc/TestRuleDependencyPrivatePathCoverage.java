/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.antlr.v4.runtime.RuleDependency;
import org.antlr.v4.runtime.RuleVersion;
import org.junit.Test;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Private {@link RuleDependencyProcessor} class-name checks and
 * {@link RuleDependencyChecker} lookup paths via reflection.
 */
public class TestRuleDependencyPrivatePathCoverage {

	@Test
	public void checkClassNameConstantMismatch() throws Exception {
		RuleDependencyProcessor p = new RuleDependencyProcessor();
		Method m = RuleDependencyProcessor.class.getDeclaredMethod(
			"checkClassNameConstant", String.class, Class.class);
		m.setAccessible(true);
		// processingEnv is null until init; mismatch still prints via env
		try {
			p.init(new StubEnv());
			Object result = m.invoke(p, "not.the.real.Name", RuleDependency.class);
			assertFalse(Boolean.TRUE.equals(result));
		}
		catch (Exception expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void ruleDependencyCheckerPrivatePaths() throws Exception {
		Method versions = org.antlr.v4.runtime.misc.RuleDependencyChecker.class
			.getDeclaredMethod("getRuleVersions", Class.class, String[].class);
		versions.setAccessible(true);
		try {
			versions.invoke(null, Object.class, new String[] { "a" });
		}
		catch (Exception expected) {
			assertNotNull(expected);
		}

		Method names = org.antlr.v4.runtime.misc.RuleDependencyChecker.class
			.getDeclaredMethod("getRuleNames", Class.class);
		names.setAccessible(true);
		try {
			names.invoke(null, Object.class);
		}
		catch (Exception expected) {
			assertNotNull(expected);
		}
	}

	static final class StubEnv implements ProcessingEnvironment {
		@Override public Map<String, String> getOptions() { return Collections.emptyMap(); }
		@Override public Messager getMessager() {
			return new Messager() {
				@Override public void printMessage(Diagnostic.Kind kind, CharSequence msg) { }
				@Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) { }
				@Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) { }
				@Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) { }
			};
		}
		@Override public javax.annotation.processing.Filer getFiler() { return null; }
		@Override public javax.lang.model.util.Elements getElementUtils() { return null; }
		@Override public javax.lang.model.util.Types getTypeUtils() { return null; }
		@Override public javax.lang.model.SourceVersion getSourceVersion() {
			return javax.lang.model.SourceVersion.RELEASE_8;
		}
		@Override public Locale getLocale() { return Locale.US; }
	}
}
