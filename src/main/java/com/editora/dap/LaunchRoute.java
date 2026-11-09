package com.editora.dap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Which debugger runs a launch.json configuration, and what it is sent: the configuration as VS Code would send it,
 * with the {@code type} it is known by in VS Code mapped to Editora's adapter for it, and the fields only VS Code itself
 * reads (its tasks, presentation, console) left out. Microsoft's C/C++ {@code cppdbg} is told in GDB's or CodeLLDB's
 * own fields. Pure.
 */
public final class LaunchRoute {

    private LaunchRoute() {}

    /** The debugger a configuration goes to. */
    public enum Kind {
        /** CodeLLDB ({@code lldb}; {@code cppdbg} with {@code MIMode: lldb}). */
        CODELLDB,
        /** GDB's DAP mode ({@code gdb}; {@code cppdbg}). */
        GDB,
        /** Delve ({@code go}). */
        DELVE,
        /** debugpy ({@code debugpy}, {@code python}). */
        DEBUGPY,
        /** vscode-js-debug ({@code node}, {@code pwa-node}). */
        JS_DEBUG,
        /** java-debug, through a run configuration ({@code java}). */
        JAVA,
        /** An adapter of the user's ({@link CustomAdapters}). */
        CUSTOM,
        /** None: {@link Route#reason} says why. */
        UNSUPPORTED
    }

    /**
     * Where a configuration goes and what it is sent: {@code profile} (null for Java and unsupported), the request
     * ({@code launch} or {@code attach}) with its arguments, and for an unsupported one why.
     */
    public record Route(
            Kind kind, @Nullable DebugLanguage profile, Map<String, Object> request, boolean attach, String reason) {}

    /** Fields VS Code reads itself and adapters need not see. */
    static final Set<String> VS_CODE_ONLY = Set.of(
            "name",
            "preLaunchTask",
            "postDebugTask",
            "presentation",
            "internalConsoleOptions",
            "console",
            "serverReadyAction",
            "debugServer",
            "osx",
            "linux",
            "windows");

    /** The route of {@code body} (a substituted configuration), with {@code custom} adapters known by their type. */
    public static Route of(Map<String, Object> body, Map<String, DebugLanguage> custom) {
        String type = text(body.get("type"));
        boolean attach = "attach".equals(text(body.get("request")));
        Map<String, Object> request = without(body);
        switch (type) {
            case "lldb", "codelldb" -> {
                boolean rust = body.containsKey("cargo") || containsString(body.get("sourceLanguages"), "rust");
                request.put("type", "lldb");
                return new Route(Kind.CODELLDB, rust ? DebugLanguages.RUST : DebugLanguages.CPP, request, attach, "");
            }
            case "gdb" -> {
                return new Route(Kind.GDB, DebugLanguages.GDB, gdbRequest(body, attach), attach, "");
            }
            case "cppdbg" -> {
                if ("lldb".equals(text(body.get("MIMode")))) {
                    return new Route(Kind.CODELLDB, DebugLanguages.CPP, codelldbFromCppdbg(body, attach), attach, "");
                }
                return new Route(Kind.GDB, DebugLanguages.GDB, gdbRequest(body, attach), attach, "");
            }
            case "go" -> {
                if ("remote".equals(text(body.get("mode")))) {
                    return unsupported(body, "remote Delve (mode: remote)");
                }
                request.put("type", "go");
                return new Route(Kind.DELVE, DebugLanguages.GO, request, attach, "");
            }
            case "debugpy", "python" -> {
                request.put("type", "python");
                return new Route(Kind.DEBUGPY, DebugLanguages.PYTHON, request, attach, "");
            }
            case "node", "pwa-node" -> {
                request.put("type", "pwa-node");
                return new Route(Kind.JS_DEBUG, DebugLanguages.JAVASCRIPT, request, attach, "");
            }
            case "java" -> {
                if (attach) {
                    return unsupported(body, "a Java attach from launch.json (use Debug: Attach)");
                }
                return new Route(Kind.JAVA, DebugLanguages.JAVA, request, false, "");
            }
            default -> {
                DebugLanguage own = custom.get(type);
                if (own != null) {
                    return new Route(Kind.CUSTOM, own, request, attach, "");
                }
                return unsupported(body, type.isEmpty() ? "a configuration without a type" : "type \"" + type + "\"");
            }
        }
    }

    private static Route unsupported(Map<String, Object> body, String what) {
        return new Route(Kind.UNSUPPORTED, null, without(body), false, what);
    }

    /** {@code body} without what only VS Code reads. */
    static Map<String, Object> without(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>(body);
        VS_CODE_ONLY.forEach(out::remove);
        return out;
    }

    /**
     * GDB's DAP request: {@code program}, {@code args}, {@code cwd}, {@code env} (an object; {@code cppdbg}'s
     * {@code environment} list too), and {@code stopAtBeginningOfMainSubprogram} for a stop on entry; an attach by
     * {@code pid} ({@code processId}).
     */
    static Map<String, Object> gdbRequest(Map<String, Object> body, boolean attach) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "gdb");
        out.put("request", attach ? "attach" : "launch");
        if (attach) {
            Object pid = body.containsKey("pid") ? body.get("pid") : body.get("processId");
            out.put("pid", number(pid));
            if (body.get("program") instanceof String program) {
                out.put("program", program);
            }
            return out;
        }
        out.put("program", text(body.get("program")));
        if (body.get("args") instanceof List<?> args) {
            out.put("args", args);
        }
        if (body.get("cwd") instanceof String cwd) {
            out.put("cwd", cwd);
        }
        Map<String, String> env = environment(body);
        if (!env.isEmpty()) {
            out.put("env", env);
        }
        if (Boolean.TRUE.equals(body.get("stopAtEntry"))
                || Boolean.TRUE.equals(body.get("stopOnEntry"))
                || Boolean.TRUE.equals(body.get("stopAtBeginningOfMainSubprogram"))) {
            out.put("stopAtBeginningOfMainSubprogram", true);
        }
        return out;
    }

    /** CodeLLDB's request for a {@code cppdbg} configuration that asks for LLDB. */
    static Map<String, Object> codelldbFromCppdbg(Map<String, Object> body, boolean attach) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "lldb");
        out.put("request", attach ? "attach" : "launch");
        if (attach) {
            out.put("pid", number(body.get("processId")));
            return out;
        }
        out.put("program", text(body.get("program")));
        if (body.get("args") instanceof List<?> args) {
            out.put("args", args);
        }
        if (body.get("cwd") instanceof String cwd) {
            out.put("cwd", cwd);
        }
        Map<String, String> env = environment(body);
        if (!env.isEmpty()) {
            out.put("env", env);
        }
        out.put("stopOnEntry", Boolean.TRUE.equals(body.get("stopAtEntry")));
        out.put("sourceLanguages", List.of("cpp"));
        return out;
    }

    /** The environment a configuration sets: an {@code env} object, or {@code cppdbg}'s list of {@code {name, value}}. */
    static Map<String, String> environment(Map<String, Object> body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body.get("env") instanceof Map<?, ?> env) {
            env.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        if (body.get("environment") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("name") != null) {
                    out.put(
                            String.valueOf(m.get("name")),
                            m.get("value") == null ? "" : String.valueOf(m.get("value")));
                }
            }
        }
        return out;
    }

    private static Object number(@Nullable Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.strip());
            } catch (NumberFormatException e) {
                return s;
            }
        }
        return value == null ? 0L : value;
    }

    private static boolean containsString(@Nullable Object list, String wanted) {
        return list instanceof List<?> l && l.contains(wanted);
    }

    private static String text(@Nullable Object o) {
        return o instanceof String s ? s : "";
    }

    /**
     * The executable a CodeLLDB {@code cargo} build made: among {@code built}, the test binaries for a
     * {@code cargo test}, else the programs, narrowed by the {@code filter}'s {@code name} and {@code kind}; null
     * unless exactly one is left.
     */
    public static java.nio.file.@Nullable Path cargoExecutable(
            List<RustLaunch.Artifact> built, boolean test, @Nullable String name, @Nullable String kind) {
        List<RustLaunch.Artifact> fit = built.stream()
                .filter(a -> a.test() == test)
                .filter(a -> name == null || name.equals(a.target()))
                .filter(a -> kind == null || a.kinds().contains(kind))
                .toList();
        return fit.size() == 1 ? fit.getFirst().executable() : null;
    }

    /** A {@code java} configuration as a run configuration: its arguments quoted, its environment as pairs. */
    public static com.editora.config.RunConfiguration javaRunConfiguration(
            String name, Map<String, Object> body, java.nio.file.Path workspace) {
        return new com.editora.config.RunConfiguration(
                "launch.json: " + name,
                "java",
                "",
                text(body.get("mainClass")),
                text(body.get("projectName")),
                words(body.get("args")),
                words(body.get("vmArgs")),
                body.get("cwd") instanceof String cwd && !cwd.isBlank() ? cwd : workspace.toString(),
                pairs(environment(body)),
                "",
                body.get("javaExec") instanceof String exec ? javaHomeOf(exec) : "",
                "",
                "",
                false,
                "");
    }

    /** A string as it is; a list of words quoted for {@link com.editora.run.ProgramArgs#parse}. */
    static String words(@Nullable Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof List<?> l) {
            List<String> out = new java.util.ArrayList<>();
            l.forEach(w -> out.add(LaunchJson.shellQuote(String.valueOf(w))));
            return String.join(" ", out);
        }
        return "";
    }

    private static String pairs(Map<String, String> env) {
        List<String> out = new java.util.ArrayList<>();
        env.forEach((k, v) -> out.add(k + "=" + LaunchJson.shellQuote(v)));
        return String.join(" ", out);
    }

    /** The JDK folder of {@code javaExec} ({@code /jdk/bin/java} → {@code /jdk}). */
    private static String javaHomeOf(String javaExec) {
        java.nio.file.Path bin = java.nio.file.Path.of(javaExec).getParent();
        java.nio.file.Path home = bin == null ? null : bin.getParent();
        return home == null ? "" : home.toString();
    }
}
