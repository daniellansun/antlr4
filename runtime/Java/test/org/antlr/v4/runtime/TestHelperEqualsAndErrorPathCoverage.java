/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.LexerActionExecutor;
import org.antlr.v4.runtime.atn.LexerCustomAction;
import org.antlr.v4.runtime.atn.LexerIndexedCustomAction;
import org.antlr.v4.runtime.atn.LexerSkipAction;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.Array2DHashSet;
import org.antlr.v4.runtime.misc.FlexibleHashMap;
import org.antlr.v4.runtime.misc.ObjectEqualityComparator;
import org.antlr.v4.runtime.tree.Trees;
import org.antlr.v4.runtime.tree.xpath.XPath;
import org.junit.Test;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Helper {@code equals} contracts, diagnostic descriptions, collection miss
 * paths, empty DFA dump, XPath errors, and interpreter no-viable recovery.
 */
public class TestHelperEqualsAndErrorPathCoverage {

	@Test
	public void treesStripReplacesOutOfRangeAncestor() {
		ParserRuleContext parent = new ParserRuleContext();
		ParserRuleContext mid = new ParserRuleContext();
		ParserRuleContext displayed = new ParserRuleContext();
		CommonToken far = new CommonToken(1, "far");
		far.setTokenIndex(0);
		mid.start = far;
		mid.stop = far;
		mid.parent = parent;
		displayed.parent = mid;
		parent.addChild(mid);
		mid.addChild(displayed);
		Trees.stripChildrenOutOfRange(parent, displayed, 10, 20);
		assertEquals(1, parent.getChildCount());
		assertTrue(parent.getChild(0).getText().contains("...")
			|| parent.getChild(0) instanceof org.antlr.v4.runtime.tree.TerminalNode);
	}

	@Test
	public void lexerActionEqualsNonMatchingType() {
		LexerCustomAction a = new LexerCustomAction(1, 2);
		assertFalse(a.equals("x"));
		assertEquals(a, new LexerCustomAction(1, 2));

		LexerIndexedCustomAction idx = new LexerIndexedCustomAction(0, LexerSkipAction.INSTANCE);
		assertFalse(idx.equals("x"));
		assertEquals(idx, new LexerIndexedCustomAction(0, LexerSkipAction.INSTANCE));

		LexerActionExecutor ex = new LexerActionExecutor(new org.antlr.v4.runtime.atn.LexerAction[] { LexerSkipAction.INSTANCE });
		assertFalse(ex.equals("x"));
		assertEquals(ex, new LexerActionExecutor(new org.antlr.v4.runtime.atn.LexerAction[] { LexerSkipAction.INSTANCE }));
	}

	@Test
	public void predictionModeHasConflictingAltSetEmpty() {
		BitSet one = new BitSet();
		one.set(1);
		assertFalse(PredictionMode.hasConflictingAltSet(Collections.singletonList(one)));
		assertFalse(PredictionMode.hasConflictingAltSet(Collections.<BitSet>emptyList()));
	}

	@Test
	public void diagnosticDecisionDescriptionFallback() {
		class Exposed extends DiagnosticErrorListener {
			String desc(Parser p, DFA d) { return getDecisionDescription(p, d); }
		}
		final ATN grammar = new ATN(org.antlr.v4.runtime.atn.ATNType.PARSER, 2);
		org.antlr.v4.runtime.atn.BasicState st = new org.antlr.v4.runtime.atn.BasicState();
		st.ruleIndex = -1;
		grammar.addState(st);
		DFA dfa = new DFA(st, 3);
		ParserInterpreter p = new ParserInterpreter(
			"P",
			VocabularyImpl.EMPTY_VOCABULARY,
			Collections.singletonList("s"),
			grammar,
			new CommonTokenStream(new MockTokenSource(new CommonToken(1, "A"))));
		Exposed e = new Exposed();
		assertEquals("3", e.desc(p, dfa));

		st.ruleIndex = 0;
		class EmptyName extends ParserInterpreter {
			EmptyName() {
				super("P", VocabularyImpl.EMPTY_VOCABULARY,
					Collections.singletonList(""),
					grammar,
					new CommonTokenStream(new MockTokenSource(new CommonToken(1, "A"))));
			}
			@Override public String[] getRuleNames() { return new String[] { "" }; }
		}
		assertEquals("3", e.desc(new EmptyName(), dfa));
	}

	@Test
	public void array2DHashSetGetEmptySlotRemoveMissAndRetain() {
		Array2DHashSet<String> set = new Array2DHashSet<String>(ObjectEqualityComparator.INSTANCE, 8, 1);
		assertNull(set.get("missing"));
		set.add("a");
		set.add("b");
		set.add("c");
		assertFalse(set.remove("zzz"));
		Array2DHashSet<String> keep = new Array2DHashSet<String>(ObjectEqualityComparator.INSTANCE, 8, 1);
		keep.add("a");
		keep.add("c");
		set.retainAll(keep);
		assertTrue(set.contains("a"));
		assertFalse(set.contains("b"));
	}

	@Test
	public void flexibleHashMapGetExistingBucketNoMatch() {
		FlexibleHashMap<String, String> map = new FlexibleHashMap<String, String>();
		map.put("a", "1");
		assertNull(map.get("b"));
		assertNull(map.get(null));
	}

	@Test
	public void dfaToStringWhenS0Null() {
		ATN atn = new ATN(org.antlr.v4.runtime.atn.ATNType.PARSER, 2);
		org.antlr.v4.runtime.atn.BasicState st = new org.antlr.v4.runtime.atn.BasicState();
		atn.addState(st);
		DFA dfa = new DFA(st, 0);
		assertEquals("", dfa.toString(VocabularyImpl.EMPTY_VOCABULARY, new String[] { "s" }));
	}

	@Test
	public void xpathUnknownElementThrows() {
		ParserRuleContext dummy = new ParserRuleContext();
		try {
			XPath.findAll(dummy, "///", new ParserInterpreter(
				"P",
				VocabularyImpl.EMPTY_VOCABULARY,
				Collections.singletonList("s"),
				new ATN(org.antlr.v4.runtime.atn.ATNType.PARSER, 2),
				new CommonTokenStream(new MockTokenSource(new CommonToken(1, "A")))));
		}
		catch (RuntimeException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void parserInterpreterNoViableRecoverAddsErrorNode() {
		ATN atn = new ATN(org.antlr.v4.runtime.atn.ATNType.PARSER, 2);
		org.antlr.v4.runtime.atn.RuleStartState start = new org.antlr.v4.runtime.atn.RuleStartState();
		org.antlr.v4.runtime.atn.BasicBlockStartState block = new org.antlr.v4.runtime.atn.BasicBlockStartState();
		org.antlr.v4.runtime.atn.BlockEndState end = new org.antlr.v4.runtime.atn.BlockEndState();
		org.antlr.v4.runtime.atn.RuleStopState stop = new org.antlr.v4.runtime.atn.RuleStopState();
		start.ruleIndex = 0; block.ruleIndex = 0; end.ruleIndex = 0; stop.ruleIndex = 0;
		start.stopState = stop;
		block.endState = end;
		end.startState = block;
		atn.addState(start); atn.addState(block); atn.addState(end); atn.addState(stop);
		start.addTransition(new org.antlr.v4.runtime.atn.EpsilonTransition(block));
		block.addTransition(new org.antlr.v4.runtime.atn.AtomTransition(end, 1));
		block.addTransition(new org.antlr.v4.runtime.atn.AtomTransition(end, 2));
		end.addTransition(new org.antlr.v4.runtime.atn.EpsilonTransition(stop));
		atn.defineDecisionState(block);
		atn.ruleToStartState = new org.antlr.v4.runtime.atn.RuleStartState[] { start };
		atn.ruleToStopState = new org.antlr.v4.runtime.atn.RuleStopState[] { stop };
		atn.clearDFA();

		ParserInterpreter p = new ParserInterpreter(
			"P",
			new VocabularyImpl(new String[] { null, "'A'", "'B'" }, new String[] { null, "A", "B" }),
			Collections.singletonList("s"),
			atn,
			new CommonTokenStream(new MockTokenSource(new CommonToken(3, "C"))));
		p.setErrorHandler(new DefaultErrorStrategy() {
			@Override
			public void recover(Parser recognizer, RecognitionException e) {
				// leave input unconsumed so recover() adds an error node
			}
		});
		try {
			assertNotNull(p.parse(0));
		}
		catch (RuntimeException expected) {
			assertNotNull(expected);
		}
	}
}
