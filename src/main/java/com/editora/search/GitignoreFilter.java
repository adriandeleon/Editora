package com.editora.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A pragmatic, pure {@code .gitignore} matcher used by the built-in search walker (the fallback when
 * ripgrep — which is natively {@code .gitignore}-aware — isn't installed). Parses a single
 * {@code .gitignore} file and decides whether a root-relative path should be excluded.
 *
 * <p>Supports the rules that matter for "don't search build output": comments / blank lines, {@code !}
 * negation (last match wins), trailing-{@code /} directory-only patterns, leading- or interior-slash
 * <em>anchoring</em> to the root vs. a slash-less pattern matching a path's base name at any depth, and the
 * {@code *} / {@code **} / {@code ?} / {@code [...]} globs. So {@code target/}, {@code node_modules/},
 * {@code build/}, {@code *.log}, {@code /dist} all work. It reads only the project root's {@code .gitignore}
 * (not nested ones, {@code .git/info/exclude}, or the global excludes file) — ripgrep covers the full
 * semantics when present; this is the no-tools fallback.
 *
 * <p>Pure (the matching is) + unit-tested; {@link #load} does the one file read.
 */
public final class GitignoreFilter {

    /** A filter that ignores nothing (no {@code .gitignore}, or the feature is off). */
    public static final GitignoreFilter NONE = new GitignoreFilter(List.of());

    private record Rule(Pattern regex, boolean negated, boolean dirOnly, boolean anchored) {}

    private final List<Rule> rules;

    private GitignoreFilter(List<Rule> rules) {
        this.rules = rules;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** Loads {@code <root>/.gitignore}, or {@link #NONE} when it's absent/unreadable. */
    public static GitignoreFilter load(Path root) {
        if (root == null) {
            return NONE;
        }
        try {
            Path gi = root.resolve(".gitignore");
            if (!Files.isRegularFile(gi)) {
                return NONE;
            }
            return parse(Files.readString(gi));
        } catch (IOException | RuntimeException e) {
            return NONE; // never let a bad .gitignore break search
        }
    }

    /** Parses {@code .gitignore} text into a filter. */
    public static GitignoreFilter parse(String text) {
        if (text == null || text.isBlank()) {
            return NONE;
        }
        List<Rule> rules = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String line = raw.stripTrailing(); // also drops a trailing '\r' from CRLF
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            boolean negated = false;
            if (line.startsWith("!")) {
                negated = true;
                line = line.substring(1);
            } else if (line.startsWith("\\#") || line.startsWith("\\!")) {
                line = line.substring(1); // an escaped leading '#'/'!' is a literal
            }
            boolean dirOnly = line.endsWith("/");
            if (dirOnly) {
                line = line.substring(0, line.length() - 1);
            }
            // A slash anywhere (now that any trailing one is gone) anchors the pattern to the root; a
            // leading slash also anchors but isn't part of the path to match (paths are root-relative).
            boolean anchored = line.indexOf('/') >= 0;
            if (line.startsWith("/")) {
                line = line.substring(1);
            }
            if (line.isEmpty()) {
                continue;
            }
            Pattern rx = compile(line);
            if (rx != null) {
                rules.add(new Rule(rx, negated, dirOnly, anchored));
            }
        }
        return rules.isEmpty() ? NONE : new GitignoreFilter(rules);
    }

    /**
     * Whether {@code relPath} (root-relative, {@code /}-separated, no leading slash) is excluded.
     * Rules are applied in order and the last match wins, so a later {@code !pattern} re-includes.
     */
    public boolean ignored(String relPath, boolean isDir) {
        if (relPath == null || relPath.isEmpty() || rules.isEmpty()) {
            return false;
        }
        String base = relPath.substring(relPath.lastIndexOf('/') + 1);
        boolean ignored = false;
        for (Rule r : rules) {
            if (r.dirOnly() && !isDir) {
                continue;
            }
            String target = r.anchored() ? relPath : base;
            if (r.regex().matcher(target).matches()) {
                ignored = !r.negated();
            }
        }
        return ignored;
    }

    /** Translates a gitignore glob (slashes intact) into an anchored full-string regex. */
    private static Pattern compile(String glob) {
        StringBuilder sb = new StringBuilder();
        int n = glob.length();
        for (int i = 0; i < n; i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < n && glob.charAt(i + 1) == '*') {
                        boolean slashBefore = i == 0 || glob.charAt(i - 1) == '/';
                        i++; // consume the second '*'
                        boolean slashAfter = i + 1 < n && glob.charAt(i + 1) == '/';
                        if (slashBefore && slashAfter) {
                            sb.append("(?:.*/)?"); // "**/" → zero or more leading path segments
                            i++; // consume the '/'
                        } else {
                            sb.append(".*"); // a bare/edge "**" crosses '/'
                        }
                    } else {
                        sb.append("[^/]*"); // "*" stays within a path segment
                    }
                }
                case '?' -> sb.append("[^/]");
                case '[' -> {
                    int j = i + 1;
                    StringBuilder cls = new StringBuilder("[");
                    if (j < n && glob.charAt(j) == '!') {
                        cls.append('^');
                        j++;
                    } else if (j < n && glob.charAt(j) == '^') {
                        cls.append("\\^");
                        j++;
                    }
                    boolean closed = false;
                    while (j < n) {
                        char cc = glob.charAt(j++);
                        if (cc == ']') {
                            cls.append(']');
                            closed = true;
                            break;
                        }
                        cls.append(cc);
                    }
                    if (closed) {
                        sb.append(cls);
                        i = j - 1;
                    } else {
                        sb.append("\\["); // unterminated → literal '['
                    }
                }
                case '\\' -> {
                    if (i + 1 < n) {
                        sb.append(Pattern.quote(String.valueOf(glob.charAt(++i))));
                    }
                }
                case '.', '(', ')', '+', '|', '^', '$', '{', '}', '@' ->
                    sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        try {
            return Pattern.compile("^" + sb + "$");
        } catch (PatternSyntaxException e) {
            return null;
        }
    }
}
