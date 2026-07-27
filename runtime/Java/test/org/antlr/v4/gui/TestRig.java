/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.gui;

/**
 * Test-only stub so {@link org.antlr.v4.runtime.misc.TestRig} can resolve and
 * invoke {@code org.antlr.v4.gui.TestRig.main} on the test classpath.
 */
public class TestRig {
	/** Last args passed to {@link #main}; for assertions. */
	public static String[] lastArgs;

	/** When true, {@link #main} throws to exercise the proxy catch path. */
	public static boolean throwOnMain;

	public static void main(String[] args) {
		lastArgs = args;
		if (throwOnMain) {
			throw new RuntimeException("stub TestRig failure");
		}
		// no-op success path
	}
}
