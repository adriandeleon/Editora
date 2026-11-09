package com.editora.dap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.jspecify.annotations.Nullable;

/**
 * The user's own debug adapters, from {@code debug-adapters.json} (the config folder's, and a trusted project's
 * {@code .editora/debug-adapters.json}): any program that speaks DAP — lldb-dap, netcoredbg, a language's own — started
 * as Editora starts Delve or CodeLLDB. Each says its launch.json {@code type}, its command, how it talks
 * ({@code stdio}; {@code port}, a port Editora gives it as {@code {port}}; {@code announce}, a port it prints, matched by
 * {@code announce}'s first group), the editor languages it debugs, and a {@code launch} template F5 sends (VS Code's
 * variables put in). Pure: {@link #parse} reads one file's text.
 */
public final class CustomAdapters {

    private CustomAdapters() {}

    /** One adapter: its profile, and what F5 sends it ({@code launch}; empty: F5 does not, only launch.json). */
    public record Adapter(DebugLanguage profile, Map<String, Object> launch) {}

    /** What a file holds: its adapters, and why each one it could not read was refused. */
    public record Parsed(List<Adapter> adapters, List<String> problems) {}

    /** The types Editora's own adapters are known by: a user's adapter takes none of them. */
    static final Set<String> BUILT_IN_TYPES =
            Set.of("lldb", "codelldb", "gdb", "cppdbg", "go", "debugpy", "python", "node", "pwa-node", "java");

    /** {@code text}: {@code {"adapters": [...]}} or a bare array of adapters. */
    public static Parsed parse(String text) {
        List<Adapter> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Object root;
        try {
            root = LaunchJson.toJava(
                    com.google.gson.JsonParser.parseString(LaunchJson.withoutCommentsAndTrailingCommas(text)));
        } catch (RuntimeException e) {
            return new Parsed(List.of(), List.of(String.valueOf(e.getMessage())));
        }
        Object list = root instanceof Map<?, ?> m ? m.get("adapters") : root;
        if (!(list instanceof List<?> entries)) {
            return new Parsed(List.of(), List.of("no \"adapters\" list"));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Object e : entries) {
            if (!(e instanceof Map<?, ?> m)) {
                problems.add("an adapter that is not an object");
                continue;
            }
            String problem = problem(m, seen);
            if (problem != null) {
                problems.add(problem);
                continue;
            }
            String type = String.valueOf(m.get("type"));
            seen.add(type);
            out.add(new Adapter(profile(m), launch(m)));
        }
        return new Parsed(out, problems);
    }

    /** Why adapter {@code m} cannot be used, or null. */
    private static @Nullable String problem(Map<?, ?> m, Set<String> seen) {
        if (!(m.get("type") instanceof String type) || type.isBlank()) {
            return "an adapter without a \"type\"";
        }
        if (BUILT_IN_TYPES.contains(type)) {
            return "\"" + type + "\": a type of Editora's own adapters";
        }
        if (seen.contains(type)) {
            return "\"" + type + "\": named twice";
        }
        if (!(m.get("command") instanceof List<?> command)
                || command.isEmpty()
                || !command.stream().allMatch(c -> c instanceof String)) {
            return "\"" + type + "\": \"command\" is a list of strings, the program first";
        }
        String transport = m.get("transport") instanceof String t ? t : "stdio";
        if (!Set.of("stdio", "port", "announce").contains(transport)) {
            return "\"" + type + "\": \"transport\" is stdio, port or announce";
        }
        if (transport.equals("port")
                != command.stream().anyMatch(w -> String.valueOf(w).contains(ExternalAdapter.PORT))) {
            return "\"" + type + "\": a port transport, and only that, has " + ExternalAdapter.PORT + " in its command";
        }
        if (transport.equals("announce")) {
            if (!(m.get("announce") instanceof String pattern)) {
                return "\"" + type + "\": an announce transport needs \"announce\", a pattern whose group is the port";
            }
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                return "\"" + type + "\": \"announce\" is not a pattern: " + e.getDescription();
            }
        }
        return null;
    }

    private static DebugLanguage profile(Map<?, ?> m) {
        String type = String.valueOf(m.get("type"));
        List<String> command = new ArrayList<>();
        if (m.get("command") instanceof List<?> words) {
            words.forEach(c -> command.add(String.valueOf(c)));
        }
        String transport = m.get("transport") instanceof String t ? t : "stdio";
        ExternalAdapter external = new ExternalAdapter(
                command,
                switch (transport) {
                    case "port" -> ExternalAdapter.Transport.PORT_ARGUMENT;
                    case "announce" -> ExternalAdapter.Transport.ANNOUNCED_PORT;
                    default -> ExternalAdapter.Transport.STDIO;
                },
                transport.equals("announce") ? Pattern.compile(String.valueOf(m.get("announce"))) : null);
        Set<String> languages = new LinkedHashSet<>();
        if (m.get("languages") instanceof List<?> l) {
            l.forEach(x -> languages.add(String.valueOf(x)));
        }
        String name = m.get("name") instanceof String n && !n.isBlank() ? n : type;
        return new DebugLanguage(
                type,
                name,
                languages,
                DapServerRegistry.Kind.EXTERNAL,
                type,
                m.get("adapterID") instanceof String id && !id.isBlank() ? id : type,
                "",
                List.of(),
                null,
                DebugLanguage.Availability.LOCATED,
                List.of(),
                null,
                null,
                false,
                "debug.error.customUnavailable",
                external);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> launch(Map<?, ?> m) {
        return m.get("launch") instanceof Map<?, ?> l ? new LinkedHashMap<>((Map<String, Object>) l) : Map.of();
    }

    /** The adapters of several files, the later ones (a project's) replacing earlier ones of the same type. */
    public static List<Adapter> merge(List<List<Adapter>> files) {
        Map<String, Adapter> byType = new LinkedHashMap<>();
        for (List<Adapter> file : files) {
            for (Adapter a : file) {
                byType.put(a.profile().id(), a);
            }
        }
        return List.copyOf(byType.values());
    }

    /** An example file, written when the user first opens theirs. */
    public static final String EXAMPLE = """
            {
              // Debug adapters of your own: any program that speaks the Debug Adapter Protocol.
              // "type" is the name a .vscode/launch.json configuration uses; "languages" are the files F5 debugs with it.
              "adapters": [
                // {
                //   "type": "lldb-dap",
                //   "name": "LLDB (lldb-dap)",
                //   "command": ["lldb-dap"],
                //   "transport": "stdio",              // or "port" ({port} in the command), or "announce"
                //   "languages": ["zig"],
                //   "launch": { "program": "${fileDirname}/${fileBasenameNoExtension}", "cwd": "${fileDirname}" }
                // }
              ]
            }
            """;
}
