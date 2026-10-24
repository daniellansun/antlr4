/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen.target;

import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.tool.ast.GrammarAST;
import org.stringtemplate.v4.STGroup;

/** Target whose templates have no header-file extension. */
public class PlainTarget extends JavaTarget {
	public PlainTarget(CodeGenerator gen) {
		super(gen);
	}

	@Override
	protected boolean visibleGrammarSymbolCausesIssueInGeneratedCode(GrammarAST idNode) {
		return false;
	}

	@Override
	protected STGroup loadTemplates() {
		STGroup group = new STGroup('<', '>');
		group.defineTemplate("codeFileExtension", ".txt");
		return group;
	}
}
