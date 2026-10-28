package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.editora.config.Settings;
import org.jspecify.annotations.Nullable;

/**
 * Pure: bash-language-server's own settings — its {@code bashIde} configuration section — as Editora sends them
 * in answer to {@code workspace/configuration} and in a {@code workspace/didChangeConfiguration} push.
 *
 * <p>Until these were sent the server ran on its built-in defaults with no way to change them: no ShellCheck
 * arguments, no shfmt style, a fixed 500-file background-analysis cap (symbols in a bigger repository silently
 * missing from Go to Symbol and completion), no explainshell.
 *
 * <p>The values come from the global settings, overlaid by a project's {@code .editora/settings.json}
 * {@code bashIde} object, which uses the server's own key names (the ones VS Code's bash-ide extension uses):
 * {@code shellcheckPath}, {@code shellcheckArguments}, {@code explainshellEndpoint},
 * {@code backgroundAnalysisMaxFiles}, {@code includeAllWorkspaceSymbols}, {@code enableSourceErrorDiagnostics},
 * {@code globPattern}, {@code logLevel}, {@code shellcheckExternalSources}, {@code backgroundAnalysisIgnore} and
 * a {@code shfmt} object ({@code path}, {@code languageDialect}, {@code simplifyCode}, {@code binaryNextLine},
 * {@code caseIndent}, {@code funcNextLine}, {@code spaceRedirects}, {@code keepPadding},
 * {@code ignoreEditorconfig}, {@code additionalArguments}). An empty {@code shellcheckPath} or {@code shfmt.path}
 * turns that tool off, as in VS Code (Bash review A8, D7). A value of the wrong type is left out: the server
 * rejects its whole configuration over one bad key and would run on its defaults.
 *
 * <p>Two project keys name a program the server runs ({@code shellcheckPath}, {@code shfmt.path}) and one sends
 * the code under the caret to a server ({@code explainshellEndpoint}); like a project's server command, those
 * are honoured only in a trusted folder. So are keys Editora does not know (a newer server's): one of them
 * could name a program too. The rest only change how a script is checked or printed.
 *
 * @param shellcheckPath ShellCheck to run; blank = {@code shellcheck} on PATH
 * @param shfmtPath shfmt to run; blank = {@code shfmt} on PATH
 * @param shellcheckEnabled false = the server is told {@code shellcheckPath: ""} (no linting)
 * @param shfmtEnabled false = the server is told {@code shfmt.path: ""} (no formatting)
 * @param sourceErrorDiagnostics the server's {@code enableSourceErrorDiagnostics}: a hint on {@code source} lines
 *     it cannot follow
 * @param extra further top-level {@code bashIde} keys a project set, sent as given
 * @param shfmtExtra further {@code shfmt} keys a project set, sent as given
 */
public record BashIdeConfig(
        String shellcheckPath,
        String shellcheckArguments,
        String shfmtPath,
        String shfmtDialect,
        boolean shfmtSimplify,
        boolean shfmtBinaryNextLine,
        boolean shfmtCaseIndent,
        boolean shfmtFuncNextLine,
        boolean shfmtSpaceRedirects,
        boolean shfmtKeepPadding,
        boolean shfmtIgnoreEditorconfig,
        int backgroundAnalysisMaxFiles,
        boolean includeAllWorkspaceSymbols,
        String explainshellEndpoint,
        boolean shellcheckEnabled,
        boolean shfmtEnabled,
        boolean sourceErrorDiagnostics,
        Map<String, Object> extra,
        Map<String, Object> shfmtExtra) {

    /** The configuration section bash-language-server reads. */
    public static final String SECTION = "bashIde";

    /** The default ShellCheck and shfmt commands (bare names, looked up on PATH by the server). */
    public static final String SHELLCHECK = "shellcheck";

    public static final String SHFMT = "shfmt";

    /**
     * The scripts background analysis reads (the server's {@code globPattern}): its own default, plus ksh, mksh
     * and Bats files, which Editora sends to the server as shell but whose functions were missing from Go to
     * Symbol and cross-file completion unless the file was open (Bash review D5).
     */
    public static final String DEFAULT_GLOB = "**/*@(.sh|.inc|.bash|.command|.ksh|.mksh|.bats)";

    /**
     * The server's log level: warnings and errors, which are all Editora shows (in the Debug Log). At the
     * server's own default ({@code info}) it sent a line for every lint and format that nobody read.
     */
    public static final String DEFAULT_LOG_LEVEL = "warning";

    private static final List<String> LOG_LEVELS = List.of("debug", "info", "warning", "error");

    private static final List<String> DIALECTS = Settings.BASH_SHFMT_DIALECTS;

    /** The top-level project keys this record reads itself; any other key is {@link #extra}. */
    private static final Set<String> KNOWN = Set.of(
            "shellcheckPath",
            "shellcheckArguments",
            "explainshellEndpoint",
            "backgroundAnalysisMaxFiles",
            "includeAllWorkspaceSymbols",
            "enableSourceErrorDiagnostics",
            "shfmt");

    /** The {@code shfmt} switches this record reads itself (with {@code path} and {@code languageDialect}). */
    private static final Set<String> SHFMT_SWITCHES = Set.of(
            "simplifyCode",
            "binaryNextLine",
            "caseIndent",
            "funcNextLine",
            "spaceRedirects",
            "keepPadding",
            "ignoreEditorconfig");

    public BashIdeConfig {
        shellcheckPath = blankToEmpty(shellcheckPath);
        shellcheckArguments = blankToEmpty(shellcheckArguments);
        shfmtPath = blankToEmpty(shfmtPath);
        shfmtDialect = shfmtDialect != null && DIALECTS.contains(shfmtDialect) ? shfmtDialect : "auto";
        backgroundAnalysisMaxFiles = Math.clamp(backgroundAnalysisMaxFiles, 0, Settings.BASH_BACKGROUND_ANALYSIS_MAX);
        explainshellEndpoint = blankToEmpty(explainshellEndpoint);
        extra = frozen(extra);
        shfmtExtra = frozen(shfmtExtra);
    }

    /** The server's own defaults (what it used before Editora sent anything). */
    public static BashIdeConfig defaults() {
        return of(new Settings());
    }

    /** The global settings' values. */
    public static BashIdeConfig of(Settings s) {
        return new BashIdeConfig(
                s.getBashShellcheckPath(),
                s.getBashShellcheckArguments(),
                s.getBashShfmtPath(),
                s.getBashShfmtDialect(),
                s.isBashShfmtSimplify(),
                s.isBashShfmtBinaryNextLine(),
                s.isBashShfmtCaseIndent(),
                s.isBashShfmtFuncNextLine(),
                s.isBashShfmtSpaceRedirects(),
                s.isBashShfmtKeepPadding(),
                s.isBashShfmtIgnoreEditorconfig(),
                s.getBashBackgroundAnalysisMaxFiles(),
                s.isBashIncludeAllWorkspaceSymbols(),
                s.getBashExplainshellEndpoint(),
                s.isBashShellcheckEnabled(),
                s.isBashShfmtEnabled(),
                s.isBashSourceErrorDiagnostics(),
                Map.of(),
                Map.of());
    }

    /**
     * This configuration with a project's {@code bashIde} object laid over it. Keys it does not set (or sets
     * to a value of the wrong type) keep this configuration's value; the program paths, the explainshell
     * endpoint and keys Editora does not know are taken only when {@code trusted}. An empty
     * {@code shellcheckPath} / {@code shfmt.path} turns the tool off, trusted or not: that runs nothing.
     */
    public BashIdeConfig withProject(Map<String, Object> project, boolean trusted) {
        if (project == null || project.isEmpty()) {
            return this;
        }
        Map<?, ?> shfmt = project.get("shfmt") instanceof Map<?, ?> m ? m : Map.of();
        Builder b = edit();
        if (project.get("shellcheckPath") instanceof String s && (s.isBlank() || trusted)) {
            b.shellcheckEnabled = !s.isBlank();
            b.shellcheckPath = s.isBlank() ? shellcheckPath : s;
        }
        if (shfmt.get("path") instanceof String s && (s.isBlank() || trusted)) {
            b.shfmtEnabled = !s.isBlank();
            b.shfmtPath = s.isBlank() ? shfmtPath : s;
        }
        Object args = project.get("shellcheckArguments");
        b.shellcheckArguments = args instanceof List<?> list ? joinArguments(list) : str(args, shellcheckArguments);
        b.shfmtDialect = str(shfmt.get("languageDialect"), shfmtDialect);
        b.shfmtSimplify = bool(shfmt.get("simplifyCode"), shfmtSimplify);
        b.shfmtBinaryNextLine = bool(shfmt.get("binaryNextLine"), shfmtBinaryNextLine);
        b.shfmtCaseIndent = bool(shfmt.get("caseIndent"), shfmtCaseIndent);
        b.shfmtFuncNextLine = bool(shfmt.get("funcNextLine"), shfmtFuncNextLine);
        b.shfmtSpaceRedirects = bool(shfmt.get("spaceRedirects"), shfmtSpaceRedirects);
        b.shfmtKeepPadding = bool(shfmt.get("keepPadding"), shfmtKeepPadding);
        b.shfmtIgnoreEditorconfig = bool(shfmt.get("ignoreEditorconfig"), shfmtIgnoreEditorconfig);
        b.backgroundAnalysisMaxFiles = project.get("backgroundAnalysisMaxFiles") instanceof Number n
                ? n.intValue()
                : backgroundAnalysisMaxFiles;
        b.includeAllWorkspaceSymbols = bool(project.get("includeAllWorkspaceSymbols"), includeAllWorkspaceSymbols);
        b.sourceErrorDiagnostics = bool(project.get("enableSourceErrorDiagnostics"), sourceErrorDiagnostics);
        if (trusted) {
            b.explainshellEndpoint = str(project.get("explainshellEndpoint"), explainshellEndpoint);
        }
        Map<String, Object> more = new LinkedHashMap<>(extra);
        project.forEach((key, value) -> {
            Object ok = KNOWN.contains(key) ? null : extraValue(key, value, trusted);
            if (ok != null) {
                more.put(key, ok);
            }
        });
        b.extra = more;
        Map<String, Object> moreShfmt = new LinkedHashMap<>(shfmtExtra);
        shfmt.forEach((key, value) -> {
            Object ok = key instanceof String k && !knownShfmt(k) ? shfmtExtraValue(k, value, trusted) : null;
            if (ok != null) {
                moreShfmt.put((String) key, ok);
            }
        });
        b.shfmtExtra = moreShfmt;
        return b.build();
    }

    private static boolean knownShfmt(String key) {
        return "path".equals(key) || "languageDialect".equals(key) || SHFMT_SWITCHES.contains(key);
    }

    /**
     * A further top-level key's value as it is sent, or null to leave it out: the keys bash-language-server
     * 5.6–5.8 reads, of the type it accepts; any other key only from a trusted folder, as written.
     */
    private static @Nullable Object extraValue(String key, @Nullable Object value, boolean trusted) {
        return switch (key) {
            case "globPattern" -> value instanceof String s && !s.isBlank() ? s.strip() : null;
            case "logLevel" ->
                value instanceof String s && LOG_LEVELS.contains(s.strip().toLowerCase(Locale.ROOT))
                        ? s.strip().toLowerCase(Locale.ROOT)
                        : null;
            case "shellcheckExternalSources" -> value instanceof Boolean b ? b : null;
            case "backgroundAnalysisIgnore" -> strings(value);
            default -> trusted && json(value) ? value : null;
        };
    }

    private static @Nullable Object shfmtExtraValue(String key, @Nullable Object value, boolean trusted) {
        if ("additionalArguments".equals(key)) {
            return value instanceof String s ? tokenize(s) : strings(value);
        }
        return trusted && json(value) ? value : null;
    }

    /**
     * What a project's {@code bashIde} object asks for that only a trusted folder is given, one line per key
     * ({@code "bash: shellcheckPath = /opt/sc"}). Empty when it asks for nothing that needs trust.
     */
    public static List<String> trustRequests(Map<String, Object> project) {
        if (project == null || project.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Map<?, ?> shfmt = project.get("shfmt") instanceof Map<?, ?> m ? m : Map.of();
        addRequest(out, "shellcheckPath", project.get("shellcheckPath"));
        addRequest(out, "shfmt.path", shfmt.get("path"));
        addRequest(out, "explainshellEndpoint", project.get("explainshellEndpoint"));
        project.forEach((key, value) -> {
            if (!KNOWN.contains(key) && extraValue(key, value, false) == null && extraValue(key, value, true) != null) {
                out.add("bash: " + key + " = " + value);
            }
        });
        return List.copyOf(out);
    }

    private static void addRequest(List<String> out, String key, @Nullable Object value) {
        if (value instanceof String s && !s.isBlank()) {
            out.add("bash: " + key + " = " + s.strip());
        }
    }

    /**
     * The keys of a project's {@code bashIde} object that this window uses, in the server's names
     * ({@code shfmt.caseIndent}): of a usable type, and those needing trust only when {@code trusted}. For the
     * Settings card, whose rows they override (Bash review A10).
     */
    public static List<String> overriddenKeys(Map<String, Object> project, boolean trusted) {
        if (project == null || project.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        project.forEach((key, value) -> {
            if ("shfmt".equals(key) && value instanceof Map<?, ?> shfmt) {
                shfmt.forEach((k, v) -> {
                    if (k instanceof String name && usableShfmt(name, v, trusted)) {
                        out.add("shfmt." + name);
                    }
                });
            } else if (usable(key, value, trusted)) {
                out.add(key);
            }
        });
        return List.copyOf(out);
    }

    private static boolean usable(String key, @Nullable Object value, boolean trusted) {
        return switch (key) {
            case "shellcheckPath" -> value instanceof String s && (s.isBlank() || trusted);
            case "explainshellEndpoint" -> trusted && value instanceof String;
            case "shellcheckArguments" -> value instanceof String || value instanceof List<?>;
            case "backgroundAnalysisMaxFiles" -> value instanceof Number;
            case "includeAllWorkspaceSymbols", "enableSourceErrorDiagnostics" -> value instanceof Boolean;
            case "shfmt" -> false;
            default -> extraValue(key, value, trusted) != null;
        };
    }

    private static boolean usableShfmt(String key, @Nullable Object value, boolean trusted) {
        if ("path".equals(key)) {
            return value instanceof String s && (s.isBlank() || trusted);
        }
        if ("languageDialect".equals(key)) {
            return value instanceof String s && DIALECTS.contains(s);
        }
        return SHFMT_SWITCHES.contains(key) ? value instanceof Boolean : shfmtExtraValue(key, value, trusted) != null;
    }

    /**
     * This configuration with blank ShellCheck/shfmt paths filled in from the copies Editora installed
     * ({@code null} = none installed). A path the user set is kept.
     */
    public BashIdeConfig withInstalledTools(@Nullable String installedShellcheck, @Nullable String installedShfmt) {
        Builder b = edit();
        if (shellcheckPath.isEmpty() && installedShellcheck != null) {
            b.shellcheckPath = installedShellcheck;
        }
        if (shfmtPath.isEmpty() && installedShfmt != null) {
            b.shfmtPath = installedShfmt;
        }
        return b.build();
    }

    /**
     * For a server running elsewhere (another host, a container, WSL): a path names a file on this machine, so
     * ShellCheck and shfmt are looked up on that side's PATH instead (a bare command name is kept).
     */
    public BashIdeConfig forRemote() {
        Builder b = edit();
        if (isPath(shellcheckPath)) {
            b.shellcheckPath = "";
        }
        if (isPath(shfmtPath)) {
            b.shfmtPath = "";
        }
        return b.build();
    }

    /** This configuration reading at most {@code max} workspace scripts in the background (0 = none). */
    public BashIdeConfig withBackgroundAnalysisMaxFiles(int max) {
        Builder b = edit();
        b.backgroundAnalysisMaxFiles = max;
        return b.build();
    }

    /**
     * Which configuration each Bash server root gets (Bash review A1): {@code project} (the user's with the
     * window's project laid over it) for a root inside the window's project, or holding it; {@code user}
     * everywhere else — another repository, {@code ~/bin}, the loose-scripts root. Before, the project's
     * ShellCheck arguments and shfmt style (and a trusted project's tool paths) reached every script the window
     * opened.
     */
    public static Function<Path, BashIdeConfig> perRoot(
            BashIdeConfig project, BashIdeConfig user, @Nullable Path projectRoot) {
        if (projectRoot == null || project.equals(user)) {
            return root -> user;
        }
        Path pr = projectRoot.toAbsolutePath().normalize();
        return root -> {
            Path r = root.toAbsolutePath().normalize();
            return r.getFileSystem() == pr.getFileSystem() && (r.startsWith(pr) || pr.startsWith(r)) ? project : user;
        };
    }

    /** The ShellCheck command the server runs; blank when linting is switched off (what it is sent then). */
    public String effectiveShellcheck() {
        return !shellcheckEnabled ? "" : shellcheckPath.isEmpty() ? SHELLCHECK : shellcheckPath;
    }

    /** The shfmt command the server runs; blank when formatting is switched off. */
    public String effectiveShfmt() {
        return !shfmtEnabled ? "" : shfmtPath.isEmpty() ? SHFMT : shfmtPath;
    }

    /**
     * The command for {@code toolId} ({@code shellcheck} / {@code shfmt}) whether or not it is switched on: what
     * Settings shows as found or missing under its on/off switch.
     */
    public String toolCommand(String toolId) {
        String path = SHFMT.equals(toolId) ? shfmtPath : shellcheckPath;
        return path.isEmpty() ? (SHFMT.equals(toolId) ? SHFMT : SHELLCHECK) : path;
    }

    /** Whether the server is told to use the tool {@code toolId} ({@code shellcheck} / {@code shfmt}) at all. */
    public boolean toolEnabled(String toolId) {
        return SHFMT.equals(toolId) ? shfmtEnabled : shellcheckEnabled;
    }

    /** The explainshell endpoint the server is sent: blank when there is none or it is refused. */
    public String sentExplainshellEndpoint() {
        return explainshellAllowed(explainshellEndpoint) ? explainshellEndpoint : "";
    }

    /**
     * Whether {@code endpoint} may be given hover text: blank (off), an {@code https://} address, or plain
     * {@code http://} to this machine only. The server sends it the whole command line under the caret,
     * secrets in it included, so a plain-http server elsewhere would carry them across the network unencrypted
     * (Bash review B10).
     */
    public static boolean explainshellAllowed(@Nullable String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return true;
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(endpoint.strip());
        } catch (java.net.URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return false;
        }
        if ("https".equals(scheme)) {
            return true;
        }
        return "http".equals(scheme)
                && (host.equals("localhost")
                        || host.endsWith(".localhost")
                        || host.startsWith("127.")
                        || host.equals("[::1]"));
    }

    /** The {@code bashIde} object itself, in the server's key names. */
    public Map<String, Object> toSection() {
        Map<String, Object> shfmt = new LinkedHashMap<>();
        shfmt.put("path", effectiveShfmt());
        shfmt.put("languageDialect", shfmtDialect);
        shfmt.put("simplifyCode", shfmtSimplify);
        shfmt.put("binaryNextLine", shfmtBinaryNextLine);
        shfmt.put("caseIndent", shfmtCaseIndent);
        shfmt.put("funcNextLine", shfmtFuncNextLine);
        shfmt.put("spaceRedirects", shfmtSpaceRedirects);
        shfmt.put("keepPadding", shfmtKeepPadding);
        shfmt.put("ignoreEditorconfig", shfmtIgnoreEditorconfig);
        shfmt.putAll(shfmtExtra);
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("shellcheckPath", effectiveShellcheck());
        // A list, not the string the server splits on single spaces: a quoted argument with a space in it
        // ("--rcfile '/a b/rc'") reached ShellCheck in pieces (Bash review A8).
        section.put("shellcheckArguments", tokenize(shellcheckArguments));
        section.put("explainshellEndpoint", sentExplainshellEndpoint());
        section.put("backgroundAnalysisMaxFiles", backgroundAnalysisMaxFiles);
        section.put("includeAllWorkspaceSymbols", includeAllWorkspaceSymbols);
        section.put("enableSourceErrorDiagnostics", sourceErrorDiagnostics);
        section.put("globPattern", DEFAULT_GLOB);
        section.put("logLevel", DEFAULT_LOG_LEVEL);
        section.putAll(extra);
        section.put("shfmt", shfmt);
        return section;
    }

    /**
     * Splits a command-line argument string the way a shell would: on blanks outside quotes, with
     * {@code '…'}, {@code "…"} and backslash escapes. Pure.
     */
    public static List<String> tokenize(@Nullable String arguments) {
        List<String> out = new ArrayList<>();
        if (arguments == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < arguments.length()) {
                    cur.append(arguments.charAt(++i));
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && i + 1 < arguments.length()) {
                cur.append(arguments.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inWord = false;
                }
            } else {
                cur.append(c);
                inWord = true;
            }
        }
        if (inWord) {
            out.add(cur.toString());
        }
        return out;
    }

    /** A project's list of arguments as one string that {@link #tokenize} reads back the same. */
    private static String joinArguments(List<?> list) {
        StringBuilder sb = new StringBuilder();
        for (Object o : list) {
            if (o instanceof String s && !s.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                boolean plain = s.chars().noneMatch(ch -> Character.isWhitespace(ch) || "'\"\\".indexOf(ch) >= 0);
                sb.append(plain ? s : "'" + s.replace("'", "'\\''") + "'");
            }
        }
        return sb.toString();
    }

    private static @Nullable List<String> strings(@Nullable Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s)) {
                return null;
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    /** Whether {@code value} is plain JSON data (what Jackson reads a settings file into). */
    private static boolean json(@Nullable Object value) {
        return value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof List<?>
                || value instanceof Map<?, ?>;
    }

    private static Map<String, Object> frozen(@Nullable Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null && v != null) {
                copy.put(k, v);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static boolean isPath(String command) {
        return command.indexOf('/') >= 0 || command.indexOf('\\') >= 0;
    }

    private static String str(@Nullable Object value, String fallback) {
        return value instanceof String s ? s : fallback;
    }

    private static boolean bool(@Nullable Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }

    private static String blankToEmpty(String s) {
        return s == null || s.isBlank() ? "" : s.strip();
    }

    private Builder edit() {
        return new Builder(this);
    }

    /** A copy being changed field by field (the record's withers). */
    private static final class Builder {
        String shellcheckPath;
        String shellcheckArguments;
        String shfmtPath;
        String shfmtDialect;
        boolean shfmtSimplify;
        boolean shfmtBinaryNextLine;
        boolean shfmtCaseIndent;
        boolean shfmtFuncNextLine;
        boolean shfmtSpaceRedirects;
        boolean shfmtKeepPadding;
        boolean shfmtIgnoreEditorconfig;
        int backgroundAnalysisMaxFiles;
        boolean includeAllWorkspaceSymbols;
        String explainshellEndpoint;
        boolean shellcheckEnabled;
        boolean shfmtEnabled;
        boolean sourceErrorDiagnostics;
        Map<String, Object> extra;
        Map<String, Object> shfmtExtra;

        Builder(BashIdeConfig c) {
            shellcheckPath = c.shellcheckPath;
            shellcheckArguments = c.shellcheckArguments;
            shfmtPath = c.shfmtPath;
            shfmtDialect = c.shfmtDialect;
            shfmtSimplify = c.shfmtSimplify;
            shfmtBinaryNextLine = c.shfmtBinaryNextLine;
            shfmtCaseIndent = c.shfmtCaseIndent;
            shfmtFuncNextLine = c.shfmtFuncNextLine;
            shfmtSpaceRedirects = c.shfmtSpaceRedirects;
            shfmtKeepPadding = c.shfmtKeepPadding;
            shfmtIgnoreEditorconfig = c.shfmtIgnoreEditorconfig;
            backgroundAnalysisMaxFiles = c.backgroundAnalysisMaxFiles;
            includeAllWorkspaceSymbols = c.includeAllWorkspaceSymbols;
            explainshellEndpoint = c.explainshellEndpoint;
            shellcheckEnabled = c.shellcheckEnabled;
            shfmtEnabled = c.shfmtEnabled;
            sourceErrorDiagnostics = c.sourceErrorDiagnostics;
            extra = c.extra;
            shfmtExtra = c.shfmtExtra;
        }

        BashIdeConfig build() {
            return new BashIdeConfig(
                    shellcheckPath,
                    shellcheckArguments,
                    shfmtPath,
                    shfmtDialect,
                    shfmtSimplify,
                    shfmtBinaryNextLine,
                    shfmtCaseIndent,
                    shfmtFuncNextLine,
                    shfmtSpaceRedirects,
                    shfmtKeepPadding,
                    shfmtIgnoreEditorconfig,
                    backgroundAnalysisMaxFiles,
                    includeAllWorkspaceSymbols,
                    explainshellEndpoint,
                    shellcheckEnabled,
                    shfmtEnabled,
                    sourceErrorDiagnostics,
                    extra,
                    shfmtExtra);
        }
    }
}
