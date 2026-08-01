/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.Target;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.RuleAST;
import org.junit.Test;

import static org.junit.Assert.*;

public class TestTargetImplementationsCoverage {

	@Test
	public void testJavaTargetAndBaseTargetMethods() throws Exception {
		String grammarStr = "grammar T;\n a : 'x' ;\n";
		Grammar g = new Grammar(grammarStr);
		g.tool.process(g, false);
		CodeGenerator gen = new CodeGenerator(g);
		Target target = gen.getTarget();

		assertNotNull(target);
		assertEquals("Java", target.getLanguage());
		assertEquals(gen, target.getCodeGenerator());

		// Test target methods
		Rule r = new Rule(g, "a", new RuleAST(new CommonToken(1, "a")), 0);
		assertNotNull(target.getRuleFunctionContextStructName(r));
		assertNotNull(target.getAltLabelContextStructName("X"));
		assertNotNull(target.getImplicitTokenLabel("t"));
		assertNotNull(target.getImplicitSetLabel("s"));

		assertNotNull(target.getTargetStringLiteralFromANTLRStringLiteral(gen, "'foo'", true));
		assertNotNull(target.getTargetStringLiteralFromString("bar"));

		target.encodeIntAsCharEscape(10);
		target.encodeIntAsCharEscape(65);
		target.encodeIntAsCharEscape(0x100);

		assertTrue(target.supportsOverloadedMethods());
	}
}
