package com.editora.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Editora's own copy of the TypeScript / JavaScript server: typescript-language-server and the TypeScript whose
 * {@code tsserver} it runs, both pinned ({@link InstallCatalog#TYPESCRIPT_LS_VERSION}, {@link
 * InstallCatalog#TYPESCRIPT_VERSION}) and installed with {@code npm install --prefix} into {@link #DEST} under the
 * config dir — never {@code npm install -g}, which replaced the user's own global {@code tsc} (JS review B104).
 *
 * <p>Why TypeScript 5.9 and not a newer one: TypeScript 7 has no {@code tsserver} at all, and TypeScript 6 changed
 * the default of {@code types} to {@code []}, so an installed {@code @types/node} no longer counts unless a tsconfig
 * lists it: every Node.js project written for TypeScript 5 showed "Cannot find name 'process'", and plain JavaScript
 * hovered every Node.js API as {@code any} (JS review B101). typescript-language-server cannot pass {@code types} for
 * the inferred projects of loose files, so the TypeScript itself has to be one that includes them.
 *
 * <p>A server the user installed (on PATH, or a command of their own) is still used; Editora's TypeScript is then
 * still preferred over the one beside that server whenever this copy is installed ({@link #ownTsserver}).
 */
public final class TypeScriptServerInstall {

    private TypeScriptServerInstall() {}

    /** Where the prefix lives under the config dir. */
    public static final String DEST = "plugins/lsp/typescript";

    /** The server's program, as npm links it into {@code node_modules/.bin}. */
    public static final String PROGRAM = "typescript-language-server";

    /** The server id it serves. */
    public static final String SERVER_ID = "typescript";

    /** The default command, which finds the program on PATH. */
    static final String DEFAULT_COMMAND = PROGRAM + " --stdio";

    /** The prefix folder under {@code configDir}. */
    public static Path folder(Path configDir) {
        return configDir.resolve(DEST);
    }

    /** The launcher in Editora's prefix (npm's {@code .bin} link, or its {@code .cmd} shim on Windows), or null. */
    public static @Nullable Path ownLauncher(@Nullable Path configDir) {
        return ownLauncher(configDir, isWindows());
    }

    static @Nullable Path ownLauncher(@Nullable Path configDir, boolean windows) {
        if (configDir == null) {
            return null;
        }
        Path launcher =
                folder(configDir).resolve("node_modules").resolve(".bin").resolve(PROGRAM + (windows ? ".cmd" : ""));
        return Files.isRegularFile(launcher) ? launcher : null;
    }

    /** The {@code tsserver.js} of the TypeScript in Editora's prefix, or null when it is not installed. */
    public static @Nullable Path ownTsserver(@Nullable Path configDir) {
        if (configDir == null) {
            return null;
        }
        Path tsserver = sdkDir(folder(configDir)).resolve("tsserver.js");
        return Files.isRegularFile(tsserver) ? tsserver : null;
    }

    /** The TypeScript {@code lib} folder (an SDK, as astro-ls takes it) in Editora's prefix, or null. */
    public static @Nullable Path ownSdk(@Nullable Path configDir) {
        if (configDir == null) {
            return null;
        }
        Path lib = sdkDir(folder(configDir));
        return Files.isRegularFile(lib.resolve("typescript.js")) ? lib : null;
    }

    /** {@code <prefix>/node_modules/typescript/lib}. Pure. */
    static Path sdkDir(Path prefix) {
        return prefix.resolve("node_modules").resolve("typescript").resolve("lib");
    }

    /**
     * The command the TypeScript server runs: {@code configured} as it is, unless it is blank or the bare default
     * ({@code typescript-language-server --stdio}) — then Editora's own copy when it is installed, else the default,
     * which is looked up on PATH.
     */
    public static String effectiveCommand(@Nullable String configured, @Nullable Path configDir) {
        return commandWith(configured, ownLauncher(configDir));
    }

    /** {@link #effectiveCommand(String, Path)} with the own launcher already looked up (null: none). Pure. */
    static String commandWith(@Nullable String configured, @Nullable Path ownLauncher) {
        String raw = configured == null ? "" : configured;
        String normalized = String.join(" ", com.editora.lsp.LspServerRegistry.tokenize(raw));
        if (!raw.isBlank() && !normalized.equals(DEFAULT_COMMAND)) {
            return raw;
        }
        return ownLauncher == null ? raw : InstallCatalog.quoteCommandPath(ownLauncher.toString()) + " --stdio";
    }

    /**
     * Whether a TypeScript of version {@code version} (as its package.json says it, "" when unknown) is one Editora
     * may leave the server on: one with a tsserver and the TypeScript 5 handling of {@code types}. TypeScript 6 and
     * newer are not (see the class comment), nor is a TypeScript with no tsserver. Pure.
     */
    public static boolean suitable(@Nullable String version, boolean hasTsserver) {
        if (!hasTsserver) {
            return false;
        }
        int major = major(version);
        return major > 0 && major < 6;
    }

    /** The major version of {@code version} ("5.9.3" → 5), or 0 when there is none. Pure. */
    public static int major(@Nullable String version) {
        if (version == null) {
            return 0;
        }
        Matcher m = MAJOR.matcher(version.strip());
        if (!m.lookingAt()) {
            return 0;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final Pattern MAJOR = Pattern.compile("v?(\\d+)");

    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    /** The typescript-language-server version in a prefix, from its package.json; null when not installed. */
    static @Nullable String installedVersion(Path prefix) {
        Path pkg = prefix.resolve("node_modules").resolve(PROGRAM).resolve("package.json");
        try {
            if (!Files.isRegularFile(pkg) || Files.size(pkg) > 1024 * 1024) {
                return null;
            }
            Matcher m = VERSION.matcher(Files.readString(pkg, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The packages the install puts in the prefix, pinned. */
    public static List<String> packages() {
        return List.of(PROGRAM + "@" + InstallCatalog.TYPESCRIPT_LS_VERSION, InstallCatalog.TYPESCRIPT_PACKAGE);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
