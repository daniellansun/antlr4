/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen.model;

import org.antlr.v4.codegen.OutputModelFactory;

/**
 * Private typed-context helper method emitted only for semantic predicates
 * whose expression references {@code _localctx} with a rule-specific subtype.
 */
public class FlatSempredHelper extends OutputModelObject {
	public final int index;
	public final String ctxType;
	@ModelElement public final Action action;

	public FlatSempredHelper(OutputModelFactory factory, int index, String ctxType, Action action) {
		super(factory);
		this.index = index;
		this.ctxType = ctxType;
		this.action = action;
	}
}
