package com.editora.deploy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Which files of a project a deployment leaves behind: the built-in patterns plus the deployment's own, one per
 * line. Pure.
 *
 * <p>The patterns read like a {@code .gitignore}'s, without negation:
 *
 * <ul>
 *   <li>a pattern without a {@code /} (other than a trailing one) matches a file or folder of that name at any
 *       depth — {@code *.log}, {@code .DS_Store}, {@code node_modules};
 *   <li>a trailing {@code /} matches only folders — {@code target/};
 *   <li>a pattern with a {@code /} in it matches from the project root — {@code src/generated/} or
 *       {@code /docs/*.pdf} — where {@code **} stands for any number of folders;
 *   <li>{@code *} and {@code ?} never cross a {@code /}; a line starting with {@code #} is a comment;
 *   <li>{@code !} followed by a built-in pattern takes it out ({@code !build/}); any other {@code !} line is
 *       ignored.
 * </ul>
 *
 * <p>The built-in patterns are what no host needs: version control, editor state, and what the host builds or
 * installs for itself — a deployment sends sources and the host builds them (Maven's {@code target/}, Gradle's
 * {@code build/}, {@code node_modules/}, a virtual environment).
 */
public final class DeployExcludes {

    /** Left out of every deployment, whatever it adds. */
    public static final List<String> DEFAULTS = List.of(
            ".git/",
            ".hg/",
            ".svn/",
            ".idea/",
            ".DS_Store",
            "Thumbs.db",
            "target/",
            "build/",
            "out/",
            ".gradle/",
            "node_modules/",
            ".venv/",
            "venv/",
            "__pycache__/",
            "*.pyc",
            "*.class",
            "*.hprof",
            "*~",
            "*.swp");

    /** One parsed pattern. */
    private record Rule(Pattern regex, boolean directoryOnly, boolean anchored, String text) {}

    private final List<Rule> rules;

    private DeployExcludes(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * The built-in patterns and then {@code extra}'s lines. A line {@code !<built-in>} (exactly as listed, such
     * as {@code !build/}) takes that built-in pattern out — for a project whose sources live in such a folder.
     */
    public static DeployExcludes of(String extra) {
        List<String> own = lines(extra);
        List<String> lines = new ArrayList<>();
        for (String builtIn : DEFAULTS) {
            if (!own.contains("!" + builtIn)) {
                lines.add(builtIn);
            }
        }
        lines.addAll(own);
        return ofPatterns(lines);
    }

    /** Exactly {@code patterns}, no built-in ones (for tests and previews). */
    public static DeployExcludes ofPatterns(List<String> patterns) {
        List<Rule> rules = new ArrayList<>();
        for (String raw : patterns) {
            Rule rule = parse(raw);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return new DeployExcludes(rules);
    }

    /** The non-blank, non-comment lines of {@code text}, stripped. */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\R")) {
            String s = line.strip();
            if (!s.isEmpty() && !s.startsWith("#")) {
                out.add(s);
            }
        }
        return out;
    }

    private static @Nullable Rule parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) {
            return null; // negation is not supported: a "!" pattern is ignored rather than misread
        }
        s = s.replace('\\', '/');
        boolean directoryOnly = s.endsWith("/");
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        boolean anchored = s.contains("/");
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            return null;
        }
        return new Rule(Pattern.compile(globToRegex(s)), directoryOnly, anchored, raw.strip());
    }

    /** {@code glob} as a regular expression over a {@code /}-separated path. */
    static String globToRegex(String glob) {
        StringBuilder re = new StringBuilder();
        int n = glob.length();
        for (int i = 0; i < n; i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < n && glob.charAt(i + 1) == '*') {
                    boolean slashAfter = i + 2 < n && glob.charAt(i + 2) == '/';
                    re.append(slashAfter ? "(?:.*/)?" : ".*");
                    i += slashAfter ? 2 : 1;
                } else {
                    re.append("[^/]*");
                }
            } else if (c == '?') {
                re.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                re.append('\\').append(c);
            } else {
                re.append(c);
            }
        }
        return re.toString();
    }

    /** Whether the folder {@code relative} ({@code /}-separated, from the project root) is left out whole. */
    public boolean excludesDirectory(String relative) {
        return matches(relative, true);
    }

    /**
     * Whether the file {@code relative} is left out: itself, or because a folder it is in is. ({@code /}-separated,
     * from the project root.)
     */
    public boolean excludes(String relative) {
        if (relative == null || relative.isEmpty()) {
            return true;
        }
        String[] parts = relative.split("/");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) {
                prefix.append('/');
            }
            prefix.append(parts[i]);
            if (matches(prefix.toString(), true)) {
                return true;
            }
        }
        return matches(relative, false);
    }

    private boolean matches(String path, boolean directory) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        for (Rule rule : rules) {
            if (rule.directoryOnly() && !directory) {
                continue;
            }
            String subject = rule.anchored() ? path : name;
            if (rule.regex().matcher(subject).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The folder names a host-side {@code find} can skip without looking inside: every folder pattern that is a
     * plain name (no {@code /}), as {@code find -name} reads it. Others are still filtered here; this only keeps
     * a host's own {@code target/} or {@code node_modules/} out of the listing.
     */
    public List<String> prunableNames() {
        List<String> names = new ArrayList<>();
        for (Rule rule : rules) {
            String text = rule.text().replace('\\', '/');
            while (text.endsWith("/")) {
                text = text.substring(0, text.length() - 1);
            }
            // A folder-only plain name; "[" would be a bracket glob to find but a literal here.
            if (rule.directoryOnly() && !rule.anchored() && !text.isEmpty() && !text.contains("[")) {
                names.add(text);
            }
        }
        return names;
    }
}
