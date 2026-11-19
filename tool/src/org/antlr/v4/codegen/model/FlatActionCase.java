/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen.model;

import org.antlr.v4.codegen.OutputModelFactory;

/**
 * Single case entry for flat dense {@code switch (actionIndex)} dispatch in
 * generated {@code Lexer.action(RuleContext, int, int)}.
 */
public class FlatActionCase extends OutputModelObject {
	public final int index;
	@ModelElement public final Action action;

	public FlatActionCase(OutputModelFactory factory, int index, Action action) {
		super(factory);
		this.index = index;
		this.action = action;
	}
}
