/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.v4.unicode.UnicodeDataTemplateController;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class TestUnicodeDataCoverage {

	@Test
	public void testUnicodeDataTemplateControllerGetProperties() {
		Map<String, Object> props = UnicodeDataTemplateController.getProperties();
		assertNotNull(props);
		assertTrue(props.containsKey("propertyCodePointRanges"));
		assertTrue(props.containsKey("propertyAliases"));
	}
}
