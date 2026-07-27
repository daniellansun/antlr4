/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Test;

import java.util.concurrent.CancellationException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestParseCancellationException {
	@Test
	public void constructors() {
		ParseCancellationException e1 = new ParseCancellationException();
		assertTrue(e1 instanceof CancellationException);
		assertNull(e1.getCause());

		ParseCancellationException e2 = new ParseCancellationException("msg");
		assertEquals("msg", e2.getMessage());

		RuntimeException cause = new RuntimeException("root");
		ParseCancellationException e3 = new ParseCancellationException(cause);
		assertSame(cause, e3.getCause());

		ParseCancellationException e4 = new ParseCancellationException("m", cause);
		assertEquals("m", e4.getMessage());
		assertSame(cause, e4.getCause());
	}
}
