package com.editora.jstools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * Which of ESLint, Prettier and Biome a JavaScript or TypeScript file has: their configuration files, looked for from
 * the file's folder up to the project root, and the copies installed in the project's {@code node_modules}. Only a
 * project's own copy is ever used — never one on PATH or a global one — as VS Code's extensions and WebStorm do.
 *
 * <p>Pure apart from the {@link Lookup} the caller supplies, so a test decides what exists.
 */
public final class JsTools {

    private JsTools() {}

    /** The file name extensions this covers: JavaScript and TypeScript, with JSX. */
    public static final List<String> EXTENSIONS = List.of("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts");

    /** ESLint's flat configuration (ESLint 9, and 8.57 opt-in), in the order ESLint itself looks for them. */
    public static final List<String> FLAT_CONFIGS = List.of(
            "eslint.config.js",
            "eslint.config.mjs",
            "eslint.config.cjs",
            "eslint.config.ts",
            "eslint.config.mts",
            "eslint.config.cts");

    /** ESLint's older configuration files ({@code .eslintrc*}); {@code package.json}'s {@code eslintConfig} too. */
    public static final List<String> LEGACY_CONFIGS =
            List.of(".eslintrc.js", ".eslintrc.cjs", ".eslintrc.yaml", ".eslintrc.yml", ".eslintrc.json", ".eslintrc");

    /** Prettier's configuration files; {@code package.json}'s {@code prettier} key counts too. */
    public static final List<String> PRETTIER_CONFIGS = List.of(
            ".prettierrc",
            ".prettierrc.json",
            ".prettierrc.json5",
            ".prettierrc.yaml",
            ".prettierrc.yml",
            ".prettierrc.toml",
            ".prettierrc.js",
            ".prettierrc.cjs",
            ".prettierrc.mjs",
            ".prettierrc.ts",
            ".prettierrc.mts",
            ".prettierrc.cts",
            "prettier.config.js",
            "prettier.config.cjs",
            "prettier.config.mjs",
            "prettier.config.ts",
            "prettier.config.mts",
            "prettier.config.cts");

    /** Biome's configuration files. */
    public static final List<String> BIOME_CONFIGS = List.of("biome.json", "biome.jsonc");

    /** With no project root, how many folders (the file's own included) are searched. */
    static final int NO_PROJECT_LEVELS = 6;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The three tools. */
    public enum Kind {
        ESLINT("ESLint", "eslint", "eslint", List.of("eslint/bin/eslint.js")),
        PRETTIER("Prettier", "prettier", "prettier", List.of("prettier/bin/prettier.cjs", "prettier/bin-prettier.js")),
        BIOME("Biome", "biome", "@biomejs/biome", List.of("@biomejs/biome/bin/biome"));

        private final String displayName;
        private final String binName;
        private final String packageName;
        private final List<String> entries;

        Kind(String displayName, String binName, String packageName, List<String> entries) {
            this.displayName = displayName;
            this.binName = binName;
            this.packageName = packageName;
            this.entries = entries;
        }

        /** The npm package it comes in. */
        public String packageName() {
            return packageName;
        }

        /** The name shown to the user ("ESLint"). */
        public String displayName() {
            return displayName;
        }

        /** The command's name in {@code node_modules/.bin}. */
        public String binName() {
            return binName;
        }

        /** The package's own start script(s) under {@code node_modules}, run with Node.js; newest layout first. */
        List<String> entries() {
            return entries;
        }
    }

    /**
     * What the lookups look at.
     *
     * @param projectRoot the window's project folder; the search stops there (null: at most a few folders up)
     * @param home the user's home folder, never searched with no project (may be null)
     * @param windows whether {@code node_modules/.bin} shims are {@code .cmd} files (they are not used there)
     * @param isFile whether a path is an existing regular file
     * @param readFile a small file's text (a {@code package.json}), or empty when unreadable
     */
    public record Lookup(
            @Nullable Path projectRoot,
            @Nullable Path home,
            boolean windows,
            Predicate<Path> isFile,
            Function<Path, Optional<String>> readFile) {

        /** This machine's file system. Blocking: off the FX thread. */
        public static Lookup local(@Nullable Path projectRoot) {
            String home = System.getProperty("user.home");
            boolean windows = com.editora.Os.isWindows();
            return new Lookup(
                    projectRoot,
                    home == null ? null : Path.of(home),
                    windows,
                    Files::isRegularFile,
                    JsTools::readSmall);
        }
    }

    /** Whether {@code file} is a JavaScript or TypeScript file by its name. */
    public static boolean applies(@Nullable Path file) {
        Path name = file == null ? null : file.getFileName();
        if (name == null) {
            return false;
        }
        String s = name.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(s.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * The folders searched for {@code file}, nearest first: its own folder (above {@code node_modules} for a file
     * inside one), up to the project root when the file is in it; otherwise at most {@link #NO_PROJECT_LEVELS}
     * folders, never the home folder or above it.
     */
    static List<Path> folders(Path file, Lookup in) {
        Path abs = file.toAbsolutePath().normalize();
        Path start = com.editora.run.NodeRuntimes.outsideNodeModules(abs);
        Path dir = start.equals(abs) ? start.getParent() : start;
        Path project = in.projectRoot() == null
                ? null
                : in.projectRoot().toAbsolutePath().normalize();
        boolean inProject = project != null && dir != null && dir.startsWith(project);
        Path home = in.home() == null ? null : in.home().toAbsolutePath().normalize();
        List<Path> out = new ArrayList<>();
        while (dir != null) {
            if (!inProject && (out.size() >= NO_PROJECT_LEVELS || dir.equals(home))) {
                break;
            }
            out.add(dir);
            if (inProject && dir.equals(project)) {
                break;
            }
            dir = dir.getParent();
        }
        return out;
    }

    /** An ESLint configuration: the folder ESLint runs from, the file, and whether it is a flat one. */
    public record EslintConfig(Path dir, Path file, boolean flat) {}

    /** A tool installed in a project: how to start it, and the folder whose {@code node_modules} holds it. */
    public record Tool(Kind kind, Path script, boolean viaNode, Path root) {

        /** The argv prefix: {@code node} (its argv prefix) then the script, or the script by itself. */
        public List<String> command(List<String> node) {
            List<String> out = new ArrayList<>();
            if (viaNode) {
                out.addAll(node);
            }
            out.add(script.toString());
            return List.copyOf(out);
        }
    }

    /**
     * The version a tool's own {@code package.json} names ({@code 9.12.0}), read, never run: Doctor shows it without
     * starting the project's code. Empty when it cannot be read.
     */
    public static Optional<String> installedVersion(Tool tool, Lookup in) {
        Path pkg = tool.root()
                .resolve("node_modules")
                .resolve(tool.kind().packageName())
                .resolve("package.json");
        return packageJson(pkg, in).map(j -> j.path("version").asText("")).filter(v -> !v.isBlank());
    }

    /** The nearest ESLint configuration for {@code file}: a flat one before an older one in the same folder. */
    public static Optional<EslintConfig> eslintConfig(Path file, Lookup in) {
        for (Path dir : folders(file, in)) {
            for (String name : FLAT_CONFIGS) {
                if (in.isFile().test(dir.resolve(name))) {
                    return Optional.of(new EslintConfig(dir, dir.resolve(name), true));
                }
            }
            for (String name : LEGACY_CONFIGS) {
                if (in.isFile().test(dir.resolve(name))) {
                    return Optional.of(new EslintConfig(dir, dir.resolve(name), false));
                }
            }
            Path pkg = dir.resolve("package.json");
            if (in.isFile().test(pkg)
                    && packageJson(pkg, in).map(j -> j.has("eslintConfig")).orElse(false)) {
                return Optional.of(new EslintConfig(dir, pkg, false));
            }
        }
        return Optional.empty();
    }

    /**
     * The project's own copy of {@code kind} nearest to {@code file}: the package's start script under
     * {@code node_modules} (run with the project's Node.js), else on macOS and Linux its {@code node_modules/.bin}
     * command. Windows' {@code .cmd} shims are never run: a batch file re-parses its arguments.
     */
    public static Optional<Tool> tool(Kind kind, Path file, Lookup in) {
        for (Path dir : folders(file, in)) {
            Path modules = dir.resolve("node_modules");
            for (String entry : kind.entries()) {
                Path script = modules.resolve(entry);
                if (in.isFile().test(script)) {
                    return Optional.of(new Tool(kind, script, true, dir));
                }
            }
            Path bin = modules.resolve(".bin").resolve(kind.binName());
            if (!in.windows() && in.isFile().test(bin)) {
                return Optional.of(new Tool(kind, bin, false, dir));
            }
        }
        return Optional.empty();
    }

    /** What ESLint has for a file: its configuration and its installed copy, each when found. */
    public record EslintSetup(Optional<EslintConfig> config, Optional<Tool> tool) {

        /** Both found: ESLint can run (in a trusted folder). */
        public boolean ready() {
            return config.isPresent() && tool.isPresent();
        }

        /** The folder ESLint runs from (the configuration's), when it has one. */
        public Optional<Path> dir() {
            return config.map(EslintConfig::dir);
        }
    }

    /** {@code file}'s ESLint setup. */
    public static EslintSetup eslint(Path file, Lookup in) {
        Optional<EslintConfig> config = eslintConfig(file, in);
        return new EslintSetup(config, config.isEmpty() ? Optional.empty() : tool(Kind.ESLINT, file, in));
    }

    /** What formats a file. */
    public enum Formatter {
        PRETTIER,
        BIOME,
        /** The TypeScript language server, as before. */
        SERVER
    }

    /**
     * A decision for Format Document.
     *
     * @param formatter who formats
     * @param tool the Prettier or Biome to run (null for the server)
     * @param dir the folder it runs from (null for the server)
     * @param problem why the chosen formatter cannot run, as a message key taking the tool's name; null when it can.
     *     Set only for an explicit choice: {@code auto} then falls back to the server without a word.
     */
    public record FormatPlan(
            Formatter formatter,
            @Nullable Tool tool,
            @Nullable Path dir,
            @Nullable String problem) {

        static FormatPlan server() {
            return new FormatPlan(Formatter.SERVER, null, null, null);
        }

        static FormatPlan cannot(String problem) {
            return new FormatPlan(Formatter.SERVER, null, null, problem);
        }
    }

    /** The message key for an explicitly chosen formatter that is not installed in the project. */
    public static final String NOT_INSTALLED = "status.jsFormat.notInstalled";

    /** The message key for an explicitly chosen formatter in a folder that is not trusted. */
    public static final String UNTRUSTED = "status.jsFormat.untrusted";

    /**
     * Who formats {@code file} under the Settings choice {@code setting} ({@code auto|prettier|biome|server}):
     *
     * <ul>
     *   <li>{@code auto}: Prettier when the project uses it (a Prettier configuration, a {@code prettier} key, or
     *       {@code prettier} among a {@code package.json}'s dependencies) and has it installed; else Biome when it
     *       has a {@code biome.json(c)} and Biome installed; else the server;
     *   <li>{@code prettier} / {@code biome}: that tool when installed, whatever the configuration says; else a
     *       {@link FormatPlan#problem};
     *   <li>{@code server}: the server.
     * </ul>
     *
     * A tool runs only when {@code trusted} says both the folder it runs from and the one holding it are trusted.
     */
    public static FormatPlan formatPlan(String setting, Path file, Lookup in, Predicate<Path> trusted) {
        boolean auto = !"prettier".equals(setting) && !"biome".equals(setting);
        if ("server".equals(setting)) {
            return FormatPlan.server();
        }
        if (auto || "prettier".equals(setting)) {
            Optional<Path> configured = prettierConfigured(file, in);
            Optional<Tool> tool = auto && configured.isEmpty() ? Optional.empty() : tool(Kind.PRETTIER, file, in);
            if (tool.isPresent()) {
                Path dir = tool.get().root();
                if (trusted.test(dir)) {
                    return new FormatPlan(Formatter.PRETTIER, tool.get(), dir, null);
                }
                if (!auto) {
                    return FormatPlan.cannot(UNTRUSTED);
                }
            } else if (!auto) {
                return FormatPlan.cannot(NOT_INSTALLED);
            }
        }
        Optional<Path> biomeConfig = firstConfig(file, in, BIOME_CONFIGS);
        Optional<Tool> biome = auto && biomeConfig.isEmpty() ? Optional.empty() : tool(Kind.BIOME, file, in);
        if (biome.isPresent()) {
            // Biome reads its configuration from the folder it runs in (and above): the configuration's folder
            Path dir = biomeConfig.orElse(biome.get().root());
            if (trusted.test(dir) && trusted.test(biome.get().root())) {
                return new FormatPlan(Formatter.BIOME, biome.get(), dir, null);
            }
            return auto ? FormatPlan.server() : FormatPlan.cannot(UNTRUSTED);
        }
        return auto ? FormatPlan.server() : FormatPlan.cannot(NOT_INSTALLED);
    }

    /**
     * The folder of the nearest Prettier configuration for {@code file}: a configuration file, or a
     * {@code package.json} with a {@code prettier} key or {@code prettier} in its dependencies.
     */
    public static Optional<Path> prettierConfigured(Path file, Lookup in) {
        for (Path dir : folders(file, in)) {
            for (String name : PRETTIER_CONFIGS) {
                if (in.isFile().test(dir.resolve(name))) {
                    return Optional.of(dir);
                }
            }
            Path pkg = dir.resolve("package.json");
            if (in.isFile().test(pkg)
                    && packageJson(pkg, in)
                            .map(j -> j.has("prettier")
                                    || j.path("devDependencies").has("prettier")
                                    || j.path("dependencies").has("prettier"))
                            .orElse(false)) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }

    /** The folder of the nearest of {@code names} for {@code file}. */
    public static Optional<Path> firstConfig(Path file, Lookup in, List<String> names) {
        for (Path dir : folders(file, in)) {
            for (String name : names) {
                if (in.isFile().test(dir.resolve(name))) {
                    return Optional.of(dir);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<JsonNode> packageJson(Path pkg, Lookup in) {
        Optional<String> text = in.readFile().apply(pkg);
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        try {
            String s = text.get();
            JsonNode root = MAPPER.readTree(s.startsWith("﻿") ? s.substring(1) : s);
            return root != null && root.isObject() ? Optional.of(root) : Optional.empty();
        } catch (java.io.IOException | RuntimeException e) {
            return Optional.empty(); // a broken package.json says nothing about the tools
        }
    }

    /** A {@code package.json} of up to 1 MB. */
    private static Optional<String> readSmall(Path p) {
        try {
            return Files.size(p) > 1_048_576 ? Optional.empty() : Optional.of(Files.readString(p));
        } catch (java.io.IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
