package com.editora.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The order a review shows its files in: source first, then tests, configuration, docs, and generated files (lock
 * files, minified and built output) last, each group in the order it came. Read from the path alone. Pure.
 */
public final class ReviewFileGroups {

    private ReviewFileGroups() {}

    /** A kind of file, in review order; {@link #key} names it in the message catalogs. */
    public enum Group {
        SOURCE("review.group.source"),
        TESTS("review.group.tests"),
        CONFIG("review.group.config"),
        DOCS("review.group.docs"),
        GENERATED("review.group.generated");

        private final String key;

        Group(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private static final List<String> LOCK_FILES = List.of(
            "package-lock.json",
            "npm-shrinkwrap.json",
            "yarn.lock",
            "pnpm-lock.yaml",
            "bun.lockb",
            "cargo.lock",
            "poetry.lock",
            "pipfile.lock",
            "uv.lock",
            "gemfile.lock",
            "composer.lock",
            "go.sum",
            "flake.lock",
            "pubspec.lock",
            "mix.lock",
            "gradle.lockfile");

    private static final List<String> CONFIG_NAMES = List.of(
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "package.json",
            "tsconfig.json",
            "cargo.toml",
            "pyproject.toml",
            "setup.cfg",
            "go.mod",
            "gemfile",
            "makefile",
            "dockerfile",
            "cmakelists.txt",
            ".editorconfig",
            ".gitignore",
            ".gitattributes");

    private static final List<String> CONFIG_EXTENSIONS =
            List.of(".yml", ".yaml", ".toml", ".ini", ".cfg", ".conf", ".properties", ".env");

    /** The group of {@code path} (a repository-relative or absolute path, either separator). */
    public static Group classify(String path) {
        String p = path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = p.substring(p.lastIndexOf('/') + 1);
        String slashed = path == null ? "" : path.replace('\\', '/');
        String original = slashed.substring(slashed.lastIndexOf('/') + 1); // FooTest, not Contest
        String dirs = "/" + p.substring(0, p.lastIndexOf('/') + 1);
        if (LOCK_FILES.contains(name)
                || name.endsWith(".min.js")
                || name.endsWith(".min.css")
                || name.endsWith(".map")
                || name.endsWith(".snap")
                || dirs.contains("/generated/")
                || dirs.contains("/dist/")
                || dirs.contains("/node_modules/")
                || dirs.contains("/vendor/")) {
            return Group.GENERATED;
        }
        if (dirs.contains("/test/")
                || dirs.contains("/tests/")
                || dirs.contains("/__tests__/")
                || dirs.contains("/spec/")
                || original.matches(".+(Test|Tests|Spec|IT)\\.[A-Za-z0-9]+")
                || name.matches(".*[._-](test|spec)\\.[a-z0-9]+")
                || name.matches("test_.*\\.py")
                || name.matches(".*_test\\.(go|py|rb|exs?|dart)")) {
            return Group.TESTS;
        }
        if (name.endsWith(".md")
                || name.endsWith(".rst")
                || name.endsWith(".adoc")
                || name.equals("license")
                || name.startsWith("changelog")
                || dirs.contains("/docs/")
                || dirs.contains("/user-docs/")) {
            return Group.DOCS;
        }
        if (CONFIG_NAMES.contains(name)
                || dirs.startsWith("/.github/")
                || dirs.contains("/.github/")
                || CONFIG_EXTENSIONS.stream().anyMatch(name::endsWith)) {
            return Group.CONFIG;
        }
        return Group.SOURCE;
    }

    /** The indices of {@code paths} in review order: by group, then as they came. */
    public static List<Integer> order(List<String> paths) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            idx.add(i);
        }
        idx.sort(Comparator.comparing(i -> classify(paths.get(i)).ordinal())); // List.sort is stable
        return idx;
    }

    /**
     * What an agent is asked for a walkthrough of a change set: a summary, the risky spots, and an order to review
     * it in, then {@code patches} (one unified diff per file, in review order). English: read by a model.
     */
    public static String walkthroughPrompt(List<String> patches, int omitted) {
        StringBuilder p = new StringBuilder();
        p.append("Walk me through this change set before I review it. In short sections: what it does as a whole,")
                .append(" the spots most likely to be wrong (with file and line), and the order to read the files in")
                .append(" and why. Do not change any file.\n");
        for (String patch : patches) {
            p.append("\n```diff\n").append(patch.stripTrailing()).append("\n```\n");
        }
        if (omitted > 0) {
            p.append("\n(").append(omitted).append(" more file(s) left out for length.)\n");
        }
        return p.toString();
    }
}
