package com.editora.dap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the argument maps for the DAP {@code launch} and {@code attach} requests understood by the
 * Microsoft java-debug adapter. Pure shaping (unit-tested): the maps are passed straight to
 * {@code DapClient.launch/attach} as the request body. Only the fields the adapter needs are emitted;
 * blank/empty optional fields are omitted so the adapter falls back to its defaults.
 */
public final class LaunchConfig {

    private LaunchConfig() {}

    /**
     * A {@code launch} request body. {@code mainClass} is required; {@code projectName}/{@code classPaths}/
     * {@code modulePaths}/{@code javaExec}/{@code cwd}/{@code args} are optional (omitted when blank/empty).
     * Program args are passed as one quoted string ({@link #javaArgs}) — java-debug's {@code args} is a
     * String — encoded so an argument containing spaces stays one argument.
     */
    public static Map<String, Object> launch(
            String mainClass,
            String projectName,
            List<String> classPaths,
            List<String> modulePaths,
            String javaExec,
            String cwd,
            List<String> args,
            String vmArgs,
            boolean stopOnEntry) {
        return launch(
                mainClass, projectName, classPaths, modulePaths, javaExec, cwd, args, vmArgs, Map.of(), stopOnEntry);
    }

    /** As above, plus {@code env} — extra environment variables for the debuggee (java-debug's {@code env} map). */
    public static Map<String, Object> launch(
            String mainClass,
            String projectName,
            List<String> classPaths,
            List<String> modulePaths,
            String javaExec,
            String cwd,
            List<String> args,
            String vmArgs,
            Map<String, String> env,
            boolean stopOnEntry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "java");
        m.put("name", "Editora (Launch)");
        m.put("request", "launch");
        m.put("mainClass", mainClass == null ? "" : mainClass);
        if (env != null && !env.isEmpty()) {
            m.put("env", env);
        }
        if (notBlank(vmArgs)) {
            // java-debug's vmArgs is a single string of JVM options (kept verbatim, so a quoted value survives).
            m.put("vmArgs", vmArgs);
        }
        if (notBlank(projectName)) {
            m.put("projectName", projectName);
        }
        if (notEmpty(classPaths)) {
            m.put("classPaths", classPaths);
        }
        if (notEmpty(modulePaths)) {
            m.put("modulePaths", modulePaths);
        }
        if (notBlank(javaExec)) {
            m.put("javaExec", javaExec);
        }
        if (notBlank(cwd)) {
            m.put("cwd", cwd);
        }
        if (notEmpty(args)) {
            // java-debug declares LaunchArguments.args as a String and decodes with a plain Gson: a JSON
            // array makes it throw while decoding the request, which it then never answers (the launch
            // hangs until the request timeout). So send ONE string — but not a bare space-join, which
            // would turn `"hello world" second` into three arguments: quote it so the adapter's own
            // tokenizer splits it back into the argv ProgramArgs.tokenize produced (what Run passes).
            m.put("args", javaArgs(args, isWindows()));
        }
        m.put("console", "internalConsole");
        m.put("stopOnEntry", stopOnEntry);
        return m;
    }

    /**
     * Encodes an argv as the single command-line string java-debug expects in {@code args}, so that its
     * tokenizer ({@code DebugUtility.parseArguments}) yields the same arguments back. The adapter tokenizes
     * by its own OS: elsewhere a double-quoted argument with {@code \} and {@code "} backslash-escaped; on
     * Windows the {@code CommandLineToArgvW} convention (backslashes are literal unless they precede a
     * quote). An argument needing no quoting is passed through unchanged. Known limit: the adapter's Windows
     * tokenizer has no spelling for an empty argument (it keeps {@code ""} literally).
     */
    static String javaArgs(List<String> argv, boolean windows) {
        StringBuilder sb = new StringBuilder();
        for (String arg : argv) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            String a = arg == null ? "" : arg;
            sb.append(windows ? quoteWindows(a) : quotePosix(a));
        }
        return sb.toString();
    }

    private static String quotePosix(String a) {
        boolean plain = !a.isEmpty()
                && a.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'' || c == '\\');
        return plain ? a : '"' + a.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String quoteWindows(String a) {
        boolean plain = !a.isEmpty() && a.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"');
        if (plain) {
            return a;
        }
        StringBuilder sb = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                sb.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                sb.append("\\".repeat(backslashes)).append(c);
                backslashes = 0;
            }
        }
        return sb.append("\\".repeat(backslashes * 2)).append('"').toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    /**
     * A {@code launch} request body for a single-program adapter (debugpy / vscode-js-debug). {@code type}
     * is the adapter's launch type ({@code "python"} or {@code "pwa-node"}); {@code program} is the script
     * to run (required). {@code cwd} + {@code stopOnEntry} are common; for {@code "python"} the
     * {@code runtimeExecutable} (the interpreter) is emitted as {@code "python"}, and for any other type it
     * is emitted as {@code "runtimeExecutable"} (the node binary) — both omitted when blank so the adapter
     * uses its default. Pure shaping (unit-tested).
     */
    public static Map<String, Object> program(
            String type, String program, String cwd, String runtimeExecutable, boolean stopOnEntry) {
        return program(type, program, cwd, runtimeExecutable, List.of(), stopOnEntry);
    }

    /** As {@link #program(String, String, String, String, boolean)} with program {@code args}
     *  (debugpy and js-debug both take an argv array; omitted when empty). */
    public static Map<String, Object> program(
            String type, String program, String cwd, String runtimeExecutable, List<String> args, boolean stopOnEntry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type == null ? "" : type);
        m.put("name", "Editora (Launch)");
        m.put("request", "launch");
        m.put("program", program == null ? "" : program);
        if (notBlank(cwd)) {
            m.put("cwd", cwd);
        }
        if (notEmpty(args)) {
            m.put("args", args);
        }
        if (notBlank(runtimeExecutable)) {
            if ("python".equals(type)) {
                m.put("python", runtimeExecutable);
            } else {
                m.put("runtimeExecutable", runtimeExecutable);
            }
        }
        m.put("console", "internalConsole");
        m.put("stopOnEntry", stopOnEntry);
        return m;
    }

    /** An {@code attach} request body for a running JVM (JDWP). Blank host defaults to {@code localhost}. */
    public static Map<String, Object> attach(String host, int port) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "java");
        m.put("name", "Editora (Attach)");
        m.put("request", "attach");
        m.put("hostName", notBlank(host) ? host : "localhost");
        m.put("port", port);
        return m;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean notEmpty(List<?> l) {
        return l != null && !l.isEmpty();
    }
}
