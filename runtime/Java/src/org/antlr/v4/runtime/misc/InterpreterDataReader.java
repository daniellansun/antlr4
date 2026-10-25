/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.misc;

import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNDeserializer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// A class to read plain text interpreter data produced by ANTLR.
public class InterpreterDataReader {

	public static class InterpreterData {
	  ATN atn;
	  Vocabulary vocabulary;
	  List<String> ruleNames;
	  List<String> channels; // Only valid for lexer grammars.
	  List<String> modes; // ditto
	}

	/**
	 * The structure of the data file is very simple. Everything is line based with empty lines
	 * separating the different parts. For lexers the layout is:
	 * token literal names:
	 * ...
	 *
	 * token symbolic names:
	 * ...
	 *
	 * rule names:
	 * ...
	 *
	 * channel names:
	 * ...
	 * A line of two quote characters is a hole and is stored as an empty name,
	 * so the indexes stay aligned with the lexer. A blank line still ends the
	 * section. The word null is kept as text; older files used it for placeholders.
	 *
	 * mode names:
	 * ...
	 *
	 * atn:
	 * <a single line with comma separated int values> enclosed in a pair of squared brackets.
	 *
	 * Data for a parser does not contain channel and mode names.
	 */
	public static InterpreterData parseFile(String fileName) {
		InterpreterData result = new InterpreterData();
		result.ruleNames = new ArrayList<>();

		try (BufferedReader br = new BufferedReader(new FileReader(fileName))) {
			String line;
			List<String> literalNames = new ArrayList<>();
			List<String> symbolicNames = new ArrayList<>();

			line = br.readLine();
			if (!line.equals("token literal names:"))
				throw new RuntimeException("Unexpected data entry");
			while ((line = br.readLine()) != null) {
				if (line.isEmpty())
					break;
				literalNames.add(line.equals("null") ? "" : line);
			}

			line = br.readLine();
			if (!line.equals("token symbolic names:"))
				throw new RuntimeException("Unexpected data entry");
			while ((line = br.readLine()) != null) {
				if (line.isEmpty())
					break;
				symbolicNames.add(line.equals("null") ? "" : line);
			}

			result.vocabulary = new VocabularyImpl(literalNames.toArray(new String[0]), symbolicNames.toArray(new String[0]));

			line = br.readLine();
			if (!line.equals("rule names:"))
				throw new RuntimeException("Unexpected data entry");
			while ((line = br.readLine()) != null) {
				if (line.isEmpty())
					break;
				result.ruleNames.add(line);
			}

			// The rule-name loop consumed the separating blank. Tool.generateInterpreterData
			// then writes "channel names:" for a lexer, or one more blank and "atn:"
			// for a parser (the extra blank is outside the lexer-only block).
			line = br.readLine();
			if ("channel names:".equals(line)) {
				result.channels = new ArrayList<String>();
				while ((line = br.readLine()) != null) {
					if (line.isEmpty())
						break;
					result.channels.add("\"\"".equals(line) ? "" : line);
				}

				line = br.readLine();
				if (line == null || !line.equals("mode names:"))
					throw new RuntimeException("Unexpected data entry");
				result.modes = new ArrayList<String>();
				while ((line = br.readLine()) != null) {
					if (line.isEmpty())
						break;
					result.modes.add(line);
				}
				line = br.readLine();
			}
			else if (line != null && line.isEmpty()) {
				line = br.readLine();
			}

			if (line == null || !line.equals("atn:"))
				throw new RuntimeException("Unexpected data entry");
			line = br.readLine();
			if (line == null)
				throw new RuntimeException("Unexpected data entry");
			String payload = line.trim();
			if (payload.startsWith("[") && payload.endsWith("]") && payload.length() >= 2) {
				payload = payload.substring(1, payload.length() - 1).trim();
			}
			String[] elements = payload.isEmpty() ? new String[0] : payload.split(",");
			char[] serializedATN = new char[elements.length];

			for (int i = 0; i < elements.length; ++i) {
				serializedATN[i] = (char) Integer.parseInt(elements[i].trim());
			}

			ATNDeserializer deserializer = new ATNDeserializer();
			result.atn = deserializer.deserialize(serializedATN);
		} catch (IOException e) {
			// We just swallow the error and return empty objects instead.
		}
		// We just swallow the error and return empty objects instead.

		return result;
	}

}
