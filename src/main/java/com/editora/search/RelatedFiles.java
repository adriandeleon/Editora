package com.editora.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The file names most likely to be the counterpart of the one you are in: a test and its subject, a C
 * header and its implementation, a component and its stylesheet.
 *
 * <p>Pairs like these are the most frequent two-file round trip in editing, and every editor that does
 * not offer it makes you retype the name with a different suffix. The rules are conventions rather than
 * facts, so this returns <em>candidate names</em> in preference order and the caller resolves which of
 * them actually exist — the convention being wrong then costs nothing, because a name that does not
 * exist is simply not offered.
 *
 * <p>Pure and toolkit-free; it never touches the filesystem.
 */
public final class RelatedFiles {

    private RelatedFiles() {}

    /** Test-name affixes, in the order they are tried. */
    private static final List<String> TEST_SUFFIXES = List.of("Test", "Tests", "Spec", "IT", "_test", ".test", ".spec");

    /** Implementation/header extension pairs, each direction listed. */
    private static final List<List<String>> EXTENSION_GROUPS = List.of(
            List.of("c", "h"),
            List.of("cc", "hh"),
            List.of("cpp", "hpp", "hxx", "h"),
            List.of("m", "h"),
            List.of("ts", "css", "scss", "html"),
            List.of("tsx", "css", "scss"),
            List.of("jsx", "css", "scss"),
            List.of("js", "css", "scss", "html"),
            List.of("vue", "css", "scss"));

    /**
     * Candidate counterpart file names for {@code fileName}, best first, excluding the name itself.
     *
     * <p>Both directions are produced: from {@code Foo.java} you get {@code FooTest.java} and friends, and
     * from {@code FooTest.java} you get {@code Foo.java}. Which of them exists is the caller's problem.
     */
    public static List<String> candidates(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return List.of();
        }
        String name = fileName.trim();
        int dot = name.lastIndexOf('.');
        // A dotfile (".gitignore") is all base and no extension; a name with no dot likewise.
        String base = dot <= 0 ? name : name.substring(0, dot);
        String ext = dot <= 0 ? "" : name.substring(dot + 1);

        Set<String> out = new LinkedHashSet<>();
        String subject = testSubject(base);
        if (subject != null) {
            // We are in the test: its subject is by far the likeliest destination, so it leads.
            out.add(withExtension(subject, ext));
        } else {
            for (String suffix : TEST_SUFFIXES) {
                out.add(withExtension(base + suffix, ext));
            }
            // The leading-`test_` spelling testSubject() understands, so the jump works in both directions.
            if (!base.startsWith(".")) { // a dotfile has no test_ twin worth offering
                out.add(withExtension("test_" + base, ext));
            }
        }
        for (String other : siblingExtensions(ext)) {
            out.add(withExtension(base, other));
        }
        out.remove(name); // a rule that maps a name to itself offers nothing
        return List.copyOf(out);
    }

    /**
     * The subject {@code base} is a test of, or {@code null} when it does not look like a test name.
     *
     * <p>The suffix must leave something behind: a file actually named {@code Test.java} is a subject in
     * its own right, not a test of the empty string.
     */
    /**
     * Where the JUnit test of {@code javaFile} goes when it has none: the same package under the matching
     * {@code src/test/java} — {@code src/main/java/com/acme/Shape.java} gives
     * {@code src/test/java/com/acme/ShapeTest.java}. Null for a file that is a test already, is not Java, or is
     * not under {@code src/main/java} (Java review B21: Related File had nothing to offer a class with no test).
     */
    public static java.nio.file.@Nullable Path testFor(java.nio.file.@Nullable Path javaFile) {
        if (javaFile == null || javaFile.getFileName() == null) {
            return null;
        }
        String name = javaFile.getFileName().toString();
        if (!name.endsWith(".java") || name.length() <= ".java".length()) {
            return null;
        }
        String base = name.substring(0, name.length() - ".java".length());
        if (testSubject(base) != null || base.equals("package-info") || base.equals("module-info")) {
            return null;
        }
        java.nio.file.Path parent = javaFile.getParent();
        // The innermost src/main/java: a module's own, not an outer folder that happens to be named so.
        for (java.nio.file.Path p = parent; p != null; p = p.getParent()) {
            java.nio.file.Path main = p.getParent();
            java.nio.file.Path src = main == null ? null : main.getParent();
            if (p.getFileName() != null
                    && p.getFileName().toString().equals("java")
                    && main != null
                    && main.getFileName() != null
                    && main.getFileName().toString().equals("main")
                    && src != null
                    && src.getFileName() != null
                    && src.getFileName().toString().equals("src")) {
                return src.resolve("test")
                        .resolve("java")
                        .resolve(p.relativize(parent))
                        .resolve(base + "Test.java");
            }
        }
        return null;
    }

    public static @Nullable String testSubject(String base) {
        if (base == null) {
            return null;
        }
        for (String suffix : TEST_SUFFIXES) {
            if (base.length() > suffix.length() && base.endsWith(suffix)) {
                return base.substring(0, base.length() - suffix.length());
            }
        }
        // The other convention: a leading `test_`, as Python and Go spell it.
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.startsWith("test_") && base.length() > 5) {
            return base.substring(5);
        }
        return null;
    }

    /** Extensions paired with {@code ext} by convention, in order; empty when it belongs to no group. */
    private static List<String> siblingExtensions(String ext) {
        if (ext.isEmpty()) {
            return List.of();
        }
        String lower = ext.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (List<String> group : EXTENSION_GROUPS) {
            if (!group.contains(lower)) {
                continue;
            }
            for (String candidate : group) {
                if (!candidate.equals(lower) && !out.contains(candidate)) {
                    out.add(candidate);
                }
            }
        }
        return out;
    }

    private static String withExtension(String base, String ext) {
        return ext.isEmpty() ? base : base + "." + ext;
    }
}
