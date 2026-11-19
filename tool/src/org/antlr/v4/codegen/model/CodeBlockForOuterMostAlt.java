/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.codegen.model;

import org.antlr.v4.codegen.OutputModelFactory;
import org.antlr.v4.tool.Alternative;

/** The code associated with the outermost alternative of a rule.
 *  Sometimes we might want to treat them differently in the
 *  code generation.
 */
public class CodeBlockForOuterMostAlt extends CodeBlockForAlt {
	/**
	 * The label for the alternative; or null if the alternative is not labeled.
	 */
	public String altLabel;
	/**
	 * The alternative.
	 */
	public Alternative alt;
	/**
	 * Tier 2a: true when the enclosing single-alt rule already constructed the
	 * labeled context directly at rule entry.
	 */
	public boolean directInit;
	/**
	 * Tier 2a with listener generation enabled: fallback copyFrom only when
	 * streaming {@code _parseListeners != null}.
	 */
	public boolean directInitHasListener;
	/**
	 * Tier 2b/2c: non-null when the enclosing rule defers context creation and
	 * {@code enterRule} into this alternative arm.
	 */
	public RuleFunction deferredRule;
	/**
	 * Decision state number for {@code setState}/{@code _sync} on direct entry
	 * under Tier 2b.
	 */
	public int deferredStateNumber;

	public CodeBlockForOuterMostAlt(OutputModelFactory factory, Alternative alt) {
		super(factory);
		this.alt = alt;
		altLabel = alt.ast.altLabel!=null ? alt.ast.altLabel.getText() : null;
	}
}
