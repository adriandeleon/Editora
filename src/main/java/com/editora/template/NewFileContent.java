package com.editora.template;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The pure half of "New ▸ &lt;type&gt;": turns the name the user typed into a path to create, works
 * out the Java package the target folder implies, and renders the type's initial contents.
 *
 * <p>Kept toolkit-free and IO-free so every rule that decides <em>where a file lands</em> is
 * unit-tested rather than inspected. That matters here: the typed name reaches the filesystem, so
 * {@link #plan} refuses anything that could escape the target folder ({@code ..}, an absolute path,
 * a drive prefix) instead of leaving it to the caller — which still re-checks containment, the same
 * belt-and-braces the template writer uses.
 */
public final class NewFileContent {

    private NewFileContent() {}

    /** Where a new file goes and what it knows about itself. {@code relativePath} uses {@code /}. */
    public record Plan(String relativePath, String baseName, String packageName) {

        /** The file name alone (the last segment of {@link #relativePath}). */
        public String fileName() {
            int slash = relativePath.lastIndexOf('/');
            return slash < 0 ? relativePath : relativePath.substring(slash + 1);
        }
    }

    /** Rendered contents plus the offset the caret should land on. */
    public record Rendered(String text, int caret) {}

    /** Source-root layouts whose tail is a Java package, longest first (Maven/Gradle conventions). */
    private static final List<List<String>> SOURCE_ROOTS =
            List.of(List.of("src", "main", "java"), List.of("src", "test", "java"), List.of("src"));

    /**
     * The path to create, or null when {@code input} is unusable (blank after trimming is <em>not</em>
     * unusable — it falls back to the type's suggested name).
     *
     * <p>Two shapes are accepted, matching what IDEs let you type in this prompt:
     *
     * <ul>
     *   <li>Java kinds: a qualified name, {@code util.text.Slug} → {@code util/text/Slug.java} in
     *       package {@code <basePackage>.util.text}. Every segment must be a Java identifier, which
     *       is also what makes it safe — {@code ..} is not one.
     *   <li>Everything else: a relative path, {@code sub/notes.md}. Absolute paths and {@code ..}
     *       segments are refused.
     * </ul>
     *
     * <p>The type's extension is appended only when the typed name has none, so typing an explicit
     * {@code notes.json} under "New ▸ Text File" gives you JSON rather than {@code notes.json.txt}.
     */
    public static Plan plan(NewFileType type, String input, String basePackage) {
        String name = input == null ? "" : input.trim();
        if (name.isEmpty()) {
            name = type.suggestedFileName();
        }
        if (name.isEmpty()) {
            return null; // the generic "File…" entry with nothing typed
        }
        return type.isJava() ? planJava(type, name, basePackage) : planPlain(type, name);
    }

    private static Plan planJava(NewFileType type, String input, String basePackage) {
        String name = input;
        if (name.regionMatches(true, name.length() - 5, ".java", 0, 5)) {
            name = name.substring(0, name.length() - 5);
        }
        // A path-style qualified name is accepted too ("util/Slug"), since the prompt sits on a folder.
        // Empty segments are kept, not dropped: "../Escape" collapses to "...Escape", and silently
        // creating Escape.java from a name that tried to climb out is worse than refusing it.
        List<String> segments = new ArrayList<>(
                List.of(name.replace('\\', '/').replace('/', '.').split("\\.", -1)));
        if (segments.isEmpty()) {
            return null;
        }
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i).trim();
            segments.set(i, segment);
            // package-info / module-info are the two legal Java file names that are not identifiers,
            // and only as the final segment (there is no package called "package-info").
            boolean special =
                    i == segments.size() - 1 && (segment.equals("package-info") || segment.equals("module-info"));
            if (!special && !isJavaIdentifier(segment)) {
                return null;
            }
        }
        String simple = segments.get(segments.size() - 1);
        List<String> subPackages = segments.subList(0, segments.size() - 1);
        String relative = String.join("/", segments) + ".java";
        String pkg = joinPackage(basePackage, subPackages);
        return new Plan(relative, simple, pkg);
    }

    private static Plan planPlain(NewFileType type, String input) {
        String normalized = input.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":")) {
            return null; // absolute, or a Windows drive/ADS prefix
        }
        List<String> segments = splitPath(normalized);
        if (segments.isEmpty()) {
            return null;
        }
        for (String segment : segments) {
            if (segment.equals(".") || segment.equals("..")) {
                return null;
            }
        }
        int last = segments.size() - 1;
        segments.set(last, withExtension(segments.get(last), type.extension()));
        String fileName = segments.get(last);
        return new Plan(String.join("/", segments), baseNameOf(fileName), "");
    }

    /**
     * {@code name} plus {@code extension}, unless it already carries a dot — an explicit
     * {@code notes.json} keeps its type, and a dotfile ({@code .gitignore}) is left exactly as typed
     * rather than becoming {@code .gitignore.txt}.
     */
    static String withExtension(String name, String extension) {
        if (extension.isEmpty() || name.indexOf('.') >= 0) {
            return name;
        }
        return name + "." + extension;
    }

    /** The file name without its extension; a dotfile ({@code .gitignore}) keeps its whole name. */
    static String baseNameOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * The Java package {@code dir} implies, or {@code ""} when it isn't under a recognizable source
     * root (a loose folder, or a tree we can't read as packages).
     *
     * <p>Path-based rather than build-model-based on purpose: the Project tree is what the user
     * right-clicked, and every JVM layout Editora meets puts packages under {@code src/main/java},
     * {@code src/test/java} or {@code src}. A folder whose name isn't a Java identifier
     * ({@code my-notes}) ends the walk with what precedes it, since the rest cannot be a package.
     */
    public static String packageFor(Path dir) {
        if (dir == null) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        for (Path part : dir.toAbsolutePath().normalize()) {
            segments.add(part.toString());
        }
        int start = sourceRootEnd(segments);
        if (start < 0) {
            return "";
        }
        List<String> pkg = new ArrayList<>();
        for (String segment : segments.subList(start, segments.size())) {
            if (!isJavaIdentifier(segment)) {
                return String.join(".", pkg);
            }
            pkg.add(segment);
        }
        return String.join(".", pkg);
    }

    /** The index just past the last source-root marker in {@code segments}, or -1 if there is none. */
    private static int sourceRootEnd(List<String> segments) {
        for (List<String> root : SOURCE_ROOTS) {
            for (int i = segments.size() - root.size(); i >= 0; i--) {
                if (segments.subList(i, i + root.size()).equals(root)) {
                    return i + root.size();
                }
            }
        }
        return -1;
    }

    /**
     * The type's initial contents with its tokens filled in: <code>{package}</code> becomes a package
     * declaration (nothing outside a source root), <code>{name}</code> the base name, and
     * <code>{cursor}</code> is removed, reporting where the caret goes.
     */
    public static Rendered render(NewFileType type, String baseName, String packageName) {
        String template = type.template();
        if (template.isEmpty()) {
            return new Rendered("", 0);
        }
        String declaration = packageName == null || packageName.isEmpty() ? "" : "package " + packageName + ";\n\n";
        String text = template.replace("{package}", declaration).replace("{name}", baseName);
        int caret = text.indexOf("{cursor}");
        if (caret >= 0) {
            text = text.substring(0, caret) + text.substring(caret + "{cursor}".length());
        }
        text = trimTrailingBlankLines(text);
        return new Rendered(text, caret < 0 ? text.length() : Math.min(caret, text.length()));
    }

    /** Drops trailing blank lines, leaving exactly one newline at the end of a non-empty file. */
    private static String trimTrailingBlankLines(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return end == 0 ? "" : text.substring(0, end) + "\n";
    }

    /** Splits a path on {@code /}, dropping empty segments (so {@code a//b} and {@code a/} are fine). */
    private static List<String> splitPath(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split("/")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String joinPackage(String base, List<String> extra) {
        List<String> parts = new ArrayList<>();
        if (base != null && !base.isBlank()) {
            parts.add(base);
        }
        parts.addAll(extra);
        return String.join(".", parts);
    }

    /** True for a segment usable as a Java package/type name — which also rules out {@code ..}. */
    static boolean isJavaIdentifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
