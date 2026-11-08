package com.editora.dap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * How a debug adapter that is its own program is started and spoken to: Delve ({@code dlv dap}), CodeLLDB,
 * {@code lldb-dap}, GDB's DAP mode, netcoredbg. Pure: it builds the command and reads the adapter's announcement;
 * {@link DapManager} starts the process and connects.
 *
 * @param command the adapter's command, its executable first; {@value #PORT} stands for the port Editora picks when
 *     the transport is {@link Transport#PORT_ARGUMENT}
 * @param transport how DAP reaches the adapter
 * @param announcement for {@link Transport#ANNOUNCED_PORT}: the pattern of the line the adapter prints once it
 *     listens, whose first group is the port (Delve: {@code DAP server listening at: 127.0.0.1:38697}); null
 *     otherwise
 */
public record ExternalAdapter(
        List<String> command, Transport transport, @Nullable Pattern announcement) {

    /** Stands for the port in a {@link Transport#PORT_ARGUMENT} command. */
    public static final String PORT = "{port}";

    /** How DAP reaches the adapter. */
    public enum Transport {
        /** Over the adapter process's standard input and output (lldb-dap, {@code gdb -i dap}, netcoredbg). */
        STDIO,
        /** On a port Editora picks and passes in the command ({@value #PORT}), then connects to. */
        PORT_ARGUMENT,
        /** On a port the adapter picks and prints ({@link #announcement}), then connects to (Delve). */
        ANNOUNCED_PORT
    }

    public ExternalAdapter {
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("an adapter needs a command");
        }
        if ((transport == Transport.ANNOUNCED_PORT) != (announcement != null)) {
            throw new IllegalArgumentException("an announced port, and only that, needs the announcement's pattern");
        }
        if ((transport == Transport.PORT_ARGUMENT) != command.contains(PORT)) {
            throw new IllegalArgumentException("a port argument, and only that, has " + PORT + " in its command");
        }
    }

    /**
     * The command to start, with {@code executable} (a configured or located path; blank keeps the command's own)
     * in place of the first word and {@code port} in place of {@value #PORT}.
     */
    public List<String> argv(@Nullable String executable, int port) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < command.size(); i++) {
            String word = command.get(i);
            if (i == 0 && executable != null && !executable.isBlank()) {
                word = executable;
            }
            out.add(word.replace(PORT, String.valueOf(port)));
        }
        return out;
    }

    /** The port in {@code line} of the adapter's output when it is the announcement, else -1. */
    public int announcedPort(@Nullable String line) {
        if (announcement == null || line == null) {
            return -1;
        }
        Matcher m = announcement.matcher(line);
        if (!m.find()) {
            return -1;
        }
        try {
            int port = Integer.parseInt(m.group(1));
            return port > 0 && port < 65536 ? port : -1;
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return -1;
        }
    }

    /**
     * A {@code launch} request for a program an external adapter runs: {@code program} with its arguments, folder,
     * environment and stop-on-entry, under the adapter's {@code type} — the fields the DAP adapters this path serves
     * read alike ({@code program}, {@code args}, {@code cwd}, {@code env}, {@code stopOnEntry}). A language adds its own
     * (Delve's {@code mode}) to {@code extra}, which wins.
     */
    public static Map<String, Object> launchRequest(
            String type,
            String program,
            List<String> args,
            @Nullable String cwd,
            Map<String, String> env,
            boolean stopOnEntry,
            Map<String, Object> extra) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", type);
        m.put("request", "launch");
        m.put("program", program);
        m.put("args", List.copyOf(args));
        if (cwd != null && !cwd.isBlank()) {
            m.put("cwd", cwd);
        }
        if (!env.isEmpty()) {
            m.put("env", Map.copyOf(env));
        }
        m.put("stopOnEntry", stopOnEntry);
        m.putAll(extra);
        return m;
    }
}
