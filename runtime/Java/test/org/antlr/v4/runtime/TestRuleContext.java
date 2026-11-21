/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.AbstractParseTreeVisitor;
import org.antlr.v4.runtime.tree.ErrorNodeImpl;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;
import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestRuleContext {
	@Test
	public void depthAndIsEmpty() {
		RuleContext root = new RuleContext();
		assertTrue(root.isEmpty());
		assertEquals(1, root.depth());
		assertEquals(-1, root.invokingState);

		RuleContext child = new RuleContext(root, 5);
		assertFalse(child.isEmpty());
		assertEquals(2, child.depth());
		assertEquals(5, child.invokingState);
		assertSame(root, child.parent);

		RuleContext grandchild = RuleContext.getChildContext(child, 9);
		assertEquals(3, grandchild.depth());
	}

	@Test
	public void defaultChildAccessors() {
		RuleContext ctx = new RuleContext();
		assertEquals(0, ctx.getChildCount());
		assertNull(ctx.getChild(0));
		assertEquals("", ctx.getText());
		assertEquals(-1, ctx.getRuleIndex());
		assertEquals(ATN.INVALID_ALT_NUMBER, ctx.getAltNumber());
		ctx.setAltNumber(2); // no-op default
		assertEquals(ATN.INVALID_ALT_NUMBER, ctx.getAltNumber());
		assertSame(Interval.INVALID, ctx.getSourceInterval());
		assertSame(ctx, ctx.getRuleContext());
		assertSame(ctx, ctx.getPayload());
	}

	@Test
	public void setParent() {
		RuleContext a = new RuleContext();
		RuleContext b = new RuleContext();
		b.setParent(a);
		assertSame(a, b.getParent());
	}

	@Test
	public void toStringWithoutRuleNames() {
		RuleContext root = new RuleContext();
		RuleContext child = new RuleContext(root, 3);
		RuleContext gchild = new RuleContext(child, 7);
		assertEquals("[]", root.toString());
		assertEquals("[3]", child.toString());
		assertEquals("[7 3]", gchild.toString());
	}

	@Test
	public void toStringWithRuleNames() {
		List<String> names = Arrays.asList("s", "expr", "atom");
		RuleContextWithIndex root = new RuleContextWithIndex(null, -1, 0);
		RuleContextWithIndex child = new RuleContextWithIndex(root, 1, 1);
		assertEquals("[expr s]", child.toString(names));
		assertEquals("[s]", root.toString(names));
	}

	@Test
	public void toStringTree() {
		RuleContext ctx = new RuleContext();
		assertEquals("[]", ctx.toStringTree());
		assertEquals("[]", ctx.toStringTree((List<String>) null));
	}

	@Test
	public void acceptVisitsChildren() {
		final boolean[] visited = {false};
		RuleContext ctx = new RuleContext();
		ctx.accept(new ParseTreeVisitor<Void>() {
			@Override public Void visit(org.antlr.v4.runtime.tree.ParseTree tree) { return null; }
			@Override public Void visitChildren(org.antlr.v4.runtime.tree.RuleNode node) {
				visited[0] = true;
				return null;
			}
			@Override public Void visitTerminal(org.antlr.v4.runtime.tree.TerminalNode node) { return null; }
			@Override public Void visitErrorNode(org.antlr.v4.runtime.tree.ErrorNode node) { return null; }
		});
		assertTrue(visited[0]);
	}

	@Test
	public void parserRuleContextChildrenAndText() {
		ParserRuleContext ctx = new ParserRuleContext();
		CommonToken a = new CommonToken(1, "hello");
		CommonToken b = new CommonToken(2, "world");
		ctx.addChild(a);
		ctx.addChild(b);
		assertEquals(2, ctx.getChildCount());
		assertEquals("helloworld", ctx.getText());
		assertEquals("hello", ctx.getToken(1, 0).getText());
		assertEquals(1, ctx.getTokens(1).size());
		assertNull(ctx.getToken(99, 0));
	}

	@Test
	public void parserRuleContextRuleChildren() {
		ParserRuleContext parent = new ParserRuleContext();
		ParserRuleContext child0 = new ParserRuleContext(parent, 1);
		ParserRuleContext child1 = new ParserRuleContext(parent, 2);
		parent.addChild(child0);
		parent.addChild(child1);
		assertEquals(2, parent.getRuleContexts(ParserRuleContext.class).size());
		assertSame(child0, parent.getRuleContext(ParserRuleContext.class, 0));
		assertSame(child1, parent.getChild(ParserRuleContext.class, 1));
	}

	@Test
	public void parserRuleContextSourceInterval() {
		ParserRuleContext ctx = new ParserRuleContext();
		assertSame(Interval.INVALID, ctx.getSourceInterval());
		CommonToken start = new CommonToken(1, "a");
		start.setTokenIndex(2);
		CommonToken stop = new CommonToken(1, "b");
		stop.setTokenIndex(5);
		ctx.start = start;
		ctx.stop = stop;
		assertEquals(Interval.of(2, 5), ctx.getSourceInterval());
		assertSame(start, ctx.getStart());
		assertSame(stop, ctx.getStop());
	}

	@Test
	public void emptyStopMakesEmptyInterval() {
		ParserRuleContext ctx = new ParserRuleContext();
		CommonToken start = new CommonToken(1, "a");
		start.setTokenIndex(3);
		ctx.start = start;
		assertEquals(Interval.of(3, 2), ctx.getSourceInterval());
	}

	@Test
	public void addErrorNodeAndRemoveLastChild() {
		ParserRuleContext ctx = new ParserRuleContext();
		CommonToken bad = new CommonToken(1, "!");
		ctx.addErrorNode(bad);
		assertEquals(1, ctx.getChildCount());
		assertTrue(ctx.getChild(0) instanceof org.antlr.v4.runtime.tree.ErrorNode);
		ctx.removeLastChild();
		assertEquals(0, ctx.getChildCount());
	}

	@Test
	public void copyFromCopiesErrorNodes() {
		ParserRuleContext src = new ParserRuleContext(null, 4);
		CommonToken bad = new CommonToken(1, "x");
		src.addErrorNode(bad);
		src.start = new CommonToken(1, "s");
		src.stop = new CommonToken(1, "e");
		ParserRuleContext dest = new ParserRuleContext();
		dest.copyFrom(src);
		assertEquals(4, dest.invokingState);
		assertEquals(1, dest.getChildCount());
		assertSame(src.start, dest.start);
		assertSame(src.stop, dest.stop);
	}

	@Test
	public void emptyContextSingleton() {
		assertSame(ParserRuleContext.emptyContext(), ParserRuleContext.emptyContext());
	}

	@Test
	public void ruleContextWithAltNum() {
		RuleContextWithAltNum ctx = new RuleContextWithAltNum();
		assertEquals(ATN.INVALID_ALT_NUMBER, ctx.getAltNumber());
		ctx.setAltNumber(3);
		assertEquals(3, ctx.getAltNumber());

		ParserRuleContext parent = new ParserRuleContext();
		RuleContextWithAltNum child = new RuleContextWithAltNum(parent, 7);
		assertSame(parent, child.getParent());
		assertEquals(7, child.invokingState);
	}

	@Test
	public void interpreterRuleContextRuleIndex() {
		ParserRuleContext parent = new ParserRuleContext();
		InterpreterRuleContext ctx = new InterpreterRuleContext(parent, 5, 12);
		assertEquals(12, ctx.getRuleIndex());
		assertEquals(5, ctx.invokingState);
		assertSame(parent, ctx.getParent());
	}

	@Test
	public void interpreterRuleContextPrivateCtorViaReflection() throws Exception {
		java.lang.reflect.Constructor<InterpreterRuleContext> ctor =
			InterpreterRuleContext.class.getDeclaredConstructor(int.class);
		ctor.setAccessible(true);
		InterpreterRuleContext ctx = ctor.newInstance(7);
		assertEquals(7, ctx.getRuleIndex());
	}

	@Test
	public void toStringTreeWithRuleNamesList() {
		InterpreterRuleContext ctx = new InterpreterRuleContext(null, -1, 0);
		assertEquals("s", ctx.toStringTree(Arrays.asList("s", "expr")));
	}

	@Test
	public void replaceLastChildSelfCopyTerminalAndVisitorEdgeCases() {
		ParserRuleContext root = new ParserRuleContext();
		CommonToken tA = new CommonToken(1, "A");
		TerminalNodeImpl leaf = new TerminalNodeImpl(tA, root);
		assertSame(tA, leaf.getSymbol());
		assertSame(root, leaf.getParent());

		// replaceLastChild when children == null -> adds child; when non-empty -> replaces in place
		root.replaceLastChild(leaf);
		assertEquals(1, root.getChildCount());
		assertSame(leaf, root.getChild(0));
		TerminalNodeImpl leaf2 = new TerminalNodeImpl(new CommonToken(2, "B"), root);
		root.replaceLastChild(leaf2);
		assertEquals(1, root.getChildCount());
		assertSame(leaf2, root.getChild(0));

		// RuleContext.getText(): 0, 1 (normal), 1 (null text -> "null"), 2+ children
		RuleContext emptyRc = new ParserRuleContext();
		assertEquals("", emptyRc.getText());
		ParserRuleContext singleNullText = new ParserRuleContext();
		singleNullText.addChild(new TerminalNodeImpl(new CommonToken(1), singleNullText));
		assertEquals("null", singleNullText.getText());
		assertEquals("B", root.getText());
		root.addChild(new TerminalNodeImpl(new CommonToken(3, "C"), root));
		assertEquals("BC", root.getText());

		// copyFrom with ErrorNodeImpl + self-copy safety
		ParserRuleContext src = new ParserRuleContext();
		ErrorNodeImpl err = new ErrorNodeImpl(new CommonToken(9, "err"));
		src.addErrorNode(err);
		ParserRuleContext dst = new ParserRuleContext();
		dst.copyFrom(src);
		assertEquals(1, dst.getChildCount());
		assertSame(dst, err.getParent());
		dst.copyFrom(dst);
		assertEquals(1, dst.getChildCount());

		// AbstractParseTreeVisitor.visitChildren: null children, shouldVisitNextChild=false, custom RuleNode
		AbstractParseTreeVisitor<String> visitor = new AbstractParseTreeVisitor<String>() {
			@Override protected String defaultResult() { return ""; }
			@Override protected String aggregateResult(String agg, String next) { return agg + next; }
			@Override public String visitTerminal(TerminalNode node) { return node.getText(); }
			@Override
			protected boolean shouldVisitNextChild(RuleNode node, String currentResult) {
				return currentResult.length() < 1;
			}
		};
		assertEquals("", visitor.visitChildren(new ParserRuleContext()));
		assertEquals("B", visitor.visitChildren(root));

		final TerminalNodeImpl customChild = new TerminalNodeImpl(new CommonToken(1, "Z"));
		RuleNode nonPrcRuleNode = new RuleNode() {
			@Override public RuleContext getRuleContext() { return null; }
			@Override public RuleNode getParent() { return null; }
			@Override public Object getPayload() { return null; }
			@Override public ParseTree getChild(int i) { return i == 0 ? customChild : null; }
			@Override public int getChildCount() { return 1; }
			@Override public String toStringTree() { return "Z"; }
			@Override public <T> T accept(ParseTreeVisitor<? extends T> v) { return v.visitChildren(this); }
			@Override public String getText() { return "Z"; }
			@Override public String toStringTree(Parser parser) { return "Z"; }
			@Override public Interval getSourceInterval() { return Interval.INVALID; }
		};
		assertEquals("Z", visitor.visitChildren(nonPrcRuleNode));

		// Parser.consume() with _buildParseTrees == false and an active ParseTreeListener
		CommonTokenStream ts = new CommonTokenStream(new ListTokenSource(Arrays.<Token>asList(
			new CommonToken(1, "a"), new CommonToken(Token.EOF, "<EOF>")
		)));
		Parser miniParser = new Parser(ts) {
			@Override public String[] getTokenNames() { return new String[] { "<INVALID>", "A" }; }
			@Override public String[] getRuleNames() { return new String[] { "r" }; }
			@Override public String getGrammarFileName() { return "M.g4"; }
			@Override public ATN getATN() { return new ATN(ATNType.PARSER, 1); }
		};
		miniParser.setBuildParseTree(false);
		final List<String> visited = new ArrayList<String>();
		miniParser.addParseListener(new ParseTreeListener() {
			@Override public void visitTerminal(TerminalNode node) { visited.add(node.getText()); }
			@Override public void visitErrorNode(org.antlr.v4.runtime.tree.ErrorNode node) {}
			@Override public void enterEveryRule(ParserRuleContext ctx) {}
			@Override public void exitEveryRule(ParserRuleContext ctx) {}
		});
		miniParser.enterRule(new ParserRuleContext(), 0, 0);
		miniParser.consume();
		assertEquals(Collections.singletonList("a"), visited);
		miniParser.consume(); // consumes EOF into lastConsumed
		miniParser.exitRule();
	}

	/** Helper context that reports a fixed rule index for toString tests. */
	private static final class RuleContextWithIndex extends RuleContext {
		private final int ruleIndex;

		RuleContextWithIndex(RuleContext parent, int invokingState, int ruleIndex) {
			super(parent, invokingState);
			this.ruleIndex = ruleIndex;
		}

		@Override
		public int getRuleIndex() {
			return ruleIndex;
		}
	}
}
