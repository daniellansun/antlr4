/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen.target;

import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.tool.ast.GrammarAST;
import org.stringtemplate.v4.STGroup;

/** Test-only target. Loaded when a grammar sets {@code language=Cov}. */
public class CovTarget extends JavaTarget {
	public CovTarget(CodeGenerator gen) {
		super(gen);
	}

	@Override
	public boolean needsHeader() {
		return true;
	}

	@Override
	protected boolean visibleGrammarSymbolCausesIssueInGeneratedCode(GrammarAST idNode) {
		return "bad".equals(idNode.getText());
	}

	@Override
	protected STGroup loadTemplates() {
		STGroup group = new STGroup('<', '>');
		group.defineTemplate("codeFileExtension", ".cov");
		group.defineTemplate("headerFile", "header");
		group.defineTemplate("headerFileExtension", ".h");
		return group;
	}
}
