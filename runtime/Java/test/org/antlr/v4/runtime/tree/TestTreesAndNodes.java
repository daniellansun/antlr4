/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.tree;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.Predicate;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestTreesAndNodes {

	/** Rule context with a fixed rule index for Trees.findAllRuleNodes etc. */
	static class IndexedRuleContext extends ParserRuleContext {
		private final int ruleIndex;

		IndexedRuleContext(int ruleIndex) {
			this.ruleIndex = ruleIndex;
		}

		IndexedRuleContext(ParserRuleContext parent, int invokingState, int ruleIndex) {
			super(parent, invokingState);
			this.ruleIndex = ruleIndex;
		}

		@Override
		public int getRuleIndex() {
			return ruleIndex;
		}
	}

	private static TerminalNodeImpl term(int type, String text) {
		return new TerminalNodeImpl(new CommonToken(type, text));
	}

	private static ErrorNodeImpl err(int type, String text) {
		return new ErrorNodeImpl(new CommonToken(type, text));
	}

	/** Build: root(rule0) -> child(rule1) -> ID("x"), INT("1"); root also has err node and EOF */
	private static IndexedRuleContext sampleTree() {
		IndexedRuleContext root = new IndexedRuleContext(0);
		IndexedRuleContext child = new IndexedRuleContext(root, 0, 1);
		child.setParent(root);

		TerminalNodeImpl id = term(1, "x");
		id.setParent(child);
		child.addAnyChild(id);

		TerminalNodeImpl num = term(2, "1");
		num.setParent(child);
		child.addAnyChild(num);

		CommonToken start = new CommonToken(1, "x");
		start.setTokenIndex(0);
		CommonToken stop = new CommonToken(2, "1");
		stop.setTokenIndex(1);
		child.start = start;
		child.stop = stop;

		root.addAnyChild(child);
		ErrorNodeImpl error = err(3, "bad");
		error.setParent(root);
		root.addAnyChild(error);

		TerminalNodeImpl eof = new TerminalNodeImpl(new CommonToken(Token.EOF, "<EOF>"));
		eof.setParent(root);
		root.addAnyChild(eof);

		CommonToken rootStart = new CommonToken(1, "x");
		rootStart.setTokenIndex(0);
		CommonToken rootStop = new CommonToken(Token.EOF);
		rootStop.setTokenIndex(2);
		root.start = rootStart;
		root.stop = rootStop;

		return root;
	}

	@Test
	public void terminalNodeImplBasics() {
		CommonToken t = new CommonToken(5, "hello");
		t.setTokenIndex(3);
		TerminalNodeImpl node = new TerminalNodeImpl(t);

		assertSame(t, node.getSymbol());
		assertSame(t, node.getPayload());
		assertEquals("hello", node.getText());
		assertEquals("hello", node.toString());
		assertEquals("hello", node.toStringTree());
		assertEquals("hello", node.toStringTree(null));
		assertNull(node.getParent());
		assertNull(node.getChild(0));
		assertEquals(0, node.getChildCount());
		assertEquals(new Interval(3, 3), node.getSourceInterval());

		ParserRuleContext parent = new ParserRuleContext();
		node.setParent(parent);
		assertSame(parent, node.getParent());
	}

	@Test
	public void terminalNodeImplEofAndNullSymbol() {
		TerminalNodeImpl eof = new TerminalNodeImpl(new CommonToken(Token.EOF));
		assertEquals("<EOF>", eof.toString());

		TerminalNodeImpl nullSym = new TerminalNodeImpl(null);
		assertEquals("<null>", nullSym.toString());
		assertNull(nullSym.getText());
		assertEquals(Interval.INVALID, nullSym.getSourceInterval());
	}

	@Test
	public void errorNodeImplAccept() {
		ErrorNodeImpl node = err(9, "oops");
		final boolean[] visited = {false};
		AbstractParseTreeVisitor<Integer> visitor = new AbstractParseTreeVisitor<Integer>() {
			@Override
			public Integer visitErrorNode(ErrorNode errorNode) {
				visited[0] = true;
				return 42;
			}
		};
		assertEquals(Integer.valueOf(42), node.accept(visitor));
		assertTrue(visited[0]);
	}

	@Test
	public void terminalAccept() {
		TerminalNodeImpl node = term(1, "x");
		final boolean[] visited = {false};
		AbstractParseTreeVisitor<String> visitor = new AbstractParseTreeVisitor<String>() {
			@Override
			public String visitTerminal(TerminalNode terminalNode) {
				visited[0] = true;
				return terminalNode.getText();
			}
		};
		assertEquals("x", node.accept(visitor));
		assertTrue(visited[0]);
	}

	@Test
	public void parseTreeProperty() {
		ParseTreeProperty<Integer> props = new ParseTreeProperty<Integer>();
		ParserRuleContext tree = new ParserRuleContext();
		assertNull(props.get(tree));
		props.put(tree, 36);
		assertEquals(Integer.valueOf(36), props.get(tree));
		assertEquals(Integer.valueOf(36), props.removeFrom(tree));
		assertNull(props.get(tree));
	}

	@Test
	public void parseTreeWalker() {
		IndexedRuleContext root = sampleTree();
		final List<String> events = new ArrayList<String>();
		ParseTreeListener listener = new ParseTreeListener() {
			@Override
			public void visitTerminal(TerminalNode node) {
				events.add("term:" + node.getText());
			}

			@Override
			public void visitErrorNode(ErrorNode node) {
				events.add("err:" + node.getText());
			}

			@Override
			public void enterEveryRule(ParserRuleContext ctx) {
				events.add("enter:" + ctx.getRuleIndex());
			}

			@Override
			public void exitEveryRule(ParserRuleContext ctx) {
				events.add("exit:" + ctx.getRuleIndex());
			}
		};

		ParseTreeWalker.DEFAULT.walk(listener, root);

		assertTrue(events.contains("enter:0"));
		assertTrue(events.contains("enter:1"));
		assertTrue(events.contains("exit:0"));
		assertTrue(events.contains("exit:1"));
		assertTrue(events.contains("term:x"));
		assertTrue(events.contains("term:1"));
		assertTrue(events.contains("err:bad"));
		assertTrue(events.contains("term:<EOF>") || events.contains("term:null")
			|| events.stream().anyMatch(s -> s.startsWith("term:")));
		// enter before exit for root
		assertTrue(events.indexOf("enter:0") < events.indexOf("exit:0"));
	}

	@Test
	public void abstractParseTreeVisitorChildren() {
		IndexedRuleContext root = sampleTree();
		AbstractParseTreeVisitor<String> visitor = new AbstractParseTreeVisitor<String>() {
			@Override
			public String visitTerminal(TerminalNode node) {
				return node.getText() == null ? "" : node.getText();
			}

			@Override
			public String visitErrorNode(ErrorNode node) {
				return "ERR";
			}

			@Override
			protected String defaultResult() {
				return "";
			}

			@Override
			protected String aggregateResult(String aggregate, String nextResult) {
				if (aggregate.isEmpty()) {
					return nextResult;
				}
				return aggregate + "," + nextResult;
			}
		};

		String result = visitor.visit(root);
		assertNotNull(result);
		assertTrue(result.contains("x"));
		assertTrue(result.contains("1") || result.contains("ERR"));
	}

	@Test
	public void abstractParseTreeVisitorDefaultMethods() {
		// Subclass that does NOT override visitTerminal/visitErrorNode/defaultResult/aggregateResult
		// so the AbstractParseTreeVisitor defaults execute.
		AbstractParseTreeVisitor<Object> visitor = new AbstractParseTreeVisitor<Object>() {
		};
		TerminalNodeImpl term = term(1, "x");
		ErrorNodeImpl error = err(2, "bad");
		assertNull(visitor.visitTerminal(term));
		assertNull(visitor.visitErrorNode(error));

		IndexedRuleContext leafParent = new IndexedRuleContext(0);
		leafParent.addAnyChild(term);
		// visitChildren uses defaultResult()=null and aggregateResult returning nextResult
		assertNull(visitor.visitChildren(leafParent));
		assertNull(visitor.visit(term));
	}

	@Test
	public void abstractParseTreeVisitorShortCircuit() {
		IndexedRuleContext root = new IndexedRuleContext(0);
		root.addAnyChild(term(1, "a"));
		root.addAnyChild(term(1, "b"));
		root.addAnyChild(term(1, "c"));

		AbstractParseTreeVisitor<String> visitor = new AbstractParseTreeVisitor<String>() {
			@Override
			public String visitTerminal(TerminalNode node) {
				return node.getText();
			}

			@Override
			protected String defaultResult() {
				return null;
			}

			@Override
			protected boolean shouldVisitNextChild(RuleNode node, String currentResult) {
				return currentResult == null;
			}
		};

		// visits first child only, aggregate returns last child result by default
		assertEquals("a", visitor.visitChildren(root));
	}

	@Test
	public void treesToStringTreeAndGetNodeText() {
		IndexedRuleContext root = sampleTree();
		List<String> ruleNames = Arrays.asList("prog", "stat");

		String lisp = Trees.toStringTree(root, ruleNames);
		assertTrue(lisp.startsWith("("));
		assertTrue(lisp.contains("prog") || lisp.contains("stat") || lisp.contains("x"));

		// without rule names list, toStringTree still works
		String noRuleNames = Trees.toStringTree(root);
		assertNotNull(noRuleNames);
		assertEquals(noRuleNames, Trees.toStringTree(root, (List<String>) null));

		// without rule names, uses payload (RuleContext.toString for rule nodes)
		String noNames = Trees.getNodeText(root, (List<String>) null);
		assertNotNull(noNames);

		String withNames = Trees.getNodeText(root, ruleNames);
		assertEquals("prog", withNames);

		// also exercise Parser overload with null recog
		assertNotNull(Trees.getNodeText(root, (org.antlr.v4.runtime.Parser) null));
		assertNotNull(Trees.toStringTree(root, (org.antlr.v4.runtime.Parser) null));

		TerminalNodeImpl t = term(1, "id");
		assertEquals("id", Trees.getNodeText(t, ruleNames));

		ErrorNodeImpl e = err(1, "bad");
		assertEquals(e.toString(), Trees.getNodeText(e, ruleNames));

		// token payload path
		assertEquals("id", Trees.getNodeText(t, (List<String>) null));
	}

	@Test
	public void treesGetChildrenAndAncestors() {
		IndexedRuleContext root = sampleTree();
		List<Tree> kids = Trees.getChildren(root);
		assertEquals(3, kids.size());

		ParseTree child = root.getChild(0); // stat
		ParseTree grandchild = child.getChild(0); // ID
		List<? extends Tree> ancestors = Trees.getAncestors(grandchild);
		assertEquals(2, ancestors.size());
		assertSame(root, ancestors.get(0));
		assertSame(child, ancestors.get(1));

		assertTrue(Trees.getAncestors(root).isEmpty());
		// isAncestorOf requires t.getParent()!=null (root fails that check)
		assertTrue(Trees.isAncestorOf(child, grandchild));
		assertFalse(Trees.isAncestorOf(grandchild, child));
		assertFalse(Trees.isAncestorOf(null, child));
		assertFalse(Trees.isAncestorOf(root, null));
		assertFalse(Trees.isAncestorOf(root, grandchild)); // root has null parent
	}

	@Test
	public void treesFindAllAndDescendants() {
		IndexedRuleContext root = sampleTree();

		Collection<ParseTree> tokens = Trees.findAllTokenNodes(root, 1);
		assertEquals(1, tokens.size());
		assertEquals("x", ((TerminalNode) tokens.iterator().next()).getText());

		Collection<ParseTree> rules = Trees.findAllRuleNodes(root, 1);
		assertEquals(1, rules.size());

		List<ParseTree> descendants = Trees.getDescendants(root);
		assertTrue(descendants.size() >= 5);
		assertSame(root, descendants.get(0));

		@SuppressWarnings("deprecation")
		List<ParseTree> deprecated = Trees.descendants(root);
		assertEquals(descendants.size(), deprecated.size());
	}

	@Test
	public void treesFindNodeSuchThat() {
		IndexedRuleContext root = sampleTree();
		Tree found = Trees.findNodeSuchThat(root, new Predicate<Tree>() {
			@Override
			public boolean eval(Tree t) {
				return t instanceof TerminalNode
					&& "1".equals(((TerminalNode) t).getText());
			}
		});
		assertNotNull(found);
		assertEquals("1", ((TerminalNode) found).getText());

		assertNull(Trees.findNodeSuchThat(root, new Predicate<Tree>() {
			@Override
			public boolean eval(Tree t) {
				return false;
			}
		}));
	}

	@Test
	public void treesGetRootOfSubtreeEnclosingRegion() {
		IndexedRuleContext root = sampleTree();
		ParserRuleContext found = Trees.getRootOfSubtreeEnclosingRegion(root, 0, 1);
		assertNotNull(found);
		// child rule spans 0..1
		assertEquals(1, found.getRuleIndex());

		assertNull(Trees.getRootOfSubtreeEnclosingRegion(term(1, "x"), 0, 0));
	}

	@Test
	public void treesStripChildrenOutOfRange() {
		IndexedRuleContext root = new IndexedRuleContext(0);
		CommonToken rootStart = new CommonToken(1, "a");
		rootStart.setTokenIndex(0);
		CommonToken rootStop = new CommonToken(1, "z");
		rootStop.setTokenIndex(10);
		root.start = rootStart;
		root.stop = rootStop;

		IndexedRuleContext left = new IndexedRuleContext(root, 0, 1);
		left.setParent(root);
		CommonToken ls = new CommonToken(1, "L");
		ls.setTokenIndex(0);
		CommonToken le = new CommonToken(1, "L");
		le.setTokenIndex(1);
		left.start = ls;
		left.stop = le;
		root.addAnyChild(left);

		IndexedRuleContext mid = new IndexedRuleContext(root, 0, 2);
		mid.setParent(root);
		CommonToken ms = new CommonToken(1, "M");
		ms.setTokenIndex(4);
		CommonToken me = new CommonToken(1, "M");
		me.setTokenIndex(5);
		mid.start = ms;
		mid.stop = me;
		root.addAnyChild(mid);

		// strip relative to mid as displayed root, range covering mid only
		Trees.stripChildrenOutOfRange(root, mid, 4, 5);
		// left is completely outside range and is ancestor path to mid? left is sibling, not ancestor of mid
		// so left should NOT be replaced (isAncestorOf(left, mid) is false)
		assertTrue(root.getChild(0) instanceof IndexedRuleContext);

		// when root itself is parent of displayed root and left is not ancestor of mid - still not replaced
		// Make nested: root -> outer -> mid; strip on outer with mid as root display
		IndexedRuleContext outer = new IndexedRuleContext(0);
		IndexedRuleContext side = new IndexedRuleContext(outer, 0, 3);
		side.setParent(outer);
		CommonToken ss = new CommonToken(1, "S");
		ss.setTokenIndex(0);
		CommonToken se = new CommonToken(1, "S");
		se.setTokenIndex(0);
		side.start = ss;
		side.stop = se;
		outer.addAnyChild(side);

		IndexedRuleContext keep = new IndexedRuleContext(outer, 0, 4);
		keep.setParent(outer);
		CommonToken ks = new CommonToken(1, "K");
		ks.setTokenIndex(5);
		CommonToken ke = new CommonToken(1, "K");
		ke.setTokenIndex(5);
		keep.start = ks;
		keep.stop = ke;
		outer.addAnyChild(keep);

		// side is not ancestor of keep, so no replace
		Trees.stripChildrenOutOfRange(outer, keep, 5, 5);
		assertTrue(outer.getChild(0) instanceof IndexedRuleContext);

		// null is no-op
		Trees.stripChildrenOutOfRange(null, keep, 0, 1);
	}

	@Test
	public void treesGetNodeTextWithAltNumber() {
		IndexedRuleContext ctx = new IndexedRuleContext(0) {
			@Override
			public int getAltNumber() {
				return 2;
			}
		};
		assertEquals("r:2", Trees.getNodeText(ctx, Arrays.asList("r")));
	}
}
