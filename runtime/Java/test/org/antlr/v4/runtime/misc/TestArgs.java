/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class TestArgs {
	@Test
	public void notNullWithNonNullValueDoesNothing() {
		Args.notNull("param", "value");
		Args.notNull("param", Integer.valueOf(1));
	}

	@Test
	public void notNullWithNullThrows() {
		Throwable t = assertThrows(NullPointerException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				Args.notNull("myParam", null);
			}
		});
		assertEquals("myParam cannot be null.", t.getMessage());
	}
}
