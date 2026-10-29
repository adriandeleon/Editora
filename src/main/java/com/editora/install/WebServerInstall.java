package com.editora.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * Editora's own copy of the HTML, CSS and JSON servers: {@code vscode-langservers-extracted}, pinned to {@link
 * InstallCatalog#WEB_LS_VERSION} and installed with {@code npm install --prefix} into {@link #DEST} under the config
 * dir (HTML review A2/A12), and the default command that finds it before PATH.
 *
 * <p>Why a private prefix: the package keeps VS Code's directory arithmetic but not its layout. The HTML server's
 * embedded JavaScript mode loads TypeScript's {@code lib.*.d.ts} from {@code <package>/../node_modules/typescript/lib}
 * ({@code javascriptLibs.js}: {@code join(serverFolder, '../../node_modules/typescript/lib')}), one folder too high in
 * every npm layout, so {@code <script>} had no DOM and no standard library at all: {@code document.} completed nothing
 * and every built-in hovered as {@code any}. Inside its own prefix Editora can put a link where the bundle looks
 * ({@link #linkTypeScriptLibs}) without touching the user's global npm folder. Doctor flags a copy without one
 * ({@link #typeScriptLibsMissing}) — a global install has the same hole.
 */
public final class WebServerInstall {

    private WebServerInstall() {}

    /** Where the prefix lives under the config dir: one install for the three servers. */
    public static final String DEST = "plugins/lsp/web";

    /** The servers the package brings that Editora runs. */
    public static final Set<String> SERVER_IDS = Set.of("html", "css", "json");

    /** The library the embedded JavaScript mode asks for first; the others are referenced from it. */
    static final String FIRST_LIB = "lib.es2020.full.d.ts";

    /** The server's log line for a library it could not read ({@code console.log} in {@code javascriptLibs.js}). */
    private static final String UNABLE_TO_LOAD = "Unable to load library lib.";

    /** Whether {@code serverId} is one of {@link #SERVER_IDS}. */
    public static boolean serves(@Nullable String serverId) {
        return serverId != null && SERVER_IDS.contains(serverId);
    }

    /** The program of {@code serverId} ({@code vscode-html-language-server}). Pure. */
    public static String program(String serverId) {
        return "vscode-" + serverId + "-language-server";
    }

    /** The prefix folder under {@code configDir}. */
    public static Path folder(Path configDir) {
        return configDir.resolve(DEST);
    }

    /** The package inside a prefix. */
    static Path packageDir(Path prefix) {
        return prefix.resolve("node_modules").resolve(InstallCatalog.WEB_LS_PACKAGE);
    }

    /**
     * The launcher of {@code serverId} in Editora's prefix — npm's {@code node_modules/.bin} link, its {@code .cmd} shim
     * on Windows — when that copy is installed, else null.
     */
    public static @Nullable Path ownLauncher(@Nullable Path configDir, String serverId) {
        return ownLauncher(configDir, serverId, isWindows());
    }

    static @Nullable Path ownLauncher(@Nullable Path configDir, String serverId, boolean windows) {
        if (configDir == null || !serves(serverId)) {
            return null;
        }
        Path bin = folder(configDir).resolve("node_modules").resolve(".bin");
        Path launcher = bin.resolve(program(serverId) + (windows ? ".cmd" : ""));
        return Files.isRegularFile(launcher) ? launcher : null;
    }

    /**
     * The command {@code serverId} runs: {@code configured} as it is, unless it is blank or the bare default
     * ({@code vscode-html-language-server --stdio}) — then Editora's own copy when it is installed, else the default,
     * which is looked up on PATH. Other servers' commands pass through.
     */
    public static String effectiveCommand(String serverId, @Nullable String configured, @Nullable Path configDir) {
        String raw = configured == null ? "" : configured;
        if (!serves(serverId)) {
            return raw;
        }
        String normalized = String.join(" ", com.editora.lsp.LspServerRegistry.tokenize(raw));
        String fallback = com.editora.lsp.LspServerRegistry.defaultCommandFor(serverId);
        if (!raw.isBlank() && !normalized.equals(fallback)) {
            return raw;
        }
        Path own = ownLauncher(configDir, serverId);
        return own == null ? raw : InstallCatalog.quoteCommandPath(own.toString()) + " --stdio";
    }

    /**
     * Puts TypeScript's library folder where the package's HTML server looks for it, inside {@code prefix}: a link
     * {@code node_modules/node_modules/typescript} → the TypeScript npm installed, or, where a link cannot be made (a
     * Windows account without the right), a copy of its {@code lib.*.d.ts} files (1.7 MB). Idempotent.
     *
     * @throws IOException when the install has no TypeScript to point at, or neither a link nor a copy can be made
     */
    public static void linkTypeScriptLibs(Path prefix) throws IOException {
        Path pkg = packageDir(prefix);
        Path typescript = installedTypeScript(pkg);
        if (typescript == null) {
            throw new IOException("the package's TypeScript was not installed with it");
        }
        Path wanted = expectedTypeScript(pkg);
        if (Files.isRegularFile(wanted.resolve("lib").resolve(FIRST_LIB))) {
            return;
        }
        Path holder = pkg.resolveSibling("node_modules");
        Files.createDirectories(holder);
        if (Files.isSymbolicLink(wanted) || Files.isRegularFile(wanted)) {
            Files.delete(wanted); // a dangling link from an earlier copy
        }
        try {
            if (!Files.exists(wanted)) {
                Files.createSymbolicLink(wanted, holder.relativize(typescript));
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            copyLibs(typescript, wanted);
        }
        if (!Files.isRegularFile(wanted.resolve("lib").resolve(FIRST_LIB))) {
            copyLibs(typescript, wanted);
        }
    }

    /** Copies {@code typescript}'s {@code lib/lib.*.d.ts} into {@code wanted/lib}, a folder (not a link). */
    private static void copyLibs(Path typescript, Path wanted) throws IOException {
        if (Files.isSymbolicLink(wanted)) {
            Files.delete(wanted);
        }
        Path from = typescript.resolve("lib");
        Path to = wanted.resolve("lib");
        Files.createDirectories(to);
        try (Stream<Path> files = Files.list(from)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (name.startsWith("lib.") && name.endsWith(".d.ts")) {
                    Files.copy(f, to.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** The TypeScript package npm installed for {@code pkg}: nested in it, or hoisted beside it; else null. */
    static @Nullable Path installedTypeScript(Path pkg) {
        for (Path candidate :
                List.of(pkg.resolve("node_modules").resolve("typescript"), pkg.resolveSibling("typescript"))) {
            if (Files.isRegularFile(candidate.resolve("lib").resolve(FIRST_LIB))) {
                return candidate;
            }
        }
        return null;
    }

    /** Where the package's HTML server reads TypeScript from: {@code <pkg>/../node_modules/typescript}. Pure. */
    static Path expectedTypeScript(Path pkg) {
        return pkg.resolveSibling("node_modules").resolve("typescript");
    }

    /**
     * Whether the vscode-langservers-extracted that {@code launcher} (a resolved server command) belongs to lacks the
     * TypeScript libraries its HTML server loads for {@code <script>}: true for every plain npm install of 4.10.0, false
     * for Editora's own copy and when {@code launcher} is not that package's (a user's own server). File reads only.
     */
    public static boolean typeScriptLibsMissing(Path launcher) {
        Path pkg = packageOf(launcher);
        return pkg != null
                && !Files.isRegularFile(expectedTypeScript(pkg).resolve("lib").resolve(FIRST_LIB));
    }

    /**
     * The vscode-langservers-extracted folder {@code launcher} belongs to: its real path's ancestors (npm links
     * {@code bin/vscode-html-language-server} into the package), or a {@code node_modules} beside one of them (npm's
     * Windows {@code .cmd} shims sit in the global prefix or in {@code .bin}); else null.
     */
    static @Nullable Path packageOf(Path launcher) {
        Path dir;
        try {
            dir = launcher.toRealPath().getParent();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            if (InstallCatalog.WEB_LS_PACKAGE.equals(packageName(dir.resolve("package.json")))) {
                return dir;
            }
            for (Path beside : List.of(dir.resolve("node_modules"), dir)) {
                Path pkg = beside.resolve(InstallCatalog.WEB_LS_PACKAGE);
                if (InstallCatalog.WEB_LS_PACKAGE.equals(packageName(pkg.resolve("package.json")))) {
                    return pkg;
                }
            }
        }
        return null;
    }

    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private static @Nullable String packageName(Path packageJson) {
        return field(packageJson, NAME);
    }

    private static @Nullable String field(Path packageJson, Pattern p) {
        try {
            if (!Files.isRegularFile(packageJson) || Files.size(packageJson) > 1024 * 1024) {
                return null;
            }
            Matcher m = p.matcher(Files.readString(packageJson, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The package version in a prefix ({@code 4.10.0}), from its {@code package.json}; null when not installed. */
    static @Nullable String installedVersion(Path prefix) {
        return field(packageDir(prefix).resolve("package.json"), VERSION);
    }

    /** Whether {@code message} (a server's log line) says a TypeScript library could not be loaded. Pure. */
    public static boolean unableToLoadLibrary(@Nullable String message) {
        return message != null && message.contains(UNABLE_TO_LOAD);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
