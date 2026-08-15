/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.dfa;

import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.ATNSimulator;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.PredictionContext;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

/**
 * {@link DFASerializer} context-edge labels and accept-state string forms.
 */
public class TestDFASerializerLabelCoverage {

	@Test
	public void contextLabelsAndStateString() {
		final ATN grammar = new ATN(ATNType.PARSER, 2);
		org.antlr.v4.runtime.atn.BasicState start = new org.antlr.v4.runtime.atn.BasicState();
		grammar.addState(start);
		final DFA decision = new DFA(start, 0);
		class Exposed extends DFASerializer {
			Exposed() { super(decision, VocabularyImpl.EMPTY_VOCABULARY, new String[] { "s" }, grammar); }
			String ctx(int i) { return getContextLabel(i); }
			String st(DFAState s) { return getStateString(s); }
		}
		Exposed ser = new Exposed();
		assertNotNull(ser.ctx(PredictionContext.EMPTY_FULL_STATE_KEY));
		assertNotNull(ser.ctx(PredictionContext.EMPTY_LOCAL_STATE_KEY));
		assertNotNull(ser.ctx(0));
		assertNotNull(ser.st(ATNSimulator.ERROR));
		DFAState s = new DFAState(decision, new ATNConfigSet());
		s.setAcceptState(new AcceptStateInfo(2));
		s.predicates = new DFAState.PredPrediction[0];
		assertNotNull(ser.st(s));
	}
}
