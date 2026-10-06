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

    /**
     * A source location found in one console line. {@code file} may be a bare name (Java) or a path;
     * {@code line} is 1-based as printed; {@code raw} is the console line verbatim.
     *
     * <p>{@code raw} exists because a language server can resolve a frame far better than this regex can —
     * jdtls's {@code java.project.resolveStackTraceLocation} works off the real classpath and can place a
     * frame inside a dependency or the JDK (#744) — and it wants the <em>whole line</em>, not the pieces
     * we picked out of it.
     */
    public record Link(String file, int line, String raw) {
        /** The two-arg form, for callers (and tests) that don't need the original line. */
        public Link(String file, int line) {
            this(file, line, null);
        }
    }

    // Line numbers are {@code \d{1,9}}: a longer digit run is not a line number, and would overflow parseInt.
    private static final Pattern JAVA = Pattern.compile("\\(([A-Za-z0-9_$]+\\.java):(\\d{1,9})\\)");
    private static final Pattern PYTHON = Pattern.compile("File \"([^\"]+\\.py[a-z]?)\", line (\\d{1,9})(?!\\d)");
    /**
     * Anchored at a token start (the lookbehind): unanchored, the pattern was retried from every index of a
     * long unbroken token — a 64K base64 line — each time consuming the rest and backing off, which froze the
     * FX thread for seconds on a double-click.
     */
    private static final Pattern NODE =
            Pattern.compile("(?<![^\\s():])((?:[A-Za-z]:)?[^\\s():]+\\.(?:js|mjs|cjs|ts)):(\\d{1,9})(?!\\d)(?::\\d+)?");

    /**
     * A Java frame's qualified class and file: {@code at [loader/module/]pkg.Outer$Inner.method(File.java:12)}.
     * Group 1 is the qualified class, group 2 the file. The loader/module prefix ({@code app//},
     * {@code java.base/}) is skipped, and the method may be {@code <init>} or a {@code lambda$x$0}.
     */
    private static final Pattern JAVA_FRAME = Pattern.compile(
            "\\bat\\s+(?:[^\\s/(]*+/)*+([\\p{L}\\p{N}_$.]+)\\.[^.\\s(]+\\(([A-Za-z0-9_$]+\\.java):\\d+\\)");

    private StackTraceLinks() {}

    /**
     * Where a Java frame's source file sits below a source root — {@code com/foo/Bar.java} for
     * {@code at com.foo.Bar.baz(Bar.java:12)} — or {@code null} when the link is not a Java frame or the
     * class is in the default package (then the bare file name is all there is).
     *
     * <p>The frame prints a <em>bare</em> file name, and resolving that alone finds a file only if it is
     * open or lies directly in the project root. The qualified class beside it says which directory the file
     * is in: the package comes from the class, the file name from the parentheses (a nested or secondary
     * top-level class lives in a file named after another class, so the class name is not used for it).
     */
    public static String javaSourcePath(Link link) {
        if (link == null || link.raw() == null) {
            return null;
        }
        Matcher m = JAVA_FRAME.matcher(link.raw());
        if (!m.find() || !m.group(2).equals(link.file())) {
            return null;
        }
        String qualifiedClass = m.group(1);
        int lastDot = qualifiedClass.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        return qualifiedClass.substring(0, lastDot).replace('.', '/') + "/" + m.group(2);
    }

    /** The first source location in {@code line}, or null when the line holds none. */
    public static Link parse(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        Matcher m = JAVA.matcher(line);
        if (m.find()) {
            return new Link(m.group(1), Integer.parseInt(m.group(2)), line);
        }
        m = PYTHON.matcher(line);
        if (m.find()) {
            return new Link(m.group(1), Integer.parseInt(m.group(2)), line);
        }
        m = NODE.matcher(line);
        if (m.find()) {
            return new Link(m.group(1), Integer.parseInt(m.group(2)), line);
        }
        return null;
    }
}
