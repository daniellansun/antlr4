/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen;

import org.antlr.v4.codegen.model.chunk.ActionChunk;
import org.antlr.v4.codegen.model.chunk.ActionTemplate;
import org.antlr.v4.codegen.model.chunk.ActionText;
import org.antlr.v4.codegen.model.chunk.ArgRef;
import org.antlr.v4.codegen.model.chunk.NonLocalAttrRef;
import org.antlr.v4.codegen.model.chunk.RulePropertyRef_parser;
import org.antlr.v4.codegen.model.chunk.SetAttr;
import org.antlr.v4.codegen.model.chunk.SetNonLocalAttr;
import org.antlr.v4.codegen.model.chunk.ThisRulePropertyRef_parser;
import org.junit.Test;
import org.stringtemplate.v4.ST;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Package-private unit tests for {@link OutputModelController#analyzeActionChunks}
 * verifying exhaustive {@link ActionChunk} classification, {@link SetNonLocalAttr}
 * precedence over {@link SetAttr}, {@code rhsChunks} recursion for {@code predIndex},
 * {@link ActionTemplate}, and conservative unknown-subclass fallback.
 */
public class TestOutputModelControllerActionChunks {

	private static final class CustomUnknownChunk extends ActionChunk {
		CustomUnknownChunk() {
			super(null);
		}
	}

	@Test
	public void testAnalyzeActionChunksAllBranches() {
		OutputModelController.ActionRefs refs = new OutputModelController.ActionRefs();

		// 1. Null list and null element guards
		OutputModelController.analyzeActionChunks(null, refs);
		assertFalse(refs.referencesLocalCtx);
		assertFalse(refs.referencesPredIndex);

		OutputModelController.analyzeActionChunks(Collections.<ActionChunk>singletonList(null), refs);
		assertFalse(refs.referencesLocalCtx);
		assertFalse(refs.referencesPredIndex);

		// 2. Parser/non-local refs never set localctx or predIndex
		OutputModelController.analyzeActionChunks(Arrays.<ActionChunk>asList(
			new ThisRulePropertyRef_parser(null, "r"),
			new RulePropertyRef_parser(null, "r"),
			new NonLocalAttrRef(null, "outer", "v", 1)
		), refs);
		assertFalse(refs.referencesLocalCtx);
		assertFalse(refs.referencesPredIndex);

		// 3. SetNonLocalAttr (extends SetAttr!) must NOT set localctx when RHS is plain,
		//    and MUST propagate both predIndex and _localctx from rhsChunks
		refs.reset();
		OutputModelController.analyzeActionChunks(Collections.<ActionChunk>singletonList(
			new SetNonLocalAttr(null, "outer", "v", 1,
				Collections.<ActionChunk>singletonList(new ActionText(null, "predIndex == 0")))
		), refs);
		assertFalse("SetNonLocalAttr must be checked before SetAttr", refs.referencesLocalCtx);
		assertTrue("SetNonLocalAttr must inspect rhsChunks for predIndex", refs.referencesPredIndex);

		refs.reset();
		OutputModelController.analyzeActionChunks(Collections.<ActionChunk>singletonList(
			new SetNonLocalAttr(null, "outer", "v", 1,
				Collections.<ActionChunk>singletonList(new ArgRef(null, "x")))
		), refs);
		assertTrue("SetNonLocalAttr must propagate _localctx from rhsChunks", refs.referencesLocalCtx);
		assertFalse(refs.referencesPredIndex);

		// 4. SetAttr sets localctx AND inspects rhsChunks for predIndex
		refs.reset();
		OutputModelController.analyzeActionChunks(Collections.<ActionChunk>singletonList(
			new SetAttr(null, "v",
				Collections.<ActionChunk>singletonList(new ActionText(null, "predIndex + 1")))
		), refs);
		assertTrue(refs.referencesLocalCtx);
		assertTrue(refs.referencesPredIndex);

		// 5. All localctx-referencing chunk families set localctx only
		for (ActionChunk chunk : Arrays.<ActionChunk>asList(
			new ArgRef(null, "x"),
			new org.antlr.v4.codegen.model.chunk.LocalRef(null, "v"),
			new org.antlr.v4.codegen.model.chunk.RetValueRef(null, "ret"),
			new org.antlr.v4.codegen.model.chunk.QRetValueRef(null, "r", "ret"),
			new org.antlr.v4.codegen.model.chunk.TokenRef(null, "A"),
			new org.antlr.v4.codegen.model.chunk.LabelRef(null, "lbl"),
			new org.antlr.v4.codegen.model.chunk.ListLabelRef(null, "lbls"),
			new org.antlr.v4.codegen.model.chunk.TokenPropertyRef_text(null, "A"),
			new org.antlr.v4.codegen.model.chunk.RulePropertyRef_ctx(null, "p"),
			new org.antlr.v4.codegen.model.chunk.ThisRulePropertyRef_ctx(null, "s")
		)) {
			refs.reset();
			OutputModelController.analyzeActionChunks(Collections.singletonList(chunk), refs);
			assertTrue("Expected localctx for " + chunk.getClass().getSimpleName(), refs.referencesLocalCtx);
			assertFalse("Unexpected predIndex for " + chunk.getClass().getSimpleName(), refs.referencesPredIndex);
		}

		// 6. ActionTemplate conservatively marks both localctx and predIndex
		refs.reset();
		OutputModelController.analyzeActionChunks(Collections.<ActionChunk>singletonList(new ActionTemplate(null, new ST("x"))), refs);
		assertTrue(refs.referencesLocalCtx);
		assertTrue(refs.referencesPredIndex);

		// 7. Unknown future ActionChunk subclass conservatively marks both localctx and predIndex
		refs.reset();
		OutputModelController.analyzeActionChunks(Collections.<ActionChunk>singletonList(new CustomUnknownChunk()), refs);
		assertTrue(refs.referencesLocalCtx);
		assertTrue(refs.referencesPredIndex);
	}
}
