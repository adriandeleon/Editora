package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools Editora runs on a remote host, as the Settings → Remote page offers them for one host: the
 * named tools each get a field, language servers share one text box of {@code server = command} lines
 * (stored as {@code lsp.<server>}). A blank field means "whatever the host's {@code PATH} finds". Pure.
 */
final class HostTools {

    /** One named tool: its id in the connection store and the message key of its label. */
    record Tool(String id, String labelKey) {}

    /** The named tools, in the order the page shows them. */
    static final List<Tool> NAMED = List.of(
            new Tool("git", "settings.remote.tool.git"),
            new Tool("gh", "settings.remote.tool.gh"),
            new Tool("rg", "settings.remote.tool.rg"),
            new Tool("jdk.home", "settings.remote.tool.jdkHome"),
            new Tool("node", "settings.remote.tool.node"),
            new Tool("dap.python", "settings.remote.tool.dapPython"),
            new Tool("dap.js", "settings.remote.tool.dapJs"),
            new Tool("dap.java", "settings.remote.tool.dapJava"));

    static final String SERVER_PREFIX = "lsp.";

    private HostTools() {}

    /** The language-server lines for {@code tools}: {@code server = command}, one per line. */
    static String serversText(Map<String, String> tools) {
        StringBuilder out = new StringBuilder();
        tools.forEach((tool, command) -> {
            if (tool.startsWith(SERVER_PREFIX) && tool.length() > SERVER_PREFIX.length()) {
                out.append(out.isEmpty() ? "" : "\n")
                        .append(tool.substring(SERVER_PREFIX.length()))
                        .append(" = ")
                        .append(command);
            }
        });
        return out.toString();
    }

    /**
     * Reads {@code server = command} lines back into {@code lsp.<server>} entries. A line without {@code =},
     * with an empty side, or starting with {@code #} is skipped; a later line for the same server wins.
     */
    static Map<String, String> parseServers(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            int eq = trimmed.indexOf('=');
            if (trimmed.startsWith("#") || eq <= 0) {
                continue;
            }
            String server = trimmed.substring(0, eq).strip();
            String command = trimmed.substring(eq + 1).strip();
            if (!server.isEmpty() && !command.isEmpty() && server.indexOf(' ') < 0) {
                out.put(SERVER_PREFIX + server, command);
            }
        }
        return out;
    }

    /**
     * The whole tool map from the page: the named fields ({@code named}: tool id → text) and the server
     * lines, plus whatever other tool ids {@code previous} held that this page has no field for (kept as
     * they were).
     */
    static Map<String, String> merge(Map<String, String> previous, Map<String, String> named, String serversText) {
        Map<String, String> out = new LinkedHashMap<>();
        previous.forEach((tool, command) -> {
            boolean shown = tool.startsWith(SERVER_PREFIX) || NAMED.stream().anyMatch(t -> t.id().equals(tool));
            if (!shown) {
                out.put(tool, command);
            }
        });
        named.forEach((tool, command) -> {
            if (command != null && !command.isBlank()) {
                out.put(tool, command.strip());
            }
        });
        out.putAll(parseServers(serversText));
        return out;
    }
}
