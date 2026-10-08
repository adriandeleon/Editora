package com.editora.template;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    /** Why a typed name was refused — each maps to its own message, so the user learns what to change. */
    public enum Refusal {
        /** Not usable as a file name (empty segment, {@code ..}, absolute, a trailing slash, …). */
        INVALID_NAME,
        /** A character no file name may hold on every platform, or a trailing dot/space. */
        ILLEGAL_CHARACTER,
        /** A Windows device name ({@code CON}, {@code NUL}, …). */
        RESERVED_NAME,
        /** {@code ~/…}: the prompt names a file in the shown folder, it does not expand a home path. */
        HOME_SHORTHAND,
        /** A Java type or package segment that is not an identifier. */
        NOT_A_JAVA_NAME,
        /** A Java keyword used as a type or package name. */
        JAVA_KEYWORD,
        /** A Java type named with another type's extension ({@code Foo.txt}). */
        NOT_A_JAVA_FILE,
        /** {@code package-info.java} in a folder that is not a package. */
        NO_PACKAGE
    }

    /** The outcome of {@link #decide}: exactly one of {@code plan} / {@code refusal} is non-null. */
    public record Decision(Plan plan, Refusal refusal) {
        static Decision refuse(Refusal why) {
            return new Decision(null, why);
        }
    }

    /** Java's reserved words and literals: never a type name or a package segment. */
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "true",
            "false",
            "null",
            "_");

    /** Restricted identifiers: legal as a package segment, illegal as the name of a type. */
    private static final Set<String> JAVA_RESTRICTED_TYPE_NAMES = Set.of("var", "yield", "record", "sealed", "permits");

    /**
     * The path to create, or null when {@code input} is unusable (blank after trimming is <em>not</em>
     * unusable — it falls back to the type's suggested name). {@link #decide} also says why.
     */
    public static Plan plan(NewFileType type, String input, String basePackage) {
        return decide(type, input, basePackage).plan();
    }

    /**
     * Decides what the typed name means: the path to create, or why it is refused.
     *
     * <p>Two shapes are accepted, matching what IDEs let you type in this prompt:
     *
     * <ul>
     *   <li>Java kinds: a qualified name, {@code util.text.Slug} → {@code util/text/Slug.java} in
     *       package {@code <basePackage>.util.text}. Every segment must be a Java identifier that is not
     *       a keyword, which is also what makes it safe — {@code ..} is not one.
     *   <li>Everything else: a relative path, {@code sub/notes.md}. Absolute paths, {@code ..} and
     *       {@code ~} segments, a trailing slash, and names no platform-neutral file may have are refused.
     * </ul>
     *
     * <p>The type's extension is appended unless the typed name already ends in a <em>known</em> one, so
     * {@code notes.json} under "New ▸ Text File" is JSON while {@code release-1.2} under Markdown becomes
     * {@code release-1.2.md}.
     */
    public static Decision decide(NewFileType type, String input, String basePackage) {
        String name = input == null ? "" : input.trim();
        if (name.isEmpty()) {
            name = type.suggestedFileName();
        }
        if (name.isEmpty()) {
            return Decision.refuse(Refusal.INVALID_NAME); // the generic "File…" entry with nothing typed
        }
        return type.isJava() ? decideJava(name, basePackage) : decidePlain(type, name);
    }

    private static Decision decideJava(String input, String basePackage) {
        String name = input;
        if (name.regionMatches(true, name.length() - 5, ".java", 0, 5)) {
            name = name.substring(0, name.length() - 5);
        }
        if (name.startsWith("~")) {
            return Decision.refuse(Refusal.HOME_SHORTHAND);
        }
        // A path-style qualified name is accepted too ("util/Slug"), since the prompt sits on a folder.
        // Empty segments are kept, not dropped: "../Escape" collapses to "...Escape", and silently
        // creating Escape.java from a name that tried to climb out is worse than refusing it.
        List<String> segments = new ArrayList<>(
                List.of(name.replace('\\', '/').replace('/', '.').split("\\.", -1)));
        if (segments.isEmpty()) {
            return Decision.refuse(Refusal.INVALID_NAME);
        }
        int last = segments.size() - 1;
        // "Foo.txt" is a type called Foo with someone else's extension, not a class `txt` in package Foo.
        // Extensions are lower case and type names conventionally are not, so a real type is rarely caught.
        String tail = segments.get(last);
        if (last > 0
                && tail.equals(tail.toLowerCase(java.util.Locale.ROOT))
                && PortableFileName.isKnownExtension(tail)) {
            return Decision.refuse(Refusal.NOT_A_JAVA_FILE);
        }
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i).trim();
            segments.set(i, segment);
            // package-info / module-info are the two legal Java file names that are not identifiers,
            // and only as the final segment (there is no package called "package-info").
            boolean special = i == last && (segment.equals("package-info") || segment.equals("module-info"));
            if (special) {
                continue;
            }
            if (!isJavaIdentifier(segment)) {
                return Decision.refuse(Refusal.NOT_A_JAVA_NAME);
            }
            if (JAVA_KEYWORDS.contains(segment) || (i == last && JAVA_RESTRICTED_TYPE_NAMES.contains(segment))) {
                return Decision.refuse(Refusal.JAVA_KEYWORD);
            }
            if (PortableFileName.isWindowsReserved(segment)) {
                return Decision.refuse(Refusal.RESERVED_NAME);
            }
        }
        String simple = segments.get(last);
        List<String> subPackages = segments.subList(0, last);
        String relative = String.join("/", segments) + ".java";
        String pkg = joinPackage(basePackage, subPackages);
        if (simple.equals("package-info") && pkg.isEmpty()) {
            // A package-info with no package to describe is not a file javac accepts.
            return Decision.refuse(Refusal.NO_PACKAGE);
        }
        return new Decision(new Plan(relative, simple, pkg), null);
    }

    private static Decision decidePlain(NewFileType type, String input) {
        String normalized = input.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.endsWith("/")) {
            return Decision.refuse(Refusal.INVALID_NAME); // absolute, or a folder rather than a file
        }
        List<String> segments = new ArrayList<>();
        for (String part : normalized.split("/", -1)) {
            // Only the ends are trimmed of spaces the user typed around the name; a segment that is empty
            // ("a//b") or made of spaces is refused below rather than silently dropped.
            segments.add(part.strip());
        }
        for (String segment : segments) {
            switch (PortableFileName.check(segment)) {
                case OK -> {}
                case HOME_SHORTHAND -> {
                    return Decision.refuse(Refusal.HOME_SHORTHAND);
                }
                case RESERVED_NAME -> {
                    return Decision.refuse(Refusal.RESERVED_NAME);
                }
                case ILLEGAL_CHARACTER, TRAILING_DOT_OR_SPACE -> {
                    return Decision.refuse(Refusal.ILLEGAL_CHARACTER);
                }
                case NOT_A_NAME -> {
                    return Decision.refuse(Refusal.INVALID_NAME);
                }
            }
        }
        int last = segments.size() - 1;
        String fileName = withExtension(segments.get(last), type.extension());
        if (PortableFileName.isWindowsReserved(fileName)) {
            return Decision.refuse(Refusal.RESERVED_NAME);
        }
        segments.set(last, fileName);
        return new Decision(new Plan(String.join("/", segments), baseNameOf(fileName), ""), null);
    }

    /**
     * {@code name} plus {@code extension}, unless it already ends in a <em>known</em> extension — an
     * explicit {@code notes.json} keeps its type, while the dots of {@code release-1.2} or {@code notes.v2}
     * are part of the name. A dotfile ({@code .gitignore}) is left exactly as typed rather than becoming
     * {@code .gitignore.txt}.
     */
    static String withExtension(String name, String extension) {
        if (extension.isEmpty()) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        if (dot == 0 && name.indexOf('.', 1) < 0) {
            return name; // a dotfile
        }
        if (dot > 0 && PortableFileName.isKnownExtension(name.substring(dot + 1))) {
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
     * The Java package {@code dir} implies with no project to bound the search: only the conventional
     * {@code src/main/java} and {@code src/test/java} markers count. See {@link #packageFor(Path, Path)}.
     */
    public static String packageFor(Path dir) {
        return packageFor(dir, null);
    }

    /**
     * The Java package {@code dir} implies, or {@code ""} when it isn't under a recognizable source root.
     *
     * <p>Bounded by {@code projectRoot}: nothing at or above it is ever read as a source root or a package
     * segment, so a project kept under {@code ~/src} does not turn {@code ~/src/acme/app} into
     * {@code package acme.app;}. Below the root a source root is {@code src/main/java} or
     * {@code src/test/java} at any depth (a module's own), or a plain {@code src} directly under the
     * project root — unless that {@code src} holds the Maven layout ({@code src/main/…},
     * {@code src/test/…}), where only the {@code java} folders are sources and {@code src/main/resources}
     * is not a package. With no project root (or a folder outside it) only the two conventional markers
     * count.
     *
     * <p>Path-based rather than build-model-based on purpose: the Project tree is what the user
     * right-clicked. A folder whose name isn't a Java identifier ({@code my-notes}) ends the walk with
     * what precedes it, since the rest cannot be a package.
     */
    public static String packageFor(Path dir, Path projectRoot) {
        if (dir == null) {
            return "";
        }
        Path abs = dir.toAbsolutePath().normalize();
        Path root = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        boolean bounded = root != null && abs.startsWith(root);
        List<String> segments = new ArrayList<>();
        for (Path part : bounded ? root.relativize(abs) : abs) {
            if (!part.toString().isEmpty()) {
                segments.add(part.toString());
            }
        }
        int start = sourceRootEnd(segments, bounded);
        if (start < 0) {
            return "";
        }
        List<String> pkg = new ArrayList<>();
        for (String segment : segments.subList(start, segments.size())) {
            if (!isJavaIdentifier(segment) || JAVA_KEYWORDS.contains(segment)) {
                return String.join(".", pkg);
            }
            pkg.add(segment);
        }
        return String.join(".", pkg);
    }

    /**
     * The index just past the innermost source-root marker in {@code segments}, or -1 if there is none.
     * {@code plainSrc} allows a leading {@code src} (the segments are then relative to a project root).
     */
    private static int sourceRootEnd(List<String> segments, boolean plainSrc) {
        for (int i = segments.size() - 3; i >= 0; i--) {
            if (segments.get(i).equals("src")
                    && (segments.get(i + 1).equals("main")
                            || segments.get(i + 1).equals("test"))
                    && segments.get(i + 2).equals("java")) {
                return i + 3;
            }
        }
        if (plainSrc && !segments.isEmpty() && segments.get(0).equals("src")) {
            boolean mavenLayout = segments.size() > 1
                    && (segments.get(1).equals("main") || segments.get(1).equals("test"));
            return mavenLayout ? -1 : 1;
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
