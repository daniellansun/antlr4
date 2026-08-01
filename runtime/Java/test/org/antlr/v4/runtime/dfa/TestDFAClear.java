/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.dfa;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.TokensStartState;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Verifies in-place {@link DFA#clear()} reuses the DFA shell for
 * {@link org.antlr.v4.runtime.atn.ATN#clearDFA()}.
 */
public class TestDFAClear {

	@Test
	public void clearResetsStatesAndStart() {
		ATN atn = new ATN(ATNType.PARSER, 10);
		BasicState start = new BasicState();
		start.ruleIndex = 0;
		atn.addState(start);
		// DecisionState is abstract in some versions — use a simple BasicState
		// attached as atn start for the DFA constructor path used by modes.
		TokensStartState modeStart = new TokensStartState();
		modeStart.ruleIndex = 0;
		atn.addState(modeStart);

		DFA dfa = new DFA(modeStart, 0);
		DFAState s = new DFAState(dfa, new org.antlr.v4.runtime.atn.ATNConfigSet());
		s = dfa.addState(s);
		dfa.s0.set(s);
		assertNotNull(dfa.s0.get());
		assertTrue(dfa.states.size() >= 1);

		DFA same = dfa;
		dfa.clear();
		assertSame(same, dfa);
		assertNull(dfa.s0.get());
		assertTrue(dfa.states.isEmpty());
	}

	@Test
	public void atnClearDfaReusesArraySlots() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);
		TokensStartState modeStart = new TokensStartState();
		atn.addState(modeStart);
		atn.modeToStartState.add(modeStart);
		atn.modeToDFA = new DFA[] { new DFA(modeStart) };
		DFA original = atn.modeToDFA[0];
		atn.clearDFA();
		assertSame(original, atn.modeToDFA[0]);
		assertNull(atn.modeToDFA[0].s0.get());
	}
}
