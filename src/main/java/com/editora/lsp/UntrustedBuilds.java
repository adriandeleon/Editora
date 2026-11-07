package com.editora.lsp;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The language servers that run a project's own code to load it, and what they may do in a folder that is not
 * trusted. Loading a project is not reading it: jdtls imports a Gradle build by running its build scripts (and a
 * Maven one by loading its build extensions), Kotlin's server imports Gradle the same way, Metals runs sbt or Mill or
 * compiles Scala macros, rust-analyzer builds {@code build.rs} scripts and proc macros, Ruby LSP runs Bundler on the
 * project's {@code Gemfile}, csharp-ls evaluates MSBuild projects, and clojure-lsp asks Leiningen, Boot or
 * shadow-cljs for a classpath. Each of those is code from the folder, run with the user's privileges, so it waits for
 * the same trust a Run with {@code ./mvnw} does (see {@code config.TrustStore}).
 *
 * <ul>
 *   <li><b>Java</b> starts anyway, with its Maven and Gradle imports off ({@link #withImportsOff}): a file still gets
 *       syntax checks, the JDK's types and navigation within itself, and trusting the folder restarts the server with
 *       the build.
 *   <li>The <b>others</b> are not started on a folder with their build files ({@link #withholds}); a loose file of
 *       theirs, with no build to load, starts as before.
 * </ul>
 *
 * Pure apart from {@link #hasBuild}, which lists a few folders and is called off the FX thread.
 */
public final class UntrustedBuilds {

    private UntrustedBuilds() {}

    /** The build files that make each gated server run the folder's code, by server id. */
    private static final Map<String, List<String>> BUILD_FILES = Map.of(
            LspServerRegistry.JAVA_SERVER_ID,
            List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"),
            "kotlin",
            List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"),
            "scala",
            List.of("build.sbt", "build.sc", "build.mill", "build.mill.yaml", "project.scala"),
            "rust",
            List.of("Cargo.toml"),
            "ruby",
            List.of("Gemfile", "gems.rb"),
            "csharp",
            List.of(
                    ".sln",
                    ".slnx",
                    ".csproj",
                    ".fsproj",
                    ".vbproj",
                    "Directory.Build.props",
                    "Directory.Build.targets"),
            "clojure",
            List.of("project.clj", "build.boot", "shadow-cljs.edn"));

    /**
     * How deep below the server's root each one looks for a build: rust-analyzer finds crates one folder down, and a
     * C# solution's projects are usually two ({@code src/App/App.csproj}).
     */
    private static final Map<String, Integer> DEPTH = Map.of("rust", 1, "csharp", 2);

    /** Folders never searched for a build: the build tools' own output, and dependencies. */
    private static final Set<String> SKIPPED = Set.of("node_modules", "target", "build", "bin", "obj", "out");

    /** The most folders one search lists, so a huge tree never stalls a server's start. */
    private static final int MAX_FOLDERS = 400;

    /** Whether {@code serverId} is one whose project loading runs the folder's code. */
    public static boolean gated(@Nullable String serverId) {
        return serverId != null && BUILD_FILES.containsKey(serverId);
    }

    /** Whether {@code serverId} is started without its build in an untrusted folder (Java), not withheld. */
    public static boolean startsWithoutBuild(@Nullable String serverId) {
        return LspServerRegistry.JAVA_SERVER_ID.equals(serverId);
    }

    /** Whether jdtls on a folder of this trust must be told not to import Maven or Gradle builds. */
    public static boolean importsOff(@Nullable String serverId, boolean folderTrusted) {
        return !folderTrusted && startsWithoutBuild(serverId);
    }

    /**
     * Whether {@code serverId} must not be started on {@code root} at all: a gated server other than Java, on a folder
     * that is not trusted and holds its build files. Reads the disk: call it where a server's start already does.
     */
    public static boolean withholds(String serverId, Path root, boolean folderTrusted) {
        return !folderTrusted && gated(serverId) && !startsWithoutBuild(serverId) && hasBuild(serverId, root);
    }

    /** Whether {@code root} (or a folder close below it, for Rust and C#) holds {@code serverId}'s build files. */
    public static boolean hasBuild(String serverId, @Nullable Path root) {
        List<String> names = BUILD_FILES.get(serverId);
        if (names == null || root == null) {
            return false;
        }
        int[] budget = {MAX_FOLDERS};
        return search(root, names, DEPTH.getOrDefault(serverId, 0), budget);
    }

    private static boolean search(Path dir, List<String> names, int depth, int[] budget) {
        if (budget[0]-- <= 0) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            List<Path> below = new java.util.ArrayList<>();
            for (Path entry : entries) {
                Path fileName = entry.getFileName();
                String name = fileName == null ? "" : fileName.toString();
                if (matches(name, names)) {
                    return true;
                }
                if (depth > 0
                        && !name.startsWith(".")
                        && !SKIPPED.contains(name.toLowerCase(Locale.ROOT))
                        && Files.isDirectory(entry)) {
                    below.add(entry);
                }
            }
            for (Path sub : below) {
                if (search(sub, names, depth - 1, budget)) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return false; // a folder that cannot be listed loads nothing from it
        }
        return false;
    }

    /** A build file by its exact name, or (for an entry starting with a dot) by its extension. */
    private static boolean matches(String name, List<String> names) {
        for (String n : names) {
            if (n.startsWith(".") ? name.length() > n.length() && name.endsWith(n) : name.equals(n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code java} (jdtls's {@code settings.java}) with the Maven and Gradle imports off: jdtls then serves the files
     * without loading the build, and never asks to update a build configuration. Applied to the settings a server
     * starts with and to every later push of them, so a push cannot turn an import on. Pure.
     */
    public static Map<String, Object> withImportsOff(Map<String, Object> java) {
        Map<String, Object> out = new HashMap<>(java);
        Map<String, Object> imports = copy(out.get("import"));
        Map<String, Object> gradle = copy(imports.get("gradle"));
        gradle.put("enabled", false);
        gradle.put("wrapper", Map.of("enabled", false));
        imports.put("gradle", Map.copyOf(gradle));
        Map<String, Object> maven = copy(imports.get("maven"));
        maven.put("enabled", false);
        imports.put("maven", Map.copyOf(maven));
        out.put("import", Map.copyOf(imports));
        Map<String, Object> configuration = copy(out.get("configuration"));
        configuration.put("updateBuildConfiguration", "disabled");
        out.put("configuration", Map.copyOf(configuration));
        return out;
    }

    /**
     * jdtls's {@code initializationOptions} with {@link #withImportsOff} applied to {@code settings.java}; anything
     * else is returned as it is. Pure.
     */
    public static @Nullable Object initOptionsWithImportsOff(@Nullable Object initOptions) {
        if (!(initOptions instanceof Map<?, ?> options) || !(options.get("settings") instanceof Map<?, ?> settings)) {
            return initOptions;
        }
        Map<String, Object> java = copy(settings.get("java"));
        Map<String, Object> newSettings = copy(settings);
        newSettings.put("java", Map.copyOf(withImportsOff(java)));
        Map<String, Object> out = copy(options);
        out.put("settings", Map.copyOf(newSettings));
        return out;
    }

    private static Map<String, Object> copy(@Nullable Object value) {
        Map<String, Object> out = new HashMap<>();
        if (value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(String.valueOf(k), v);
                }
            });
        }
        return out;
    }
}
