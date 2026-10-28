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
        return runtimes(jdks, preferredHome, true);
    }

    /**
     * As {@link #runtimes(List, String)}; {@code local}: the JDKs are on this machine, so whether each has its
     * sources can be read here. A remote host's are not looked for on this machine's disk.
     */
    public static List<Map<String, Object>> runtimes(List<Jdk> jdks, @Nullable String preferredHome, boolean local) {
        if (jdks == null || jdks.isEmpty()) {
            return List.of();
        }
        String preferred = realPath(preferredHome);
        // Each JDK's own path is resolved too: a JDK reached through a symlink or an alias (macOS's /var →
        // /private/var, a Windows 8.3 short name) is the preferred one even though the strings differ.
        java.util.Set<String> preferredPaths = new java.util.HashSet<>();
        for (Jdk j : jdks) {
            if (j != null && j.path() != null && preferred != null && preferred.equals(realPath(j.path()))) {
                preferredPaths.add(j.path());
            }
        }
        Map<Integer, String> byMajor = new LinkedHashMap<>();
        jdks.stream()
                .filter(j -> j != null
                        && j.major() > 0
                        && j.path() != null
                        && !j.path().isBlank())
                .sorted(Comparator.comparingInt(Jdk::major)
                        .reversed()
                        // one with its sources first: a distro JDK without them (Debian's openjdk-21-jdk with no
                        // -source package) gives JDK parameters as arg0 and signature help no docs (fourth review
                        // DF1), even as the preferred JDK, which then yields to its twin (fifth review DF1)
                        .thenComparing(j -> local && !hasSources(j.path()))
                        // then the preferred JDK holds its major's slot against another install of the same major
                        .thenComparing(j -> !preferredPaths.contains(j.path())))
                .forEach(j -> byMajor.putIfAbsent(j.major(), j.path()));

        if (byMajor.isEmpty()) {
            return List.of();
        }
        // The default is the preferred JDK's major: its twin with sources stands in for it.
        Integer preferredMajor = jdks.stream()
                .filter(j -> j != null && j.path() != null && preferredPaths.contains(j.path()))
                .map(Jdk::major)
                .findFirst()
                .orElse(null);
        String chosen = preferredMajor != null && byMajor.containsKey(preferredMajor)
                ? byMajor.get(preferredMajor)
                : byMajor.values().iterator().next();
        List<Map<String, Object>> out = new ArrayList<>(byMajor.size());
        for (Map.Entry<Integer, String> e : byMajor.entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", executionEnvironment(e.getKey()));
            entry.put("path", e.getValue());
            if (e.getValue().equals(chosen)) {
                entry.put("default", true);
            }
            out.add(Map.copyOf(entry));
        }
        return List.copyOf(out);
    }

    /**
     * The JDK jdtls gives a project by default when neither it nor another JDK of its version has sources, else
     * null: its parameters read {@code arg0} and its methods have no docs, which Doctor says how to fix (sixth review
     * DF1). The default is {@code preferredHome}'s when it is among {@code jdks}, else the newest.
     */
    public static @Nullable Jdk withoutSources(List<Jdk> jdks, @Nullable String preferredHome) {
        List<Jdk> all = allWithoutSources(jdks, preferredHome);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Every JDK jdtls is given for a release ({@link #runtimes}) that has no sources, the default first: a release-21
     * project on a distro JDK 21 showed {@code arg0} while the default JDK 25 had its sources (seventh review DR2).
     * {@link #runtimes} already prefers a twin with sources, so a JDK listed here has none.
     */
    public static List<Jdk> allWithoutSources(List<Jdk> jdks, @Nullable String preferredHome) {
        List<Jdk> out = new ArrayList<>();
        for (Map<String, Object> entry : runtimes(jdks, preferredHome)) {
            String path = String.valueOf(entry.get("path"));
            if (hasSources(path)) {
                continue;
            }
            int major = jdks.stream()
                    .filter(j -> j != null && path.equals(j.path()))
                    .mapToInt(Jdk::major)
                    .findFirst()
                    .orElse(0);
            Jdk bare = new Jdk(major, path);
            if (Boolean.TRUE.equals(entry.get("default"))) {
                out.add(0, bare);
            } else {
                out.add(bare);
            }
        }
        return out;
    }

    /**
     * The JDKs without sources that the project at {@code projectRoot} gets: its stated release's JDK when jdtls is
     * given one, else the default. Doctor warned for every bare JDK on the machine, though no project used it, and
     * named only one (eighth review DQ4). No project: the default's.
     */
    public static List<Jdk> withoutSourcesFor(
            List<Jdk> jdks, @Nullable String preferredHome, java.nio.file.@Nullable Path projectRoot) {
        int release = statedRelease(projectRoot);
        List<Jdk> bare = allWithoutSources(jdks, preferredHome);
        List<Map<String, Object>> given = runtimes(jdks, preferredHome);
        boolean releaseGiven = release > 0
                && given.stream().anyMatch(e -> executionEnvironment(release).equals(e.get("name")));
        String defaultPath = given.stream()
                .filter(e -> Boolean.TRUE.equals(e.get("default")))
                .map(e -> String.valueOf(e.get("path")))
                .findFirst()
                .orElse(null);
        return bare.stream()
                .filter(j -> releaseGiven ? j.major() == release : j.path().equals(defaultPath))
                .toList();
    }

    /**
     * The Java release the build file in {@code root} states, or 0 when it states none or there is none. A pom's
     * compiler plugin {@code <release>} comes before {@code maven.compiler.release}, both before a {@code source},
     * and Spring Boot's {@code java.version} counts (else its parent's default); a {@code ${property}} is read from
     * the pom's own properties, then its parent's; a profile's are left out, as profiles may not be active; a
     * reactor's first module that states one answers for it; a module inherits its parents' (pom chain). A Gradle
     * build's toolchain first, then {@code release} or {@code sourceCompatibility}, a version catalog's value, a
     * convention plugin's, else an included project's. Comments are left out (ninth review CP2, tenth review CO3,
     * eleventh review CM5).
     */
    static int statedRelease(java.nio.file.@Nullable Path root) {
        return statedRelease(root, 0);
    }

    private static int statedRelease(java.nio.file.@Nullable Path root, int depth) {
        if (root == null || depth > 2) {
            return 0;
        }
        String pom = buildFile(root.resolve("pom.xml"));
        if (pom != null) {
            String xml = pomCode(pom);
            java.util.Map<String, String> properties = pomProperties(xml);
            List<String> chain = new java.util.ArrayList<>(List.of(xml)); // this pom, then its parents (CM5)
            parentProperties(root, xml, properties, 0, chain);
            List<String> plugins =
                    chain.stream().map(x -> compilerPlugin(x, false)).toList();
            List<String> managed =
                    chain.stream().map(x -> compilerPlugin(x, true)).toList();
            for (List<String> blocks : List.of(plugins, managed)) {
                int release = firstRelease(blocks, "<release>\\s*([^<]+?)\\s*</release>", properties);
                if (release > 0) {
                    return release;
                }
            }
            for (String property : List.of("maven.compiler.release", "java.version")) {
                int release = property(properties, property, chain);
                if (release > 0) {
                    return release;
                }
            }
            for (List<String> blocks : List.of(plugins, managed)) {
                int release = firstRelease(blocks, "<source>\\s*([^<]+?)\\s*</source>", properties);
                if (release > 0) {
                    return release;
                }
            }
            int source = property(properties, "maven.compiler.source", chain);
            if (source > 0) {
                return source;
            }
            List<String> bare = chain.stream() // a release or source no plugin is named for: the last resort
                    .map(x -> x.replaceAll("(?s)<executions>.*?</executions>", ""))
                    .toList();
            for (String pattern : List.of("<release>\\s*([^<]+?)\\s*</release>", "<source>\\s*([^<]+?)\\s*</source>")) {
                int release = firstRelease(bare, pattern, properties);
                if (release > 0) {
                    return release;
                }
            }
            java.util.regex.Matcher module = java.util.regex.Pattern.compile("<module>\\s*([^<]+?)\\s*</module>")
                    .matcher(xml);
            while (module.find()) {
                int release = statedRelease(root.resolve(module.group(1)), depth + 1);
                if (release > 0) {
                    return release;
                }
            }
            java.util.regex.Matcher parent = java.util.regex.Pattern.compile("(?s)<parent>(.*?)</parent>")
                    .matcher(xml);
            if (parent.find() && parent.group(1).matches("(?s).*<artifactId>\\s*spring-boot-starter-parent\\s*<.*")) {
                java.util.regex.Matcher version = java.util.regex.Pattern.compile("<version>\\s*(\\d+)\\.")
                        .matcher(parent.group(1));
                if (version.find()) { // Spring Boot's own java.version: 17 since 3.0, 1.8 before
                    return Integer.parseInt(version.group(1)) >= 3 ? 17 : 8;
                }
            }
            return 0;
        }
        for (String name : List.of("build.gradle.kts", "build.gradle")) {
            String gradle = buildFile(root.resolve(name));
            int release = gradle == null ? 0 : gradleRelease(gradle, root);
            if (release > 0) {
                return release;
            }
        }
        int convention = conventionRelease(root);
        if (convention > 0) {
            return convention;
        }
        for (String name : List.of("settings.gradle.kts", "settings.gradle")) {
            String settings = buildFile(root.resolve(name));
            if (settings == null) {
                continue;
            }
            for (String project : includedProjects(gradleCode(settings))) {
                int release = statedRelease(root.resolve(project.replace(':', '/')), depth + 1);
                if (release > 0) {
                    return release;
                }
            }
        }
        return 0;
    }

    /**
     * The maven-compiler-plugin's configuration in a pom: under {@code <plugins>} ({@code managed} false) or under
     * {@code <pluginManagement>} (true), its {@code <executions>} left out (an MR-jar's per-execution release is not
     * the project's, eleventh review DM8); empty when there is none.
     */
    private static String compilerPlugin(String xml, boolean managed) {
        java.util.regex.Matcher pm = java.util.regex.Pattern.compile("(?s)<pluginManagement>(.*?)</pluginManagement>")
                .matcher(xml);
        String where = managed ? (pm.find() ? pm.group(1) : "") : pm.replaceAll("");
        java.util.regex.Matcher plugin = java.util.regex.Pattern.compile(
                        "(?s)<artifactId>\\s*maven-compiler-plugin\\s*</artifactId>(.*?)</plugin>")
                .matcher(where);
        return plugin.find() ? plugin.group(1).replaceAll("(?s)<executions>.*?</executions>", "") : "";
    }

    /** The first release {@code pattern} finds in {@code blocks}, in order, its {@code ${…}} resolved; else 0. */
    private static int firstRelease(List<String> blocks, String pattern, java.util.Map<String, String> properties) {
        for (String block : blocks) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(block);
            while (m.find()) {
                int release = javaRelease(resolve(m.group(1), properties));
                if (release > 0) {
                    return release;
                }
            }
        }
        return 0;
    }

    /** The release in property {@code name}, from the poms' properties, else written anywhere in {@code chain}. */
    private static int property(java.util.Map<String, String> properties, String name, List<String> chain) {
        String value = properties.get(name);
        if (value == null) {
            value = chain.stream()
                    .map(x -> java.util.regex.Pattern.compile(
                                    "<" + java.util.regex.Pattern.quote(name) + ">\\s*([^<]+?)\\s*<")
                            .matcher(x))
                    .filter(java.util.regex.Matcher::find)
                    .map(m -> m.group(1))
                    .findFirst()
                    .orElse(null);
        }
        return value == null ? 0 : javaRelease(resolve(value, properties));
    }

    /** A Gradle build file without its comments. */
    private static String gradleCode(String gradle) {
        return gradle.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    /**
     * The release a Gradle build states: a toolchain first (it decides the JDK), then {@code options.release} or
     * {@code release.set}, then a source or target compatibility; a version catalog's value ({@code
     * libs.versions.jdk}) is read from {@code gradle/libs.versions.toml}, and a {@code
     * providers.gradleProperty("javaVersion")} from {@code gradle.properties} (twelfth review CM5), as is a bare
     * property a toolchain names ({@code JavaLanguageVersion.of(javaVersion)}, thirteenth review CM5); a variable named
     * {@code release} is none (eleventh review CM5).
     */
    private static int gradleRelease(String gradle, java.nio.file.Path root) {
        String code = gradleCode(gradle);
        String stated = "(\\d+|libs\\.versions\\.[\\w.]+|providers\\.gradleProperty\\(\\s*['\"][\\w.-]+['\"]\\s*\\))";
        String toolchain = stated.replaceFirst("\\)$", "|(?:project\\\\.)?[A-Za-z_]\\\\w*)");
        for (String pattern : List.of(
                "jvmToolchain\\s*\\(\\s*" + toolchain,
                "JavaLanguageVersion\\.of\\(\\s*" + toolchain,
                "(?<![\\w$])(?<!def\\s)(?<!val\\s)(?<!var\\s)release(?:\\.set)?\\s*[(=]\\s*" + stated,
                "(?:source|target)Compatibility\\s*=\\s*(?:JavaVersion\\.VERSION_(?:1_)?(\\d+)|['\"]?(?:1\\.)?(\\d+))")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(code);
            if (m.find()) {
                for (int g = 1; g <= m.groupCount(); g++) {
                    String value = m.group(g);
                    if (value != null) {
                        int release = value.startsWith("libs.")
                                ? catalogVersion(root, value)
                                : value.startsWith("providers.")
                                        ? gradleProperty(root, value.replaceAll("(?s).*['\"]([\\w.-]+)['\"].*", "$1"))
                                        : Character.isDigit(value.charAt(0))
                                                ? Integer.parseInt(value)
                                                : gradleProperty(root, value.replaceFirst("^project\\.", ""));
                        if (release > 0) {
                            return release;
                        }
                    }
                }
            }
        }
        return 0;
    }

    /** A release in {@code gradle.properties}'s {@code name} ({@code javaVersion=21}), up to the build's root, or 0. */
    private static int gradleProperty(java.nio.file.Path root, String name) {
        for (java.nio.file.Path dir = root; dir != null; dir = dir.getParent()) {
            String properties = buildFile(dir.resolve("gradle.properties"));
            if (properties != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                                "(?m)^\\s*" + java.util.regex.Pattern.quote(name) + "\\s*[=:]\\s*(?:1\\.)?(\\d+)")
                        .matcher(properties);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
            if (java.nio.file.Files.exists(dir.resolve("settings.gradle"))
                    || java.nio.file.Files.exists(dir.resolve("settings.gradle.kts"))) {
                return 0; // the build's root
            }
        }
        return 0;
    }

    /** A version catalog's {@code libs.versions.<name>} from {@code gradle/libs.versions.toml}, or 0. */
    private static int catalogVersion(java.nio.file.Path root, String accessor) {
        String name = accessor.replaceFirst("^libs\\.versions\\.", "").replaceFirst("\\.get$", "");
        for (java.nio.file.Path dir = root; dir != null; dir = dir.getParent()) {
            String toml = buildFile(dir.resolve("gradle/libs.versions.toml"));
            if (toml != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                                "(?m)^\\s*([\\w.-]+)\\s*=\\s*\"(?:1\\.)?(\\d+)[^\"]*\"")
                        .matcher(toml);
                while (m.find()) {
                    if (m.group(1).replaceAll("[-_.]", "").equals(name.replaceAll("[-_.]", ""))) {
                        return Integer.parseInt(m.group(2));
                    }
                }
                return 0;
            }
            if (java.nio.file.Files.exists(dir.resolve("settings.gradle"))
                    || java.nio.file.Files.exists(dir.resolve("settings.gradle.kts"))) {
                return 0; // the build's root: no catalog above it counts
            }
        }
        return 0;
    }

    /**
     * A release a convention plugin under {@code buildSrc} or {@code build-logic} states, or 0 (DM8); not the plugin
     * build's own build or settings file, whose toolchain compiles the plugins (twelfth review CM5).
     */
    private static int conventionRelease(java.nio.file.Path root) {
        for (String folder : List.of("buildSrc", "build-logic")) {
            java.nio.file.Path dir = root.resolve(folder);
            if (!java.nio.file.Files.isDirectory(dir)) {
                continue;
            }
            try (var files = java.nio.file.Files.walk(dir, 8)) {
                for (java.nio.file.Path f : files.filter(f -> {
                            String n = f.getFileName().toString();
                            return (n.endsWith(".gradle.kts") || n.endsWith(".gradle"))
                                    && !n.matches("(build|settings)\\.gradle(\\.kts)?");
                        })
                        .limit(50)
                        .toList()) {
                    String text = buildFile(f);
                    int release = text == null ? 0 : gradleRelease(text, root);
                    if (release > 0) {
                        return release;
                    }
                }
            } catch (java.io.IOException | RuntimeException e) {
                // unreadable: no convention release
            }
        }
        return 0;
    }

    /**
     * Every project a settings file includes, as written ({@code include("app", "lib")}, {@code include 'a'}), an
     * {@code include(} list over several lines too (twelfth review CM5), and a Groovy {@code include 'a',} whose list
     * goes on after a line's comma (thirteenth review CM5).
     */
    private static List<String> includedProjects(String settings) {
        List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher include = java.util.regex.Pattern.compile(
                        "\\binclude\\b\\s*(?:\\(([^)]*)|((?:[^\n]*,[ \\t]*\\r?\\n)*[^\n]*))")
                .matcher(settings);
        while (include.find()) {
            java.util.regex.Matcher name = java.util.regex.Pattern.compile("['\"]:?([\\w.-]+(?::[\\w.-]+)*)['\"]")
                    .matcher(include.group(1) != null ? include.group(1) : include.group(2));
            while (name.find()) {
                out.add(name.group(1));
            }
        }
        return out;
    }

    /** A pom without its comments and profiles: a profile may not be active. */
    private static String pomCode(String pom) {
        return pom.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)<profiles>.*?</profiles>", "");
    }

    /** A pom's own properties, the first of each name. */
    private static java.util.Map<String, String> pomProperties(String xml) {
        java.util.Map<String, String> properties = new java.util.HashMap<>();
        java.util.regex.Matcher block = java.util.regex.Pattern.compile("(?s)<properties>(.*?)</properties>")
                .matcher(xml);
        while (block.find()) {
            java.util.regex.Matcher p = java.util.regex.Pattern.compile("<([\\w.-]+)>\\s*([^<]*?)\\s*</\\1>")
                    .matcher(block.group(1));
            while (p.find()) {
                properties.putIfAbsent(p.group(1), p.group(2));
            }
        }
        return properties;
    }

    /**
     * Adds the properties of the parent pom at {@code <relativePath>} (by default ../pom.xml) the child lacks, and each
     * parent's text to {@code chain}: a module inherits its parent's compiler configuration too (eleventh review CM5).
     */
    private static void parentProperties(
            java.nio.file.Path root,
            String xml,
            java.util.Map<String, String> properties,
            int depth,
            List<String> chain) {
        java.util.regex.Matcher parent =
                java.util.regex.Pattern.compile("(?s)<parent>(.*?)</parent>").matcher(xml);
        if (depth > 3 || !parent.find()) {
            return;
        }
        java.util.regex.Matcher relative = java.util.regex.Pattern.compile(
                        "<relativePath>\\s*([^<]*?)\\s*</relativePath>")
                .matcher(parent.group(1));
        String path = relative.find() ? relative.group(1) : "../pom.xml";
        if (path.isEmpty()) {
            return; // <relativePath/>: from a repository, not on disk
        }
        java.nio.file.Path file = root.resolve(path).normalize();
        if (java.nio.file.Files.isDirectory(file)) {
            file = file.resolve("pom.xml");
        }
        String pom = buildFile(file);
        if (pom == null || file.getParent() == null) {
            return;
        }
        String parentXml = pomCode(pom);
        if (!declares(parentXml, parent.group(1))) {
            return; // an aggregator above, not the parent (spring-boot-starter-parent): not read (twelfth review CL3)
        }
        pomProperties(parentXml).forEach(properties::putIfAbsent);
        chain.add(parentXml);
        parentProperties(file.getParent(), parentXml, properties, depth + 1, chain);
    }

    /**
     * Whether {@code pom} may be the project a {@code <parent>} block names: its own artifactId, and its groupId (or
     * its parent's), where both are written.
     */
    private static boolean declares(String pom, String parentBlock) {
        String own = pom.replaceAll(
                "(?s)<(parent|build|dependencies|dependencyManagement|reporting)>.*?</\\1>", ""); // its own tags
        String wantedArtifact = tag(parentBlock, "artifactId");
        String artifact = tag(own, "artifactId");
        if (wantedArtifact != null && artifact != null && !wantedArtifact.equals(artifact)) {
            return false;
        }
        String wantedGroup = tag(parentBlock, "groupId");
        String group = tag(own, "groupId");
        if (group == null) {
            java.util.regex.Matcher inherited = java.util.regex.Pattern.compile("(?s)<parent>(.*?)</parent>")
                    .matcher(pom);
            group = inherited.find() ? tag(inherited.group(1), "groupId") : null;
        }
        return wantedGroup == null || group == null || wantedGroup.equals(group);
    }

    /** The first {@code <name>}'s text in {@code xml}, or null. */
    private static @Nullable String tag(String xml, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">")
                .matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /** A build file's text, or null when there is none or it is too large or unreadable. */
    private static @Nullable String buildFile(java.nio.file.Path file) {
        try {
            return java.nio.file.Files.isRegularFile(file) && java.nio.file.Files.size(file) <= 1_000_000
                    ? java.nio.file.Files.readString(file)
                    : null;
        } catch (java.io.IOException | RuntimeException e) {
            return null; // unreadable: as if it stated none
        }
    }

    /** {@code value} with {@code ${name}} replaced from {@code properties}, a few levels deep. */
    private static String resolve(String value, java.util.Map<String, String> properties) {
        String out = value;
        for (int i = 0; i < 5 && out.contains("${"); i++) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(
                        sb, java.util.regex.Matcher.quoteReplacement(properties.getOrDefault(m.group(1), "")));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    /** {@code 17} of "17", "1.8" → 8; 0 when it is no release. */
    private static int javaRelease(String value) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^\\s*(?:1\\.)?(\\d+)\\s*$").matcher(value);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
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

    /** jdtls's launcher's own floor, which is what is known when its bundles cannot be read. */
    static final int SERVER_MIN_MAJOR = 21;

    /**
     * The oldest Java the jdtls whose launcher is {@code launcher} runs on: the newest
     * {@code Bundle-RequiredExecutionEnvironment} of its core bundles, never below {@link #SERVER_MIN_MAJOR}. jdtls
     * 1.63's {@code org.eclipse.jdt.core} and {@code org.eclipse.core.resources} need Java 25 while its launcher
     * still checks for 21, so on a JDK 21 it crash-looped (sixth review DS1); read from the install, the next pin
     * follows by itself.
     */
    public static int serverMinMajor(@Nullable String launcher) {
        java.nio.file.Path exe = launcher == null || launcher.isBlank() ? null : onPath(launcher);
        java.nio.file.Path plugins;
        try {
            java.nio.file.Path bin = exe == null ? null : exe.toRealPath().getParent();
            plugins = bin == null || bin.getParent() == null
                    ? null
                    : bin.getParent().resolve("plugins");
        } catch (java.io.IOException | RuntimeException e) {
            plugins = null;
        }
        if (plugins == null || !java.nio.file.Files.isDirectory(plugins)) {
            return SERVER_MIN_MAJOR;
        }
        // read at every start, not kept: an Update replaces the folder's bundles in place, and a kept floor started
        // the new jdtls on the old Java (seventh review CR1); three manifests cost next to nothing
        return Math.max(SERVER_MIN_MAJOR, bundleFloor(plugins));
    }

    /** The newest {@code JavaSE-N} the core bundles in {@code plugins} require; 0 when none says. */
    static int bundleFloor(java.nio.file.Path plugins) {
        int floor = 0;
        try (var jars = java.nio.file.Files.list(plugins)) {
            for (java.nio.file.Path jar : jars.toList()) {
                String name = jar.getFileName().toString();
                if (!name.endsWith(".jar")
                        || !(name.startsWith("org.eclipse.jdt.core_")
                                || name.startsWith("org.eclipse.core.resources_")
                                || name.startsWith("org.eclipse.jdt.ls.core_"))) {
                    continue;
                }
                try (var file = new java.util.jar.JarFile(jar.toFile())) {
                    var manifest = file.getManifest();
                    String ee = manifest == null
                            ? null
                            : manifest.getMainAttributes().getValue("Bundle-RequiredExecutionEnvironment");
                    var m = ee == null ? null : EXECUTION_ENVIRONMENT.matcher(ee);
                    while (m != null && m.find()) {
                        floor = Math.max(floor, Integer.parseInt(m.group(1)));
                    }
                } catch (java.io.IOException | RuntimeException e) {
                    // an unreadable bundle says nothing
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            return floor;
        }
        return floor;
    }

    private static final java.util.regex.Pattern EXECUTION_ENVIRONMENT =
            java.util.regex.Pattern.compile("JavaSE-(?:1\\.)?(\\d+)");

    /** {@code command} as a path: itself when it names a file, else the first match on the PATH; null when none. */
    private static java.nio.file.@Nullable Path onPath(String command) {
        java.nio.file.Path p = java.nio.file.Path.of(command);
        if (p.isAbsolute() || p.getNameCount() > 1) {
            return p;
        }
        String path = System.getenv("PATH");
        for (String dir : path == null ? new String[0] : path.split(java.io.File.pathSeparator)) {
            java.nio.file.Path exe = java.nio.file.Path.of(dir, command);
            if (java.nio.file.Files.isExecutable(exe)) {
                return exe;
            }
        }
        return null;
    }

    /**
     * What jdtls's launcher should be told to run on, given the major version of the Java it would pick itself
     * ({@code launcherMajor}: {@code JAVA_HOME}'s, else the one on the PATH; 0 when unknown). Null executable
     * when the launcher's own choice will do — it is new enough, or unknown and left to the launcher. Otherwise
     * the preferred JDK when it is new enough, else the newest discovered one that is, and {@code missing} when
     * none is (Java review C5: with {@code JAVA_HOME} on 17 jdtls could not start, and Editora only said it
     * "keeps crashing").
     */
    public static ServerJava serverJava(int launcherMajor, List<Jdk> jdks, @Nullable String preferredHome) {
        return serverJava(launcherMajor, jdks, preferredHome, SERVER_MIN_MAJOR);
    }

    /** As {@link #serverJava(int, List, String)} for a jdtls that needs Java {@code minMajor} ({@link #serverMinMajor}). */
    public static ServerJava serverJava(
            int launcherMajor, List<Jdk> jdks, @Nullable String preferredHome, int minMajor) {
        if (launcherMajor <= 0 || launcherMajor >= minMajor) {
            return new ServerJava(null, false);
        }
        String preferred = realPath(preferredHome);
        Jdk pick = null;
        for (Jdk j : jdks == null ? List.<Jdk>of() : jdks) {
            if (j == null || j.major() < minMajor || j.path() == null) {
                continue;
            }
            if (preferred != null && (j.path().equals(preferred) || preferred.equals(realPath(j.path())))) {
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
        java.nio.file.Path exe = launcherJava();
        return exe == null ? 0 : javaMajor(exe);
    }

    /**
     * The {@code java} jdtls's launcher picks by itself, links resolved: {@code JAVA_HOME/bin/java} when it exists,
     * else the one on the {@code PATH}; null when there is none. A {@code JAVA_HOME} that does not exist is passed
     * over, as the launcher runs the {@code PATH}'s then (sixth review CV5).
     */
    public static java.nio.file.@Nullable Path launcherJava() {
        boolean windows = java.io.File.separatorChar == '\\';
        String javaHome = System.getenv("JAVA_HOME");
        java.nio.file.Path exe = null;
        if (javaHome != null && !javaHome.isBlank()) {
            java.nio.file.Path inHome = java.nio.file.Path.of(javaHome, "bin", windows ? "java.exe" : "java");
            exe = java.nio.file.Files.isRegularFile(inHome) ? inHome : null;
        }
        if (exe == null) {
            exe = onPath(windows ? "java.exe" : "java");
        }
        try {
            return exe == null ? null : exe.toRealPath();
        } catch (java.io.IOException | RuntimeException e) {
            return exe;
        }
    }

    /** The major version of the JDK whose {@code bin/java} is {@code exe}, from its {@code release} file; 0 if unknown. */
    public static int javaMajor(java.nio.file.Path exe) {
        try {
            java.nio.file.Path bin = exe.toRealPath().getParent();
            java.nio.file.Path home = bin == null ? null : bin.getParent();
            return home == null ? 0 : majorFromRelease(java.nio.file.Files.readString(home.resolve("release")));
        } catch (java.io.IOException | RuntimeException e) {
            return 0;
        }
    }
}
