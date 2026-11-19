/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen.model;

import org.antlr.v4.codegen.OutputModelFactory;
import org.antlr.v4.runtime.atn.StarLoopEntryState;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.tool.ast.GrammarAST;

import java.util.List;

/** */
public class LL1StarBlockSingleAlt extends LL1Loop {
	private final MatchToken singleTokenCandidate;

	public LL1StarBlockSingleAlt(OutputModelFactory factory, GrammarAST starRoot, List<CodeBlockForAlt> alts) {
		super(factory, starRoot, alts);

		StarLoopEntryState star = (StarLoopEntryState)starRoot.atnState;
		loopBackStateNumber = star.loopBackState.stateNumber;
		this.decision = star.decision;
		IntervalSet[] altLookSets = factory.getGrammar().decisionLOOK.get(decision);
		assert altLookSets.length == 2;
		IntervalSet enterLook = altLookSets[0];
		@SuppressWarnings("unused")
		IntervalSet exitLook = altLookSets[1];
		MatchToken candidate = factory.getGenerator().getTarget().supportsCompactSingleTokenStarLoop()
				? extractSingleTokenMatch(alts)
				: null;
		if (candidate != null && enterLook.size() == 1 && enterLook.getMinElement() == candidate.ttype) {
			singleTokenCandidate = candidate;
		}
		else {
			singleTokenCandidate = null;
			loopExpr = addCodeForLoopLookaheadTempVar(enterLook);
		}
	}

	/**
	 * Non-null when this star loop matches solely a single unlabeled non-EOF
	 * token whose type equals the sole entry lookahead symbol (e.g. {@code NL*}),
	 * and no extension has added preamble/iteration operations.
	 */
	public MatchToken getSingleTokenMatch() {
		if (singleTokenCandidate == null) {
			return null;
		}
		if ((preamble != null && !preamble.isEmpty()) || (iteration != null && !iteration.isEmpty())) {
			return null;
		}
		return singleTokenCandidate;
	}
}
