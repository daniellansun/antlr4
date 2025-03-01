/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.tool.ast;

import org.antlr.runtime.CharStream;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.Token;
import org.antlr.runtime.tree.CommonTree;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.antlr.runtime.tree.Tree;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.parse.GrammarASTAdaptor;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.tool.Grammar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class GrammarAST extends CommonTree {
	private static final GrammarAST[] EMPTY_GRAMMARAST_ARRAY = new GrammarAST[0];
	/** For error msgs, nice to know which grammar this AST lives in */
	// TODO: try to remove
	public Grammar g;

	/** If we build an ATN, we make AST node point at left edge of ATN construct */
	public ATNState atnState;

	public String textOverride;

    public GrammarAST() {}
    public GrammarAST(Token t) { super(t); }
    public GrammarAST(GrammarAST node) {
		super(node);
		this.g = node.g;
		this.atnState = node.atnState;
		this.textOverride = node.textOverride;
	}
    public GrammarAST(int type) { super(new CommonToken(type, ANTLRParser.tokenNames[type])); }
    public GrammarAST(int type, Token t) {
		this(new CommonToken(t));
		token.setType(type);
	}
    public GrammarAST(int type, Token t, String text) {
		this(new CommonToken(t));
		token.setType(type);
		token.setText(text);
    }

	public GrammarAST[] getChildrenAsArray() {
		return children.toArray(EMPTY_GRAMMARAST_ARRAY);
	}

	public List<GrammarAST> getNodesWithType(int ttype) {
		return getNodesWithType(IntervalSet.of(ttype));
	}

	public List<GrammarAST> getAllChildrenWithType(int type) {
		List<GrammarAST> nodes = new ArrayList<GrammarAST>();
		for (int i = 0; children!=null && i < children.size(); i++) {
			Tree t = (Tree) children.get(i);
			if ( t.getType()==type ) {
				nodes.add((GrammarAST)t);
			}
		}
		return nodes;
	}

	public List<GrammarAST> getNodesWithType(IntervalSet types) {
		List<GrammarAST> nodes = new ArrayList<GrammarAST>();
		List<GrammarAST> work = new LinkedList<GrammarAST>();
		work.add(this);
		GrammarAST t;
		while ( !work.isEmpty() ) {
			t = work.remove(0);
			if ( types==null || types.contains(t.getType()) ) nodes.add(t);
			if ( t.children!=null ) {
				work.addAll(Arrays.asList(t.getChildrenAsArray()));
			}
		}
		return nodes;
	}

	public List<GrammarAST> getNodesWithTypePreorderDFS(IntervalSet types) {
		ArrayList<GrammarAST> nodes = new ArrayList<GrammarAST>();
		getNodesWithTypePreorderDFS_(nodes, types);
		return nodes;
	}

	public void getNodesWithTypePreorderDFS_(List<GrammarAST> nodes, IntervalSet types) {
		if ( types.contains(this.getType()) ) nodes.add(this);
		// walk all children of root.
		for (int i= 0; i < getChildCount(); i++) {
			GrammarAST child = (GrammarAST)getChild(i);
			child.getNodesWithTypePreorderDFS_(nodes, types);
		}
	}

	public GrammarAST getNodeWithTokenIndex(int index) {
		if ( this.getToken()!=null && this.getToken().getTokenIndex()==index ) {
			return this;
		}
		// walk all children of root.
		for (int i= 0; i < getChildCount(); i++) {
			GrammarAST child = (GrammarAST)getChild(i);
			GrammarAST result = child.getNodeWithTokenIndex(index);
			if ( result!=null ) {
				return result;
			}
		}
		return null;
	}

	public AltAST getOutermostAltNode() {
		if ( this instanceof AltAST && parent.parent instanceof RuleAST ) {
			return (AltAST)this;
		}
		if ( parent!=null ) return ((GrammarAST)parent).getOutermostAltNode();
		return null;
	}

	/** Walk ancestors of this node until we find ALT with
	 *  alt!=null or leftRecursiveAltInfo!=null. Then grab label if any.
	 *  If not a rule element, just returns null.
	 */
	public String getAltLabel() {
		List<? extends Tree> ancestors = this.getAncestors();
		if ( ancestors==null ) return null;
		for (int i=ancestors.size()-1; i>=0; i--) {
			GrammarAST p = (GrammarAST)ancestors.get(i);
			if ( p.getType()== ANTLRParser.ALT ) {
				AltAST a = (AltAST)p;
				if ( a.altLabel!=null ) return a.altLabel.getText();
				if ( a.leftRecursiveAltInfo!=null ) {
					return a.leftRecursiveAltInfo.altLabel;
				}
			}
		}
		return null;
	}

	public boolean deleteChild(org.antlr.runtime.tree.Tree t) {
		for (Object c : children) {
			if (c == t) {
				deleteChild(t.getChildIndex());
				return true;
			}
		}
		return false;
	}

    // TODO: move to basetree when i settle on how runtime works
    // TODO: don't include this node!!
	// TODO: reuse other method
    public CommonTree getFirstDescendantWithType(int type) {
        if ( getType()==type ) return this;
        if ( children==null ) return null;
        for (Object c : children) {
            GrammarAST t = (GrammarAST)c;
            if ( t.getType()==type ) return t;
            CommonTree d = t.getFirstDescendantWithType(type);
            if ( d!=null ) return d;
        }
        return null;
    }

	// TODO: don't include this node!!
	public CommonTree getFirstDescendantWithType(org.antlr.runtime.BitSet types) {
		if ( types.member(getType()) ) return this;
		if ( children==null ) return null;
		for (Object c : children) {
			GrammarAST t = (GrammarAST)c;
			if ( types.member(t.getType()) ) return t;
			CommonTree d = t.getFirstDescendantWithType(types);
			if ( d!=null ) return d;
		}
		return null;
	}

	public void setType(int type) {
		token.setType(type);
	}
//
//	@Override
//	public String getText() {
//		if ( textOverride!=null ) return textOverride;
//        if ( token!=null ) {
//            return token.getText();
//        }
//        return "";
//	}

	public void setText(String text) {
//		textOverride = text; // don't alt tokens as others might see
		token.setText(text); // we delete surrounding tree, so ok to alter
	}

//	@Override
//	public boolean equals(Object obj) {
//		return super.equals(obj);
//	}

	@Override
    public GrammarAST dupNode() {
        return new GrammarAST(this);
    }

	public GrammarAST dupTree() {
		GrammarAST t = this;
		CharStream input = this.token.getInputStream();
		GrammarASTAdaptor adaptor = new GrammarASTAdaptor(input);
		return (GrammarAST)adaptor.dupTree(t);
	}

	public String toTokenString() {
		CharStream input = this.token.getInputStream();
		GrammarASTAdaptor adaptor = new GrammarASTAdaptor(input);
		CommonTreeNodeStream nodes =
			new CommonTreeNodeStream(adaptor, this);
		StringBuilder buf = new StringBuilder();
		GrammarAST o = (GrammarAST)nodes.LT(1);
		int type = adaptor.getType(o);
		while ( type!=Token.EOF ) {
			buf.append(" ");
			buf.append(o.getText());
			nodes.consume();
			o = (GrammarAST)nodes.LT(1);
			type = adaptor.getType(o);
		}
		return buf.toString();
	}

	public Object visit(GrammarASTVisitor v) { return v.visit(this); }
}
