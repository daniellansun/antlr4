/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestSemanticContext {

	/** Minimal recognizer stub that records/returns configurable pred results. */
	private static final class StubRecognizer extends Recognizer<Token, ParserATNSimulator> {
		boolean semResult = true;
		boolean precResult = true;
		int lastRule = -1;
		int lastPred = -1;
		int lastPrec = -1;

		@Override public String[] getTokenNames() { return new String[0]; }
		@Override public String[] getRuleNames() { return new String[0]; }
		@Override public String getGrammarFileName() { return "stub"; }
		@Override public ATN getATN() { return new ATN(ATNType.PARSER, 1); }
		@Override public IntStream getInputStream() { return null; }

		@Override
		public boolean sempred(RuleContext localctx, int ruleIndex, int predIndex) {
			lastRule = ruleIndex;
			lastPred = predIndex;
			return semResult;
		}

		@Override
		public boolean precpred(RuleContext localctx, int precedence) {
			lastPrec = precedence;
			return precResult;
		}
	}

	@Test
	public void testNoneAndPredicate() {
		assertNotNull(SemanticContext.NONE);
		assertTrue(SemanticContext.NONE instanceof SemanticContext.Predicate);
		SemanticContext.Predicate none = (SemanticContext.Predicate) SemanticContext.NONE;
		assertEquals(-1, none.ruleIndex);
		assertEquals(-1, none.predIndex);
		assertFalse(none.isCtxDependent);

		SemanticContext.Predicate p = new SemanticContext.Predicate(1, 2, true);
		assertEquals(1, p.ruleIndex);
		assertEquals(2, p.predIndex);
		assertTrue(p.isCtxDependent);
		assertEquals(p, new SemanticContext.Predicate(1, 2, true));
		assertNotEquals(p, new SemanticContext.Predicate(1, 3, true));
		assertEquals(p.hashCode(), new SemanticContext.Predicate(1, 2, true).hashCode());
		assertTrue(p.toString().contains("1:2"));

		StubRecognizer r = new StubRecognizer();
		r.semResult = true;
		assertTrue(p.eval(r, null));
		assertEquals(1, r.lastRule);
		assertEquals(2, r.lastPred);
		r.semResult = false;
		assertFalse(p.eval(r, null));

		// NONE eval always true via default Predicate with -1 indices...
		// actually calls sempred(-1,-1) which returns our semResult
		r.semResult = true;
		assertTrue(SemanticContext.NONE.eval(r, null));
	}

	@Test
	public void testPrecedencePredicate() {
		SemanticContext.PrecedencePredicate p = new SemanticContext.PrecedencePredicate(3);
		assertEquals(3, p.precedence);
		assertEquals(p, new SemanticContext.PrecedencePredicate(3));
		assertNotEquals(p, new SemanticContext.PrecedencePredicate(4));
		assertEquals(0, p.compareTo(new SemanticContext.PrecedencePredicate(3)));
		assertTrue(p.compareTo(new SemanticContext.PrecedencePredicate(5)) < 0);
		assertTrue(p.toString().contains("3"));

		StubRecognizer r = new StubRecognizer();
		r.precResult = true;
		assertTrue(p.eval(r, null));
		assertSame(SemanticContext.NONE, p.evalPrecedence(r, null));
		r.precResult = false;
		assertFalse(p.eval(r, null));
		assertNull(p.evalPrecedence(r, null));

		// default evalPrecedence on Predicate returns this
		assertSame(SemanticContext.NONE, SemanticContext.NONE.evalPrecedence(r, null));
	}

	@Test
	public void testAndOrFactoryAndEval() {
		SemanticContext.Predicate p1 = new SemanticContext.Predicate(0, 0, false);
		SemanticContext.Predicate p2 = new SemanticContext.Predicate(0, 1, false);

		assertSame(p2, SemanticContext.and(null, p2));
		assertSame(p1, SemanticContext.and(p1, null));
		assertSame(p1, SemanticContext.and(SemanticContext.NONE, p1));
		assertSame(p2, SemanticContext.and(p2, SemanticContext.NONE));

		SemanticContext and = SemanticContext.and(p1, p2);
		assertTrue(and instanceof SemanticContext.AND);
		SemanticContext.AND andOp = (SemanticContext.AND) and;
		assertEquals(2, andOp.getOperands().size());
		assertEquals(and, new SemanticContext.AND(p1, p2));
		assertEquals(and.hashCode(), new SemanticContext.AND(p1, p2).hashCode());
		assertTrue(and.toString().contains("&&"));

		StubRecognizer r = new StubRecognizer();
		r.semResult = true;
		assertTrue(and.eval(r, null));
		r.semResult = false;
		assertFalse(and.eval(r, null));

		assertSame(p2, SemanticContext.or(null, p2));
		assertSame(p1, SemanticContext.or(p1, null));
		assertSame(SemanticContext.NONE, SemanticContext.or(SemanticContext.NONE, p1));
		assertSame(SemanticContext.NONE, SemanticContext.or(p1, SemanticContext.NONE));

		SemanticContext or = SemanticContext.or(p1, p2);
		assertTrue(or instanceof SemanticContext.OR);
		assertEquals(or, new SemanticContext.OR(p1, p2));
		assertTrue(or.toString().contains("||"));
		r.semResult = true;
		assertTrue(or.eval(r, null));
		r.semResult = false;
		assertFalse(or.eval(r, null));

		// nested flatten
		SemanticContext nestedAnd = SemanticContext.and(and, p1);
		assertTrue(nestedAnd instanceof SemanticContext.AND);
		SemanticContext nestedOr = SemanticContext.or(or, p2);
		assertTrue(nestedOr instanceof SemanticContext.OR);
	}

	@Test
	public void testAndOrWithPrecedenceReduction() {
		SemanticContext.PrecedencePredicate hi = new SemanticContext.PrecedencePredicate(5);
		SemanticContext.PrecedencePredicate lo = new SemanticContext.PrecedencePredicate(2);
		SemanticContext.Predicate p = new SemanticContext.Predicate(0, 0, false);

		// AND keeps lowest precedence
		SemanticContext and = new SemanticContext.AND(hi, lo);
		// may also include p when mixed
		SemanticContext and2 = SemanticContext.and(and, p);
		assertNotNull(and2);

		// OR keeps highest precedence
		SemanticContext or = new SemanticContext.OR(hi, lo);
		assertNotNull(or);
	}

	@Test
	public void testEvalPrecedenceOnOperators() {
		StubRecognizer r = new StubRecognizer();
		SemanticContext.PrecedencePredicate pTrue = new SemanticContext.PrecedencePredicate(1);
		SemanticContext.PrecedencePredicate pFalse = new SemanticContext.PrecedencePredicate(2);
		SemanticContext.Predicate other = new SemanticContext.Predicate(0, 0, false);

		// AND: if any precedence is false -> null
		r.precResult = false;
		assertNull(new SemanticContext.AND(pFalse, other).evalPrecedence(r, null));

		// AND: all precedence true and only those -> NONE
		r.precResult = true;
		assertSame(SemanticContext.NONE, new SemanticContext.AND(pTrue, pTrue).evalPrecedence(r, null));

		// AND: unchanged when operands have no precedence
		SemanticContext andPreds = new SemanticContext.AND(other, new SemanticContext.Predicate(0, 1, false));
		assertSame(andPreds, andPreds.evalPrecedence(r, null));

		// OR: if any precedence true -> NONE
		r.precResult = true;
		assertSame(SemanticContext.NONE, new SemanticContext.OR(pTrue, other).evalPrecedence(r, null));

		// OR: all false precedence -> null when only precedence preds
		r.precResult = false;
		assertNull(new SemanticContext.OR(pFalse, pFalse).evalPrecedence(r, null));

		// OR unchanged for plain preds
		SemanticContext orPreds = new SemanticContext.OR(other, new SemanticContext.Predicate(0, 1, false));
		assertSame(orPreds, orPreds.evalPrecedence(r, null));
	}

	@Test
	public void testAndOrEvalPrecedencePartialReductionAndEquals() {
		StubRecognizer r = new StubRecognizer();
		SemanticContext.PrecedencePredicate prec = new SemanticContext.PrecedencePredicate(1);
		SemanticContext.Predicate p0 = new SemanticContext.Predicate(0, 0, false);
		SemanticContext.Predicate p1 = new SemanticContext.Predicate(0, 1, false);

		// AND: mixed precedence true + ordinary pred => reduces to the ordinary pred
		r.precResult = true;
		SemanticContext andMixed = new SemanticContext.AND(prec, p0);
		SemanticContext reducedAnd = andMixed.evalPrecedence(r, null);
		assertNotNull(reducedAnd);
		assertTrue(reducedAnd.equals(p0) || reducedAnd == p0);

		// OR: mixed precedence false + ordinary pred => reduces to ordinary pred
		r.precResult = false;
		SemanticContext orMixed = new SemanticContext.OR(prec, p0);
		SemanticContext reducedOr = orMixed.evalPrecedence(r, null);
		assertNotNull(reducedOr);
		assertTrue(reducedOr.equals(p0) || reducedOr == p0);

		// equals / hashCode / toString remaining branches
		SemanticContext.AND a1 = new SemanticContext.AND(p0, p1);
		SemanticContext.AND a2 = new SemanticContext.AND(p0, p1);
		assertEquals(a1, a2);
		assertEquals(a1.hashCode(), a2.hashCode());
		assertEquals(a1, a1);
		assertNotEquals(a1, null);
		assertNotEquals(a1, p0);
		assertNotEquals(a1, new SemanticContext.AND(p0, p0));
		assertTrue(a1.toString().contains("&&"));

		SemanticContext.OR o1 = new SemanticContext.OR(p0, p1);
		SemanticContext.OR o2 = new SemanticContext.OR(p0, p1);
		assertEquals(o1, o2);
		assertEquals(o1.hashCode(), o2.hashCode());
		assertEquals(o1, o1);
		assertNotEquals(o1, null);
		assertNotEquals(o1, p0);
		assertNotEquals(o1, new SemanticContext.OR(p0, p0));
		assertTrue(o1.toString().contains("||"));

		// and/or factory collapsing to single operand when duplicates reduce
		SemanticContext singleAnd = SemanticContext.and(p0, p0);
		assertNotNull(singleAnd);
		SemanticContext singleOr = SemanticContext.or(p0, p0);
		assertNotNull(singleOr);
	}
}
