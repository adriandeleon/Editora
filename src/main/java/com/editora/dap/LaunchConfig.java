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
        return launch(
                mainClass,
                projectName,
                classPaths,
                modulePaths,
                javaExec,
                cwd,
                args,
                vmArgs,
                env,
                stopOnEntry,
                Target.LOCAL);
    }

    /**
     * The machine a launch's adapter runs on, as far as the request body cares: java-debug splits {@code args}
     * by <em>its</em> OS's rules, and a command-line limit and a JDK's {@code release} file are that machine's
     * (review D8: a Windows client used to quote for Windows a program that a Linux host would run).
     *
     * @param windows whether the adapter's machine is Windows
     * @param local whether it is this machine (so its JDKs can be read here)
     */
    public record Target(boolean windows, boolean local) {
        /** This machine. */
        public static final Target LOCAL = new Target(isWindows(), true);

        /** A remote host, container or WSL distribution: POSIX, and its files are not this machine's. */
        public static final Target REMOTE_POSIX = new Target(false, false);
    }

    /** As above, for an adapter on {@code target}. */
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
            boolean stopOnEntry,
            Target target) {
        Target on = target == null ? Target.LOCAL : target;
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
            m.put("args", javaArgs(args, on.windows()));
        }
        // A JDK of another machine cannot be read here: one new enough for an argument file is assumed there.
        String shorten = JavaLaunchSupport.shortenCommandLine(
                m, on.windows(), on.local() ? JavaLaunchSupport::supportsArgFiles : java -> true);
        if (shorten != null) {
            m.put("shortenCommandLine", shorten);
        }
        m.put("console", "internalConsole");
        m.put("stopOnEntry", stopOnEntry);
        return m;
    }

    /** The {@code console} of a launch whose debuggee the client starts itself (DAP {@code runInTerminal}). */
    public static final String CLIENT_CONSOLE = "integratedTerminal";

    /**
     * Turns a {@link #launch} body into one that asks the adapter to hand the debuggee's command line back
     * to the client ({@code runInTerminal}) instead of starting it: the only way the program gets a standard
     * input, since java-debug's {@code internalConsole} has none. Returns {@code launch}, changed in place.
     */
    public static Map<String, Object> inClientConsole(Map<String, Object> launch) {
        launch.put("console", CLIENT_CONSOLE);
        return launch;
    }

    /**
     * Encodes an argv as the single command-line string java-debug expects in {@code args}, so that its
     * tokenizer ({@code DebugUtility.parseArguments}) yields the same arguments back. The adapter tokenizes
     * by its own OS: elsewhere a double-quoted argument with {@code \} and {@code "} backslash-escaped; on
     * Windows the {@code CommandLineToArgvW} convention (backslashes are literal unless they precede a
     * quote). An argument needing no quoting is passed through unchanged. Known limit: the adapter's Windows
     * tokenizer has no spelling for an empty argument (it keeps {@code ""} literally).
     */
    public static String javaArgs(List<String> argv, boolean windows) {
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
        return attach(host, port, null);
    }

    /**
     * As above, naming the jdtls project the debugged code belongs to (omitted when blank). An attach has no
     * main class java-debug could infer the project from, and without one it can evaluate nothing — not an
     * expression, not a logpoint, and not a breakpoint condition, which it then treats as met.
     */
    public static Map<String, Object> attach(String host, int port, String projectName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "java");
        m.put("name", "Editora (Attach)");
        m.put("request", "attach");
        m.put("hostName", notBlank(host) ? host : "localhost");
        m.put("port", port);
        if (notBlank(projectName)) {
            m.put("projectName", projectName);
        }
        return m;
    }

    /**
     * An {@code attach} request body for vscode-js-debug ({@code type}, e.g. {@code pwa-node}) to a Node.js on this
     * machine waiting on its inspector {@code port} (started with {@code --inspect-brk}): js-debug resumes it once the
     * breakpoints are set ({@code continueOnAttach}), and steps over {@code skipFiles} (glob patterns or paths).
     */
    public static Map<String, Object> nodeAttach(String type, String name, int port, java.util.List<String> skipFiles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("name", notBlank(name) ? name : "Editora (Attach)");
        m.put("request", "attach");
        m.put("address", "127.0.0.1");
        m.put("port", port);
        m.put("continueOnAttach", true);
        m.put("attachExistingChildren", false);
        if (skipFiles != null && !skipFiles.isEmpty()) {
            m.put("skipFiles", java.util.List.copyOf(skipFiles));
        }
        return m;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean notEmpty(List<?> l) {
        return l != null && !l.isEmpty();
    }
}
