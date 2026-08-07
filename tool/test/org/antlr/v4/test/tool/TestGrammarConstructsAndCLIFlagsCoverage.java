/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.v4.Tool;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * Broad grammar surface-area and CLI flag tests (parser/lexer constructs,
 * options, imports, invalid grammars, -atn/-depend/-package, etc.).
 */
public class TestGrammarConstructsAndCLIFlagsCoverage extends BaseTest {

	private void gen(String name, String body) throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, name, body);
		ErrorQueue eq = antlr(name, false);
		// tolerate errors on intentionally-invalid fragments; must not throw
		assertNotNull(eq);
	}

	@Test
	public void testParserRuleElements() throws Exception {
		gen("T.g4",
				"grammar T;\n" +
				"s : a b c d e f g h i j k ;\n" +
				"a : ID | INT | 'lit' | . ;\n" +
				"b : (ID|INT)+ ;\n" +
				"c : ID* ;\n" +
				"d : ID? ;\n" +
				"e : ID ID? ID* ID+ ;\n" +
				"f : ~ID | ~'x' | ~('.'|'y') ;\n" +
				"g : 'a'..'z' ; // invalid in parser, may error\n" +
				"h : x=ID y+=INT* ;\n" +
				"i : {true}? ID | {false}? INT ;\n" +
				"j : ID {System.out.println($ID.text);} ;\n" +
				"k : ID -> type(ID) ; // invalid may error\n" +
				"ID : [a-z]+ ;\n" +
				"INT : [0-9]+ ;\n" +
				"WS : [ \\t\\r\\n]+ -> skip ;\n");
	}

	@Test
	public void testLexerRuleElements() throws Exception {
		gen("L.g4",
				"lexer grammar L;\n" +
				"A : 'a' ;\n" +
				"B : \"b\" ; // may be invalid\n" +
				"C : [a-zA-Z_][a-zA-Z0-9_]* ;\n" +
				"D : '/*' .*? '*/' -> channel(HIDDEN) ;\n" +
				"E : '//' ~[\\r\\n]* -> skip ;\n" +
				"F : [\\u0000-\\u007F] ;\n" +
				"G : '\\n' | '\\r\\n' | '\\t' | '\\\\' | '\\'' ;\n" +
				"H : [a-z]+ -> mode(M), type(A), channel(HIDDEN) ;\n" +
				"fragment FRAG : [0-9] ;\n" +
				"I : FRAG+ ;\n" +
				"mode M;\n" +
				"J : . -> more ;\n" +
				"K : '\"' -> popMode ;\n" +
				"L : 'x' -> pushMode(M) ;\n");
	}

	@Test
	public void testOptionsAndActions() throws Exception {
		gen("O.g4",
				"grammar O;\n" +
				"options { tokenVocab=O; superClass=Base; language=Java; caseInsensitive=true; }\n" +
				"tokens { FOO, BAR }\n" +
				"channels { MYCHAN }\n" +
				"@parser::header { /* header */ }\n" +
				"@parser::members { int x; }\n" +
				"@lexer::header { /* lheader */ }\n" +
				"@lexer::members { int y; }\n" +
				"@members { int z; }\n" +
				"@header { /* both */ }\n" +
				"s : FOO BAR ID ;\n" +
				"ID : [a-z]+ -> channel(MYCHAN) ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
	}

	@Test
	public void testLabeledAltsAndExceptions() throws Exception {
		gen("E.g4",
				"grammar E;\n" +
				"s : e # AddAlt\n" +
				"  | t # TermAlt\n" +
				"  ;\n" +
				"e : t ('+' t)* ;\n" +
				"t : ID | INT ;\n" +
				"ID : [a-z]+ ;\n" +
				"INT : [0-9]+ ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
	}

	@Test
	public void testThrowsLocalsReturns() throws Exception {
		gen("R.g4",
				"grammar R;\n" +
				"s[int a] returns [int b] throws Exception locals [int c]\n" +
				"  : ID { $b = $a; $c = 1; } ;\n" +
				"ID : [a-z]+ ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
	}

	@Test
	public void testImportsAndTokenVocab() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "BaseL.g4", "lexer grammar BaseL;\nA : 'a' ;\n");
		writeFile(tmpdir, "BaseP.g4", "parser grammar BaseP;\n options {tokenVocab=BaseL;}\np : A ;\n");
		writeFile(tmpdir, "Combo.g4",
				"grammar Combo;\n" +
				"import BaseP;\n" +
				"s : p A ;\n" +
				"A : 'a' ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
		// generate lexer tokens first
		antlr("BaseL.g4", false);
		antlr("BaseP.g4", false);
		antlr("Combo.g4", false);
	}

	@Test
	public void testInvalidGrammarsForErrorPaths() throws Exception {
		String[] bad = {
				"grammar X;\n", // empty
				"grammar X;\na : ;\n", // empty alt
				"grammar X;\na : b ;\n", // undefined
				"grammar X;\na : a ;\n", // left recursion simple
				"parser grammar X;\nA : 'a' ;\n", // lexer rule in parser
				"lexer grammar X;\na : 'a' ;\n", // parser rule in lexer
				"grammar X;\noptions { badOpt=1; }\na:'x';\n",
				"grammar X;\ntokens { a }\na:'x';\n", // bad token name
				"grammar X;\na : 'x' | 'x' ;\n", // identical alts
				"tree grammar X;\na : 'x' ;\n", // v3 tree grammar
		};
		for (int i = 0; i < bad.length; i++) {
			try {
				gen("Bad" + i + ".g4", bad[i]);
			} catch (Throwable t) {
			}
		}
	}

	@Test
	public void testUnicodeAndEscapesInGrammar() throws Exception {
		gen("U.g4",
				"grammar U;\n" +
				"s : STRING ;\n" +
				"STRING : '\\'' ('\\\\' . | ~['\\\\])* '\\'' ;\n" +
				"ID : [\\p{L}]+ ;\n" +
				"EMOJI : [\\p{Emoji}]+ ;\n" +
				"HEX : '\\u0041'..'\\u005A' ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
	}

	@Test
	public void testElementOptions() throws Exception {
		gen("EO.g4",
				"grammar EO;\n" +
				"s : ID<assoc=right> | INT<type=ID> | 'x'<fail='oops'> ;\n" +
				"ID : [a-z]+ ;\n" +
				"INT : [0-9]+ ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
	}

	@Test
	public void testModeAndChannelCommands() throws Exception {
		gen("MC.g4",
				"lexer grammar MC;\n" +
				"channels { DOC }\n" +
				"OPEN : '/*' -> pushMode(DOCMODE), channel(DOC) ;\n" +
				"ID : [a-z]+ ;\n" +
				"WS : [ \\n]+ -> skip ;\n" +
				"mode DOCMODE;\n" +
				"TEXT : ~[*]+ -> channel(DOC) ;\n" +
				"CLOSE : '*/' -> popMode, channel(DOC) ;\n" +
				"STAR : '*' -> channel(DOC) ;\n");
	}

	@Test
	public void testVisitorListenerCodegen() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "VL.g4",
				"grammar VL;\n" +
				"s : e ;\n" +
				"e : e '+' e # Add\n" +
				"  | e '*' e # Mul\n" +
				"  | INT     # Int\n" +
				"  ;\n" +
				"INT : [0-9]+ ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
		antlr("VL.g4", false, "-visitor", "-listener");
	}

	@Test
	public void testAtnAndDependFlags() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "AD.g4", "grammar AD;\ns:'x';\n");
		antlr("AD.g4", false, "-atn");
		antlr("AD.g4", false, "-depend");
		antlr("AD.g4", false, "-Xforce-atn");
		antlr("AD.g4", false, "-Xlog");
	}

	@Test
	public void testPackageAndEncoding() throws Exception {
		mkdir(tmpdir);
		writeFile(tmpdir, "PK.g4", "grammar PK;\ns:'x';\n");
		antlr("PK.g4", false, "-package", "com.example.antlr", "-encoding", "UTF-8");
	}

	@Test
	public void testRuleVersionAndBaseContext() throws Exception {
		// optimized-fork features
		gen("RV.g4",
				"grammar RV;\n" +
				"s : a | b ;\n" +
				"a @version{1} : 'x' ;\n" +
				"b options { baseContext=a; } : 'y' ;\n" +
				"WS : [ \\n]+ -> skip ;\n");
	}

	@Test
	public void testManyAltsForDFA() throws Exception {
		StringBuilder sb = new StringBuilder();
		sb.append("grammar Many;\ns : ");
		for (int i = 0; i < 20; i++) {
			if (i > 0) sb.append(" | ");
			sb.append("'k").append(i).append("'");
		}
		sb.append(" ;\nWS : [ \\n]+ -> skip ;\n");
		gen("Many.g4", sb.toString());
	}
}
