package com.editora.dap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Maps an editor language id (as resolved by {@code editor.LanguageRegistry.forFileName}) to a <b>debug
 * adapter</b> spec — its transport {@link Kind}, the DAP {@code launch} request {@code type}, the
 * {@code initialize} adapter id, and (for the standalone adapters) the default interpreter + adapter
 * arguments. Mirrors {@code LspServerRegistry}, but debug-adapter-centric.
 *
 * <p>Three transports:
 * <ul>
 *   <li><b>java</b> → {@link Kind#JDTLS}: the Microsoft java-debug adapter is started <em>inside</em> jdtls
 *       via {@code workspace/executeCommand} (which returns a socket port); the registry carries no command
 *       (the {@link DapManager} drives the jdtls flow).</li>
 *   <li><b>python</b> → {@link Kind#STDIO}: {@code <python> -m debugpy.adapter}, DAP over the process's
 *       stdin/stdout; launch {@code type="python"}.</li>
 *   <li><b>javascript</b> → {@link Kind#SOCKET}: {@code node <dapDebugServer.js> <port>}, DAP over a TCP
 *       socket; launch {@code type="pwa-node"}.</li>
 * </ul>
 *
 * <p>All methods are static + pure (no process launch, no I/O), so they are unit-testable. The
 * {@link DapManager} assembles the final argv from {@link DapServerSpec} (interpreter/entry + located
 * adapter path + port). Adding a language later (e.g. Go delve, Ruby) = one more {@link Def} entry.
 */
public final class DapServerRegistry {

    /** Adapter transport: jdtls-hosted (java), a stdio subprocess (debugpy), or a socket server (js-debug). */
    public enum Kind {
        JDTLS,
        STDIO,
        SOCKET
    }

    /**
     * A resolved debug-adapter spec. {@code launchType} is the DAP {@code launch} request {@code type};
     * {@code adapterId} is the {@code initialize} {@code adapterID}. For {@link Kind#STDIO}/{@link Kind#SOCKET},
     * {@code defaultInterpreter} is the runtime that runs the adapter (e.g. {@code python3}/{@code node}) and
     * {@code adapterArgs} are the fixed args after it (e.g. {@code -m debugpy.adapter}); the located adapter
     * entry + port (js) are appended by the caller.
     */
    public record DapServerSpec(
            String language,
            Kind kind,
            String launchType,
            String adapterId,
            String defaultInterpreter,
            List<String> adapterArgs) {}

    /** Default interpreter when the user leaves the Settings command field blank. */
    public static final String DEFAULT_PYTHON_INTERPRETER = "python3";

    public static final String DEFAULT_NODE_INTERPRETER = "node";

    /** Per-language debug-adapter definitions (the served editor language ids → adapter transport/launch). */
    private enum Def {
        JAVA("java", Kind.JDTLS, "java", "java", "", List.of(), Set.of("java")),
        PYTHON(
                "python",
                Kind.STDIO,
                "python",
                "python",
                DEFAULT_PYTHON_INTERPRETER,
                List.of("-m", "debugpy.adapter"),
                Set.of("python")),
        JAVASCRIPT(
                "javascript",
                Kind.SOCKET,
                "pwa-node",
                "pwa-node",
                DEFAULT_NODE_INTERPRETER,
                List.of(),
                Set.of("javascript"));

        final String language;
        final Kind kind;
        final String launchType;
        final String adapterId;
        final String defaultInterpreter;
        final List<String> adapterArgs;
        final Set<String> languageIds;

        Def(
                String language,
                Kind kind,
                String launchType,
                String adapterId,
                String defaultInterpreter,
                List<String> adapterArgs,
                Set<String> languageIds) {
            this.language = language;
            this.kind = kind;
            this.launchType = launchType;
            this.adapterId = adapterId;
            this.defaultInterpreter = defaultInterpreter;
            this.adapterArgs = adapterArgs;
            this.languageIds = languageIds;
        }
    }

    private DapServerRegistry() {}

    private static Def defFor(String languageId) {
        if (languageId == null) {
            return null;
        }
        for (Def d : Def.values()) {
            if (d.languageIds.contains(languageId)) {
                return d;
            }
        }
        return null;
    }

    /** The editor language ids that can be debugged ({@code java}, {@code python}, {@code javascript}). */
    public static Set<String> languageIdsForDebug() {
        return Set.of("java", "python", "javascript");
    }

    /** Whether a debug adapter is registered for {@code languageId}. */
    public static boolean isDebuggable(String languageId) {
        return defFor(languageId) != null;
    }

    /** The transport kind for {@code languageId}, or {@code null} when unsupported. */
    public static Kind kindFor(String languageId) {
        Def d = defFor(languageId);
        return d == null ? null : d.kind;
    }

    /** The full spec for {@code languageId}, or {@code null} when unsupported. */
    public static DapServerSpec specFor(String languageId) {
        Def d = defFor(languageId);
        if (d == null) {
            return null;
        }
        return new DapServerSpec(d.language, d.kind, d.launchType, d.adapterId, d.defaultInterpreter, d.adapterArgs);
    }

    /**
     * The interpreter argv that runs the adapter for {@code languageId}: the configured {@code command}
     * (tokenized, honoring quotes) when non-blank, else the language's default interpreter. Empty for an
     * unsupported language or one without a standalone interpreter (java). For python this is the
     * interpreter only (without {@code -m debugpy.adapter} — see {@link DapServerSpec#adapterArgs}).
     */
    public static List<String> interpreterArgv(String languageId, String command) {
        Def d = defFor(languageId);
        if (d == null || d.defaultInterpreter.isEmpty()) {
            return List.of();
        }
        String cmd = command == null || command.isBlank() ? d.defaultInterpreter : command;
        return tokenize(cmd);
    }

    /**
     * Splits a command string into argv on unquoted whitespace, honoring single/double quotes so a path
     * with spaces stays one token. Pure; identical to {@code LspServerRegistry.tokenize}.
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
