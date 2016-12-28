/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen.model.chunk;

import org.antlr.v4.codegen.model.decl.StructDecl;

public class LabelRef extends ActionChunk {
	public String name;

	public LabelRef(StructDecl ctx, String name) {
		super(ctx);
		this.name = name;
	}
}
