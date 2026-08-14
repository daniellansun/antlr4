/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The following images show the relation of states and
 * {@link ATNState#transitions} for various grammar constructs.
 *
 * <ul>
 *
 * <li>Solid edges marked with an &#0949; indicate a required
 * {@link EpsilonTransition}.</li>
 *
 * <li>Dashed edges indicate locations where any transition derived from
 * {@link Transition} might appear.</li>
 *
 * <li>Dashed nodes are place holders for either a sequence of linked
 * {@link BasicState} states or the inclusion of a block representing a nested
 * construct in one of the forms below.</li>
 *
 * <li>Nodes showing multiple outgoing alternatives with a {@code ...} support
 * any number of alternatives (one or more). Nodes without the {@code ...} only
 * support the exact number of alternatives shown in the diagram.</li>
 *
 * </ul>
 *
 * <h2>Basic Blocks</h2>
 *
 * <h3>Rule</h3>
 *
 * <embed src="images/Rule.svg" type="image/svg+xml"/>
 *
 * <h3>Block of 1 or more alternatives</h3>
 *
 * <embed src="images/Block.svg" type="image/svg+xml"/>
 *
 * <h2>Greedy Loops</h2>
 *
 * <h3>Greedy Closure: {@code (...)*}</h3>
 *
 * <embed src="images/ClosureGreedy.svg" type="image/svg+xml"/>
 *
 * <h3>Greedy Positive Closure: {@code (...)+}</h3>
 *
 * <embed src="images/PositiveClosureGreedy.svg" type="image/svg+xml"/>
 *
 * <h3>Greedy Optional: {@code (...)?}</h3>
 *
 * <embed src="images/OptionalGreedy.svg" type="image/svg+xml"/>
 *
 * <h2>Non-Greedy Loops</h2>
 *
 * <h3>Non-Greedy Closure: {@code (...)*?}</h3>
 *
 * <embed src="images/ClosureNonGreedy.svg" type="image/svg+xml"/>
 *
 * <h3>Non-Greedy Positive Closure: {@code (...)+?}</h3>
 *
 * <embed src="images/PositiveClosureNonGreedy.svg" type="image/svg+xml"/>
 *
 * <h3>Non-Greedy Optional: {@code (...)??}</h3>
 *
 * <embed src="images/OptionalNonGreedy.svg" type="image/svg+xml"/>
 */
public abstract class ATNState {
	public static final int INITIAL_NUM_TRANSITIONS = 4;

	// constants for serialization
	public static final int INVALID_TYPE = 0;
	public static final int BASIC = 1;
	public static final int RULE_START = 2;
	public static final int BLOCK_START = 3;
	public static final int PLUS_BLOCK_START = 4;
	public static final int STAR_BLOCK_START = 5;
	public static final int TOKEN_START = 6;
	public static final int RULE_STOP = 7;
	public static final int BLOCK_END = 8;
	public static final int STAR_LOOP_BACK = 9;
	public static final int STAR_LOOP_ENTRY = 10;
	public static final int PLUS_LOOP_BACK = 11;
	public static final int LOOP_END = 12;

	public static final List<String> serializationNames =
		Collections.unmodifiableList(Arrays.asList(
			"INVALID",
			"BASIC",
			"RULE_START",
			"BLOCK_START",
			"PLUS_BLOCK_START",
			"STAR_BLOCK_START",
			"TOKEN_START",
			"RULE_STOP",
			"BLOCK_END",
			"STAR_LOOP_BACK",
			"STAR_LOOP_ENTRY",
			"PLUS_LOOP_BACK",
			"LOOP_END"
		));

	public static final int INVALID_STATE_NUMBER = -1;

    /** Which ATN are we in? */
   	public ATN atn = null;

	public int stateNumber = INVALID_STATE_NUMBER;

	public int ruleIndex; // at runtime, we don't have Rule objects

	public boolean epsilonOnlyTransitions = false;

	/** Track the transitions emanating from this ATN state. */
	protected final List<Transition> transitions =
		new ArrayList<>(INITIAL_NUM_TRANSITIONS);

	protected List<Transition> optimizedTransitions = transitions;

	/**
	 * Optional array view of {@link #optimizedTransitions} for the prediction
	 * hot path. Populated by {@link #freezeOptimizedTransitions()} after ATN
	 * construction / deserialization finishes mutating transitions. Cleared
	 * automatically if optimized transitions are mutated again.
	 *
	 * <p>PERF: {@link #getOptimizedTransition(int)} and
	 * {@link #getNumberOfOptimizedTransitions()} prefer this array so epsilon
	 * closure and reach avoid {@link ArrayList#get} / {@link ArrayList#size}
	 * virtual calls and bounds-check overhead on every edge walk.</p>
	 */
	private Transition[] optimizedTransitionsArray;

	/** Used to cache lookahead during parsing, not used during construction */
    public IntervalSet nextTokenWithinRule;

	/**
	 * Gets the state number.
	 *
	 * @return the state number
	 */
	public final int getStateNumber() {
		return stateNumber;
	}

	/**
	 * For all states except {@link RuleStopState}, this returns the state
	 * number. Returns -1 for stop states.
	 *
	 * @return -1 for {@link RuleStopState}, otherwise the state number
	 */
	public int getNonStopStateNumber() {
		return getStateNumber();
	}

	@Override
	public int hashCode() { return stateNumber; }

	@Override
	public boolean equals(Object o) {
		// are these states same object?
		if ( o instanceof ATNState ) return stateNumber==((ATNState)o).stateNumber;
		return false;
	}

	public boolean isNonGreedyExitState() {
		return false;
	}

	@Override
	public String toString() {
		return String.valueOf(stateNumber);
	}

	public Transition[] getTransitions() {
		return transitions.toArray(new Transition[0]);
	}

	public int getNumberOfTransitions() {
		return transitions.size();
	}

	public void addTransition(Transition e) {
		addTransition(transitions.size(), e);
	}

	public void addTransition(int index, Transition e) {
		if (transitions.isEmpty()) {
			epsilonOnlyTransitions = e.isEpsilon();
		}
		else if (epsilonOnlyTransitions != e.isEpsilon()) {
			System.err.format(Locale.getDefault(), "ATN state %d has both epsilon and non-epsilon transitions.\n", stateNumber);
			epsilonOnlyTransitions = false;
		}

		// When optimizedTransitions still aliases transitions, invalidate any
		// freeze snapshot so hot-path array views cannot miss the new edge.
		if (optimizedTransitions == transitions) {
			optimizedTransitionsArray = null;
		}
		transitions.add(index, e);
	}

	public Transition transition(int i) {
		return transitions.get(i);
	}

	public void setTransition(int i, Transition e) {
		if (optimizedTransitions == transitions) {
			optimizedTransitionsArray = null;
		}
		transitions.set(i, e);
	}

	public Transition removeTransition(int index) {
		if (optimizedTransitions == transitions) {
			optimizedTransitionsArray = null;
		}
		return transitions.remove(index);
	}

	public abstract int getStateType();

	public final boolean onlyHasEpsilonTransitions() {
		return epsilonOnlyTransitions;
	}

	public void setRuleIndex(int ruleIndex) { this.ruleIndex = ruleIndex; }

	public boolean isOptimized() {
		return optimizedTransitions != transitions;
	}

	public int getNumberOfOptimizedTransitions() {
		Transition[] arr = optimizedTransitionsArray;
		if (arr != null) {
			return arr.length;
		}
		return optimizedTransitions.size();
	}

	public Transition getOptimizedTransition(int i) {
		Transition[] arr = optimizedTransitionsArray;
		if (arr != null) {
			return arr[i];
		}
		return optimizedTransitions.get(i);
	}

	public void addOptimizedTransition(Transition e) {
		if (!isOptimized()) {
			optimizedTransitions = new ArrayList<>();
		}

		optimizedTransitionsArray = null;
		optimizedTransitions.add(e);
	}

	public void setOptimizedTransition(int i, Transition e) {
		if (!isOptimized()) {
			throw new IllegalStateException();
		}

		optimizedTransitionsArray = null;
		optimizedTransitions.set(i, e);
	}

	public void removeOptimizedTransition(int i) {
		if (!isOptimized()) {
			throw new IllegalStateException();
		}

		optimizedTransitionsArray = null;
		optimizedTransitions.remove(i);
	}

	/**
	 * Snapshot {@link #optimizedTransitions} into a fixed array for hot-path
	 * reads. Safe to call multiple times. When the list is empty, the snapshot
	 * is the shared {@link Transition#EMPTY_ARRAY}.
	 *
	 * <p>Invoked once per state after ATN deserialization / optimization so
	 * runtime prediction never pays {@link ArrayList} dispatch on transition
	 * walks. Subsequent mutations of optimized transitions (or of
	 * {@link #transitions} while it is still aliased as the optimized list)
	 * invalidate the snapshot automatically.</p>
	 */
	public final void freezeOptimizedTransitions() {
		List<Transition> list = optimizedTransitions;
		int n = list.size();
		if (n == 0) {
			optimizedTransitionsArray = Transition.EMPTY_ARRAY;
			return;
		}
		optimizedTransitionsArray = list.toArray(new Transition[n]);
	}

}
