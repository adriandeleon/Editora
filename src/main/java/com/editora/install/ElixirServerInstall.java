package com.editora.install;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * Where Elixir's language server comes from. ElixirLS is one install for two jobs: the release zip Settings →
 * Debugging installs ({@link InstallCatalog#ELIXIR_LS_STEP}, into {@link InstallCatalog#ELIXIR_LS_DIR}) holds the debug
 * adapter's launch script and the language server's ({@code language_server.sh}, {@code language_server.bat} on
 * Windows). So a blank (or default) command finds the server there before the PATH, as the HTML server's
 * ({@link WebServerInstall}) does: Editora's install under this launch's config folder, then {@code ~/.editora} and
 * {@code ~/.editora-dev} (where the debug adapter is looked for too), else {@code elixir-ls} on the PATH (Homebrew's and
 * most packages' name for it), else a {@code language_server.sh} on the PATH (an unpacked release put there).
 */
public final class ElixirServerInstall {

    private ElixirServerInstall() {}

    /** The language server's id in {@code LspServerRegistry}. */
    public static final String SERVER_ID = "elixir";

    /** The language server's launch script on {@code windows} or not. Pure. */
    static String script(boolean windows) {
        return windows ? "language_server.bat" : "language_server.sh";
    }

    /**
     * The command Elixir's server runs: {@code configured} as it is, unless it is blank or the bare default ({@code
     * elixir-ls}) — then Editora's install, else the default when the PATH has it, else a {@code language_server.sh} the
     * PATH has, else the default (which then says it is missing). Blocking: it looks at files and the PATH.
     */
    public static String effectiveCommand(@Nullable String configured, @Nullable Path configDir) {
        Path home = home();
        boolean windows = com.editora.Os.isWindows();
        return effectiveCommand(configured, configDir, home, windows, ElixirServerInstall::onPath);
    }

    /** {@link #effectiveCommand(String, Path)} with the home folder, the OS and the PATH given. Pure but for files. */
    static String effectiveCommand(
            @Nullable String configured,
            @Nullable Path configDir,
            @Nullable Path home,
            boolean windows,
            Predicate<String> onPath) {
        String raw = configured == null ? "" : configured;
        String normalized = String.join(" ", com.editora.lsp.LspServerRegistry.tokenize(raw));
        String fallback = com.editora.lsp.LspServerRegistry.defaultCommandFor(SERVER_ID);
        if (!raw.isBlank() && !normalized.equals(fallback)) {
            return raw;
        }
        Path own = ownLauncher(configDir, home, windows);
        if (own != null) {
            return InstallCatalog.quoteCommandPath(own.toString());
        }
        if (onPath.test(fallback)) {
            return raw;
        }
        String script = script(windows);
        return onPath.test(script) ? script : raw;
    }

    /** The folders Editora installs ElixirLS into, this launch's config folder first. Pure construction. */
    static List<Path> roots(@Nullable Path configDir, @Nullable Path home) {
        List<Path> roots = new ArrayList<>();
        if (configDir != null) {
            roots.add(configDir.resolve(InstallCatalog.ELIXIR_LS_DIR));
        }
        if (home != null) {
            roots.add(home.resolve(".editora").resolve(InstallCatalog.ELIXIR_LS_DIR));
            roots.add(home.resolve(".editora-dev").resolve(InstallCatalog.ELIXIR_LS_DIR));
        }
        return roots;
    }

    /** The language server's script in Editora's install, or null when none is installed. */
    static @Nullable Path ownLauncher(@Nullable Path configDir, @Nullable Path home, boolean windows) {
        String script = script(windows);
        for (Path root : roots(configDir, home)) {
            Path launcher = root.resolve(script);
            if (Files.isRegularFile(launcher)) {
                return launcher;
            }
        }
        return null;
    }

    private static @Nullable Path home() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        try {
            return Path.of(home);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Whether the augmented PATH has program {@code name}. */
    private static boolean onPath(String name) {
        String resolved = com.editora.process.ProcessRunner.resolveExecutable(List.of(name))
                .get(0);
        return !resolved.equals(name);
    }
}
