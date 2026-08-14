/* Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.NotNull;

/**
 * Snapshot of DFA simulation position for {@link ParserATNSimulator#execDFA}
 * / {@link ParserATNSimulator#execATN}.
 *
 * <p>The simulator may recycle the instance returned by
 * {@link ParserATNSimulator#getStartState} for the next prediction. Treat
 * field values as valid only until the next {@code adaptivePredict} on the
 * same simulator.</p>
 *
 * @author Sam Harwell
 */
public class SimulatorState {
	public ParserRuleContext outerContext;

	public DFAState s0;

	public boolean useContext;
	public ParserRuleContext remainingOuterContext;

	public SimulatorState(ParserRuleContext outerContext, @NotNull DFAState s0, boolean useContext, ParserRuleContext remainingOuterContext) {
		assign(outerContext, s0, useContext, remainingOuterContext);
	}

	final void assign(ParserRuleContext outerContext, @NotNull DFAState s0, boolean useContext, ParserRuleContext remainingOuterContext) {
		this.outerContext = outerContext != null ? outerContext : ParserRuleContext.emptyContext();
		this.s0 = s0;
		this.useContext = useContext;
		this.remainingOuterContext = remainingOuterContext;
	}
}
