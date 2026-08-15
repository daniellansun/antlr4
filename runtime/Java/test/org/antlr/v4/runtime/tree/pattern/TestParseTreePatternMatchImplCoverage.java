/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.tree.pattern;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.MultiMap;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * {@link ParseTreePatternMatcher#matchImpl} null arguments and
 * rule-node child-count mismatch.
 */
public class TestParseTreePatternMatchImplCoverage {

	@Test
	public void matchImplNullArgumentsAndChildCountMismatch() {
		// matcher requires lexer+parser; we only need matchImpl which is protected
		class Exposed extends ParseTreePatternMatcher {
			Exposed() {
				super(null, null);
			}
			ParseTree call(ParseTree tree, ParseTree pattern, MultiMap<String, ParseTree> labels) {
				return matchImpl(tree, pattern, labels);
			}
		}
		Exposed m = new Exposed();
		MultiMap<String, ParseTree> labels = new MultiMap<String, ParseTree>();
		try {
			m.call(null, new TerminalNodeImpl(new CommonToken(1, "x")), labels);
			fail();
		}
		catch (IllegalArgumentException expected) {
			assertNotNull(expected.getMessage());
		}
		try {
			m.call(new TerminalNodeImpl(new CommonToken(1, "x")), null, labels);
			fail();
		}
		catch (IllegalArgumentException expected) {
			assertNotNull(expected.getMessage());
		}

		ParserRuleContext a = new ParserRuleContext();
		a.addChild(new TerminalNodeImpl(new CommonToken(1, "x")));
		ParserRuleContext b = new ParserRuleContext();
		b.addChild(new TerminalNodeImpl(new CommonToken(1, "x")));
		b.addChild(new TerminalNodeImpl(new CommonToken(2, "y")));
		assertNotNull(m.call(a, b, labels));
	}
}
