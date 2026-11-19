/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen.model;

import org.antlr.v4.codegen.OutputModelFactory;
import org.antlr.v4.codegen.model.decl.Decl;
import org.antlr.v4.codegen.model.decl.TokenTypeDecl;
import org.antlr.v4.misc.Utils;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.tool.ast.GrammarAST;

import java.util.ArrayList;
import java.util.List;

/** The class hierarchy underneath SrcOp is pretty deep but makes sense that,
 *  for example LL1StarBlock is a kind of LL1Loop which is a kind of Choice.
 *  The problem is it's impossible to figure
 *  out how to construct one of these deeply nested objects because of the
 *  long super constructor call chain. Instead, I decided to in-line all of
 *  this and then look for opportunities to re-factor code into functions.
 *  It makes sense to use a class hierarchy to share data fields, but I don't
 *  think it makes sense to factor code using super constructors because
 *  it has too much work to do.
 */
public abstract class Choice extends RuleElement {
	public int decision = -1;
	public Decl label;

	@ModelElement public List<CodeBlockForAlt> alts;
	@ModelElement public List<SrcOp> preamble = new ArrayList<SrcOp>();

	public Choice(OutputModelFactory factory,
				  GrammarAST blkOrEbnfRootAST,
				  List<CodeBlockForAlt> alts)
	{
		super(factory, blkOrEbnfRootAST);
		this.alts = alts;
	}

	public void addPreambleOp(SrcOp op) {
		preamble.add(op);
	}

	public List<String[]> getAltLookaheadAsStringLists(IntervalSet[] altLookSets) {
		List<String[]> altLook = new ArrayList<String[]>();
		for (IntervalSet s : altLookSets) {
			altLook.add(factory.getTarget().getTokenTypesAsTargetLabels(factory.getGrammar(), s.toArray()));
		}
		return altLook;
	}

	public static final ThreadLocal<Boolean> TO_ADD_PREAMBLEOP = ThreadLocal.withInitial(() -> true);
	public TestSetInline addCodeForLookaheadTempVar(IntervalSet look) {
		List<SrcOp> testOps = factory.getLL1Test(look, ast);
		TestSetInline expr = Utils.find(testOps, TestSetInline.class);
		if (expr != null) {
			Decl d = new TokenTypeDecl(factory, expr.varName);
			factory.getCurrentRuleFunction().addLocalDecl(d);
			if (TO_ADD_PREAMBLEOP.get()) {
				CaptureNextTokenType nextType = new CaptureNextTokenType(factory,expr.varName);
				addPreambleOp(nextType);
			}
		}
		return expr;
	}

	public ThrowNoViableAlt getThrowNoViableAlt(OutputModelFactory factory,
												GrammarAST blkAST,
												IntervalSet expecting) {
		return new ThrowNoViableAlt(factory, blkAST, expecting);
	}

	/**
	 * Extracts the sole unlabeled non-EOF {@link MatchToken} when {@code alts}
	 * contains a single alternative whose body reduces (through any empty
	 * nested {@link CodeBlockForAlt} wrappers) to that token match alone.
	 */
	static MatchToken extractSingleTokenMatch(List<CodeBlockForAlt> alts) {
		if (alts == null || alts.size() != 1) {
			return null;
		}
		SrcOp cur = alts.get(0);
		while (cur != null && cur.getClass() == CodeBlockForAlt.class) {
			CodeBlockForAlt blk = (CodeBlockForAlt) cur;
			if ((blk.locals != null && !blk.locals.isEmpty())
					|| (blk.preamble != null && !blk.preamble.isEmpty())
					|| blk.ops == null || blk.ops.isEmpty()) {
				return null;
			}
			// DefaultOutputModelFactory.list(matchOp, null) places trailing nulls in ops.
			SrcOp sole = null;
			for (int i = 0, n = blk.ops.size(); i < n; i++) {
				SrcOp op = blk.ops.get(i);
				if (op == null) {
					continue;
				}
				if (sole != null) {
					return null;
				}
				sole = op;
			}
			if (sole == null) {
				return null;
			}
			cur = sole;
		}
		if (cur != null && cur.getClass() == MatchToken.class) {
			MatchToken mt = (MatchToken) cur;
			if ((mt.labels == null || mt.labels.isEmpty()) && !mt.isEOF
					&& mt.name != null && mt.ttype > 0) {
				return mt;
			}
		}
		return null;
	}
}
