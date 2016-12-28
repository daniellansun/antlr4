/*
 * [The "BSD license"]
 *  Copyright (c) 2012 Terence Parr
 *  Copyright (c) 2012 Sam Harwell
 *  Copyright (c) 2016 Mike Lischke
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions
 *  are met:
 *
 *  1. Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 *  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 *  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 *  NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 *  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 *  THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 *  THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.antlr.v4.codegen;

import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.ast.GrammarAST;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.gui.STViz;

import java.util.List;

public class CodeGenPipeline {
	Grammar g;

	public CodeGenPipeline(Grammar g) {
		this.g = g;
	}

	public void process() {
		CodeGenerator gen = new CodeGenerator(g);
		Target target = gen.getTarget();
		if (target == null) {
			return;
		}

		IntervalSet idTypes = new IntervalSet();
		idTypes.add(ANTLRParser.ID);
		idTypes.add(ANTLRParser.RULE_REF);
		idTypes.add(ANTLRParser.TOKEN_REF);
		List<GrammarAST> idNodes = g.ast.getNodesWithType(idTypes);
		for (GrammarAST idNode : idNodes) {
			if ( target.grammarSymbolCausesIssueInGeneratedCode(idNode) ) {
				g.tool.errMgr.grammarError(ErrorType.USE_OF_BAD_WORD,
										   g.fileName, idNode.getToken(),
										   idNode.getText());
			}
		}

		// all templates are generated in memory to report the most complete
		// error information possible, but actually writing output files stops
		// after the first error is reported
		int errorCount = g.tool.errMgr.getNumErrors();

		if ( g.isLexer() ) {
			if (target.needsHeader()) {
				ST lexer = gen.generateLexer(true); // Header file if needed.
				if (g.tool.errMgr.getNumErrors() == errorCount) {
					writeRecognizer(lexer, gen, true);
				}
			}
			ST lexer = gen.generateLexer(false);
			if (g.tool.errMgr.getNumErrors() == errorCount) {
				writeRecognizer(lexer, gen, false);
			}
		}
		else {
			if (target.needsHeader()) {
				ST parser = gen.generateParser(true);
				if (g.tool.errMgr.getNumErrors() == errorCount) {
					writeRecognizer(parser, gen, true);
				}
			}
			ST parser = gen.generateParser(false);
			if (g.tool.errMgr.getNumErrors() == errorCount) {
				writeRecognizer(parser, gen, false);
			}

			if ( g.tool.gen_listener ) {
				if (target.needsHeader()) {
					ST listener = gen.generateListener(true);
					if (g.tool.errMgr.getNumErrors() == errorCount) {
						gen.writeListener(listener, true);
					}
				}
				ST listener = gen.generateListener(false);
				if (g.tool.errMgr.getNumErrors() == errorCount) {
					gen.writeListener(listener, false);
				}

				if (target.needsHeader()) {
					ST baseListener = gen.generateBaseListener(true);
					if (g.tool.errMgr.getNumErrors() == errorCount) {
						gen.writeBaseListener(baseListener, true);
					}
				}
				if (target.wantsBaseListener()) {
					ST baseListener = gen.generateBaseListener(false);
					if ( g.tool.errMgr.getNumErrors()==errorCount ) {
						gen.writeBaseListener(baseListener, false);
					}
				}
			}
			if ( g.tool.gen_visitor ) {
				if (target.needsHeader()) {
					ST visitor = gen.generateVisitor(true);
					if (g.tool.errMgr.getNumErrors() == errorCount) {
						gen.writeVisitor(visitor, true);
					}
				}
				ST visitor = gen.generateVisitor(false);
				if (g.tool.errMgr.getNumErrors() == errorCount) {
					gen.writeVisitor(visitor, false);
				}

				if (target.needsHeader()) {
					ST baseVisitor = gen.generateBaseVisitor(true);
					if (g.tool.errMgr.getNumErrors() == errorCount) {
						gen.writeBaseVisitor(baseVisitor, true);
					}
				}
				if (target.wantsBaseVisitor()) {
					ST baseVisitor = gen.generateBaseVisitor(false);
					if ( g.tool.errMgr.getNumErrors()==errorCount ) {
						gen.writeBaseVisitor(baseVisitor, false);
					}
				}
			}
		}
		gen.writeVocabFile();
	}

	protected void writeRecognizer(ST template, CodeGenerator gen, boolean header) {
		if ( g.tool.launch_ST_inspector ) {
			STViz viz = template.inspect();
			if (g.tool.ST_inspector_wait_for_close) {
				try {
					viz.waitForClose();
				}
				catch (InterruptedException ex) {
					g.tool.errMgr.toolError(ErrorType.INTERNAL_ERROR, ex);
				}
			}
		}

		gen.writeRecognizer(template, header);
	}
}
