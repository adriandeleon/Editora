package com.editora.template;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.io.PathContainment;

/**
 * Pure template rendering: variable discovery (which variables the wizard must ask for), substitution,
 * file-name expansion, and target-path resolution with a containment guard.
 *
 * <h2>Syntax</h2>
 *
 * A template is plain text in which exactly three forms mean something:
 *
 * <ul>
 *   <li>{@code ${name}} — a variable ({@code name} is a letter or {@code _} followed by letters, digits or
 *       {@code _}): a built-in ({@link TemplateVariableResolver}) or one the wizard asks for;
 *   <li>{@code ${name:default}} — the same, with the value used when nothing supplies one (and the
 *       wizard's pre-fill); the default runs to the first <code>}</code>;
 *   <li>{@code ${cursor}} — where the caret lands; removed from the text.
 * </ul>
 *
 * Everything else is literal and written out character for character: {@code $name}, {@code $1},
 * {@code $@}, {@code ${arr[0]}}, {@code ${1:-x}}, {@code $(date)} and backslashes all survive, because a
 * template body is a file (a shell script, an awk program, a Makefile), not a snippet. A literal
 * {@code ${name}} is written {@code $${name}}: {@code $$} directly before a brace collapses to one
 * {@code $} and what follows is text.
 *
 * <p>This deliberately does <em>not</em> go through the snippet parser, whose {@code $1} tab stops,
 * {@code $name} variables and backslash escapes are right for a snippet typed into a buffer and wrong for
 * a file written to disk. No toolkit — unit-tested.
 */
public final class TemplateEngine {

    /** Matches a {@code ${name}} or {@code ${name:default}} reference (name starts with a letter/_). */
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\\}");

    /** Resolves a variable name to its value, or null when nothing supplies one. */
    @FunctionalInterface
    public interface Variables {
        String resolve(String name);
    }

    /**
     * Built-ins that are normally <em>derived from the target file name</em> ({@code fileName},
     * {@code baseName}, {@code extension}). When there is no file name to derive them from — the file-name
     * pattern itself uses one, or the template is multi-file — they must be asked for instead.
     */
    private static final Set<String> FILE_IDENTITY = Set.of("fileName", "baseName", "extension");

    private TemplateEngine() {}

    /** A variable the wizard asks for: its {@code name} and the pre-filled default value. */
    public record TemplateVar(String name, String defaultValue) {}

    /** Rendered text, the caret offset ({@code ${cursor}}, else the end), and whether a cursor was marked. */
    public record Rendered(String text, int caret, boolean hasCursor) {}

    /** What the surroundings already know, so the wizard does not ask for it. */
    public record Context(boolean projectNameKnown) {
        public static final Context NONE = new Context(false);
    }

    /**
     * The distinct, ordered named variables across {@code texts} that are <em>not</em> built-in. The first
     * default given for a name is kept as the pre-fill ({@code ""} when none).
     */
    public static List<TemplateVar> discoverVariables(String... texts) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String text : texts) {
            collect(text, Set.of(), seen);
        }
        return toVars(seen);
    }

    /**
     * Like {@link #discoverVariables}, but a file-identity built-in ({@code fileName}/{@code baseName}/
     * {@code extension}) referenced in the {@code fileNamePattern} is treated as a prompted variable,
     * because there is no source file to derive it from yet.
     */
    public static List<TemplateVar> discoverVariablesForNewFile(String fileNamePattern, String... otherTexts) {
        Map<String, String> seen = new LinkedHashMap<>();
        collect(fileNamePattern, FILE_IDENTITY, seen);
        for (String text : otherTexts) {
            collect(text, Set.of(), seen);
        }
        return toVars(seen);
    }

    /**
     * The variables the wizard must ask for to apply {@code t}: every name that is not a built-in, plus the
     * built-ins the context cannot supply a real value for —
     *
     * <ul>
     *   <li>{@code fileName}/{@code baseName}/{@code extension}: used in a single-file template's file-name
     *       pattern (there is no name to derive them from yet), or anywhere in a multi-file template (which
     *       has no one driving file);
     *   <li>{@code packageName}: unless the template creates a single Java file, whose package comes from
     *       the folder it is created in ({@link NewFileContent#packageFor});
     *   <li>{@code projectName}: when there is no project to name.
     * </ul>
     *
     * A prompted built-in keeps its {@code :default} as the pre-fill, and its answer feeds every use of the
     * name (the resolver checks the wizard's answers before its own values).
     */
    public static List<TemplateVar> promptedVariables(Template t, Context context) {
        Map<String, String> seen = new LinkedHashMap<>();
        Set<String> ask = new HashSet<>();
        if (context == null || !context.projectNameKnown()) {
            ask.add("projectName");
        }
        if (t.isMultiFile()) {
            ask.addAll(FILE_IDENTITY);
            ask.add("packageName");
            for (TemplateFile f : t.files()) {
                collect(f.path(), ask, seen);
            }
            for (TemplateFile f : t.files()) {
                collect(f.body(), ask, seen);
            }
        } else {
            if (!createsJavaFile(t)) {
                ask.add("packageName");
            }
            Set<String> inName = new HashSet<>(ask);
            inName.addAll(FILE_IDENTITY);
            collect(t.fileName(), inName, seen); // file-name pattern first, so its default ("Main") wins
            collect(t.body(), ask, seen);
        }
        return toVars(seen);
    }

    /** True for a single-file template whose file is Java source (by its file-name pattern, else language). */
    public static boolean createsJavaFile(Template t) {
        if (t == null || t.isMultiFile()) {
            return false;
        }
        String fileName = t.fileName() == null ? "" : t.fileName().trim();
        if (!fileName.isEmpty()) {
            return fileName.toLowerCase(Locale.ROOT).endsWith(".java");
        }
        return "java".equalsIgnoreCase(t.language() == null ? "" : t.language().trim());
    }

    /** Collects {@code ${name[:default]}} refs from {@code text} that are not built-in, or are in {@code ask}. */
    private static void collect(String text, Set<String> ask, Map<String, String> seen) {
        if (text == null) {
            return;
        }
        scan(text, (name, def, literal, offset) -> {
            if (!name.equals("cursor") && (!TemplateVariableResolver.isBuiltIn(name) || ask.contains(name))) {
                String had = seen.get(name);
                if (had == null || (had.isEmpty() && def != null && !def.isEmpty())) {
                    seen.put(name, def == null ? "" : def); // the first default given is the pre-fill
                }
            }
            return "";
        });
    }

    private static List<TemplateVar> toVars(Map<String, String> seen) {
        List<TemplateVar> out = new ArrayList<>();
        seen.forEach((name, def) -> out.add(new TemplateVar(name, def)));
        return out;
    }

    /** Called for each variable reference with the output offset it sits at; returns its replacement. */
    private interface Visitor {
        String visit(String name, String defaultValue, String literal, int outputOffset);
    }

    /**
     * Walks {@code text}, copying literal text to the result and replacing each variable reference by what
     * {@code visitor} returns. <code>$${</code> is the escape for a literal <code>${</code>.
     */
    private static String scan(String text, Visitor visitor) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        Matcher m = VAR.matcher(text);
        int i = 0;
        int n = text.length();
        while (i < n) {
            int dollar = text.indexOf('$', i);
            if (dollar < 0) {
                out.append(text, i, n);
                break;
            }
            out.append(text, i, dollar);
            if (text.startsWith("$${", dollar)) {
                out.append("${"); // escaped: the reference that follows is literal text
                i = dollar + 3;
            } else if (text.startsWith("${", dollar) && m.region(dollar, n).lookingAt()) {
                out.append(visitor.visit(m.group(1), m.group(2), m.group(), out.length()));
                i = m.end();
            } else {
                out.append('$');
                i = dollar + 1;
            }
        }
        return out.toString();
    }

    /**
     * Renders {@code body}: {@code ${cursor}} is removed and its offset reported (the first one wins; with
     * none the caret goes to the end), and each variable becomes its value, else its default, else — for a
     * built-in with nothing to say — nothing, else the reference itself, unchanged.
     */
    public static Rendered render(String body, Variables vars) {
        int[] caret = {-1};
        String text = scan(body == null ? "" : body, (name, def, literal, offset) -> {
            if (name.equals("cursor")) {
                if (caret[0] < 0) {
                    caret[0] = offset;
                }
                return "";
            }
            String value = vars == null ? null : vars.resolve(name);
            if (value != null) {
                return value;
            }
            if (def != null) {
                return def;
            }
            return TemplateVariableResolver.isBuiltIn(name) ? "" : literal;
        });
        boolean hasCursor = caret[0] >= 0;
        return new Rendered(text, hasCursor ? caret[0] : text.length(), hasCursor);
    }

    /** True when {@code body} marks a caret position with {@code ${cursor}}. */
    public static boolean hasCursor(String body) {
        return body != null && render(body, name -> "").hasCursor();
    }

    /** Expands a single-line pattern (file name) to plain text — {@code ${cursor}} dropped. */
    public static String expand(String pattern, Variables vars) {
        return collapseDuplicateExtension(render(pattern, vars).text());
    }

    /**
     * Collapses a doubled trailing extension in a file name / path — {@code foo.md.md} → {@code foo.md}.
     * A template pattern like {@code ${baseName:document}.md} appends {@code .md}; if the user typed a
     * {@code baseName} that already ends in {@code .md}, the naive substitution yields {@code x.md.md}.
     * Only collapses when the last two extensions are <em>identical</em> (so {@code types.d.ts},
     * {@code foo.tar.gz}, {@code foo.min.js} are untouched), and only on the final path segment (a dotted
     * directory name is left alone). Pure and unit-tested.
     */
    static String collapseDuplicateExtension(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String dir = slash >= 0 ? name.substring(0, slash + 1) : "";
        String seg = slash >= 0 ? name.substring(slash + 1) : name;
        int lastDot = seg.lastIndexOf('.');
        if (lastDot <= 0) {
            return name;
        }
        String ext = seg.substring(lastDot); // ".md"
        String rest = seg.substring(0, lastDot); // "foo.md"
        return rest.length() > ext.length() && rest.endsWith(ext) ? dir + rest : name;
    }

    /**
     * Resolves a template's {@code pathPattern} (a multi-file path, or a single file's name) against
     * {@code dir}, or {@code null} when the result is not a file <em>really</em> inside {@code dir}:
     *
     * <ul>
     *   <li>the expanded path is empty, absolute, or climbs out with {@code ..} — checked on the expanded
     *       text, so a variable that expands to {@code ../x} is caught too;
     *   <li>a segment is not a portable file name ({@link PortableFileName});
     *   <li>the canonical location is outside {@code dir}'s — a directory inside the target that is a
     *       symbolic link to somewhere else ({@link PathContainment#isWithin}).
     * </ul>
     */
    public static Path resolveTargetPath(Path dir, String pathPattern, Variables vars) {
        return containedPath(dir, expand(pathPattern, vars));
    }

    /** As {@link #resolveTargetPath}, for a relative path that is already expanded. */
    public static Path containedPath(Path dir, String relativePath) {
        if (dir == null || relativePath == null) {
            return null;
        }
        String rel = relativePath.trim().replace('\\', '/');
        if (rel.isEmpty() || rel.startsWith("/") || rel.endsWith("/")) {
            return null;
        }
        for (String segment : rel.split("/", -1)) {
            if (!PortableFileName.isPortable(segment)) {
                return null;
            }
        }
        Path base = dir.toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = base.resolve(rel).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            return null;
        }
        return PathContainment.isWithin(base, resolved) ? resolved : null;
    }
}
