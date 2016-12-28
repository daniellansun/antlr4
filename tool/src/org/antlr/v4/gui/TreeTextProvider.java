/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.gui;

import org.antlr.v4.runtime.tree.Tree;

public interface TreeTextProvider {
	String getText(Tree node);
}
