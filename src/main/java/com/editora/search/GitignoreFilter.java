package com.editora.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.editora.vfs.Vfs;

/**
 * A pragmatic, pure {@code .gitignore} matcher used by the built-in search walker (the fallback when
 * ripgrep — which is natively {@code .gitignore}-aware — isn't installed). Parses a single
 * {@code .gitignore} file and decides whether a root-relative path should be excluded.
 *
 * <p>Supports the rules that matter for "don't search build output": comments / blank lines, {@code !}
 * negation (last match wins), trailing-{@code /} directory-only patterns, leading- or interior-slash
 * <em>anchoring</em> to the root vs. a slash-less pattern matching a path's base name at any depth, and the
 * {@code *} / {@code **} / {@code ?} / {@code [...]} globs. So {@code target/}, {@code node_modules/},
 * {@code build/}, {@code *.log}, {@code /dist} all work. It reads the project root's {@code .gitignore} and,
 * when the root lies inside a git repository, those of the directories above it up to the repository's top;
 * a walk adds the {@code .gitignore} of each directory it enters through {@link #nested}. Not read:
 * {@code .git/info/exclude} and the global excludes file — ripgrep covers the full semantics when present;
 * this is the no-tools fallback.
 *
 * <p>Pure (the matching is) + unit-tested; {@link #load} does the one file read.
 */
public final class GitignoreFilter {

    /** A filter that ignores nothing (no {@code .gitignore}, or the feature is off). */
    public static final GitignoreFilter NONE = new GitignoreFilter(List.of(), false);

    private record Rule(Pattern regex, boolean negated, boolean dirOnly, boolean anchored) {}

    /**
     * One {@code .gitignore}: its rules, and where its directory lies relative to the root. For a file at or
     * above the root, {@code prefix} is the path from its directory down to the root ("" or "a/b/"). For one
     * below the root, {@code scope} is the path from the root down to its directory ("packages/a/"): it
     * speaks only for paths inside that directory, relative to it.
     */
    private record Layer(List<Rule> rules, String prefix, String scope) {}

    /** Innermost first — the root's own file, then each ancestor's — which is git's order of precedence. */
    private final List<Layer> layers;

    /** How far above the root a repository top is looked for; a bound, not a tuning knob. */
    private static final int MAX_ANCESTORS = 32;

    /**
     * Whether a walk should look for further {@code .gitignore} files below the root. True for a filter that
     * came from {@link #load} — "this project's ignore rules" — and false for {@link #NONE} ("ignore nothing")
     * and for {@link #parse}, which is exactly the text it was given.
     */
    private final boolean nests;

    private GitignoreFilter(List<Layer> layers, boolean nests) {
        this.layers = layers;
        this.nests = nests;
    }

    /** See the field: whether {@link #nested} can ever return anything but this filter. */
    public boolean nests() {
        return nests;
    }

    /**
     * This filter plus the {@code .gitignore} of {@code dir}, a directory below the root at root-relative
     * {@code relDir} — or this filter when there is none (the common case: one {@code stat}). A monorepo keeps
     * {@code node_modules} or {@code dist} in {@code packages/<name>/.gitignore}; read only at the root, the
     * rule was never seen and those trees were walked, read and charged against the caps.
     */
    public GitignoreFilter nested(Path dir, String relDir) {
        if (!nests || dir == null || relDir == null || relDir.isEmpty()) {
            return this;
        }
        List<Layer> own = new ArrayList<>(1);
        addLayer(own, dir, "");
        if (own.isEmpty()) {
            return this;
        }
        List<Layer> all = new ArrayList<>(layers.size() + 1);
        all.add(new Layer(own.get(0).rules(), "", relDir.endsWith("/") ? relDir : relDir + "/"));
        all.addAll(layers); // innermost first: the deeper file outranks the ones above it
        return new GitignoreFilter(List.copyOf(all), true);
    }

    public boolean isEmpty() {
        return layers.isEmpty();
    }

    /**
     * Loads {@code <root>/.gitignore}, or {@link #NONE} when it's absent/unreadable.
     *
     * <p>When {@code root} is a folder <em>inside</em> a git repository (a module opened as the project), the
     * {@code .gitignore} files between it and the repository top apply too, as they do for git and ripgrep.
     * Reading only the root's own file left {@code node_modules/}, {@code target/} and {@code build/} — named
     * in the top-level file — walked, indexed and charged against the caps.
     */
    public static GitignoreFilter load(Path root) {
        if (root == null) {
            return NONE;
        }
        List<Layer> layers = new ArrayList<>();
        addLayer(layers, root, "");
        try {
            if (Vfs.isLocal(root)) {
                addAncestors(layers, root.toAbsolutePath().normalize());
            }
        } catch (RuntimeException e) {
            // the root's own file is still worth having
        }
        return new GitignoreFilter(List.copyOf(layers), true);
    }

    private static void addAncestors(List<Layer> layers, Path root) {
        if (Files.exists(root.resolve(".git"))) {
            return; // the root is the repository top: nothing above it applies
        }
        List<Layer> above = new ArrayList<>();
        String prefix = "";
        Path child = root;
        for (int hops = 0; hops < MAX_ANCESTORS; hops++) {
            Path dir = child.getParent();
            Path name = child.getFileName();
            if (dir == null || name == null) {
                return; // reached the filesystem root without meeting a repository: not in one
            }
            prefix = name + "/" + prefix;
            addLayer(above, dir, prefix);
            if (Files.exists(dir.resolve(".git"))) {
                layers.addAll(above);
                return;
            }
            child = dir;
        }
    }

    private static void addLayer(List<Layer> layers, Path dir, String prefix) {
        try {
            Path gi = dir.resolve(".gitignore");
            if (Files.isRegularFile(gi)) {
                GitignoreFilter parsed = parse(Files.readString(gi));
                if (!parsed.layers.isEmpty()) {
                    layers.add(new Layer(parsed.layers.get(0).rules(), prefix, ""));
                }
            }
        } catch (IOException | RuntimeException e) {
            // never let a bad .gitignore break search
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
        return rules.isEmpty() ? NONE : new GitignoreFilter(List.of(new Layer(List.copyOf(rules), "", "")), false);
    }

    /**
     * Whether {@code relPath} (root-relative, {@code /}-separated, no leading slash) is excluded.
     * Rules are applied in order and the last match wins, so a later {@code !pattern} re-includes.
     */
    public boolean ignored(String relPath, boolean isDir) {
        if (relPath == null || relPath.isEmpty()) {
            return false;
        }
        for (Layer layer : layers) {
            String path;
            if (!layer.scope().isEmpty()) {
                if (relPath.length() <= layer.scope().length() || !relPath.startsWith(layer.scope())) {
                    continue; // a nested file says nothing about paths outside its own directory
                }
                path = relPath.substring(layer.scope().length());
            } else {
                path = layer.prefix().isEmpty() ? relPath : layer.prefix() + relPath;
            }
            Boolean decided = decide(layer.rules(), path, isDir);
            if (decided != null) {
                return decided; // a deeper .gitignore outranks the ones above it
            }
        }
        return false;
    }

    /** What {@code rules} say about {@code relPath} (relative to their own directory), or null for nothing. */
    private static Boolean decide(List<Rule> rules, String relPath, boolean isDir) {
        String base = relPath.substring(relPath.lastIndexOf('/') + 1);
        Boolean ignored = null;
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
