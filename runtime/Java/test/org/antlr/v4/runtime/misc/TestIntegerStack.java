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
import static org.junit.Assert.assertTrue;

public class TestIntegerStack {
	@Test
	public void pushPopPeek() {
		IntegerStack stack = new IntegerStack();
		stack.push(10);
		stack.push(20);
		assertEquals(20, stack.peek());
		assertEquals(20, stack.pop());
		assertEquals(10, stack.peek());
		assertEquals(10, stack.pop());
		assertTrue(stack.isEmpty());
	}

	@Test
	public void capacityAndCopyConstructors() {
		IntegerStack s1 = new IntegerStack(4);
		s1.push(1);
		s1.push(2);
		IntegerStack s2 = new IntegerStack(s1);
		assertEquals(2, s2.size());
		assertEquals(2, s2.peek());
		s2.pop();
		assertEquals(2, s1.size());
	}

	@Test
	public void peekEmptyThrows() {
		final IntegerStack stack = new IntegerStack();
		assertThrows(IndexOutOfBoundsException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				stack.peek();
			}
		});
	}

	@Test
	public void popEmptyThrows() {
		final IntegerStack stack = new IntegerStack();
		assertThrows(IndexOutOfBoundsException.class, new ThrowingRunnable() {
			@Override
			public void run() {
				stack.pop();
			}
		});
	}
}
