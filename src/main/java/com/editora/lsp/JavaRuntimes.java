package com.editora.lsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

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
        roots.add(java.nio.file.Path.of(home, ".asdf", "installs", "java"));
        roots.add(java.nio.file.Path.of(home, ".local", "share", "mise", "installs", "java"));
        roots.add(java.nio.file.Path.of(home, ".jabba", "jdk"));
        roots.add(java.nio.file.Path.of("/usr/lib/jvm"));
        roots.add(java.nio.file.Path.of("/usr/java"));
        roots.add(java.nio.file.Path.of("/Library/Java/JavaVirtualMachines"));
        roots.add(java.nio.file.Path.of("/opt/homebrew/opt"));
        roots.add(java.nio.file.Path.of("/usr/local/opt"));
        roots.add(java.nio.file.Path.of(home, ".jdks")); // JetBrains toolbox
        roots.add(java.nio.file.Path.of("C:\\Program Files\\Java"));
        roots.add(java.nio.file.Path.of("C:\\Program Files\\Eclipse Adoptium"));
        roots.add(java.nio.file.Path.of("C:\\Program Files\\Microsoft"));
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
                    addJdk(
                            found,
                            d.resolve("libexec")
                                    .resolve("openjdk.jdk")
                                    .resolve("Contents")
                                    .resolve("Home"));
                });
            } catch (java.io.IOException ignored) {
                // an unreadable JDK directory simply isn't a runtime we can offer
            }
        }
        return List.copyOf(found);
    }

    /**
     * Whether {@code dir} is a JDK rather than a bare runtime image — i.e. it ships a compiler.
     *
     * <p>A {@code release} file is not enough, and trusting one shipped a real bug. Editora's own packaged
     * runtime (jpackage's {@code <app>/lib/runtime}) carries one reading {@code JAVA_VERSION="25.0.4"} while
     * being a jlinked image with no compiler — and on non-Windows {@code scripts/aot_build.java} deletes its
     * {@code bin/} outright. Admitting it let {@code System.getProperty("java.home")} claim the JavaSE-25
     * slot in every packaged build, which jdtls rejects outright ("Invalid runtime for JavaSE-25: The path
     * (/opt/editora/lib/runtime) does not point to a JDK"). Worse, {@link #discover} adds {@code java.home}
     * first and {@link #runtimes} de-dupes by major, so it also <em>shadowed</em> a real JDK 25 installed on
     * the machine — leaving that execution environment unbound and unmarking the default, the very
     * empty-classpath failure this class exists to prevent. Invisible under {@code mvn javafx:run}, where
     * {@code java.home} is a real JDK.
     */
    static boolean hasCompiler(java.nio.file.Path dir) {
        if (dir == null) {
            return false;
        }
        java.nio.file.Path bin = dir.resolve("bin");
        return java.nio.file.Files.isRegularFile(bin.resolve("javac"))
                || java.nio.file.Files.isRegularFile(bin.resolve("javac.exe"));
    }

    /** Adds {@code dir} if it looks like a JDK (a compiler, plus a readable {@code release} with a version). */
    private static void addJdk(List<Jdk> out, java.nio.file.Path dir) {
        if (!hasCompiler(dir)) {
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

    /** As {@link #runtimes(List, String)} with no preferred JDK: the newest is the default. */
    public static List<Map<String, Object>> runtimes(List<Jdk> jdks) {
        return runtimes(jdks, null);
    }

    /**
     * The {@code java.configuration.runtimes} entries for {@code jdks}, newest first. One entry per execution
     * environment — a duplicate major (the same JDK reached through two paths, e.g. sdkman's {@code current}
     * symlink) would make jdtls reject the whole setting.
     *
     * <p>jdtls takes at most one {@code default}: the JDK for a project whose own release has no exact match,
     * for a folder with no build file, and the one Gradle runs on when nothing else names one. That is
     * {@code preferredHome} — the user's Default JDK, else {@code JAVA_HOME} ({@link #preferredHome}) — when it
     * is among them, and only otherwise the newest. Marking the newest outright handed Gradle a JDK newer than
     * it supports, and every Gradle import failed (Java review C4).
     */
    public static List<Map<String, Object>> runtimes(List<Jdk> jdks, @Nullable String preferredHome) {
        if (jdks == null || jdks.isEmpty()) {
            return List.of();
        }
        String preferred = realPath(preferredHome);
        Map<Integer, String> byMajor = new LinkedHashMap<>();
        jdks.stream()
                .filter(j -> j != null
                        && j.major() > 0
                        && j.path() != null
                        && !j.path().isBlank())
                .sorted(Comparator.comparingInt(Jdk::major)
                        .reversed()
                        // the preferred JDK holds its major's slot against another install of the same major
                        .thenComparing(j -> !j.path().equals(preferred))
                        // then one with its sources: a distro JDK without them (Debian's openjdk-21-jdk with no
                        // -source package) gave JDK parameters as arg0 and signature help no docs (fourth review DF1)
                        .thenComparing(j -> !hasSources(j.path())))
                .forEach(j -> byMajor.putIfAbsent(j.major(), j.path()));

        if (byMajor.isEmpty()) {
            return List.of();
        }
        String chosen = byMajor.containsValue(preferred)
                ? preferred
                : byMajor.values().iterator().next();
        List<Map<String, Object>> out = new ArrayList<>(byMajor.size());
        for (Map.Entry<Integer, String> e : byMajor.entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", executionEnvironment(e.getKey()));
            entry.put("path", e.getValue());
            if (!hasSources(e.getValue())) {
                entry.put("javadoc", apiDocs(e.getKey())); // jdtls reads parameter names and docs from it then
            }
            if (e.getValue().equals(chosen)) {
                entry.put("default", true);
            }
            out.add(Map.copyOf(entry));
        }
        return List.copyOf(out);
    }

    /** Whether the JDK at {@code home} has its sources ({@code lib/src.zip}, or {@code src.zip} for Java 8). */
    static boolean hasSources(String home) {
        try {
            java.nio.file.Path h = java.nio.file.Path.of(home);
            // Files.exists follows the link: a distro JDK's src.zip is a link to a package not installed
            return java.nio.file.Files.exists(h.resolve("lib").resolve("src.zip"))
                    || java.nio.file.Files.exists(h.resolve("src.zip"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The online API documentation of Java {@code major}, for a JDK without sources. */
    static String apiDocs(int major) {
        return major <= 8
                ? "https://docs.oracle.com/javase/8/docs/api/"
                : "https://docs.oracle.com/en/java/javase/" + major + "/docs/api/";
    }

    /** The path of the runtime {@link #runtimes} marked default, or null when there is none. */
    public static @Nullable String defaultPath(List<Map<String, Object>> runtimes) {
        for (Map<String, Object> entry : runtimes) {
            if (Boolean.TRUE.equals(entry.get("default")) && entry.get("path") instanceof String path) {
                return path;
            }
        }
        return null;
    }

    /**
     * The JDK the user chose for Java work: Settings' Default JDK ({@code settingHome}) when set, else
     * {@code javaHome} (the {@code JAVA_HOME} variable), else null.
     */
    public static @Nullable String preferredHome(@Nullable String settingHome, @Nullable String javaHome) {
        if (settingHome != null && !settingHome.isBlank()) {
            return settingHome.strip();
        }
        return javaHome == null || javaHome.isBlank() ? null : javaHome.strip();
    }

    private static @Nullable String realPath(@Nullable String home) {
        if (home == null || home.isBlank()) {
            return null;
        }
        try {
            return java.nio.file.Path.of(home).toRealPath().toString();
        } catch (java.io.IOException | RuntimeException e) {
            return home;
        }
    }

    /** jdtls's own floor: its launcher refuses an older Java. */
    static final int SERVER_MIN_MAJOR = 21;

    /**
     * What jdtls's launcher should be told to run on, given the major version of the Java it would pick itself
     * ({@code launcherMajor}: {@code JAVA_HOME}'s, else the one on the PATH; 0 when unknown). Null executable
     * when the launcher's own choice will do — it is new enough, or unknown and left to the launcher. Otherwise
     * the preferred JDK when it is new enough, else the newest discovered one that is, and {@code missing} when
     * none is (Java review C5: with {@code JAVA_HOME} on 17 jdtls could not start, and Editora only said it
     * "keeps crashing").
     */
    public static ServerJava serverJava(int launcherMajor, List<Jdk> jdks, @Nullable String preferredHome) {
        if (launcherMajor <= 0 || launcherMajor >= SERVER_MIN_MAJOR) {
            return new ServerJava(null, false);
        }
        String preferred = realPath(preferredHome);
        Jdk pick = null;
        for (Jdk j : jdks == null ? List.<Jdk>of() : jdks) {
            if (j == null || j.major() < SERVER_MIN_MAJOR || j.path() == null) {
                continue;
            }
            if (j.path().equals(preferred)) {
                pick = j;
                break;
            }
            if (pick == null || j.major() > pick.major()) {
                pick = j;
            }
        }
        if (pick == null) {
            return new ServerJava(null, true);
        }
        boolean windows = java.io.File.separatorChar == '\\';
        return new ServerJava(
                java.nio.file.Path.of(pick.path(), "bin", windows ? "java.exe" : "java")
                        .toString(),
                false);
    }

    /** The Java jdtls should be started on ({@code executable}, null: the launcher's own), or none qualifies. */
    public record ServerJava(@Nullable String executable, boolean missing) {}

    /**
     * The major version of the Java jdtls's launcher picks by itself: {@code JAVA_HOME}'s, else the {@code java}
     * on the {@code PATH}, read from that JDK's {@code release} file. 0 when it cannot be told.
     */
    public static int launcherJavaMajor() {
        String javaHome = System.getenv("JAVA_HOME");
        java.nio.file.Path home = null;
        if (javaHome != null && !javaHome.isBlank()) {
            home = java.nio.file.Path.of(javaHome);
        } else {
            String path = System.getenv("PATH");
            for (String dir : path == null ? new String[0] : path.split(java.io.File.pathSeparator)) {
                for (String name : List.of("java", "java.exe")) {
                    java.nio.file.Path exe = java.nio.file.Path.of(dir, name);
                    if (java.nio.file.Files.isExecutable(exe)) {
                        try {
                            java.nio.file.Path bin = exe.toRealPath().getParent();
                            home = bin == null ? null : bin.getParent();
                        } catch (java.io.IOException | RuntimeException e) {
                            home = null;
                        }
                        break;
                    }
                }
                if (home != null) {
                    break;
                }
            }
        }
        if (home == null) {
            return 0;
        }
        try {
            return majorFromRelease(java.nio.file.Files.readString(home.resolve("release")));
        } catch (java.io.IOException | RuntimeException e) {
            return 0;
        }
    }
}
