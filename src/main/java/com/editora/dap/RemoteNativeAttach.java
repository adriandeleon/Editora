package com.editora.dap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Attaching to a native program on another machine (or in a container, on a board) through a debug server there —
 * {@code gdbserver host:port program} or {@code lldb-server gdbserver host:port program} — with a copy of the program
 * here for its symbols and sources, as VS Code's {@code cppdbg} does with {@code miDebuggerServerAddress} and
 * CodeLLDB's {@code gdb-remote}. Probed with gdbserver 16.3 against GDB 16.3's DAP mode and CodeLLDB: the program is
 * held before its first instruction; GDB reports that as a stop with reason {@code attach}, CodeLLDB as a
 * {@code SIGTRAP} exception in the loader (sometimes twice); breakpoints bind once the symbols load and stop as
 * anywhere else. Pure.
 */
public final class RemoteNativeAttach {

    private RemoteNativeAttach() {}

    /** The port a debug server is usually started on ({@code gdbserver :2345 program}). */
    public static final int DEFAULT_PORT = 2345;

    /** GDB's DAP {@code attach} to the server at {@code address} ({@code host:port}), {@code program} for symbols. */
    public static Map<String, Object> gdb(String program, String address) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "gdb");
        m.put("request", "attach");
        m.put("program", program);
        m.put("target", address);
        return m;
    }

    /**
     * CodeLLDB's request for the server at {@code address}: {@code program} made the target, then {@code gdb-remote}
     * connects the process (CodeLLDB's "custom launch").
     */
    public static Map<String, Object> codelldb(String program, String address, boolean rust) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "lldb");
        m.put("request", "launch");
        m.put("targetCreateCommands", List.of("target create " + quoted(program)));
        m.put("processCreateCommands", List.of("gdb-remote " + address));
        m.put("sourceLanguages", List.of(rust ? "rust" : "cpp"));
        return m;
    }

    /**
     * Whether {@code request} attaches to a debug server: GDB's {@code attach} with a {@code target}, or CodeLLDB's
     * {@code gdb-remote}. Its first stops, in the loader before any code with sources, are passed over: the program
     * was started stopped by the server, not by a breakpoint.
     */
    public static boolean isRemote(Map<String, Object> request) {
        if ("attach".equals(request.get("request")) && request.get("target") instanceof String t && !t.isBlank()) {
            return true;
        }
        return request.get("processCreateCommands") instanceof List<?> cmds
                && cmds.stream()
                        .anyMatch(c -> c instanceof String s && s.strip().startsWith("gdb-remote"));
    }

    /**
     * {@code host:port} from what was typed — {@code 2345} alone is this machine's port, {@code :2345} too; null when
     * it is not an address.
     */
    public static @Nullable String address(@Nullable String input) {
        String s = input == null ? "" : input.strip();
        if (s.isEmpty() || s.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        int colon = s.lastIndexOf(':');
        String host = colon < 0 ? "localhost" : s.substring(0, colon);
        String port = colon < 0 ? s : s.substring(colon + 1);
        if (host.isEmpty()) {
            host = "localhost";
        }
        try {
            int p = Integer.parseInt(port);
            return p > 0 && p < 65536 ? host + ":" + p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String quoted(String path) {
        return path.contains(" ") ? "\"" + path.replace("\"", "\\\"") + "\"" : path;
    }
}
