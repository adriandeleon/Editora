package com.editora.lsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers the JDKs installed on this machine and shapes them into jdtls's
 * {@code java.configuration.runtimes} setting.
 *
 * <p><b>Why this exists.</b> m2e writes a project's required execution environment into its Eclipse
 * {@code .classpath} from the pom — {@code maven.compiler.release=17} becomes
 * {@code JRE_CONTAINER/…/JavaSE-17}. jdtls can only bind that container to a JDK it has been <em>told</em>
 * about. Declaring nothing leaves it knowing only the JVM it happens to run on, so a project targeting any
 * other release has an unresolved container — and an unresolved container means
 * {@code vscode.java.resolveClasspath} answers with an <b>empty classpath and no error</b>, which the Run
 * path can only report as "the project hasn't finished importing" for a project that imported perfectly.
 * (Observed exactly: a generated quickstart pinned to release 17 on a machine with only JDK 21 and 25.)
 *
 * <p>Versions are read from each JDK's {@code release} file rather than by running {@code java -version}:
 * this runs while a language server is starting, and forking one process per candidate directory would be
 * both slow and needless.
 *
 * <p>Pure and injectable — the caller supplies the candidate directories and a reader for a directory's
 * {@code release} file, so the mapping is unit-tested without touching a real filesystem.
 */
public final class JavaRuntimes {

    /** Where JDKs live, per platform. Cheap to probe: a directory listing, no process spawn. */
    private static List<java.nio.file.Path> searchRoots() {
        List<java.nio.file.Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        roots.add(java.nio.file.Path.of(home, ".sdkman", "candidates", "java"));
        roots.add(java.nio.file.Path.of("/usr/lib/jvm"));
        roots.add(java.nio.file.Path.of("/Library/Java/JavaVirtualMachines"));
        roots.add(java.nio.file.Path.of(home, ".jdks")); // JetBrains toolbox
        roots.add(java.nio.file.Path.of("C:\\Program Files\\Java"));
        roots.add(java.nio.file.Path.of("C:\\Program Files\\Eclipse Adoptium"));
        return roots;
    }

    /**
     * Every JDK this machine appears to have, including the one Editora is running on (which is always a
     * valid runtime and may be the only one). Best-effort: an unreadable directory is skipped.
     */
    public static List<Jdk> discover() {
        List<Jdk> found = new ArrayList<>();
        addJdk(found, java.nio.file.Path.of(System.getProperty("java.home", "")));
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            addJdk(found, java.nio.file.Path.of(javaHome));
        }
        for (java.nio.file.Path root : searchRoots()) {
            if (!java.nio.file.Files.isDirectory(root)) {
                continue;
            }
            try (java.util.stream.Stream<java.nio.file.Path> kids = java.nio.file.Files.list(root)) {
                kids.filter(java.nio.file.Files::isDirectory).forEach(d -> {
                    addJdk(found, d);
                    addJdk(found, d.resolve("Contents").resolve("Home")); // macOS bundle layout
                });
            } catch (java.io.IOException ignored) {
                // an unreadable JDK directory simply isn't a runtime we can offer
            }
        }
        return List.copyOf(found);
    }

    /** Adds {@code dir} if it looks like a JDK (has a readable {@code release} with a JAVA_VERSION). */
    private static void addJdk(List<Jdk> out, java.nio.file.Path dir) {
        if (dir == null) {
            return;
        }
        java.nio.file.Path release = dir.resolve("release");
        if (!java.nio.file.Files.isRegularFile(release)) {
            return;
        }
        try {
            int major = majorFromRelease(java.nio.file.Files.readString(release));
            if (major > 0) {
                // Resolve symlinks so sdkman's "current" and its target collapse to one entry.
                out.add(new Jdk(major, dir.toRealPath().toString()));
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // unreadable/undecodable: not a runtime we can offer
        }
    }

    /** jdtls understands {@code JavaSE-1.8} for 8 and {@code JavaSE-N} from 9 on. */
    static String executionEnvironment(int major) {
        return major <= 8 ? "JavaSE-1." + major : "JavaSE-" + major;
    }

    private JavaRuntimes() {}

    /** One discovered JDK. */
    public record Jdk(int major, String path) {}

    /**
     * The distinct major versions of the discovered JDKs, newest first — for offering the user a choice of
     * release levels they can actually compile against.
     *
     * <p>Distinct because several installs commonly share a major (a 25 and a 25.0.4 side by side), and
     * newest-first because that is the one most likely to be wanted.
     */
    public static List<Integer> majorsDescending(List<Jdk> jdks) {
        if (jdks == null) {
            return List.of();
        }
        return jdks.stream()
                .map(Jdk::major)
                .filter(m -> m > 0)
                .distinct()
                .sorted(java.util.Comparator.reverseOrder())
                .toList();
    }

    /**
     * Parses the {@code JAVA_VERSION} line of a JDK's {@code release} file into a major version, or 0.
     *
     * <p>Handles both schemes: {@code "21.0.11"} → 21 and the legacy {@code "1.8.0_402"} → 8.
     */
    public static int majorFromRelease(String releaseFileContents) {
        if (releaseFileContents == null) {
            return 0;
        }
        for (String line : releaseFileContents.split("\\R")) {
            String s = line.strip();
            if (!s.startsWith("JAVA_VERSION")) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String v = s.substring(eq + 1).strip().replace("\"", "");
            if (v.startsWith("1.")) {
                v = v.substring(2); // 1.8.0_402 -> 8.0_402
            }
            int end = 0;
            while (end < v.length() && Character.isDigit(v.charAt(end))) {
                end++;
            }
            try {
                return end == 0 ? 0 : Integer.parseInt(v.substring(0, end));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * The {@code java.configuration.runtimes} entries for {@code jdks}, newest first, with the newest marked
     * default. One entry per execution environment — a duplicate major (the same JDK reached through two
     * paths, e.g. sdkman's {@code current} symlink) would make jdtls reject the whole setting.
     */
    public static List<Map<String, Object>> runtimes(List<Jdk> jdks) {
        if (jdks == null || jdks.isEmpty()) {
            return List.of();
        }
        Map<Integer, String> byMajor = new LinkedHashMap<>();
        jdks.stream()
                .filter(j -> j != null
                        && j.major() > 0
                        && j.path() != null
                        && !j.path().isBlank())
                .sorted(Comparator.comparingInt(Jdk::major).reversed())
                .forEach(j -> byMajor.putIfAbsent(j.major(), j.path()));

        List<Map<String, Object>> out = new ArrayList<>(byMajor.size());
        boolean first = true;
        for (Map.Entry<Integer, String> e : byMajor.entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", executionEnvironment(e.getKey()));
            entry.put("path", e.getValue());
            if (first) {
                // jdtls requires at most one default; the newest is the sensible fallback for a project
                // whose own execution environment has no exact match.
                entry.put("default", true);
                first = false;
            }
            out.add(Map.copyOf(entry));
        }
        return List.copyOf(out);
    }
}
