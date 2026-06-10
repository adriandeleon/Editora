package com.editora.run;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure detection of source locations in program output (unit-tested), so the Run/Debug consoles can make
 * stack-trace lines clickable. Recognizes the frame formats of the three runnable/debuggable languages:
 * Java ({@code at pkg.Cls.m(File.java:12)} — bare file name), Python ({@code File "/path/x.py", line 12}
 * — usually absolute), and Node ({@code at fn (/path/x.js:12:5)} or a bare {@code /path/x.js:12:5}).
 */
public final class StackTraceLinks {

    /** A source location found in one console line. {@code file} may be a bare name (Java) or a path;
     *  {@code line} is 1-based as printed. */
    public record Link(String file, int line) {
    }

    private static final Pattern JAVA = Pattern.compile("\\(([A-Za-z0-9_$]+\\.java):(\\d+)\\)");
    private static final Pattern PYTHON = Pattern.compile("File \"([^\"]+\\.py[a-z]?)\", line (\\d+)");
    private static final Pattern NODE = Pattern.compile(
            "((?:[A-Za-z]:)?[^\\s():]+\\.(?:js|mjs|cjs|ts)):(\\d+)(?::\\d+)?");

    private StackTraceLinks() {
    }

    /** The first source location in {@code line}, or null when the line holds none. */
    public static Link parse(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        Matcher m = JAVA.matcher(line);
        if (m.find()) {
            return new Link(m.group(1), Integer.parseInt(m.group(2)));
        }
        m = PYTHON.matcher(line);
        if (m.find()) {
            return new Link(m.group(1), Integer.parseInt(m.group(2)));
        }
        m = NODE.matcher(line);
        if (m.find()) {
            return new Link(m.group(1), Integer.parseInt(m.group(2)));
        }
        return null;
    }
}
