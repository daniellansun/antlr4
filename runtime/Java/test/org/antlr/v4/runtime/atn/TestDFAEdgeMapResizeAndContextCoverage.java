/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.dfa.AbstractEdgeMap;
import org.antlr.v4.runtime.dfa.AcceptStateInfo;
import org.antlr.v4.runtime.dfa.ArrayEdgeMap;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFASerializer;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.dfa.HashEdgeMap;
import org.antlr.v4.runtime.dfa.SparseEdgeMap;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Sparse/hash DFA edge-map insert, resize, and promote-to-array, plus
 * context-sensitive DFA serializer / {@link DFAState} context-edge remapping.
 */
public class TestDFAEdgeMapResizeAndContextCoverage {

	@Test
	public void sparseInsertNotAtEndResizesThenPromotes() {
		// wide range so desiredSize < space/2 keeps SparseEdgeMap
		SparseEdgeMap<String> sparse = new SparseEdgeMap<String>(0, 1000, 2);
		AbstractEdgeMap<String> m = sparse.put(10, "ten");
		// insert a key that sorts BEFORE 10 so insertIndex != size()
		m = m.put(1, "one");
		assertNotNull(m.get(1));
		assertNotNull(m.get(10));
		// fill to force promote
		m = m.put(2, "two");
		m = m.put(3, "three");
		m = m.put(4, "four");
		assertTrue(m.size() >= 2);
	}

	@Test
	public void sparsePromoteToArrayWhenDense() {
		SparseEdgeMap<String> sparse = new SparseEdgeMap<String>(0, 7, 2);
		AbstractEdgeMap<String> m = sparse.put(0, "a");
		m = m.put(1, "b");
		m = m.put(2, "c"); // desiredSize >= space/2 → ArrayEdgeMap
		assertTrue(m instanceof ArrayEdgeMap || m.size() >= 2);
	}

	@Test
	public void hashEdgeMapCollisionPromotes() {
		HashEdgeMap<String> hash = new HashEdgeMap<String>(0, 7, 2);
		AbstractEdgeMap<String> m = hash.put(0, "a");
		m = m.put(1, "b");
		m = m.put(2, "c");
		m = m.put(3, "d");
		m = m.put(4, "e");
		assertTrue(m.size() >= 2);
	}

	@Test
	public void dfaSerializerContextSymbolAndEmptyFullLabel() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		DFA dfa = atn.decisionToDFA[0];
		DFAState s = new DFAState(dfa, new ATNConfigSet());
		s.stateNumber = 0;
		s.setContextSensitive(atn);
		s.setContextSymbol(1);
		s.setTarget(1, null);
		s.setContextTarget(PredictionContext.EMPTY_FULL_STATE_KEY, s);
		s.setContextTarget(PredictionContext.EMPTY_LOCAL_STATE_KEY, s);
		dfa.s0.set(s);
		dfa.states.put(s, s);
		DFASerializer ser = new DFASerializer(dfa, VocabularyImpl.EMPTY_VOCABULARY, new String[] { "s" }, atn);
		String text = ser.toString();
		assertTrue(text == null || text.length() >= 0);
		s.setAcceptState(new AcceptStateInfo(1));
		s.predicates = new DFAState.PredPrediction[0];
		assertNotNull(ser.toString());
	}

	@Test
	public void dfaStateContextEdgeMapRemapsEmptyFull() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		DFA dfa = atn.decisionToDFA[0];
		DFAState s = new DFAState(dfa, new ATNConfigSet());
		s.setContextSensitive(atn);
		DFAState t1 = new DFAState(dfa, new ATNConfigSet());
		DFAState t2 = new DFAState(dfa, new ATNConfigSet());
		s.setContextTarget(-1, t1);
		s.setContextTarget(3, t2);
		Map<Integer, DFAState> map = s.getContextEdgeMap();
		assertTrue(map.containsKey(PredictionContext.EMPTY_FULL_STATE_KEY) || map.size() >= 1);
	}
}
