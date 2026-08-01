/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.v4.Tool;
import org.antlr.v4.tool.ErrorManager;
import org.antlr.v4.tool.ErrorSeverity;
import org.antlr.v4.tool.ErrorType;
import org.junit.Test;

import static org.junit.Assert.*;

public class TestErrorManagerAndToolDeepCoverage {

	@Test
	public void testErrorTypeEnumAndErrorManager() {
		Tool tool = new Tool();
		ErrorManager errMgr = new ErrorManager(tool);

		for (ErrorType et : ErrorType.values()) {
			assertNotNull(et.name());
			ErrorSeverity severity = et.severity;
			assertNotNull(severity);
		}
	}

	@Test
	public void testToolCommandLineParsingOptions() {
		Tool tool = new Tool(new String[]{
				"-o", "target/out",
				"-lib", "target/lib",
				"-encoding", "UTF-8",
				"-listener", "-visitor",
				"-package", "com.foo",
				"-Werror"
		});

		assertEquals("target/out", tool.outputDirectory);
		assertNotNull(tool.libDirectory);
		assertEquals("UTF-8", tool.grammarEncoding);
		assertTrue(tool.gen_listener);
		assertTrue(tool.gen_visitor);
		assertEquals("com.foo", tool.genPackage);
		assertTrue(tool.warnings_are_errors);
	}
}
