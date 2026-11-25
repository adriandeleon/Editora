package com.editora.dap;

import java.nio.file.Path;
import java.util.ArrayList;
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
        return gdb(program, address, Map.of());
    }

    /**
     * {@link #gdb(String, String)} with {@code sourceMap} (the folder the program was built in over there → the one
     * here): kept as {@value #SOURCE_FILE_MAP}, which {@link DapManager} turns into GDB's {@code substitute-path}
     * ({@link #gdbSubstitutions}) when it starts GDB.
     */
    public static Map<String, Object> gdb(String program, String address, Map<String, String> sourceMap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "gdb");
        m.put("request", "attach");
        m.put("program", program);
        m.put("target", address);
        if (!sourceMap.isEmpty()) {
            m.put(SOURCE_FILE_MAP, sourceMap);
        }
        return m;
    }

    /**
     * CodeLLDB's request for the server at {@code address}: {@code program} made the target, then {@code gdb-remote}
     * connects the process (CodeLLDB's "custom launch").
     */
    public static Map<String, Object> codelldb(String program, String address, boolean rust) {
        return codelldb(program, address, rust, Map.of());
    }

    /** {@link #codelldb(String, String, boolean)} with {@code sourceMap} as CodeLLDB's own {@code sourceMap}. */
    public static Map<String, Object> codelldb(
            String program, String address, boolean rust, Map<String, String> sourceMap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "lldb");
        m.put("request", "launch");
        m.put("targetCreateCommands", List.of("target create " + quoted(program)));
        m.put("processCreateCommands", List.of("gdb-remote " + address));
        m.put("sourceLanguages", List.of(rust ? "rust" : "cpp"));
        if (!sourceMap.isEmpty()) {
            m.put("sourceMap", sourceMap);
        }
        return m;
    }

    /**
     * The request field that maps where a program was built to where its sources are here: Microsoft's {@code cppdbg}
     * {@code sourceFileMap}, which a GDB request also carries in Editora (GDB's DAP mode has no such field: it becomes
     * {@link #gdbSubstitutions} on GDB's command line).
     */
    public static final String SOURCE_FILE_MAP = "sourceFileMap";

    /**
     * A {@code sourceFileMap} read: each compiled path to its path here, in order — a string, or {@code cppdbg}'s object
     * with an {@code editorPath}. Blank entries are left out; anything else is an empty map.
     */
    public static Map<String, String> sourceFileMap(@Nullable Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((from, to) -> {
                Object local = to instanceof Map<?, ?> o ? o.get("editorPath") : to;
                if (from instanceof String f && !f.isBlank() && local instanceof String l && !l.isBlank()) {
                    out.put(f, l);
                }
            });
        }
        return out;
    }

    /**
     * GDB's arguments that rewrite each compiled path to its path here before it reads the program's symbols
     * ({@code -iex "set substitute-path FROM TO"}), so breakpoints set on the files here bind and stops name them.
     * Probed with GDB 16.3 over gdbserver: without it a breakpoint in a copy of the sources never binds.
     */
    public static List<String> gdbSubstitutions(Map<String, String> sourceMap) {
        List<String> out = new ArrayList<>();
        sourceMap.forEach((from, to) -> {
            out.add("-iex");
            out.add("set substitute-path " + gdbQuoted(from) + " " + gdbQuoted(to));
        });
        return out;
    }

    /**
     * The map for a program built from {@code remoteRoot} over there and debugged with its sources in
     * {@code localRoot}: empty when no folder was given, or it is the same.
     */
    public static Map<String, String> folderMap(@Nullable Path localRoot, @Nullable String remoteRoot) {
        String remote = remoteRoot == null ? "" : remoteRoot.strip();
        if (localRoot == null || remote.isEmpty() || remote.equals(localRoot.toString())) {
            return Map.of();
        }
        return Map.of(remote, localRoot.toString());
    }

    /** {@code s} as one GDB command argument: double quotes, a backslash or quote inside escaped. */
    static String gdbQuoted(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
