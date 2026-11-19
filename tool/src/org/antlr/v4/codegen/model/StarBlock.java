/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen.model;

import org.antlr.v4.codegen.OutputModelFactory;
import org.antlr.v4.runtime.atn.StarLoopEntryState;
import org.antlr.v4.tool.ast.GrammarAST;

import java.util.List;

public class StarBlock extends Loop {
	public String loopLabel;
	private final MatchToken candidateSingleTokenMatch;

	public StarBlock(OutputModelFactory factory,
					 GrammarAST blkOrEbnfRootAST,
					 List<CodeBlockForAlt> alts)
	{
		super(factory, blkOrEbnfRootAST, alts);
		loopLabel = factory.getTarget().getLoopLabel(blkOrEbnfRootAST);
		StarLoopEntryState star = (StarLoopEntryState)blkOrEbnfRootAST.atnState;
		loopBackStateNumber = star.loopBackState.stateNumber;
		decision = star.decision;
		// Keep raw adaptivePredict loop when force_atn is active for diagnostics/tests.
		candidateSingleTokenMatch = factory.getGrammar().tool.force_atn
			? null
			: extractSingleTokenMatch(alts);
	}

	/**
	 * Returns the single unlabeled non-EOF {@link MatchToken} when this star
	 * block has no extra iteration ops and can be emitted as a compact
	 * {@code _matchStarPredict(...)} call.
	 */
	public MatchToken getSingleTokenMatch() {
		if (candidateSingleTokenMatch != null && (iteration == null || iteration.isEmpty())) {
			return candidateSingleTokenMatch;
		}
		return null;
	}
}
