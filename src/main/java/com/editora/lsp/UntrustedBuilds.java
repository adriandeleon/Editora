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
 * project's {@code Gemfile} (and requires the workspace's own add-ons), csharp-ls evaluates MSBuild projects,
 * clojure-lsp asks Leiningen, Boot, shadow-cljs or the Clojure CLI for a classpath, Dart's analysis server runs the
 * analyzer plugins an {@code analysis_options.yaml} names, ElixirLS compiles a Mix project ({@code mix.exs} is Elixir
 * code, and so are its dependencies' macros), the Groovy server resolves a Gradle or Maven build, astro-ls loads the
 * project's framework packages, terraform-ls starts the providers in {@code .terraform}, and Phpactor loads the
 * Composer autoloader. Each of those is code from the folder, run with the user's privileges, so it waits for the
 * same trust a Run with {@code ./mvnw} does (see {@code config.TrustStore}). Which files make each one run code was
 * audited against the servers themselves (HN dry run, 2026-11-27): see {@link #SPECS}.
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

    /**
     * What makes a gated server run the folder's code: entry {@code names} (exact, or {@code *.ext} by extension)
     * found at the root or up to {@code depth} folders below it, relative {@code paths} that exist at the root,
     * every immediate child folder searched ({@code everyChild}, dot folders and build output included: the server
     * looks there itself), the entries named {@code upward} in the root's parent folders, or {@code always}.
     */
    private record Spec(
            List<String> names,
            List<String> paths,
            int depth,
            boolean everyChild,
            List<String> upward,
            boolean always) {

        static Spec of(String... names) {
            return new Spec(List.of(names), List.of(), 0, false, List.of(), false);
        }

        Spec depth(int d) {
            return new Spec(names, paths, d, everyChild, upward, always);
        }

        Spec withPaths(String... p) {
            return new Spec(names, List.of(p), depth, everyChild, upward, always);
        }

        Spec searchingEveryChild() {
            return new Spec(names, paths, depth, true, upward, always);
        }

        Spec withUpward(String... u) {
            return new Spec(names, paths, depth, everyChild, List.of(u), always);
        }
    }

    private static final List<String> JVM_BUILDS =
            List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    /** Each gated server, by id, and what makes it run the folder's code. */
    private static final Map<String, Spec> SPECS = Map.ofEntries(
            Map.entry(LspServerRegistry.JAVA_SERVER_ID, new Spec(JVM_BUILDS, List.of(), 0, false, List.of(), false)),
            Map.entry("kotlin", new Spec(JVM_BUILDS, List.of(), 0, false, List.of(), false)),
            // gls resolves a Gradle or Maven build's dependencies when it starts (the Gradle Tooling API runs the
            // build scripts, a wrapper fetches and runs its distribution) and connects to a BSP server.
            Map.entry(
                    "groovy",
                    Spec.of(
                                    "pom.xml",
                                    "build.gradle",
                                    "build.gradle.kts",
                                    "settings.gradle",
                                    "settings.gradle.kts",
                                    "gradlew",
                                    "mvnw",
                                    ".bsp")
                            .depth(1)
                            .withUpward("settings.gradle", "settings.gradle.kts")),
            // Metals also launches a .bsp/*.json server's argv and reads .bloop/*.json compiler options.
            Map.entry(
                    "scala",
                    Spec.of(
                            "build.sbt",
                            "build.sc",
                            "build.mill",
                            "build.mill.yaml",
                            "project.scala",
                            ".bsp",
                            ".bloop")),
            // rust-analyzer looks for Cargo.toml in the root, in every child folder and in the parent folders, and a
            // rust-project.json names a sysroot and proc-macro libraries.
            Map.entry(
                    "rust",
                    Spec.of("Cargo.toml", "rust-project.json", ".rust-project.json")
                            .depth(1)
                            .searchingEveryChild()
                            .withUpward("Cargo.toml", "rust-project.json", ".rust-project.json")),
            // Ruby LSP requires every ruby_lsp/**/addon.rb under the workspace, uses a .ruby-lsp bundle the folder
            // ships, and Bundler finds a Gemfile in the parent folders: no folder is safe to start it on untrusted.
            Map.entry("ruby", new Spec(List.of(), List.of(), 0, false, List.of(), true)),
            // MSBuild imports Directory.Build.props and .targets from the parent folders too.
            Map.entry(
                    "csharp",
                    Spec.of(
                                    "*.sln",
                                    "*.slnx",
                                    "*.csproj",
                                    "*.fsproj",
                                    "*.vbproj",
                                    "Directory.Build.props",
                                    "Directory.Build.targets")
                            .depth(2)
                            .withUpward("Directory.Build.props", "Directory.Build.targets")),
            // clojure-lsp asks the build tool for a classpath (deps.edn and bb.edn are read as data), and
            // .lsp/config.edn can name the command it runs instead.
            Map.entry(
                    "clojure",
                    Spec.of("project.clj", "build.boot", "shadow-cljs.edn").withPaths(".lsp/config.edn")),
            Map.entry("dart", Spec.of("analysis_options.yaml").withUpward("analysis_options.yaml")),
            Map.entry("elixir", Spec.of("mix.exs")),
            // astro-ls loads the project's own @astrojs/svelte, @astrojs/vue and Prettier from node_modules, which
            // Node finds in the parent folders too.
            Map.entry("astro", Spec.of("node_modules").withUpward("node_modules")),
            // terraform-ls asks terraform for the providers' schemas, which starts the provider programs installed
            // in a module's .terraform folder.
            Map.entry("terraform", Spec.of(".terraform").depth(3)),
            // Phpactor loads the project's Composer autoloader, and .phpactor.json can enable tools from vendor/bin.
            Map.entry("php", Spec.of(".phpactor.json", ".phpactor.yml").withPaths("vendor/autoload.php")));

    /** Folders not searched below the first level: the build tools' own output, and dependencies. */
    private static final Set<String> SKIPPED = Set.of("node_modules", "target", "build", "bin", "obj", "out");

    /**
     * The most folders one search lists, so a huge tree never stalls a server's start. A search that runs out answers
     * that there is a build: the server then waits for trust rather than start on a folder not fully looked at.
     */
    private static final int MAX_FOLDERS = 400;

    /** Whether {@code serverId} is one whose project loading runs the folder's code. */
    public static boolean gated(@Nullable String serverId) {
        return serverId != null && SPECS.containsKey(serverId);
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

    /** Whether {@code root} (or a folder near it, as {@code serverId}'s spec says) holds what makes it run code. */
    public static boolean hasBuild(String serverId, @Nullable Path root) {
        Spec spec = SPECS.get(serverId);
        if (spec == null || root == null) {
            return false;
        }
        if (spec.always()) {
            return true;
        }
        int[] budget = {MAX_FOLDERS};
        if (atRoot(root, spec) || search(root, spec, spec.depth(), true, budget)) {
            return true;
        }
        for (Path up = spec.upward().isEmpty()
                        ? null
                        : root.toAbsolutePath().normalize().getParent();
                up != null;
                up = up.getParent()) {
            for (String name : spec.upward()) {
                if (Files.exists(up.resolve(name))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether one of the spec's relative paths exists under {@code dir}. */
    private static boolean atRoot(Path dir, Spec spec) {
        for (String rel : spec.paths()) {
            if (Files.exists(dir.resolve(rel))) {
                return true;
            }
        }
        return false;
    }

    private static boolean search(Path dir, Spec spec, int depth, boolean top, int[] budget) {
        if (budget[0]-- <= 0) {
            return true; // not fully looked at: taken for a build (see MAX_FOLDERS)
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            List<Path> below = new java.util.ArrayList<>();
            for (Path entry : entries) {
                Path fileName = entry.getFileName();
                String name = fileName == null ? "" : fileName.toString();
                if (matches(name, spec.names())) {
                    return true;
                }
                boolean skipped = name.startsWith(".") || SKIPPED.contains(name.toLowerCase(Locale.ROOT));
                if (depth > 0 && (!skipped || (top && spec.everyChild())) && Files.isDirectory(entry)) {
                    below.add(entry);
                }
            }
            for (Path sub : below) {
                if (search(sub, spec, depth - 1, false, budget)) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return false; // a folder that cannot be listed loads nothing from it
        }
        return false;
    }

    /** An entry by its exact name, or by its extension for a {@code *.ext} pattern. */
    private static boolean matches(String name, List<String> names) {
        for (String n : names) {
            if (n.startsWith("*") ? name.length() > n.length() - 1 && name.endsWith(n.substring(1)) : name.equals(n)) {
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
