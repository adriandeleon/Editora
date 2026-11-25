package com.editora.dap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * PowerShell Editor Services (PSES, the engine of VS Code's PowerShell extension), as Editora runs it: its
 * {@code Start-EditorServices.ps1} on {@code pwsh}, over standard input and output, once as the language server
 * ({@code -LanguageServiceOnly}) and once per debug session as the debug adapter ({@code -DebugServiceOnly}).
 *
 * <p>A debug session's PSES has no console: VS Code shows a script's output in PSES's own terminal, which needs a real
 * one (its read loop fails on a pipe). So the script runs wrapped ({@link #wrapped}): every stream of it merged,
 * formatted as PowerShell formats it for the console, written line by line to PSES's standard error, which Editora
 * shows in the Debug console. Breakpoints stay where they are: the script itself is what runs. Pure.
 */
public final class PowerShellLaunch {

    /** launch.json's {@code type} (and the adapter's id). */
    public static final String TYPE = "PowerShell";

    /** PSES's launch script, in its release's {@code PowerShellEditorServices} folder. */
    public static final String START_SCRIPT = "Start-EditorServices.ps1";

    /** The host name PSES reports, and its version. */
    static final String HOST = "Editora";

    private PowerShellLaunch() {}

    /** Whether {@code path} is a PowerShell file F5 and Run File run (a script, not a module or a data file). */
    public static boolean isScript(@Nullable Path path) {
        return path != null
                && path.getFileName() != null
                && path.getFileName()
                        .toString()
                        .toLowerCase(java.util.Locale.ROOT)
                        .endsWith(".ps1");
    }

    /**
     * {@code pwsh}'s arguments that start PSES from {@code startScript}: as the language server ({@code debug} false) or
     * as the debug adapter, on standard input and output, logging warnings to {@code logDir}. The modules PSES ships
     * (PSScriptAnalyzer, PSReadLine) are the folder above its own.
     */
    public static List<String> serviceArgs(Path startScript, Path logDir, boolean debug, long id) {
        Path moduleDir = startScript.toAbsolutePath().getParent();
        Path bundled = moduleDir == null || moduleDir.getParent() == null ? moduleDir : moduleDir.getParent();
        String kind = debug ? "debug" : "lsp";
        List<String> out = new ArrayList<>(List.of("-NoLogo", "-NoProfile", "-NonInteractive", "-File"));
        out.add(startScript.toString());
        out.addAll(List.of("-HostName", HOST, "-HostProfileId", HOST, "-HostVersion", "1.0.0"));
        out.addAll(List.of("-BundledModulesPath", String.valueOf(bundled)));
        out.addAll(List.of(
                "-LogPath",
                logDir.resolve("editora-pses-" + kind + "-" + id + ".log").toString()));
        out.addAll(List.of(
                "-SessionDetailsPath",
                logDir.resolve("editora-pses-" + kind + "-" + id + ".json").toString()));
        out.addAll(List.of("-LogLevel", "Warning", debug ? "-DebugServiceOnly" : "-LanguageServiceOnly", "-Stdio"));
        return out;
    }

    /**
     * The launch request that debugs {@code script} with {@code args} in {@code cwd}: the script run {@link #wrapped},
     * so its output reaches the Debug console.
     */
    public static Map<String, Object> launch(Path script, List<String> args, Path cwd) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", TYPE);
        out.put("name", "Editora (PowerShell)");
        out.put("request", "launch");
        out.put("script", wrapped(invocation(script.toString(), args)));
        out.put("cwd", cwd.toString());
        out.put("createTemporaryIntegratedConsole", false);
        return out;
    }

    /**
     * A launch.json {@code PowerShell} configuration as PSES is sent it: its {@code script} — a file, run with its
     * {@code args}, or a command as it is — run {@link #wrapped}. An attach goes as it is (it has no script).
     */
    public static Map<String, Object> fromLaunchJson(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>(body);
        if (!"launch".equals(body.get("request"))
                || !(body.get("script") instanceof String script)
                || script.isBlank()) {
            return out;
        }
        List<String> args = new ArrayList<>();
        if (body.get("args") instanceof List<?> l) {
            l.forEach(a -> args.add(String.valueOf(a)));
        } else if (body.get("args") instanceof String s && !s.isBlank()) {
            args.add(s);
        }
        boolean file = isScript(pathOrNull(script.strip()));
        out.put("script", wrapped(file ? invocation(script.strip(), args) : command(script, args)));
        out.remove("args");
        out.put("createTemporaryIntegratedConsole", false);
        return out;
    }

    /** {@code & 'script' 'arg' …}: the script run with its arguments, each quoted for PowerShell. */
    static String invocation(String script, List<String> args) {
        StringBuilder sb = new StringBuilder("& ").append(quote(script));
        for (String a : args) {
            sb.append(' ').append(quote(a));
        }
        return sb.toString();
    }

    /** A launch.json command with its {@code args} after it, as written (they are PowerShell already). */
    private static String command(String command, List<String> args) {
        return args.isEmpty() ? command : command + " " + String.join(" ", args);
    }

    /** {@code s} as a PowerShell literal string: single quotes, an inner one doubled. */
    public static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /**
     * {@code command} run with all its streams merged, each record as the console writes it ({@code WARNING:},
     * {@code VERBOSE:}, {@code DEBUG:} before theirs), formatted for the console and written to standard error line by
     * line, as it comes.
     */
    public static String wrapped(String command) {
        return "& { " + command + " } *>&1 | ForEach-Object {"
                + " if ($_ -is [System.Management.Automation.WarningRecord]) { 'WARNING: ' + $_.Message }"
                + " elseif ($_ -is [System.Management.Automation.VerboseRecord]) { 'VERBOSE: ' + $_.Message }"
                + " elseif ($_ -is [System.Management.Automation.DebugRecord]) { 'DEBUG: ' + $_.Message }"
                + " else { $_ } }"
                + " | Out-String -Stream -Width 200 | ForEach-Object { [Console]::Error.WriteLine($_) }";
    }

    /** Run File's command for {@code script} with {@code args}, on {@code pwsh}. */
    public static List<String> runCommand(String pwsh, Path script, List<String> args) {
        List<String> out = new ArrayList<>(List.of(pwsh, "-NoLogo", "-NoProfile", "-File", script.toString()));
        out.addAll(args);
        return out;
    }

    /** PowerShell's executable: {@code pwsh} (PowerShell 7), else Windows PowerShell's {@code powershell}. */
    public static String executable(java.util.function.Predicate<String> onPath, boolean windows) {
        if (onPath.test("pwsh")) {
            return "pwsh";
        }
        return windows && onPath.test("powershell") ? "powershell" : "pwsh";
    }

    private static @Nullable Path pathOrNull(String s) {
        try {
            return Path.of(s);
        } catch (RuntimeException notAPath) {
            return null;
        }
    }
}
