/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.tree.ErrorNodeImpl;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Coverage for compact {@link ParserRuleContext#children} allocation.
 */
public class TestParserRuleContextChildren {

	@Test
	public void firstChildUsesCapacityFour() throws Exception {
		ParserRuleContext ctx = new ParserRuleContext();
		TerminalNodeImpl leaf = new TerminalNodeImpl(new CommonToken(1, "a"));
		leaf.setParent(ctx);
		ctx.addChild(leaf);
		assertEquals(1, ctx.getChildCount());
		assertTrue(ctx.children instanceof ArrayList);
		assertEquals(4, arrayListCapacity(ctx.children));
	}

	@Test
	public void manyChildrenGrowPastInitialCapacity() {
		ParserRuleContext ctx = new ParserRuleContext();
		for (int i = 0; i < 10; i++) {
			TerminalNodeImpl leaf = new TerminalNodeImpl(new CommonToken(1, "t" + i));
			leaf.setParent(ctx);
			ctx.addChild(leaf);
		}
		assertEquals(10, ctx.getChildCount());
		assertEquals("t9", ctx.getChild(9).getText());
	}

	@Test
	public void copyFromPreservesErrorChildrenAndCapacity() throws Exception {
		ParserRuleContext src = new ParserRuleContext();
		ErrorNodeImpl err = new ErrorNodeImpl(new CommonToken(1, "x"));
		err.setParent(src);
		src.addErrorNode(err);
		ParserRuleContext dest = new ParserRuleContext();
		dest.copyFrom(src);
		assertEquals(1, dest.getChildCount());
		assertSame(err, dest.getChild(0));
		assertTrue(dest.children instanceof ArrayList);
		assertTrue(arrayListCapacity(dest.children) >= 4);
	}

	@SuppressWarnings("rawtypes")
	private static int arrayListCapacity(java.util.List<ParseTree> list) throws Exception {
		Field f = ArrayList.class.getDeclaredField("elementData");
		f.setAccessible(true);
		return ((Object[]) f.get(list)).length;
	}
}
