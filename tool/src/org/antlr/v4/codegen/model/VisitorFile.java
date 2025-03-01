/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.codegen.model;

import org.antlr.runtime.RecognitionException;
import org.antlr.v4.codegen.OutputModelFactory;
import org.antlr.v4.runtime.misc.Tuple2;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.ActionAST;
import org.antlr.v4.tool.ast.AltAST;
import org.antlr.v4.tool.ast.RuleAST;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class VisitorFile extends OutputFile {
	public String genPackage; // from -package cmd-line
	public String accessLevel; // from -DaccessLevel cmd-line
	public String exportMacro; // from -DexportMacro cmd-line
	public String grammarName;
	public String parserName;
	/**
	 * The names of all rule contexts which may need to be visited.
	 */
	public Set<String> visitorNames = new LinkedHashSet<String>();
	/**
	 * For rule contexts created for a labeled outer alternative, maps from
	 * a listener context name to the name of the rule which defines the
	 * context.
	 */
	public Map<String, String> visitorLabelRuleNames = new LinkedHashMap<String, String>();

	@ModelElement public Action header;
	@ModelElement public Map<String, Action> namedActions;

	public VisitorFile(OutputModelFactory factory, String fileName) {
		super(factory, fileName);
		Grammar g = factory.getGrammar();
		namedActions = buildNamedActions(g);
		parserName = g.getRecognizerName();
		grammarName = g.name;

		for (Map.Entry<String, List<RuleAST>> entry : g.contextASTs.entrySet()) {
			for (RuleAST ruleAST : entry.getValue()) {
				try {
					Map<String, List<Tuple2<Integer, AltAST>>> labeledAlternatives = g.getLabeledAlternatives(ruleAST);
					visitorNames.addAll(labeledAlternatives.keySet());
				} catch (RecognitionException ex) {
				}
			}
		}

		for (Rule r : g.rules.values()) {
			visitorNames.add(r.getBaseContext());
		}

		for (Rule r : g.rules.values()) {
			Map<String, List<Tuple2<Integer,AltAST>>> labels = r.getAltLabels();
			if ( labels!=null ) {
				for (Map.Entry<String, List<Tuple2<Integer, AltAST>>> pair : labels.entrySet()) {
					visitorLabelRuleNames.put(pair.getKey(), r.name);
				}
			}
		}

		ActionAST ast = g.namedActions.get("header");
		if ( ast!=null && ast.getScope()==null)
			header = new Action(factory, ast);
		genPackage = g.tool.genPackage;
		accessLevel = g.getOptionString("accessLevel");
		exportMacro = g.getOptionString("exportMacro");
	}
}
