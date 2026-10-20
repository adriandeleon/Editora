package com.editora.dap;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * A debug adapter that a language server starts on request ({@link DapServerRegistry.Kind#LANGUAGE_SERVER}): the
 * client sends {@code workspace/executeCommand} with the server's start command, the server compiles what is to
 * be debugged and opens an adapter for it, and answers where to connect. Metals for Scala:
 * {@code debug-adapter-start} with {@code {path, runType}} answers {@code {name, uri: "tcp://127.0.0.1:5005"}}
 * (scalameta.org/metals/docs/integrations/debug-adapter-protocol). The adapter runs the program itself and
 * reports its output as DAP events.
 *
 * <p>jdtls hosts java-debug the same way, but its {@code vscode.java.startDebugSession} answers a bare port and
 * Java's launch is resolved by the client first ({@link DapServerRegistry.Kind#JDTLS}), so it keeps its own path.
 *
 * <p>Pure.
 */
public final class HostedDebugAdapter {

    private HostedDebugAdapter() {}

    /** The command that asks {@code language}'s language server for a debug adapter, or null. */
    public static @Nullable String startCommand(String language) {
        return "scala".equals(language) ? "debug-adapter-start" : null;
    }

    /**
     * The start command's argument for running {@code fileUri}'s program — Metals'
     * {@code DebugDiscoveryParams}: the file, {@code runType} {@code "run"} (its main) or {@code "testFile"} (its
     * tests), and the program's arguments, JVM options and environment.
     */
    public static Map<String, Object> discovery(
            String fileUri, boolean tests, List<String> args, List<String> jvmOptions, Map<String, String> env) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", fileUri);
        params.put("runType", tests ? "testFile" : "run");
        if (args != null && !args.isEmpty()) {
            params.put("args", List.copyOf(args));
        }
        if (jvmOptions != null && !jvmOptions.isEmpty()) {
            params.put("jvmOptions", List.copyOf(jvmOptions));
        }
        if (env != null && !env.isEmpty()) {
            params.put("env", new LinkedHashMap<>(env));
        }
        return params;
    }

    /** Where the server's adapter listens, and the session's name. */
    public record Address(String name, String host, int port) {}

    /**
     * Reads the start command's reply ({@code {name, uri: "tcp://host:port"}}); null when it names no port. A
     * bare port number is accepted too.
     */
    public static @Nullable Address address(Object reply) {
        JsonElement el = JdtlsReplies.asJson(reply instanceof Map<?, ?> m ? LspJson.toJson(m) : reply);
        if (el == null) {
            return null;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            int port = el.getAsInt();
            return port > 0 ? new Address("", "127.0.0.1", port) : null;
        }
        if (!el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String uri = JdtlsReplies.str(o, "uri");
        String name = JdtlsReplies.str(o, "name");
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            URI parsed = URI.create(uri.strip());
            int port = parsed.getPort();
            if (port <= 0) {
                return null;
            }
            String host = parsed.getHost();
            return new Address(
                    name == null ? "" : name,
                    host == null || host.isBlank() || host.equals("0.0.0.0") ? "127.0.0.1" : host,
                    port);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Turns a reply lsp4j decoded to plain collections back into JSON. */
    private static final class LspJson {
        private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

        static String toJson(Map<?, ?> m) {
            return GSON.toJson(m);
        }
    }
}
