/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen.model;

import org.antlr.v4.codegen.OutputModelFactory;
import org.antlr.v4.codegen.model.decl.TokenTypeDecl;
import org.antlr.v4.runtime.atn.DecisionState;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.tool.ast.GrammarAST;

import java.util.ArrayList;
import java.util.List;

/** (A | B | C) */
public class LL1AltBlock extends LL1Choice {
	/**
	 * Compact inline bitmask lookahead tests per alternative when a 2- or
	 * 3-arm LL(1) choice would otherwise expand into a bloated sparse
	 * {@code lookupswitch}. {@code null} when a standard {@code switch} is
	 * preferred.
	 */
	@ModelElement public List<TestSetInline> altTests;

	/**
	 * Non-null when this LL(1) choice is the outermost decision of a rule
	 * using deferred labeled-alt context allocation (Tier 2b).
	 */
	public RuleFunction deferredRule;

	public LL1AltBlock(OutputModelFactory factory, GrammarAST blkAST, List<CodeBlockForAlt> alts) {
		super(factory, blkAST, alts);
		this.decision = ((DecisionState)blkAST.atnState).decision;

		/** Lookahead for each alt 1..n */
		IntervalSet[] altLookSets = factory.getGrammar().decisionLOOK.get(decision);
		altLook = getAltLookaheadAsStringLists(altLookSets);

		IntervalSet expecting = IntervalSet.or(altLookSets); // combine alt sets
		this.error = getThrowNoViableAlt(factory, blkAST, expecting);

		maybeBuildCompactAltTests(factory, blkAST, altLookSets, alts.size());
	}

	protected void maybeBuildCompactAltTests(OutputModelFactory factory,
											 GrammarAST blkAST,
											 IntervalSet[] altLookSets,
											 int numAlts) {
		if (!factory.getGenerator().getTarget().supportsCompactLL1AltTests()) {
			return;
		}
		if (numAlts < 1 || numAlts > 3 || altLookSets == null || altLookSets.length < numAlts) {
			return;
		}
		int totalCases = 0;
		for (int i = 0; i < numAlts; i++) {
			if (altLookSets[i] == null || altLookSets[i].isNil()) {
				return;
			}
			totalCases += altLookSets[i].size();
		}
		boolean tinyChoice = (numAlts <= 2 && totalCases <= 3);
		boolean largeSparseChoice = (totalCases >= 6);
		if (!tinyChoice && !largeSparseChoice) {
			return;
		}
		int wordSize = factory.getGenerator().getTarget().getInlineTestSetWordSize();
		List<TestSetInline> tests = new ArrayList<TestSetInline>(numAlts);
		int totalBitsets = 0;
		for (int i = 0; i < numAlts; i++) {
			TestSetInline test = new TestSetInline(factory, blkAST, altLookSets[i], wordSize);
			if (test.bitsets.length == 0 || test.bitsets.length > 4) {
				return;
			}
			totalBitsets += test.bitsets.length;
			tests.add(test);
		}
		if (tinyChoice || (totalBitsets <= 6 && totalBitsets * 3 < totalCases)) {
			this.altTests = tests;
			RuleFunction rf = factory.getCurrentRuleFunction();
			if (rf != null) {
				rf.addLocalDecl(new TokenTypeDecl(factory, "_la"));
			}
		}
	}
}
