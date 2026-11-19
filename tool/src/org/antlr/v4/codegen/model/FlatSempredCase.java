/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen.model;

import org.antlr.v4.codegen.OutputModelFactory;

/**
 * Single case entry for flat dense {@code switch (predIndex)} dispatch in
 * generated {@code sempred(RuleContext, int, int)}.
 */
public class FlatSempredCase extends OutputModelObject {
	public final int index;
	public final String ctxType;
	public final boolean needsTypedCtx;
	public final boolean outlined;
	@ModelElement public final Action action;

	public FlatSempredCase(OutputModelFactory factory, int index, String ctxType, boolean needsTypedCtx, boolean outlined, Action action) {
		super(factory);
		this.index = index;
		this.ctxType = ctxType;
		this.needsTypedCtx = needsTypedCtx;
		this.outlined = outlined;
		this.action = action;
	}
}
