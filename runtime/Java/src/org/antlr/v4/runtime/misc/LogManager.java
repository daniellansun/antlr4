/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.misc;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class LogManager {
    protected static class Record {
        private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss:SSS");
        private static final ZoneId ZONE = ZoneId.systemDefault();
        long timestamp;
		StackTraceElement location;
		String component;
		String msg;
		public Record() {
			timestamp = System.currentTimeMillis();
			location = new Throwable().getStackTrace()[0];
		}

		@Override
        public String toString() {
            String result = DATE_TIME_FORMATTER.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZONE)) +
                    " " + component + " " + location.getFileName() + ":" + location.getLineNumber() + " " + msg;
            return result;
        }
	}

	protected List<Record> records;

	public void log(@Nullable String component, String msg) {
		Record r = new Record();
		r.component = component;
		r.msg = msg;
		if ( records==null ) {
			records = new ArrayList<Record>();
		}
		records.add(r);
	}

    public void log(String msg) { log(null, msg); }

    public void save(String filename) throws IOException {
        FileWriter fw = new FileWriter(filename);
		try (BufferedWriter bw = new BufferedWriter(fw)) {
			bw.write(toString());
		}
    }

    public String save() throws IOException {
        //String dir = System.getProperty("java.io.tmpdir");
        String dir = ".";
        String defaultFilename =
            dir + "/antlr-" +
            new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss").format(new Date()) + ".log";
        save(defaultFilename);
        return defaultFilename;
    }

    @Override
    public String toString() {
        if ( records==null ) return "";
        String nl = System.lineSeparator();
        StringBuilder buf = new StringBuilder();
        for (Record r : records) {
            buf.append(r);
            buf.append(nl);
        }
        return buf.toString();
    }

    public static void main(String[] args) throws IOException {
        LogManager mgr = new LogManager();
        mgr.log("atn", "test msg");
        mgr.log("dfa", "test msg 2");
        System.out.println(mgr);
        mgr.save();
    }
}
