package com.editora.lsp;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

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
 *   <li>Pyright analyses with the interpreter in {@code python.pythonPath}; without one it runs whatever
 *       {@code python3} is first on PATH, so a project's virtual environment was never seen and every
 *       installed package was an unresolved import (review B1). The session's resolved interpreter is sent in
 *       both channels. A project's own {@code pyrightconfig.json} / {@code [tool.pyright] venv} still wins
 *       inside Pyright.</li>
 * </ul>
 */
final class LspServerSettings {

    private static final String PYTHON_SERVER_ID = "python";
    private static final String CSS_SERVER_ID = "css";
    private static final String HTML_SERVER_ID = "html";
    private static final String BASH_SERVER_ID = "bash";

    private static Map<String, Object> bashSection(@Nullable BashIdeConfig bashIde) {
        return (bashIde == null ? BashIdeConfig.defaults() : bashIde).toSection();
    }

    private LspServerSettings() {}

    /**
     * The settings object to push to {@code serverId} after {@code initialized}, or {@code null} when that
     * server must not be pushed anything.
     */
    static @Nullable Map<String, Object> push(String serverId, boolean javaOnTypeFormatting) {
        return push(serverId, javaOnTypeFormatting, null);
    }

    /**
     * As {@link #push(String, boolean)}, with bash-language-server's {@code bashIde} section ({@code null} = its
     * defaults) for the Bash server.
     */
    static @Nullable Map<String, Object> push(
            String serverId, boolean javaOnTypeFormatting, @Nullable BashIdeConfig bashIde) {
        return push(serverId, javaOnTypeFormatting, bashIde, null);
    }

    /**
     * As {@link #push(String, boolean, BashIdeConfig)}, with the Python interpreter ({@code null}/blank = let the
     * server pick its own) for the Python server.
     */
    static @Nullable Map<String, Object> push(
            String serverId,
            boolean javaOnTypeFormatting,
            @Nullable BashIdeConfig bashIde,
            @Nullable String pythonPath) {
        if (BASH_SERVER_ID.equals(serverId)) {
            Map<String, Object> settings = new HashMap<>();
            settings.put(BashIdeConfig.SECTION, bashSection(bashIde));
            return settings;
        }
        if (PYTHON_SERVER_ID.equals(serverId)) {
            Map<String, Object> settings = new HashMap<>();
            settings.put("python", pythonSection(pythonPath));
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
    static @Nullable Object answer(String serverId, String section) {
        return answer(serverId, section, null);
    }

    /**
     * As {@link #answer(String, String)}, with the Bash server's {@code bashIde} section ({@code null} = its
     * defaults). bash-language-server asks for {@code bashIde} once it is initialized; answering {@code null}
     * left every one of its settings at the built-in default with no way to change them.
     */
    static @Nullable Object answer(String serverId, String section, @Nullable BashIdeConfig bashIde) {
        return answer(serverId, section, bashIde, null);
    }

    /** As {@link #answer(String, String, BashIdeConfig)}, with the Python server's interpreter (may be null). */
    static @Nullable Object answer(
            String serverId, String section, @Nullable BashIdeConfig bashIde, @Nullable String pythonPath) {
        String s = section == null ? "" : section;
        if (BASH_SERVER_ID.equals(serverId)) {
            return s.equals(BashIdeConfig.SECTION) ? bashSection(bashIde) : null;
        }
        if (PYTHON_SERVER_ID.equals(serverId)) {
            // However the server phrases it: the leaf key, the analysis object (python.analysis,
            // basedpyright.analysis), or the whole python object.
            if (s.endsWith("autoImportCompletions") || s.endsWith("autoSearchPaths")) {
                return Boolean.TRUE;
            }
            if (s.endsWith(".pythonPath")) {
                return blank(pythonPath) ? null : pythonPath;
            }
            if (s.endsWith(".analysis")) {
                return pythonAnalysis();
            }
            if (s.equals("python")) {
                return pythonSection(pythonPath);
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

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    /** The whole {@code python} object: the analysis settings, and the interpreter when one was resolved. */
    private static Map<String, Object> pythonSection(@Nullable String pythonPath) {
        Map<String, Object> python = new HashMap<>();
        python.put("analysis", pythonAnalysis());
        if (!blank(pythonPath)) {
            python.put("pythonPath", pythonPath);
        }
        return python;
    }

    /** The one {@code python.analysis} object, so the push and both answer shapes cannot drift apart. */
    private static Map<String, Object> pythonAnalysis() {
        Map<String, Object> analysis = new HashMap<>();
        analysis.put("autoImportCompletions", true);
        analysis.put("autoSearchPaths", true); // Pyright's own default, lost once this object exists
        return analysis;
    }

    /** The {@code java} object pushed to jdtls; {@code LspManager} sends the same keys at {@code initialize}. */
    static Map<String, Object> javaSettings(boolean onTypeFormatting) {
        // jdtls ADVERTISES signatureHelpProvider but its handler returns an empty result unless
        // `java.signatureHelp.enabled` is set — it ships OFF (VS Code's Java extension sets it in its
        // own defaults, which is why it "just works" there). Verified by driving a real jdtls: same
        // position, same params — 0 signatures before this flag, both overloads after (#674). Same
        // class of bug as #468's provideFormatter.
        Map<String, Object> signatureHelp = new HashMap<>();
        signatureHelp.put("enabled", true);
        // The Javadoc in the popup. jdtls reads `java.signatureHelp.description.enabled`; a bare
        // `description: true` resolved to no key at all, so the description never came (Java review A2).
        signatureHelp.put("description", Map.of("enabled", true));
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
        // Code lenses are computed only when a client asks for them, and Editora asks only while its own
        // setting is on — so the server side stays enabled. Without the second key jdtls has no
        // implementations lens at all.
        java.put("referencesCodeLens", Map.of("enabled", true));
        java.put("implementationCodeLens", "all");
        // The rest are preferences vscode-java sends from its own defaults, which jdtls's defaults assume a
        // client provides (Java review A9, A10, A15, B3). Each was verified against a real jdtls by
        // JdtlsClientSettingsProbeTest.
        //
        // jdtls holds a default type filter but installs it only when the client sends one: without it
        // JDK internals (sun.*, com.sun.*, jdk.internal.*) filled completion and the import quick fixes.
        java.put("completion", Map.of("filteredTypes", FILTERED_TYPES));
        // Inlay hints are requested only while Settings.inlayHints is on, so the server side can be generous:
        // every parameter name (InlayHintFilter applies Editora's literals/all mode on top — jdtls's own
        // default, "literals", made the "all" mode a no-op), and the inferred types of `var` and lambda
        // parameters, which jdtls leaves off by default.
        Map<String, Object> inlayHints = new HashMap<>();
        inlayHints.put("parameterNames", Map.of("enabled", "all", "suppressWhenSameNameNumbered", true));
        inlayHints.put("variableTypes", Map.of("enabled", true));
        inlayHints.put("parameterTypes", Map.of("enabled", true));
        java.put("inlayHints", inlayHints);
        // workspace/symbol answers types only unless asked for methods too.
        java.put("symbols", Map.of("includeSourceMethodDeclarations", true));
        // A pom.xml or build.gradle change reaches the classpath on its own. jdtls's default, "interactive",
        // asks a question per change, and a pom edit never took effect until it was answered (Java review C1).
        java.put("configuration", Map.of("updateBuildConfiguration", "automatic"));
        return java;
    }

    /** vscode-java's {@code java.completion.filteredTypes} default: types never offered or imported. */
    static final java.util.List<String> FILTERED_TYPES = java.util.List.of(
            "java.awt.*",
            "com.sun.*",
            "sun.*",
            "jdk.*",
            "org.graalvm.*",
            "io.micrometer.shaded.*",
            "java.beans.*",
            "java.util.Hashtable",
            "java.util.Vector");

    /**
     * jdtls's {@code workspace/configuration} question for a document's indentation, asked before every code
     * action so that quick fixes and generated members are formatted like the file (Java review B8, D9): the
     * answer, or null when {@code section} is not one of those two keys or the document's indent is unknown.
     */
    static @Nullable Object javaFormatAnswer(String section, @Nullable FormattingIndent indent) {
        if (indent == null) {
            return null;
        }
        return switch (section == null ? "" : section) {
            case "java.format.tabSize" -> indent.tabSize();
            case "java.format.insertSpaces" -> indent.insertSpaces();
            default -> null;
        };
    }
}
