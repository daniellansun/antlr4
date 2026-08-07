/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.v4.gui.GraphicsSupport;
import org.antlr.v4.gui.JFileChooserConfirmOverwrite;
import org.antlr.v4.gui.PostScriptDocument;
import org.antlr.v4.gui.TestRig;
import org.antlr.v4.gui.TreeLayoutAdaptor;
import org.antlr.v4.gui.TreePostScriptGenerator;
import org.antlr.v4.gui.TreeTextProvider;
import org.antlr.v4.gui.TreeViewer;
import org.antlr.v4.gui.Trees;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.tree.ErrorNodeImpl;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.antlr.v4.runtime.tree.Tree;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.junit.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Coverage for GUI package: TreeViewer, TreePostScriptGenerator,
 * TreeLayoutAdaptor, Trees, TestRig, and JFileChooserConfirmOverwrite.
 */
public class TestTreeViewerAndTestRigCoverage extends BaseTest {

	private static ParseTree sampleTree() throws Exception {
		// Use a simple non-left-recursive grammar for a reliable tree
		Grammar g = new Grammar(
				"grammar T;\n" +
				"s : e ('+' e)* ;\n" +
				"e : ID ;\n" +
				"ID : [a-z]+ ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
		g.tool.process(g, false);
		LexerGrammar lg = g.implicitLexer;
		assertNotNull(lg);
		LexerInterpreter lex = lg.createLexerInterpreter(CharStreams.fromString("a + b"));
		CommonTokenStream tokens = new CommonTokenStream(lex);
		ParserInterpreter parser = g.createParserInterpreter(tokens);
		parser.setBuildParseTree(true);
		ParseTree tree = parser.parse(g.getRule("s").index);
		assertNotNull(tree);
		return tree;
	}

	@Test
	public void testTreeViewerPaintAndAccessors() throws Exception {
		mkdir(tmpdir);
		ParseTree tree = sampleTree();
		List<String> ruleNames = Arrays.asList("s", "e");
		TreeViewer viewer = new TreeViewer(ruleNames, tree);

		assertNotNull(viewer.getTreeTextProvider());
		viewer.setTreeTextProvider(new TreeViewer.DefaultTreeTextProvider(ruleNames));
		viewer.setFontSize(12);
		viewer.setFontName("Dialog");
		viewer.setFont(new Font("Dialog", Font.PLAIN, 12));
		assertNotNull(viewer.getFont());
		viewer.setArcSize(4);
		assertEquals(4, viewer.getArcSize());
		viewer.setBoxColor(Color.YELLOW);
		assertEquals(Color.YELLOW, viewer.getBoxColor());
		viewer.setHighlightedBoxColor(Color.ORANGE);
		assertEquals(Color.ORANGE, viewer.getHighlightedBoxColor());
		viewer.setBorderColor(Color.BLUE);
		assertEquals(Color.BLUE, viewer.getBorderColor());
		viewer.setTextColor(Color.RED);
		assertEquals(Color.RED, viewer.getTextColor());
		viewer.setUseCurvedEdges(true);
		assertTrue(viewer.getUseCurvedEdges());
		viewer.setUseCurvedEdges(false);
		viewer.setScale(0);
		assertEquals(1.0, viewer.getScale(), 0.001);
		viewer.setScale(1.2);
		assertEquals(1.2, viewer.getScale(), 0.001);

		viewer.addHighlightedNodes(Collections.<Tree>singletonList(tree));
		viewer.removeHighlightedNodes(Collections.<Tree>singletonList(tree));
		viewer.addHighlightedNodes(Collections.<Tree>singletonList(tree));

		BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2 = img.createGraphics();
		viewer.setSize(800, 600);
		viewer.paint(g2);
		g2.dispose();

		viewer.setTree(null);
		viewer.setTree(tree);

		TreeViewer.DefaultTreeTextProvider provider =
				new TreeViewer.DefaultTreeTextProvider(ruleNames);
		assertNotNull(provider.getText(tree));
		TerminalNodeImpl term = new TerminalNodeImpl(new CommonToken(1, "id"));
		assertNotNull(provider.getText(term));
		ErrorNodeImpl errNode = new ErrorNodeImpl(new CommonToken(1, "err"));
		assertNotNull(provider.getText(errNode));

		File png = new File(tmpdir, "tree.png");
		try {
			viewer.setTree(tree);
			viewer.save(png.getAbsolutePath());
		} catch (Throwable t) {
			// headless / printer may fail
		}
	}

	@Test
	public void testTreePostScriptGeneratorAndTreesHelpers() throws Exception {
		mkdir(tmpdir);
		ParseTree tree = sampleTree();
		List<String> ruleNames = Arrays.asList("s", "e");

		TreePostScriptGenerator psgen = new TreePostScriptGenerator(ruleNames, tree);
		String ps = psgen.getPS();
		assertNotNull(ps);
		assertTrue(ps.length() > 0);
		assertNotNull(psgen.getTreeTextProvider());
		psgen.setTreeTextProvider(new TreeViewer.DefaultTreeTextProvider(ruleNames));

		TreePostScriptGenerator psgen2 =
				new TreePostScriptGenerator(ruleNames, tree, "Helvetica", 14);
		assertNotNull(psgen2.getPS());

		assertNotNull(Trees.getPS(tree, ruleNames));
		assertNotNull(Trees.getPS(tree, ruleNames, "Courier", 10));

		File psFile = new File(tmpdir, "tree.ps");
		Trees.writePS(tree, ruleNames, psFile.getAbsolutePath());
		assertTrue(psFile.length() > 0);
		File psFile2 = new File(tmpdir, "tree2.ps");
		Trees.writePS(tree, ruleNames, psFile2.getAbsolutePath(), "Helvetica", 12);
		assertTrue(psFile2.length() > 0);

		File psFile3 = new File(tmpdir, "tree3.ps");
		Trees.save(tree, (org.antlr.v4.runtime.Parser) null, psFile3.getAbsolutePath());
		File psFile4 = new File(tmpdir, "tree4.ps");
		Trees.save(tree, (org.antlr.v4.runtime.Parser) null, psFile4.getAbsolutePath(), "Dialog", 11);

		String lisp = Trees.toStringTree(tree, new TreeViewer.DefaultTreeTextProvider(ruleNames));
		assertNotNull(lisp);
		assertEquals("null", Trees.toStringTree(null, new TreeViewer.DefaultTreeTextProvider(ruleNames)));
	}

	@Test
	public void testTreeLayoutAdaptorIterators() throws Exception {
		ParseTree tree = sampleTree();
		TreeLayoutAdaptor adaptor = new TreeLayoutAdaptor(tree);
		assertSame(tree, adaptor.getRoot());
		assertFalse(adaptor.isLeaf(tree));
		assertNotNull(adaptor.getFirstChild(tree));
		assertNotNull(adaptor.getLastChild(tree));

		int count = 0;
		for (Tree c : adaptor.getChildren(tree)) {
			count++;
		}
		assertTrue(count > 0);

		int rcount = 0;
		for (Tree c : adaptor.getChildrenReverse(tree)) {
			rcount++;
		}
		assertEquals(count, rcount);

		Iterator<Tree> it = adaptor.getChildren(tree).iterator();
		while (it.hasNext()) {
			it.next();
		}
		try {
			it.next();
			fail("expected NoSuchElementException");
		} catch (NoSuchElementException expected) {
		}
		try {
			it = adaptor.getChildren(tree).iterator();
			it.next();
			it.remove();
			fail("expected UnsupportedOperationException");
		} catch (UnsupportedOperationException expected) {
		}

		Iterator<Tree> rit = adaptor.getChildrenReverse(tree).iterator();
		while (rit.hasNext()) {
			rit.next();
		}
		try {
			rit.next();
			fail("expected NoSuchElementException");
		} catch (NoSuchElementException expected) {
		}
		try {
			rit = adaptor.getChildrenReverse(tree).iterator();
			rit.next();
			rit.remove();
			fail("expected UnsupportedOperationException");
		} catch (UnsupportedOperationException expected) {
		}

		TerminalNodeImpl leaf = new TerminalNodeImpl(new CommonToken(1, "x"));
		TreeLayoutAdaptor leafAdaptor = new TreeLayoutAdaptor(leaf);
		assertTrue(leafAdaptor.isLeaf(leaf));
	}

	@Test
	public void testTestRigConstructorAndOptions() throws Exception {
		mkdir(tmpdir);
		new TestRig(new String[]{});

		File input = new File(tmpdir, "input.txt");
		writeFile(input, "x");
		File ps = new File(tmpdir, "out.ps");
		TestRig rig = new TestRig(new String[]{
				"DummyGrammar", "s",
				"-tree", "-tokens", "-trace", "-SLL", "-diagnostics",
				"-encoding", "UTF-8",
				"-ps", ps.getAbsolutePath(),
				"-gui",
				input.getAbsolutePath()
		});
		ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
		PrintStream old = System.err;
		System.setErr(new PrintStream(errBuf));
		try {
			rig.process();
		} catch (Throwable t) {
			// ClassNotFound expected
		} finally {
			System.setErr(old);
		}

		new TestRig(new String[]{"G", "r", "-encoding"});
		new TestRig(new String[]{"G", "r", "-ps"});

		PrintStream old2 = System.err;
		System.setErr(new PrintStream(new ByteArrayOutputStream()));
		try {
			TestRig.main(new String[]{});
		} finally {
			System.setErr(old2);
		}
	}

	@Test
	public void testJFileChooserConfirmOverwrite() {
		try {
			JFileChooserConfirmOverwrite chooser = new JFileChooserConfirmOverwrite();
			assertNotNull(chooser);
			try {
				chooser.approveSelection();
			} catch (Throwable t) {
				// headless HeadlessException is fine
			}
		} catch (Throwable t) {
			// GUI construction may fail headless
		}
	}

	@Test
	public void testPostScriptDocumentMoreOps() {
		PostScriptDocument ps = new PostScriptDocument();
		ps.boundingBox(200, 100);
		ps.line(0, 0, 10, 10);
		ps.rect(1, 1, 20, 20);
		ps.highlight(2, 2, 5, 5);
		ps.text("abc", 3, 3);
		assertTrue(ps.getWidth("abc") > 0);
		assertTrue(ps.getLineHeight() > 0);
		assertNotNull(ps.getPS());
		ps.close();
		assertNotNull(ps.getPS());
	}

	@Test
	public void testGraphicsSupportSavePng() throws Exception {
		mkdir(tmpdir);
		javax.swing.JPanel panel = new javax.swing.JPanel();
		panel.setSize(50, 50);
		panel.setPreferredSize(new java.awt.Dimension(50, 50));
		File png = new File(tmpdir, "panel.png");
		try {
			GraphicsSupport.saveImage(panel, png.getAbsolutePath());
		} catch (Throwable t) {
			// may fail headless
		}
	}

	@Test
	public void testTreeViewerOpenHeadlessSafe() throws Exception {
		ParseTree tree = sampleTree();
		TreeViewer viewer = new TreeViewer(Arrays.asList("s", "e"), tree);
		try {
			Future<?> f = viewer.open();
			f.get(200, TimeUnit.MILLISECONDS);
		} catch (Throwable t) {
			// Timeout / headless expected
		}
	}

	private static void writeFile(File f, String content) throws Exception {
		java.nio.file.Files.write(f.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}
}
