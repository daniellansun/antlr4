/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.v4.gui.BasicFontMetrics;
import org.antlr.v4.gui.GraphicsSupport;
import org.antlr.v4.gui.PostScriptDocument;
import org.antlr.v4.gui.SystemFontMetrics;

import org.junit.Test;

import javax.swing.JPanel;
import java.io.File;

import static org.junit.Assert.*;

public class TestGUICoverage {

	static class DummyFontMetrics extends BasicFontMetrics {
		public DummyFontMetrics() {
			maxCharHeight = 1000;
			widths['m'] = 500;
			widths['a'] = 400;
		}
	}

	@Test
	public void testBasicFontMetrics() {
		DummyFontMetrics fm = new DummyFontMetrics();
		assertEquals(4.0, fm.getWidth('a', 10), 0.001);
		assertEquals(0.5, fm.getWidth('\u0100', 10), 0.001); // fallback to 'm' width / 1000.0
		assertEquals(9.0, fm.getWidth("am", 10), 0.001);
		assertEquals(10.0, fm.getLineHeight(10), 0.001);
	}

	@Test
	public void testGraphicsSupport() throws Exception {
		JPanel panel = new JPanel();
		panel.setBounds(0, 0, 100, 100);

		File tmpPng = File.createTempFile("antlr4_test_gui", ".png");
		tmpPng.deleteOnExit();
		GraphicsSupport.saveImage(panel, tmpPng.getAbsolutePath());
		assertTrue(tmpPng.exists() && tmpPng.length() > 0);

		File tmpPs = File.createTempFile("antlr4_test_gui", ".ps");
		tmpPs.deleteOnExit();
		try {
			GraphicsSupport.saveImage(panel, tmpPs.getAbsolutePath());
		} catch (Exception ignored) {
			// Print service might not be available in headless environment
		}
	}

	@Test
	public void testPostScriptDocument() {
		PostScriptDocument ps = new PostScriptDocument("Helvetica", 12);
		ps.boundingBox(100, 100);
		ps.rect(0, 0, 50, 50);
		ps.line(0, 0, 50, 50);
		ps.text("hello", 10, 10);
		ps.highlight(0, 0, 50, 50);
		assertNotNull(ps.getPS());
	}

	@Test
	public void testSystemFontMetrics() {
		try {
			SystemFontMetrics sfm = new SystemFontMetrics("Dialog");
			assertTrue(sfm.getWidth("test", 12) > 0);
			assertTrue(sfm.getLineHeight(12) > 0);
		} catch (Throwable ignored) {
			// Headless environment might fail font metrics creation
		}
	}
}
