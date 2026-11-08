package com.editora.dap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Maps an editor language id (as resolved by {@code editor.LanguageRegistry.forFileName}) to a <b>debug
 * adapter</b> spec — its transport {@link Kind}, the DAP {@code launch} request {@code type}, the
 * {@code initialize} adapter id, and (for the standalone adapters) the default interpreter + adapter
 * arguments. Mirrors {@code LspServerRegistry}, but debug-adapter-centric.
 *
 * <p>Six transports:
 * <ul>
 *   <li><b>java</b> → {@link Kind#JDTLS}: the Microsoft java-debug adapter is started <em>inside</em> jdtls
 *       via {@code workspace/executeCommand} (which returns a socket port); the registry carries no command
 *       (the {@link DapManager} drives the jdtls flow).</li>
 *   <li><b>python</b> → {@link Kind#STDIO}: {@code <python> -m debugpy.adapter}, DAP over the process's
 *       stdin/stdout; launch {@code type="python"}.</li>
 *   <li><b>javascript</b> → {@link Kind#SOCKET}: {@code node <dapDebugServer.js> <port>}, DAP over a TCP
 *       socket; launch {@code type="pwa-node"}.</li>
 *   <li><b>groovy</b>, <b>kotlin</b>, <b>clojure</b> → {@link Kind#JVM}: Editora's own JDI adapter, in-process;
 *       nothing to install.</li>
 *   <li><b>scala</b> → {@link Kind#LANGUAGE_SERVER}: Metals starts its adapter on request
 *       ({@link HostedDebugAdapter}); a file outside an sbt, Mill, Gradle or Maven build is run by Scala CLI
 *       under Editora's JVM adapter instead ({@link #fallbackKind}).</li>
 *   <li>{@link Kind#EXTERNAL}: an adapter that is its own program ({@link ExternalAdapter}), started by the
 *       generic path; none of the languages above, which keep the flows their adapters need.</li>
 * </ul>
 *
 * <p>All methods are static + pure (no process launch, no I/O), so they are unit-testable. The
 * {@link DapManager} assembles the final argv from {@link DapServerSpec} (interpreter/entry + located
 * adapter path + port). The languages themselves are {@link DebugLanguages}' profiles: adding one (Go's Delve,
 * CodeLLDB) is one more {@link DebugLanguage}, an {@link Kind#EXTERNAL} one when the adapter is its own program.
 */
public final class DapServerRegistry {

    /** Adapter transport: jdtls-hosted (java), a stdio subprocess (debugpy), or a socket server (js-debug). */
    public enum Kind {
        JDTLS,
        STDIO,
        SOCKET,
        /** Editora's own JDI adapter ({@code com.editora.dap.jvm}), served in-process on a loopback socket. */
        JVM,
        /**
         * An adapter the language server starts when asked ({@link HostedDebugAdapter}): Metals'
         * {@code debug-adapter-start} compiles the build target and answers a socket to connect to.
         */
        LANGUAGE_SERVER,
        /**
         * An adapter that is its own program, started by its command and reached over its standard streams or a port
         * ({@link ExternalAdapter}): Delve, CodeLLDB, {@code lldb-dap}. Its profile says how.
         */
        EXTERNAL
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

    /** The launch {@code type} and adapter id of Editora's own JVM adapter. */
    public static final String JVM_LAUNCH_TYPE = "editora-jvm";

    /** Default interpreter when the user leaves the Settings command field blank. */
    public static final String DEFAULT_PYTHON_INTERPRETER = "python3";

    public static final String DEFAULT_NODE_INTERPRETER = "node";

    private DapServerRegistry() {}

    /** The adapter languages ({@code java}, {@code python}, {@code javascript}, …). */
    public static Set<String> languageIdsForDebug() {
        return DebugLanguages.ids();
    }

    /**
     * The transport a {@link Kind#LANGUAGE_SERVER} language falls back to for a file its server does not debug
     * (a Scala script outside any build: Editora's {@link Kind#JVM} adapter over Scala CLI), or null.
     */
    public static @Nullable Kind fallbackKind(String languageId) {
        DebugLanguage l = DebugLanguages.byId(languageId);
        return l == null ? null : l.fallbackKind();
    }

    /**
     * The adapter language serving editor language {@code languageId} — {@code javascript} for TypeScript and
     * JSX/TSX files too — or {@code languageId} itself when no adapter serves it. Settings, exception choices and
     * messages are kept per adapter language.
     */
    public static String adapterLanguage(String languageId) {
        DebugLanguage l = DebugLanguages.forLanguage(languageId);
        return l == null ? languageId : l.id();
    }

    /** Whether a debug adapter is registered for {@code languageId}. */
    public static boolean isDebuggable(String languageId) {
        return DebugLanguages.forLanguage(languageId) != null;
    }

    /** The transport kind for {@code languageId}, or {@code null} when unsupported. */
    public static @Nullable Kind kindFor(String languageId) {
        DebugLanguage l = DebugLanguages.forLanguage(languageId);
        return l == null ? null : l.kind();
    }

    /** The full spec for {@code languageId}, or {@code null} when unsupported. */
    public static @Nullable DapServerSpec specFor(String languageId) {
        DebugLanguage l = DebugLanguages.forLanguage(languageId);
        return l == null ? null : l.spec();
    }

    /**
     * The interpreter argv that runs the adapter for {@code languageId}: the configured {@code command}
     * (tokenized, honoring quotes) when non-blank, else the language's default interpreter. Empty for an
     * unsupported language or one without a standalone interpreter (java). For python this is the
     * interpreter only (without {@code -m debugpy.adapter} — see {@link DapServerSpec#adapterArgs}).
     */
    public static List<String> interpreterArgv(String languageId, String command) {
        DebugLanguage l = DebugLanguages.forLanguage(languageId);
        if (l == null || l.defaultInterpreter().isEmpty()) {
            return List.of();
        }
        String cmd = command == null || command.isBlank() ? l.defaultInterpreter() : command;
        return com.editora.run.ProgramArgs.commandLine(cmd);
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
