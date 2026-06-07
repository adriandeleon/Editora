package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps an editor language id (as resolved by {@code editor.LanguageRegistry.forFileName}) to a
 * <b>server</b> — its id, launch command, and project-root markers. The registry is server-centric, not
 * language-centric: one server can serve several language ids (e.g. the TypeScript server handles
 * {@code javascript}/{@code javascriptreact}/{@code typescript}/{@code typescriptreact}), so the
 * {@link LspManager} keys a single session per {@code (serverId, root)} and all four share one process.
 *
 * <p>Phase 1 ships two servers — <b>Java</b> (Eclipse JDT LS) and <b>TypeScript</b>
 * (typescript-language-server, which also covers JavaScript/JSX/TSX). Commands are user-configurable
 * (Settings) and never bundled. All methods are static + pure (no process launch, no I/O) so they are
 * unit-testable. Adding a server later = one more {@link ServerDef} entry.
 */
public final class LspServerRegistry {

    /** A resolved server launch spec: which server, the argv to launch, and its root markers. */
    public record ServerSpec(String serverId, List<String> command, List<String> rootMarkers) {
    }

    /** Build files (and {@code .git}) that mark a Java project root, nearest-first when walking up. */
    public static final List<String> JAVA_ROOT_MARKERS = List.of(
            "pom.xml", "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts", ".git");

    /** Markers for a JS/TS project root (a tsconfig/jsconfig wins, else package.json, else the repo). */
    public static final List<String> TS_ROOT_MARKERS = List.of(
            "tsconfig.json", "jsconfig.json", "package.json", ".git");

    /** Markers for a Python project root (pyproject/setup/requirements/Pipfile, else the repo). */
    public static final List<String> PYTHON_ROOT_MARKERS = List.of(
            "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile", ".git");

    /** Default server commands when the user leaves the Settings field blank. */
    public static final String DEFAULT_JAVA_COMMAND = "jdtls";
    public static final String DEFAULT_TYPESCRIPT_COMMAND = "typescript-language-server --stdio";
    public static final String DEFAULT_PYTHON_COMMAND = "pyright-langserver --stdio";

    /** A known language server: its id, default command, root markers, and the language ids it serves. */
    private enum ServerDef {
        JAVA("java", DEFAULT_JAVA_COMMAND, JAVA_ROOT_MARKERS, Set.of("java")),
        TYPESCRIPT("typescript", DEFAULT_TYPESCRIPT_COMMAND, TS_ROOT_MARKERS,
                Set.of("javascript", "javascriptreact", "typescript", "typescriptreact")),
        PYTHON("python", DEFAULT_PYTHON_COMMAND, PYTHON_ROOT_MARKERS, Set.of("python"));

        final String id;
        final String defaultCommand;
        final List<String> rootMarkers;
        final Set<String> languageIds;

        ServerDef(String id, String defaultCommand, List<String> rootMarkers, Set<String> languageIds) {
            this.id = id;
            this.defaultCommand = defaultCommand;
            this.rootMarkers = rootMarkers;
            this.languageIds = languageIds;
        }
    }

    private LspServerRegistry() {
    }

    private static ServerDef defFor(String languageId) {
        for (ServerDef d : ServerDef.values()) {
            if (d.languageIds.contains(languageId)) {
                return d;
            }
        }
        return null;
    }

    /** The server id that serves {@code languageId} (e.g. {@code typescript} for {@code javascript}), or null. */
    public static String serverIdFor(String languageId) {
        ServerDef d = defFor(languageId);
        return d == null ? null : d.id;
    }

    /** Whether any registered server serves {@code languageId}. */
    public static boolean isSupported(String languageId) {
        return defFor(languageId) != null;
    }

    /** Project-root markers for the server serving {@code languageId} (empty if unsupported). */
    public static List<String> rootMarkersFor(String languageId) {
        ServerDef d = defFor(languageId);
        return d == null ? List.of() : d.rootMarkers;
    }

    /** The default command for a server id (blank if unknown). */
    public static String defaultCommandFor(String serverId) {
        for (ServerDef d : ServerDef.values()) {
            if (d.id.equals(serverId)) {
                return d.defaultCommand;
            }
        }
        return "";
    }

    /**
     * The launch spec for {@code languageId}, resolving the server's command from {@code commands}
     * (serverId → configured command; a blank/absent entry uses the server's default), or {@code null}
     * when the language is unsupported.
     */
    public static ServerSpec specFor(String languageId, Map<String, String> commands) {
        ServerDef d = defFor(languageId);
        if (d == null) {
            return null;
        }
        String configured = commands == null ? null : commands.get(d.id);
        String cmd = configured == null || configured.isBlank() ? d.defaultCommand : configured;
        return new ServerSpec(d.id, tokenize(cmd), d.rootMarkers);
    }

    /** The tokenized argv for {@code serverId} from {@code commands} (blank ⇒ default), or empty if unknown. */
    public static List<String> commandFor(String serverId, Map<String, String> commands) {
        String configured = commands == null ? null : commands.get(serverId);
        String def = defaultCommandFor(serverId);
        if (def.isEmpty() && (configured == null || configured.isBlank())) {
            return List.of();
        }
        return tokenize(configured == null || configured.isBlank() ? def : configured);
    }

    /**
     * Splits a command string into argv on unquoted whitespace, honoring single/double quotes so a path
     * with spaces stays one token. Pure; mirrors the tokenizing done for the mmdc/maid commands.
     */
    public static List<String> tokenize(String command) {
        List<String> out = new ArrayList<>();
        if (command == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean has = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                has = true;
            } else if (Character.isWhitespace(c)) {
                if (has) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    has = false;
                }
            } else {
                cur.append(c);
                has = true;
            }
        }
        if (has) {
            out.add(cur.toString());
        }
        return out;
    }
}
