package com.editora.install;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.editora.dap.InstalledAdapter;
import com.editora.dap.PowerShellLaunch;
import org.jspecify.annotations.Nullable;

/**
 * Where PowerShell's language server comes from. PowerShell Editor Services is one install for two jobs, as ElixirLS
 * is: the language server and the debug adapter are its {@code Start-EditorServices.ps1}, started differently
 * ({@link PowerShellLaunch#serviceArgs}). A blank (or default, {@code pwsh}) command finds it where the debugger looks
 * ({@link InstalledAdapter#POWERSHELL}: Editora's {@code plugins/dap/powershell} under this launch's config folder, then
 * {@code ~/.editora} and {@code ~/.editora-dev}, then VS Code's PowerShell extension) and becomes {@code pwsh} with
 * its arguments; without it, the start script's name, which the start then says is missing.
 */
public final class PowerShellServerInstall {

    private PowerShellServerInstall() {}

    /** The language server's id in {@code LspServerRegistry}. */
    public static final String SERVER_ID = "powershell";

    /** The command PowerShell's server runs ({@link PowerShellServerInstall}). Blocking: it looks at files and PATH. */
    public static String effectiveCommand(@Nullable String configured, @Nullable Path configDir) {
        String home = System.getProperty("user.home", "");
        return effectiveCommand(
                configured,
                configDir,
                home.isBlank() ? null : Path.of(home),
                Path.of(System.getProperty("java.io.tmpdir")),
                ProcessHandle.current().pid(),
                name -> Path.of(com.editora.process.ProcessRunner.resolveExecutable(List.of(name))
                                .getFirst())
                        .isAbsolute(),
                com.editora.Os.isWindows());
    }

    /** {@link #effectiveCommand(String, Path)} with the folders, the PATH and the OS given. Pure but for files. */
    static String effectiveCommand(
            @Nullable String configured,
            @Nullable Path configDir,
            @Nullable Path home,
            Path logDir,
            long id,
            Predicate<String> onPath,
            boolean windows) {
        String raw = configured == null ? "" : configured;
        String normalized = String.join(" ", com.editora.lsp.LspServerRegistry.tokenize(raw));
        if (!raw.isBlank() && !normalized.equals(com.editora.lsp.LspServerRegistry.defaultCommandFor(SERVER_ID))) {
            return raw;
        }
        Path start = startScript(configDir, home);
        if (start == null) {
            return PowerShellLaunch.START_SCRIPT; // not on the PATH: the start says the server is missing
        }
        List<String> argv = new ArrayList<>();
        argv.add(PowerShellLaunch.executable(onPath, windows));
        argv.addAll(PowerShellLaunch.serviceArgs(start, logDir, false, id));
        List<String> quoted = new ArrayList<>();
        argv.forEach(a -> quoted.add(InstallCatalog.quoteCommandPath(a)));
        return String.join(" ", quoted);
    }

    /** PSES's start script: Editora's install under {@code configDir}, else where the debugger finds it; or null. */
    static @Nullable Path startScript(@Nullable Path configDir, @Nullable Path home) {
        if (configDir != null) {
            Path root = configDir.resolve("plugins/dap/powershell");
            for (String rel : InstalledAdapter.POWERSHELL.inFolder()) {
                Path p = root.resolve(rel);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        return home == null
                ? null
                : InstalledAdapter.POWERSHELL.locate("", home).orElse(null);
    }
}
