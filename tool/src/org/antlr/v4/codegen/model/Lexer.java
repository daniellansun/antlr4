/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen.model;

import org.antlr.v4.codegen.OutputModelFactory;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Lexer extends Recognizer {
	public Map<String,Integer> channels;
	public LexerFile file;
	public Collection<String> modes;

	@ModelElement public LinkedHashMap<Rule, RuleActionFunction> actionFuncs =
		new LinkedHashMap<Rule, RuleActionFunction>();

	/**
	 * Flat dense {@code switch (actionIndex)} cases populated when all lexer
	 * action indices across rules are globally unique, avoiding nested
	 * {@code switch (ruleIndex)} + per-rule wrapper methods.
	 */
	@ModelElement public List<FlatActionCase> flatActions;

	public Lexer(OutputModelFactory factory, LexerFile file) {
		super(factory);
		this.file = file; // who contains us?

		Grammar g = factory.getGrammar();
		channels = new LinkedHashMap<String, Integer>(g.channelNameToValueMap);
		modes = ((LexerGrammar)g).modes.keySet();
	}
}
