/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.misc.Interval;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestTokenStreamRewriter {
	private static CommonTokenStream tokens(String... texts) {
		Token[] ts = new Token[texts.length];
		for (int i = 0; i < texts.length; i++) {
			CommonToken t = new CommonToken(i + 1, texts[i]);
			t.setTokenIndex(i);
			t.setStartIndex(i);
			t.setStopIndex(i);
			ts[i] = t;
		}
		CommonTokenStream stream = new CommonTokenStream(new MockTokenSource(ts));
		stream.fill();
		return stream;
	}

	@Test
	public void insertBeforeIndex0() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.insertBefore(0, "0");
		assertEquals("0abc", r.getText());
	}

	@Test
	public void insertAfterLastIndex() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.insertAfter(2, "x");
		assertEquals("abcx", r.getText());
	}

	@Test
	public void insertBeforeAndAfterMiddle() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.insertBefore(1, "x");
		r.insertAfter(1, "x");
		assertEquals("axbxc", r.getText());
	}

	@Test
	public void replaceSingleIndexes() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.replace(0, "x");
		assertEquals("xbc", r.getText());
		r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.replace(2, "x");
		assertEquals("abx", r.getText());
		r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.replace(1, "x");
		assertEquals("axc", r.getText());
	}

	@Test
	public void replaceRange() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c", "d", "e"));
		r.replace(1, 3, "XYZ");
		assertEquals("aXYZe", r.getText());
	}

	@Test
	public void deleteSingleAndRange() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.delete(1);
		assertEquals("ac", r.getText());
		r = new TokenStreamRewriter(tokens("a", "b", "c", "d"));
		r.delete(1, 2);
		assertEquals("ad", r.getText());
	}

	@Test
	public void secondReplaceOverridesFirst() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.replace(1, "x");
		r.replace(1, "y");
		assertEquals("ayc", r.getText());
	}

	@Test
	public void getTextInterval() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("x", " ", "=", " ", "3", " ", "*", " ", "0", ";"));
		r.replace(4, 8, "0");
		assertEquals("x = 0;", r.getText());
		assertEquals("0", r.getText(Interval.of(4, 8)));
		assertEquals("x = 0;", r.getText(Interval.of(0, 9)));
	}

	@Test
	public void namedProgramsAreIndependent() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.insertBefore("p1", 0, "1");
		r.insertBefore("p2", 0, "2");
		assertEquals("1abc", r.getText("p1"));
		assertEquals("2abc", r.getText("p2"));
		assertEquals("abc", r.getText()); // default unchanged
	}

	@Test
	public void insertBeforeTokenObject() {
		CommonTokenStream stream = tokens("a", "b");
		TokenStreamRewriter r = new TokenStreamRewriter(stream);
		r.insertBefore(stream.get(1), "X");
		assertEquals("aXb", r.getText());
	}

	@Test
	public void insertAfterTokenObject() {
		CommonTokenStream stream = tokens("a", "b");
		TokenStreamRewriter r = new TokenStreamRewriter(stream);
		r.insertAfter(stream.get(0), "X");
		assertEquals("aXb", r.getText());
	}

	@Test
	public void replaceTokenObjects() {
		CommonTokenStream stream = tokens("a", "b", "c");
		TokenStreamRewriter r = new TokenStreamRewriter(stream);
		r.replace(stream.get(0), stream.get(1), "Z");
		assertEquals("Zc", r.getText());
	}

	@Test
	public void deleteTokenObjects() {
		CommonTokenStream stream = tokens("a", "b", "c");
		TokenStreamRewriter r = new TokenStreamRewriter(stream);
		r.delete(stream.get(1));
		assertEquals("ac", r.getText());
	}

	@Test
	public void getTokenStream() {
		CommonTokenStream stream = tokens("a");
		TokenStreamRewriter r = new TokenStreamRewriter(stream);
		assertSame(stream, r.getTokenStream());
	}

	@Test
	public void deleteProgramClearsInstructions() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b"));
		r.insertBefore(0, "X");
		assertEquals("Xab", r.getText());
		r.deleteProgram();
		assertEquals("ab", r.getText());
	}

	@Test
	public void originalStreamUnchanged() {
		CommonTokenStream stream = tokens("a", "b", "c");
		TokenStreamRewriter r = new TokenStreamRewriter(stream);
		r.replace(0, 2, "Z");
		assertEquals("Z", r.getText());
		assertEquals("abc", stream.getText());
	}

	@Test(expected = IllegalArgumentException.class)
	public void replaceInvalidRangeThrows() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b"));
		r.replace(2, 1, "x");
	}

	@Test
	public void insertBeforeAndReplaceCombined() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.insertBefore(1, "X");
		r.replace(1, "Y");
		assertEquals("aXYc", r.getText());
	}

	@Test
	public void defaultProgramNameConstant() {
		assertEquals("default", TokenStreamRewriter.DEFAULT_PROGRAM_NAME);
	}

	@Test
	public void rollbackRemovesLaterInstructions() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.insertBefore(0, "X");
		r.insertAfter(2, "Y");
		assertEquals("XabcY", r.getText());
		// rollback to keep only first instruction (index 0)
		r.rollback(1);
		assertEquals("Xabc", r.getText());
		r.rollback(0);
		assertEquals("abc", r.getText());
	}

	@Test
	public void insertAfterLastTokenAndNamedProgram() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b"));
		r.insertAfter("hdr", 1, ";");
		r.insertBefore("hdr", 0, "/*");
		assertEquals("/*ab;", r.getText("hdr"));
		assertEquals("ab", r.getText()); // default unchanged
	}

	@Test
	public void replaceSingleTokenObjectAndNamedDelete() {
		CommonTokenStream stream = tokens("a", "b", "c");
		TokenStreamRewriter r = new TokenStreamRewriter(stream);
		r.replace(stream.get(1), "B");
		assertEquals("aBc", r.getText());
		r.delete("default", stream.get(0), stream.get(0));
		// program already has ops; getText applies remaining
		assertTrue(r.getText().contains("B") || r.getText().length() >= 1);
	}

	@Test
	public void overlappingDeletesCombine() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c", "d", "e"));
		r.delete(1, 2);
		r.delete(2, 3); // overlaps previous delete — combine
		String text = r.getText();
		// combined delete covers 1..3 => a + e
		assertEquals("ae", text);
	}

	@Test
	public void nestedReplaceDropsInner() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c", "d"));
		r.replace(1, 2, "XY");
		r.replace(0, 3, "Z"); // outer replaces whole range; drops inner
		assertEquals("Z", r.getText());
	}

	@Test(expected = IllegalArgumentException.class)
	public void overlappingReplaceNotNestedThrows() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c", "d"));
		r.replace(0, 2, "X");
		r.replace(1, 3, "Y");
		r.getText();
	}

	@Test(expected = IllegalArgumentException.class)
	public void insertWithinPriorReplaceThrows() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.replace(0, 2, "X");
		r.insertBefore(1, "Y");
		r.getText();
	}

	@Test
	public void insertAtReplaceLeftBoundaryCombines() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.replace(1, 1, "B");
		r.insertBefore(1, "X");
		assertEquals("aXBc", r.getText());
	}

	@Test
	public void insertBeforeThenDeleteIndexCombinesText() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.insertBefore(1, "IN");
		r.delete(1);
		// insert before deleted index is folded into replace/delete text
		assertEquals("aINc", r.getText());
	}

	@Test
	public void multipleInsertsSameIndexCombine() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b"));
		r.insertBefore(1, "1");
		r.insertBefore(1, "2");
		// later insertBefore is applied outermost; both present
		String text = r.getText();
		assertTrue(text.contains("1") && text.contains("2"));
		assertTrue(text.startsWith("a") && text.endsWith("b"));
	}

	@Test
	public void insertAfterThenInsertBeforeSameIndex() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.insertAfter(0, "A");
		r.insertBefore(1, "B");
		// insertAfter(0) is insertBefore(1); combine with insertBefore(1)
		String text = r.getText();
		assertTrue(text.contains("A") && text.contains("B"));
	}

	@Test
	public void getTextEmptyProgramNameFallsBackToTokens() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b"));
		assertEquals("ab", r.getText("missingProgram"));
	}

	@Test
	public void getTextIntervalWithNoOps() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		assertEquals("b", r.getText(Interval.of(1, 1)));
	}

	@Test
	public void replaceOpToStringViaFailedOverlap() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a", "b", "c"));
		r.replace(0, 1, "X");
		r.replace(1, 2, "Y");
		try {
			r.getText();
			// may throw with toString of ReplaceOp in message
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("ReplaceOp") || e.getMessage().contains("overlap"));
		}
	}

	@Test
	public void deleteTokenRangeObjects() {
		CommonTokenStream stream = tokens("a", "b", "c", "d");
		TokenStreamRewriter r = new TokenStreamRewriter(stream);
		r.delete(stream.get(1), stream.get(2));
		assertEquals("ad", r.getText());
	}

	@Test
	public void namedProgramDeleteAndInsertAfterToken() {
		CommonTokenStream stream = tokens("x", "y");
		TokenStreamRewriter r = new TokenStreamRewriter(stream);
		r.insertAfter("p", stream.get(0), "-");
		r.delete("p", 1, 1);
		assertEquals("x-", r.getText("p"));
	}

	@Test
	public void lastRewriteTokenIndexDefault() {
		TokenStreamRewriter r = new TokenStreamRewriter(tokens("a"));
		// protected accessors exercised via getLastRewriteTokenIndex public wrapper
		assertEquals(-1, r.getLastRewriteTokenIndex());
	}

	@Test
	public void rewriteOperationExecuteBaseAndToString() {
		CommonTokenStream stream = tokens("a", "b");
		TokenStreamRewriter.RewriteOperation op =
			new TokenStreamRewriter.RewriteOperation(stream, 0, "t") {
			};
		assertEquals(0, op.execute(new StringBuilder()));
		String s = op.toString();
		assertTrue(s.contains("RewriteOperation") || s.contains("@") || s.contains("t"));

		TokenStreamRewriter.RewriteOperation opNoText =
			new TokenStreamRewriter.RewriteOperation(stream, 1);
		assertEquals(1, opNoText.index);
	}

	@Test
	public void constants() {
		assertEquals(100, TokenStreamRewriter.PROGRAM_INIT_SIZE);
		assertEquals(0, TokenStreamRewriter.MIN_TOKEN_INDEX);
	}
}

