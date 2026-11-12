package com.editora.dap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The programs on this machine Debug: Attach to Process can attach to (Visual Studio's and IntelliJ's process
 * pickers): a JVM started with the JDWP agent listening ({@code -agentlib:jdwp=…,server=y,address=5005}), a Node.js
 * started with its inspector ({@code --inspect}), a Python waiting for debugpy ({@code -m debugpy --listen 5678}), and
 * a Go program, which Delve attaches to by its process id, and a Rust or other native program built with debug
 * information, which CodeLLDB attaches to so. The same kinds of process started without debugging are
 * listed too, so the picker can say what to add. A Go program is told by its executable ({@link ProgramFiles}), not its
 * command line; not on Windows.
 *
 * <p>Read from each process's command line ({@link ProcessHandle.Info}); Windows does not give another process's
 * arguments that way, so there it is asked of {@code Win32_Process} through PowerShell. Only this user's processes
 * can be read. Parsing is pure and unit-tested; {@link #scan} does I/O and must run off the FX thread.
 */
public final class LocalProcesses {

    private LocalProcesses() {}

    /** Which debugger attaches to a process. */
    public enum Kind {
        JAVA,
        NODE,
        PYTHON,
        GO,
        /** A Rust program built with debug information: CodeLLDB attaches by process id. */
        RUST,
        /** A native program (C, C++, …) built with debug information: CodeLLDB attaches by process id. */
        NATIVE;

        /** Whether a debugger attaches to it by process id, with no port of its own. */
        public boolean byPid() {
            return this == GO || this == RUST || this == NATIVE;
        }
    }

    /**
     * A process the picker lists. {@code port > 0} when it listens for a debugger ({@code host} is where to connect);
     * {@code 0} when it was started without debugging, or listens on a port its command line does not name
     * ({@code address=0}: chosen when it started). A Go program has no port: Delve attaches to it by {@code pid}.
     */
    public record Target(long pid, Kind kind, String name, String host, int port) {

        /** Whether a debugger can attach to it: at a known address, or a Go program by its process id. */
        public boolean attachable() {
            return port > 0 || kind.byPid();
        }

        /** {@code host:port}, as Attach takes it; {@code ""} when it has none. */
        public String address() {
            return port > 0 ? host + ":" + port : "";
        }
    }

    /** Every Java, Node.js, Python and Go process of this user but this one, attachable ones first. Blocking. */
    public static List<Target> scan() {
        long self = ProcessHandle.current().pid();
        List<Target> out = new ArrayList<>();
        Map<Long, String> windows = isWindows() ? windowsCommandLines() : Map.of();
        // One read per program file, not per process; null: nothing a debugger attaches to by process id.
        Map<String, java.util.Optional<ProgramFiles.Kind>> programs = new java.util.HashMap<>();
        ProcessHandle.allProcesses().forEach(p -> {
            if (p.pid() == self) {
                return;
            }
            try {
                ProcessHandle.Info info = p.info();
                String command = info.command().orElse("");
                List<String> args = info.arguments().map(List::of).orElse(List.of());
                if (args.isEmpty() && windows.containsKey(p.pid())) {
                    List<String> argv = splitWindows(windows.get(p.pid()));
                    if (!argv.isEmpty()) {
                        command = command.isEmpty() ? argv.getFirst() : command;
                        args = argv.subList(1, argv.size());
                    }
                }
                Target t = classify(p.pid(), command, args);
                Kind byPid = t == null ? programKind(command, programs) : null;
                if (byPid != null) {
                    t = new Target(p.pid(), byPid, fileName(command), "", 0);
                }
                if (t != null) {
                    out.add(t);
                }
            } catch (RuntimeException unreadable) {
                // another user's process, or one that ended while it was being read
            }
        });
        return sorted(out);
    }

    /**
     * What {@code command} (an absolute program path) is to a debugger attaching by process id — Go, or Rust or
     * another native program with debug information — read once per path into {@code seen}; null for neither.
     */
    private static @Nullable Kind programKind(String command, Map<String, java.util.Optional<ProgramFiles.Kind>> seen) {
        if (command.isEmpty() || isWindows() || !command.startsWith("/")) {
            return null;
        }
        ProgramFiles.Kind kind = seen.computeIfAbsent(
                        command, c -> java.util.Optional.ofNullable(ProgramFiles.kind(java.nio.file.Path.of(c))))
                .orElse(null);
        return kind == null ? null : programKind(kind);
    }

    /** The picker's kind of a program file's kind. Pure. */
    static Kind programKind(ProgramFiles.Kind kind) {
        return switch (kind) {
            case GO -> Kind.GO;
            case RUST -> Kind.RUST;
            case NATIVE -> Kind.NATIVE;
        };
    }

    /** Attachable first, then by kind and name. Pure. */
    static List<Target> sorted(List<Target> all) {
        List<Target> out = new ArrayList<>(all);
        out.sort(Comparator.comparing((Target t) -> !t.attachable())
                .thenComparing(Target::kind)
                .thenComparing(t -> t.name().toLowerCase(Locale.ROOT))
                .thenComparingLong(Target::pid));
        return out;
    }

    /** The executable's file name, lower-cased and without {@code .exe}: {@code java}, {@code node}, {@code python3}. */
    static String program(String command) {
        String c = command == null ? "" : command.strip();
        int slash = Math.max(c.lastIndexOf('/'), c.lastIndexOf('\\'));
        String name = c.substring(slash + 1).toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    private static final Pattern PYTHON = Pattern.compile("python(?:w)?(?:\\d+(?:\\.\\d+)*)?");

    /** What {@code command args} is, or null when it is no Java, Node.js or Python process. Pure. */
    public static @Nullable Target classify(long pid, String command, List<String> args) {
        String program = program(command);
        if (program.equals("java") || program.equals("javaw")) {
            return java(pid, args);
        }
        if (program.equals("node") || program.equals("nodejs")) {
            return node(pid, args);
        }
        if (PYTHON.matcher(program).matches()) {
            return python(pid, args);
        }
        return null;
    }

    /** {@code java} options that take the next argument as their value. */
    private static final java.util.Set<String> JAVA_VALUE_OPTIONS = java.util.Set.of(
            "-cp",
            "-classpath",
            "--class-path",
            "-p",
            "--module-path",
            "--upgrade-module-path",
            "--add-modules",
            "--add-opens",
            "--add-exports",
            "--add-reads",
            "--patch-module",
            "--limit-modules",
            "--enable-native-access");

    private static Target java(long pid, List<String> args) {
        String host = "localhost";
        int port = 0;
        String name = "java";
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.startsWith("-agentlib:jdwp=") || a.startsWith("-Xrunjdwp:")) {
                int[] p = new int[1];
                String h = jdwp(a.substring(a.indexOf(a.startsWith("-agentlib") ? '=' : ':') + 1), p);
                if (h != null) {
                    host = h;
                    port = p[0];
                }
            } else if (a.equals("-jar") && i + 1 < args.size()) {
                name = fileName(args.get(i + 1));
                break;
            } else if ((a.equals("-m") || a.equals("--module")) && i + 1 < args.size()) {
                String m = args.get(i + 1);
                name = m.substring(m.indexOf('/') + 1);
                break;
            } else if (JAVA_VALUE_OPTIONS.contains(a)) {
                i++; // its value
            } else if (!a.startsWith("-")) {
                name = a; // the main class, or a source file
                break;
            }
        }
        return new Target(pid, Kind.JAVA, name, host, port);
    }

    /**
     * The JDWP agent's options ({@code transport=dt_socket,server=y,suspend=n,address=*:5005}): the host to connect to,
     * with its port in {@code port[0]}, or null when a debugger cannot attach (it connects out, {@code server=n}, or
     * its port is chosen when it starts, {@code address=0}). Pure.
     */
    static @Nullable String jdwp(String options, int[] port) {
        boolean server = false;
        String address = "";
        for (String part : options.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq).strip();
            String value = part.substring(eq + 1).strip();
            if (key.equals("server")) {
                server = value.equalsIgnoreCase("y");
            } else if (key.equals("address")) {
                address = value;
            }
        }
        if (!server || address.isEmpty()) {
            return null;
        }
        String[] hp = hostPort(address);
        if (hp == null) {
            return null;
        }
        int p = parsePort(hp[1]);
        if (p <= 0) {
            return null;
        }
        port[0] = p;
        return hp[0];
    }

    private static Target node(long pid, List<String> args) {
        String host = "localhost";
        int port = JsLaunch.DEFAULT_INSPECTOR_PORT;
        boolean inspecting = false;
        String name = "node";
        for (String a : args) {
            Matcher m = NODE_INSPECT.matcher(a);
            if (m.matches()) {
                // --inspect-port only names the port; the inspector listens with --inspect, -brk or -wait.
                inspecting |= !"-port".equals(m.group(1));
                String value = m.group(2);
                if (value != null && !value.isEmpty()) {
                    String[] hp = hostPort(value);
                    if (hp != null && parsePort(hp[1]) > 0) {
                        host = hp[0];
                        port = parsePort(hp[1]);
                    }
                }
            } else if (!a.startsWith("-") && name.equals("node")) {
                name = fileName(a); // the script
            }
        }
        return new Target(pid, Kind.NODE, name, host, inspecting ? port : 0);
    }

    /** {@code --inspect}, {@code --inspect-brk}, {@code --inspect-wait}, {@code --inspect-port}, each {@code =[host:]port}. */
    private static final Pattern NODE_INSPECT = Pattern.compile("--inspect(-brk|-wait|-port)?(?:=(.*))?");

    /**
     * A Python program: listening for a debugger when it runs under {@code debugpy --listen}, whether debugpy is
     * named as a module ({@code -m debugpy}) or by its folder ({@code python …/debugpy --listen 5678 app.py}). Null
     * for debugpy's own helper processes, its adapter and launcher: {@code debugpy --listen} starts an adapter
     * ({@code python …/debugpy/adapter --for-server …}) that is not a program to attach to.
     */
    private static @Nullable Target python(long pid, List<String> args) {
        String host = "localhost";
        int port = 0;
        String name = "python";
        boolean debugpy = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-m") && i + 1 < args.size()) {
                String module = args.get(++i);
                if (DEBUGPY_HELPER_MODULES.contains(module)) {
                    return null;
                }
                if (module.equals("debugpy")) {
                    debugpy = true;
                } else {
                    name = module;
                    if (!debugpy) {
                        break;
                    }
                }
            } else if (debugpy && a.equals("--listen") && i + 1 < args.size()) {
                String[] hp = hostPort(args.get(++i));
                if (hp != null && parsePort(hp[1]) > 0) {
                    host = hp[0];
                    port = parsePort(hp[1]);
                }
            } else if (debugpy && (a.equals("--connect") || a.equals("--configure-subProcess"))) {
                i++; // debugpy connects out: nothing to attach to
            } else if (a.equals("-X") || a.equals("-W") || a.equals("--log-to")) {
                i++; // an option's value, not the script
            } else if (a.equals("-c")) {
                name = "-c";
                break;
            } else if (!a.startsWith("-")) {
                String script = debugpyPart(a);
                if (script != null) {
                    if (!script.isEmpty()) {
                        return null; // debugpy's adapter or launcher
                    }
                    debugpy = true; // python …/debugpy: the package run by its folder, as -m debugpy
                    continue;
                }
                name = fileName(a); // the script
                break;
            }
        }
        return new Target(pid, Kind.PYTHON, name, host, port);
    }

    /** debugpy's helper modules, which run beside a debugged program and are never one. */
    private static final java.util.Set<String> DEBUGPY_HELPER_MODULES =
            java.util.Set.of("debugpy.adapter", "debugpy.launcher");

    /**
     * What a script path names inside debugpy: {@code ""} for the debugpy package itself ({@code …/debugpy} or its
     * {@code __main__.py}), {@code "adapter"} or {@code "launcher"} for those helpers (a folder or its
     * {@code __main__.py}), null for any other script. Pure.
     */
    static @Nullable String debugpyPart(String path) {
        List<String> parts = new ArrayList<>(List.of(path.replace('\\', '/').split("/+")));
        parts.removeIf(String::isEmpty);
        if (!parts.isEmpty() && parts.getLast().equals("__main__.py")) {
            parts.removeLast();
        }
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.getLast().equals("debugpy")) {
            return "";
        }
        if (parts.size() >= 2
                && parts.get(parts.size() - 2).equals("debugpy")
                && (parts.getLast().equals("adapter") || parts.getLast().equals("launcher"))) {
            return parts.getLast();
        }
        return null;
    }

    /**
     * {@code 5005}, {@code *:5005}, {@code 0.0.0.0:5005}, {@code [::1]:5005}, {@code host:5005} as {@code {host, port}};
     * a wildcard host is this machine. Null for something else. Pure.
     */
    static String @Nullable [] hostPort(String address) {
        String a = address.strip();
        if (a.isEmpty()) {
            return null;
        }
        String host = "localhost";
        String port = a;
        if (a.startsWith("[")) {
            int close = a.indexOf(']');
            if (close < 0 || close + 1 >= a.length() || a.charAt(close + 1) != ':') {
                return null;
            }
            host = a.substring(1, close);
            port = a.substring(close + 2);
        } else {
            int colon = a.lastIndexOf(':');
            if (colon >= 0) {
                host = a.substring(0, colon);
                port = a.substring(colon + 1);
            }
        }
        if (host.isEmpty() || host.equals("*") || host.equals("0.0.0.0") || host.equals("::")) {
            host = "localhost";
        }
        return new String[] {host, port};
    }

    private static int parsePort(String text) {
        try {
            int p = Integer.parseInt(text.strip());
            return p >= 1 && p <= 65535 ? p : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(slash + 1);
    }

    private static boolean isWindows() {
        return com.editora.Os.isWindows();
    }

    /**
     * The command lines of this user's Java, Node.js and Python processes on Windows, by pid, from
     * {@code Win32_Process}: {@link ProcessHandle.Info#arguments} is empty there for every other process. Empty when
     * PowerShell cannot say within a few seconds.
     */
    private static Map<Long, String> windowsCommandLines() {
        java.nio.file.Path out = null;
        try {
            out = java.nio.file.Files.createTempFile("editora-processes", ".txt");
            // -EncodedCommand: Java's Windows launcher does not escape quotes inside an argument, so a -Command script
            // with quotes in it reached PowerShell mangled and listed nothing.
            ProcessBuilder pb = new ProcessBuilder(
                            "powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded(WINDOWS_LIST))
                    .redirectErrorStream(true)
                    .redirectOutput(out.toFile()); // a file, so a slow PowerShell cannot hold the scan past its limit
            com.editora.process.ProcessRunner.applyStandardEnv(pb); // the output is parsed
            Process p = pb.start();
            p.getOutputStream().close();
            if (!p.waitFor(WINDOWS_LIST_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Map.of();
            }
            return parseWindowsList(java.nio.file.Files.readString(out, java.nio.charset.Charset.defaultCharset()));
        } catch (java.io.IOException | RuntimeException e) {
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } finally {
            if (out != null) {
                try {
                    java.nio.file.Files.deleteIfExists(out);
                } catch (java.io.IOException ignored) {
                    // a temporary file
                }
            }
        }
    }

    /** How long PowerShell may take to list the processes: its first start on a machine takes seconds. */
    static final int WINDOWS_LIST_SECONDS = 15;

    /** Lists this user's Java, Node.js and Python processes as {@code pid<TAB>command line} lines. */
    static final String WINDOWS_LIST = "Get-CimInstance Win32_Process -Filter \"Name like 'java%' or Name like 'node%'"
            + " or Name like 'python%'\" | ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }";

    /** {@code script} as PowerShell's {@code -EncodedCommand} takes it: Base64 of its UTF-16LE text. Pure. */
    static String encoded(String script) {
        return java.util.Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    /** {@code pid<TAB>command line} lines as a map. Pure. */
    static Map<Long, String> parseWindowsList(String text) {
        Map<Long, String> out = new java.util.HashMap<>();
        for (String line : text.split("\\R")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            try {
                out.put(Long.parseLong(line.substring(0, tab).strip()), line.substring(tab + 1));
            } catch (NumberFormatException ignored) {
                // a PowerShell warning line
            }
        }
        return out;
    }

    /** A Windows command line split as {@code CommandLineToArgvW} does, for the cases a program's line has. Pure. */
    static List<String> splitWindows(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any || cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
            } else {
                cur.append(c);
            }
        }
        if (any || cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
