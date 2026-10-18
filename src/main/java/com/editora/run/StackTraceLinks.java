package com.editora.run;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure detection of source locations in program output (unit-tested), so the Run/Debug consoles can make
 * stack-trace lines clickable. Recognizes the frame formats of the three runnable/debuggable languages:
 * Java ({@code at pkg.Cls.m(File.java:12)} — bare file name), Python ({@code File "/path/x.py", line 12}
 * — usually absolute — plus warnings, pytest and linters' {@code x.py:12:} and pdb's {@code > x.py(12)}), and Node ({@code at fn (/path/x.js:12:5)} or a bare {@code /path/x.js:12:5}).
 *
 * <p>Shell scripts too: bash's errors ({@code /path/x.sh: line 7: foo: command not found}), dash's
 * ({@code /path/x.sh: 7: foo: not found}), and the lines of an execution trace, whose prompt Run with Trace sets
 * to {@code +file:line: } ({@code ++/path/x.sh:12: echo hi}, one {@code +} per nesting level).
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
    public record Link(String file, int line, String raw, int column) {
        /** The two-arg form, for callers (and tests) that don't need the original line. */
        public Link(String file, int line) {
            this(file, line, null, 0);
        }

        /** Without a column (Java, Python and shell locations have none). */
        public Link(String file, int line, String raw) {
            this(file, line, raw, 0);
        }
    }

    /** The extensions a Node frame's file has: JavaScript and TypeScript, ESM/CJS, JSX/TSX. */
    private static final String NODE_EXT = "js|mjs|cjs|jsx|ts|mts|cts|tsx";

    // Line numbers are {@code \d{1,9}}: a longer digit run is not a line number, and would overflow parseInt.
    private static final Pattern JAVA = Pattern.compile("\\(([A-Za-z0-9_$]+\\.java):(\\d{1,9})\\)");
    /**
     * javac's (and a Java build's) error location at a word start: {@code /src/demo/Main.java:12: error: …}.
     * Not a stack frame — those are {@link #JAVA}, which is tried first.
     */
    private static final Pattern JAVA_LOCATION =
            Pattern.compile("(?<![^\\s(])((?:[A-Za-z]:)?[^\\s:()]+\\.java):(\\d{1,9}):");
    /**
     * A traceback frame: {@code File "<path>", line N}. Any path — an extensionless script run through its
     * shebang is still Python, and a path may hold a {@code "} (Python prints it unescaped) — but not the
     * pseudo-files {@code <stdin>}, {@code <string>}, {@code <frozen runpy>}.
     */
    private static final Pattern PYTHON = Pattern.compile("File \"(?!<)(.+?)\", line (\\d{1,9})(?!\\d)");
    /**
     * {@code path.py:N:} at a word start — a warning ({@code app.py:12: DeprecationWarning}), pytest's failure
     * location ({@code tests/test_x.py:14: AssertionError}), mypy/ruff/flake8 ({@code app.py:3:5: error}).
     */
    private static final Pattern PYTHON_LOCATION =
            Pattern.compile("(?<![^\\s(])((?:[A-Za-z]:)?[^\\s:()]+\\.pyi?):(\\d{1,9}):");
    /** pdb's current frame: {@code > /path/app.py(12)main()}. */
    private static final Pattern PDB = Pattern.compile("^> (.+?\\.pyi?)\\((\\d{1,9})\\)");
    /**
     * Anchored at a token start (the lookbehind): unanchored, the pattern was retried from every index of a
     * long unbroken token — a 64K base64 line — each time consuming the rest and backing off, which froze the
     * FX thread for seconds on a double-click.
     */
    private static final Pattern NODE = Pattern.compile(
            "(?<![^\\s():'\"])((?:[A-Za-z]:)?[^\\s():'\"]+\\.(?:" + NODE_EXT + ")):(\\d{1,9})(?!\\d)(?::(\\d{1,9}))?");

    /**
     * An ESM frame: {@code file:///home/u/app/index.mjs:3:9} (percent-encoded; {@code file:///C:/…} on Windows).
     * Matched as a whole URL, so its path is decoded and a drive letter kept.
     */
    private static final Pattern NODE_FILE_URL =
            Pattern.compile("file://(/[^\\s()]+?\\.(?:" + NODE_EXT + ")):(\\d{1,9})(?!\\d)(?::(\\d{1,9}))?");

    /**
     * A frame whose location is in parentheses — {@code at fn (/srv/my app/x.js:3:9)} — read up to the closing
     * parenthesis, so a folder with a space in its name stays part of the path.
     */
    private static final Pattern NODE_PAREN =
            Pattern.compile("\\((?!file:)((?:[A-Za-z]:)?[^()]*?\\.(?:" + NODE_EXT + ")):(\\d{1,9}):(\\d{1,9})\\)");

    /** tsc's error location when its output is not a terminal: {@code src/a.ts(1,7): error TS2322: …}. */
    private static final Pattern TSC = Pattern.compile("(?<![^\\s(])((?:[A-Za-z]:)?[^\\s:()]+\\.(?:" + NODE_EXT
            + "))\\((\\d{1,9}),(\\d{1,9})\\): (?:error|warning)");

    /** bash: {@code path: line N: message} — the script path as it was run, then a 1-based line. */
    private static final Pattern BASH_ERROR = Pattern.compile("^(.+?): line (\\d{1,9}): ");
    /** dash / POSIX sh: {@code path: N: message}. */
    private static final Pattern SH_ERROR = Pattern.compile("^([^:\\s][^:]*?): (\\d{1,9}): ");
    /** A trace line under Run with Trace's {@code PS4}: {@code +path:N: command} (more {@code +} when nested). */
    private static final Pattern TRACE = Pattern.compile("^\\++([^+\\s][^:]*?):(\\d{1,9}): ");

    /**
     * A Java frame's qualified class and file: {@code at [loader/module/]pkg.Outer$Inner.method(File.java:12)}.
     * Group 1 is the qualified class, group 2 the file. The loader/module prefix ({@code app//},
     * {@code java.base/}) is skipped, and the method may be {@code <init>} or a {@code lambda$x$0}.
     */
    private static final Pattern JAVA_FRAME = Pattern.compile(
            "\\bat\\s+(?:[^\\s/(]*+/)*+([\\p{L}\\p{N}_$.]+)\\.[^.\\s(]+\\(([A-Za-z0-9_$]+\\.java):\\d+\\)");

    private StackTraceLinks() {}

    /** A Node.js location in {@code line} — an ESM URL, a parenthesized frame, tsc's {@code (l,c)}, or a bare path. */
    private static Link node(String line) {
        Matcher m = NODE_FILE_URL.matcher(line);
        if (m.find()) {
            return new Link(fileUrlPath(m.group(1)), Integer.parseInt(m.group(2)), line, column(m.group(3)));
        }
        m = NODE_PAREN.matcher(line);
        if (m.find()) {
            return new Link(m.group(1), Integer.parseInt(m.group(2)), line, column(m.group(3)));
        }
        m = TSC.matcher(line);
        if (m.find()) {
            return new Link(m.group(1), Integer.parseInt(m.group(2)), line, column(m.group(3)));
        }
        m = NODE.matcher(line);
        while (m.find()) {
            int start = m.start(1);
            // webpack:///src/x.js, node:internal/…: another scheme's URL, not a file on disk.
            if (start > 1
                    && line.charAt(start - 1) == ':'
                    && SCHEME_BEFORE.matcher(line.substring(0, start - 1)).find()) {
                continue;
            }
            return new Link(m.group(1), Integer.parseInt(m.group(2)), line, column(m.group(3)));
        }
        return null;
    }

    /** A URL scheme ending right where a match starts ({@code webpack}, {@code node}) — two letters or more. */
    private static final Pattern SCHEME_BEFORE = Pattern.compile("(?:^|[\\s(])[A-Za-z][\\w+.-]+$");

    /** A {@code file://} URL's path part, percent-decoded; {@code /C:/x} → {@code C:/x}. */
    static String fileUrlPath(String urlPath) {
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(urlPath.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            decoded = urlPath; // a stray % that is not an escape: as written
        }
        return decoded.matches("^/[A-Za-z]:/.*") ? decoded.substring(1) : decoded;
    }

    private static int column(String group) {
        return group == null ? 0 : Integer.parseInt(group);
    }

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

    /** Whether {@code line} is a trace line under Run with Trace's prompt ({@code +path:N: command}). */
    public static boolean isTraceLine(String line) {
        return line != null
                && !line.isEmpty()
                && line.charAt(0) == '+'
                && TRACE.matcher(line).find();
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
        m = JAVA_LOCATION.matcher(line);
        if (m.find()) {
            return new Link(m.group(1), Integer.parseInt(m.group(2)), line);
        }
        for (Pattern python : List.of(PYTHON, PDB, PYTHON_LOCATION)) {
            m = python.matcher(line);
            if (m.find()) {
                return new Link(m.group(1), Integer.parseInt(m.group(2)), line);
            }
        }
        Link node = node(line);
        if (node != null) {
            return node;
        }
        for (Pattern shell : List.of(TRACE, BASH_ERROR, SH_ERROR)) {
            m = shell.matcher(line);
            if (m.find()) {
                return new Link(m.group(1), Integer.parseInt(m.group(2)), line);
            }
        }
        return null;
    }
}
