/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.codegen.model;

import org.antlr.v4.codegen.OutputModelFactory;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNSerializer;
import org.antlr.v4.runtime.misc.IntegerList;

import java.util.ArrayList;
import java.util.List;

public class SerializedATN extends OutputModelObject {
	private static final String[] EMPTY_STRING_ARRAY = new String[0];
	// TODO: make this into a kind of decl or multiple?
	public List<String> serialized;
	public SerializedATN(OutputModelFactory factory, ATN atn, List<String> ruleNames) {
		super(factory);
		IntegerList data = ATNSerializer.getSerialized(atn, ruleNames);
		serialized = new ArrayList<String>(data.size());
		for (int c : data.toArray()) {
			String encoded = factory.getTarget().encodeIntAsCharEscape(c == -1 ? Character.MAX_VALUE : c);
			serialized.add(encoded);
		}
//		System.out.println(ATNSerializer.getDecoded(factory.getGrammar(), atn));
	}

	public String[][] getSegments() {
		List<String[]> segments = new ArrayList<String[]>();
		int segmentLimit = factory.getTarget().getSerializedATNSegmentLimit();
		for (int i = 0; i < serialized.size(); i += segmentLimit) {
			List<String> currentSegment = serialized.subList(i, Math.min(i + segmentLimit, serialized.size()));
			segments.add(currentSegment.toArray(EMPTY_STRING_ARRAY));
		}

		return segments.toArray(new String[segments.size()][]);
	}
}
