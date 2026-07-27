/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.RuleDependencies;
import org.antlr.v4.runtime.RuleDependency;
import org.antlr.v4.runtime.RuleVersion;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNSimulator;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TestRuleDependencyChecker {
	@Test
	public void checkDependenciesOnPlainClassIsNoOp() {
		RuleDependencyChecker.checkDependencies(PlainClass.class);
		// second call hits isChecked cache
		RuleDependencyChecker.checkDependencies(PlainClass.class);
	}

	@Test
	public void getDependenciesEmpty() {
		List<Tuple2<RuleDependency, AnnotatedElement>> deps =
			RuleDependencyChecker.getDependencies(PlainClass.class);
		assertNotNull(deps);
		assertTrue(deps.isEmpty());
	}

	@Test
	public void getDependenciesFindsFieldAndMethodAnnotations() {
		List<Tuple2<RuleDependency, AnnotatedElement>> deps =
			RuleDependencyChecker.getDependencies(DependentClass.class);
		assertTrue(deps.size() >= 2);
	}

	@Test
	public void getDependenciesFindsConstructorAndTypeAnnotations() {
		List<Tuple2<RuleDependency, AnnotatedElement>> deps =
			RuleDependencyChecker.getDependencies(TypeAndCtorDependent.class);
		assertTrue("expected type/ctor annotations, got " + deps.size(), deps.size() >= 2);
	}

	@Test
	public void checkDependenciesCompatible() {
		RuleDependencyChecker.checkDependencies(CompatibleDependent.class);
		// cache hit for the dependent class path
		RuleDependencyChecker.checkDependencies(CompatibleDependent.class);
	}

	@Test
	public void checkDependenciesVersionMismatchThrows() {
		assertThrows(IllegalStateException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				RuleDependencyChecker.checkDependencies(MismatchedDependent.class);
			}
		});
	}

	@Test
	public void checkDependenciesUnknownRuleThrows() {
		assertThrows(IllegalStateException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				RuleDependencyChecker.checkDependencies(UnknownRuleDependent.class);
			}
		});
	}

	@Test
	public void checkDependenciesWithNestedClass() {
		// outer has no deps; nested has compatible deps — exercises declaredClasses loop
		RuleDependencyChecker.checkDependencies(OuterWithNested.class);
	}

	@Test
	public void checkDependenciesRecognizerWithoutRuleNames() {
		// missing ruleNames field => getRuleNames returns empty => unknown rule
		assertThrows(IllegalStateException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				RuleDependencyChecker.checkDependencies(DependentOnNoNames.class);
			}
		});
	}

	@Test
	public void checkDependenciesWithOddRuleFields() {
		// FakeRecognizer2 has RULE_Bad (skipped), RULE without method (warning), valid rules
		RuleDependencyChecker.checkDependencies(DependentOnOddRecognizer.class);
	}

	@Test
	public void privateConstructorViaReflection() throws Exception {
		Constructor<RuleDependencyChecker> ctor = RuleDependencyChecker.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		assertNotNull(ctor.newInstance());
	}

	static class PlainClass {
		int x;
		void m() {}
	}

	/**
	 * Stub recognizer with static rule metadata used by RuleDependencyChecker reflection.
	 */
	public static class FakeRecognizer extends Recognizer<Token, ATNSimulator> {
		public static final String[] ruleNames = new String[] { "a", "b" };
		public static final int RULE_a = 0;
		public static final int RULE_b = 1;

		@RuleVersion(1)
		public void a() {}

		@RuleVersion(0)
		public void b() {}

		@Override
		public String[] getTokenNames() {
			return new String[0];
		}

		@Override
		public String[] getRuleNames() {
			return ruleNames;
		}

		@Override
		public String getGrammarFileName() {
			return "Fake.g4";
		}

		@Override
		public IntStream getInputStream() {
			return null;
		}
	}

	/** Recognizer with edge-case RULE_* fields for getRuleVersions branches. */
	public static class OddRecognizer extends Recognizer<Token, ATNSimulator> {
		public static final String[] ruleNames = new String[] { "a", "b", "c" };
		public static final int RULE_a = 0;
		public static final int RULE_b = 1;
		public static final int RULE_c = 2;
		public static final int RULE_Bad = 9; // not lowercase after RULE_
		public static final int RULE_ = 8; // empty name after RULE_
		public static final int RULE_missing = 2; // method without @RuleVersion → warning
		// out-of-bounds index relative to ruleNames length
		public static final int RULE_oob = 50;

		@RuleVersion(1)
		public void a() {}

		@RuleVersion(0)
		public void b() {}

		// c() has no @RuleVersion — getRuleMethod returns null for RULE_c / RULE_missing
		public void c() {}

		public void missing() {}

		@Override public String[] getTokenNames() { return new String[0]; }
		@Override public String[] getRuleNames() { return ruleNames; }
		@Override public String getGrammarFileName() { return "Odd.g4"; }
		@Override public IntStream getInputStream() { return null; }
	}

	/** No public static ruleNames field. */
	public static class NoNamesRecognizer extends Recognizer<Token, ATNSimulator> {
		public static final int RULE_a = 0;

		@RuleVersion(0)
		public void a() {}

		@Override public String[] getTokenNames() { return new String[0]; }
		@Override public String[] getRuleNames() { return new String[] { "a" }; }
		@Override public String getGrammarFileName() { return "NoNames.g4"; }
		@Override public IntStream getInputStream() { return null; }
	}

	static class DependentClass {
		@RuleDependency(recognizer = FakeRecognizer.class, rule = 0, version = 1)
		public int field;

		@RuleDependency(recognizer = FakeRecognizer.class, rule = 1, version = 0)
		public void method() {}

		@RuleDependencies({
			@RuleDependency(recognizer = FakeRecognizer.class, rule = 0, version = 1)
		})
		public int multi;
	}

	@RuleDependency(recognizer = FakeRecognizer.class, rule = 0, version = 1)
	static class TypeAndCtorDependent {
		@RuleDependency(recognizer = FakeRecognizer.class, rule = 1, version = 0)
		public TypeAndCtorDependent() {}
	}

	static class CompatibleDependent {
		@RuleDependency(recognizer = FakeRecognizer.class, rule = 0, version = 1)
		public int ok;
	}

	static class MismatchedDependent {
		@RuleDependency(recognizer = FakeRecognizer.class, rule = 0, version = 0)
		public int bad;
	}

	static class UnknownRuleDependent {
		@RuleDependency(recognizer = FakeRecognizer.class, rule = 99, version = 0)
		public int bad;
	}

	static class OuterWithNested {
		static class Nested {
			@RuleDependency(recognizer = FakeRecognizer.class, rule = 1, version = 0)
			int ok;
		}
	}

	static class DependentOnNoNames {
		@RuleDependency(recognizer = NoNamesRecognizer.class, rule = 0, version = 0)
		int x;
	}

	static class DependentOnOddRecognizer {
		@RuleDependency(recognizer = OddRecognizer.class, rule = 0, version = 1)
		int ok;
	}
}
