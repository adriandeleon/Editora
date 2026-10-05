package com.editora.lsp;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure: the settings Editora gives a language server, chosen <b>per server id</b>.
 *
 * <p>Two channels carry them: the {@code workspace/didChangeConfiguration} push after {@code initialized}
 * ({@link #push}) and the answers to the server's own {@code workspace/configuration} requests
 * ({@link #answer}). Neither is harmless to a server it was not written for, which is why nothing here is
 * global:
 *
 * <ul>
 *   <li>vscode-json-language-server reads <i>any</i> pushed object lacking {@code json.validate.enable} —
 *       even {@code {}} — as "validation off", so a push meant for Pyright and jdtls silenced every JSON
 *       diagnostic. Servers we have no settings for get <b>no push at all</b>.</li>
 *   <li>vscode-css-language-server (and the HTML server's embedded-CSS mode) hands a {@code null}
 *       configuration answer straight to a validator that throws on it, so their own sections are answered
 *       with an empty object. The HTML server's {@code js/ts} section is the exception: {@code {}} there makes
 *       its JavaScript mode throw, so it stays {@code null}.</li>
 *   <li>Pyright sets {@code autoSearchPaths = !!analysis.autoSearchPaths} as soon as an {@code analysis}
 *       object is present, so a partial object switches its default-on {@code src/} search path off. Every
 *       analysis object built here therefore carries {@code autoSearchPaths}.</li>
 * </ul>
 */
final class LspServerSettings {

    private static final String PYTHON_SERVER_ID = "python";
    private static final String CSS_SERVER_ID = "css";
    private static final String HTML_SERVER_ID = "html";

    private LspServerSettings() {}

    /**
     * The settings object to push to {@code serverId} after {@code initialized}, or {@code null} when that
     * server must not be pushed anything.
     */
    static Map<String, Object> push(String serverId, boolean javaOnTypeFormatting) {
        if (PYTHON_SERVER_ID.equals(serverId)) {
            Map<String, Object> python = new HashMap<>();
            python.put("analysis", pythonAnalysis());
            Map<String, Object> settings = new HashMap<>();
            settings.put("python", python);
            return settings;
        }
        if (LspServerRegistry.JAVA_SERVER_ID.equals(serverId)) {
            Map<String, Object> settings = new HashMap<>();
            settings.put("java", javaSettings(javaOnTypeFormatting));
            return settings;
        }
        return null;
    }

    /**
     * The answer to {@code serverId} asking {@code workspace/configuration} for {@code section}; {@code null}
     * (the server keeps its own default) for every section that is not that server's own.
     */
    static Object answer(String serverId, String section) {
        String s = section == null ? "" : section;
        if (PYTHON_SERVER_ID.equals(serverId)) {
            // However the server phrases it: the leaf key, the analysis object (python.analysis,
            // basedpyright.analysis), or the whole python object.
            if (s.endsWith("autoImportCompletions") || s.endsWith("autoSearchPaths")) {
                return Boolean.TRUE;
            }
            if (s.endsWith(".analysis")) {
                return pythonAnalysis();
            }
            if (s.equals("python")) {
                Map<String, Object> python = new HashMap<>();
                python.put("analysis", pythonAnalysis());
                return python;
            }
            return null;
        }
        if (CSS_SERVER_ID.equals(serverId)) {
            return s.equals("css") || s.equals("scss") || s.equals("less") ? new HashMap<String, Object>() : null;
        }
        if (HTML_SERVER_ID.equals(serverId)) {
            // Not "js/ts": an empty object there throws in the server's JavaScript mode.
            return s.equals("css") || s.equals("html") || s.equals("javascript") ? new HashMap<String, Object>() : null;
        }
        return null;
    }

    /** The one {@code python.analysis} object, so the push and both answer shapes cannot drift apart. */
    private static Map<String, Object> pythonAnalysis() {
        Map<String, Object> analysis = new HashMap<>();
        analysis.put("autoImportCompletions", true);
        analysis.put("autoSearchPaths", true); // Pyright's own default, lost once this object exists
        return analysis;
    }

    private static Map<String, Object> javaSettings(boolean onTypeFormatting) {
        // jdtls ADVERTISES signatureHelpProvider but its handler returns an empty result unless
        // `java.signatureHelp.enabled` is set — it ships OFF (VS Code's Java extension sets it in its
        // own defaults, which is why it "just works" there). Verified by driving a real jdtls: same
        // position, same params — 0 signatures before this flag, both overloads after (#674). Same
        // class of bug as #468's provideFormatter.
        Map<String, Object> signatureHelp = new HashMap<>();
        signatureHelp.put("enabled", true);
        signatureHelp.put("description", true); // include the javadoc in the signature popup
        // Same shape of gate for smart-semicolon detection (#746): jdtls advertises
        // java.edit.smartSemicolonDetection unconditionally, but its handler answers null until this
        // preference is set — verified against a real jdtls (null for every argument shape before,
        // the target position after). Editora then gates the *behaviour* on its own setting, so
        // enabling the server-side capability here costs nothing when the feature is off.
        Map<String, Object> smartSemicolon = new HashMap<>();
        smartSemicolon.put("enabled", true);
        Map<String, Object> edit = new HashMap<>();
        edit.put("smartSemicolonDetection", smartSemicolon);
        Map<String, Object> java = new HashMap<>();
        java.put("signatureHelp", signatureHelp);
        java.put("edit", edit);
        // On-type formatting: jdtls only registers textDocument/onTypeFormatting while this is on (it
        // merges the pushed keys into its preferences and re-syncs its dynamic registrations).
        java.put("format", Map.of("onType", Map.of("enabled", onTypeFormatting)));
        return java;
    }
}
