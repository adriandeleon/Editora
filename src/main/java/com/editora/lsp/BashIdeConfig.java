package com.editora.lsp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * {@code backgroundAnalysisMaxFiles}, {@code includeAllWorkspaceSymbols} and a {@code shfmt} object
 * ({@code path}, {@code languageDialect}, {@code simplifyCode}, {@code binaryNextLine}, {@code caseIndent},
 * {@code funcNextLine}, {@code spaceRedirects}, {@code keepPadding}, {@code ignoreEditorconfig}).
 *
 * <p>Two project keys name a program the server runs ({@code shellcheckPath}, {@code shfmt.path}) and one sends
 * the code under the caret to a server ({@code explainshellEndpoint}); like a project's server command, those
 * are honoured only in a trusted folder. The rest only change how a script is checked or printed.
 *
 * @param shellcheckPath ShellCheck to run; blank = {@code shellcheck} on PATH
 * @param shfmtPath shfmt to run; blank = {@code shfmt} on PATH
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
        String explainshellEndpoint) {

    /** The configuration section bash-language-server reads. */
    public static final String SECTION = "bashIde";

    /** The default ShellCheck and shfmt commands (bare names, looked up on PATH by the server). */
    public static final String SHELLCHECK = "shellcheck";

    public static final String SHFMT = "shfmt";

    private static final List<String> DIALECTS = Settings.BASH_SHFMT_DIALECTS;

    public BashIdeConfig {
        shellcheckPath = blankToEmpty(shellcheckPath);
        shellcheckArguments = blankToEmpty(shellcheckArguments);
        shfmtPath = blankToEmpty(shfmtPath);
        shfmtDialect = shfmtDialect != null && DIALECTS.contains(shfmtDialect) ? shfmtDialect : "auto";
        backgroundAnalysisMaxFiles = Math.clamp(backgroundAnalysisMaxFiles, 0, Settings.BASH_BACKGROUND_ANALYSIS_MAX);
        explainshellEndpoint = blankToEmpty(explainshellEndpoint);
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
                s.getBashExplainshellEndpoint());
    }

    /**
     * This configuration with a project's {@code bashIde} object laid over it. Keys it does not set (or sets
     * to a value of the wrong type) keep this configuration's value; the program paths and the explainshell
     * endpoint are taken only when {@code trusted}.
     */
    public BashIdeConfig withProject(Map<String, Object> project, boolean trusted) {
        if (project == null || project.isEmpty()) {
            return this;
        }
        Map<?, ?> shfmt = project.get("shfmt") instanceof Map<?, ?> m ? m : Map.of();
        return new BashIdeConfig(
                trusted ? str(project.get("shellcheckPath"), shellcheckPath) : shellcheckPath,
                str(project.get("shellcheckArguments"), shellcheckArguments),
                trusted ? str(shfmt.get("path"), shfmtPath) : shfmtPath,
                str(shfmt.get("languageDialect"), shfmtDialect),
                bool(shfmt.get("simplifyCode"), shfmtSimplify),
                bool(shfmt.get("binaryNextLine"), shfmtBinaryNextLine),
                bool(shfmt.get("caseIndent"), shfmtCaseIndent),
                bool(shfmt.get("funcNextLine"), shfmtFuncNextLine),
                bool(shfmt.get("spaceRedirects"), shfmtSpaceRedirects),
                bool(shfmt.get("keepPadding"), shfmtKeepPadding),
                bool(shfmt.get("ignoreEditorconfig"), shfmtIgnoreEditorconfig),
                project.get("backgroundAnalysisMaxFiles") instanceof Number n
                        ? n.intValue()
                        : backgroundAnalysisMaxFiles,
                bool(project.get("includeAllWorkspaceSymbols"), includeAllWorkspaceSymbols),
                trusted ? str(project.get("explainshellEndpoint"), explainshellEndpoint) : explainshellEndpoint);
    }

    /**
     * What a project's {@code bashIde} object asks for that only a trusted folder is given, one line per key
     * ({@code "bash: shellcheckPath = /opt/sc"}). Empty when it asks for nothing that needs trust.
     */
    public static List<String> trustRequests(Map<String, Object> project) {
        if (project == null || project.isEmpty()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        Map<?, ?> shfmt = project.get("shfmt") instanceof Map<?, ?> m ? m : Map.of();
        addRequest(out, "shellcheckPath", project.get("shellcheckPath"));
        addRequest(out, "shfmt.path", shfmt.get("path"));
        addRequest(out, "explainshellEndpoint", project.get("explainshellEndpoint"));
        return List.copyOf(out);
    }

    private static void addRequest(List<String> out, String key, @Nullable Object value) {
        if (value instanceof String s && !s.isBlank()) {
            out.add("bash: " + key + " = " + s.strip());
        }
    }

    /**
     * This configuration with blank ShellCheck/shfmt paths filled in from the copies Editora installed
     * ({@code null} = none installed). A path the user set is kept.
     */
    public BashIdeConfig withInstalledTools(@Nullable String installedShellcheck, @Nullable String installedShfmt) {
        return new BashIdeConfig(
                shellcheckPath.isEmpty() && installedShellcheck != null ? installedShellcheck : shellcheckPath,
                shellcheckArguments,
                shfmtPath.isEmpty() && installedShfmt != null ? installedShfmt : shfmtPath,
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
                explainshellEndpoint);
    }

    /**
     * For a server running on another host: a path names a file on this machine, so ShellCheck and shfmt are
     * looked up on that host's PATH instead (a bare command name is kept).
     */
    public BashIdeConfig forRemote() {
        return new BashIdeConfig(
                isPath(shellcheckPath) ? "" : shellcheckPath,
                shellcheckArguments,
                isPath(shfmtPath) ? "" : shfmtPath,
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
                explainshellEndpoint);
    }

    /** This configuration reading at most {@code max} workspace scripts in the background (0 = none). */
    public BashIdeConfig withBackgroundAnalysisMaxFiles(int max) {
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
                max,
                includeAllWorkspaceSymbols,
                explainshellEndpoint);
    }

    /** The ShellCheck command the server is told to run. */
    public String effectiveShellcheck() {
        return shellcheckPath.isEmpty() ? SHELLCHECK : shellcheckPath;
    }

    /** The shfmt command the server is told to run. */
    public String effectiveShfmt() {
        return shfmtPath.isEmpty() ? SHFMT : shfmtPath;
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
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("shellcheckPath", effectiveShellcheck());
        section.put("shellcheckArguments", shellcheckArguments);
        section.put("explainshellEndpoint", explainshellEndpoint);
        section.put("backgroundAnalysisMaxFiles", backgroundAnalysisMaxFiles);
        section.put("includeAllWorkspaceSymbols", includeAllWorkspaceSymbols);
        section.put("shfmt", shfmt);
        return section;
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
}
