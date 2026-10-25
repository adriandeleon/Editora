package com.editora.dap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.run.NodeRuntimes;
import org.jspecify.annotations.Nullable;

/**
 * What Editora asks vscode-js-debug for, and how it reads what js-debug says back. Pure (unit-tested).
 *
 * <p>The {@code launch} body is the whole of a JavaScript debug session's setup, so each field here is a choice:
 * <ul>
 *   <li>{@code console}: with the Debugging setting "Run the debugged program in the Debug console" on, the
 *       program is started by Editora ({@code integratedTerminal} → the adapter's {@code runInTerminal}), which
 *       gives it a standard input and all of its output and exit code. Off, js-debug starts it and
 *       {@code outputCapture: "std"} reads its stdout/stderr — the default ({@code "console"}) only sees
 *       {@code console.*} calls, so {@code process.stdout.write} output never appeared (review D1).</li>
 *   <li>{@code cwd} and {@code __workspaceFolder}: the package root, as Run and npm use it. js-debug resolves
 *       {@code ${workspaceFolder}} (its default {@code outFiles} and {@code resolveSourceMapLocations}) from
 *       them; with the file's own folder a compiled {@code dist/} file was never mapped back to its {@code .ts}
 *       (review D3).</li>
 *   <li>{@code skipFiles}: Node's own modules, as VS Code's templates set it — no loader frames in every stack,
 *       no stepping into them (review D10).</li>
 *   <li>{@code runtimeExecutable}/{@code runtimeArgs}: the Node.js {@link NodeRuntimes} resolved for the file.</li>
 * </ul>
 */
public final class JsLaunch {

    private JsLaunch() {}

    /** js-debug's name for Node's built-in modules in {@code skipFiles}. */
    public static final String NODE_INTERNALS = "<node_internals>/**";

    /**
     * The oldest Node.js major that runs the vscode-js-debug server itself. The program being debugged may be
     * older: js-debug then runs on another Node and debugs it through the inspector.
     */
    public static final int MIN_ADAPTER_NODE_MAJOR = com.editora.install.VersionFloor.NODE_JS_DEBUG.major();

    /**
     * The {@code launch} request for {@code program}. {@code runtime} is the Node command (its executable, then
     * any arguments it carries); {@code args} the program's own arguments; {@code clientConsole} whether Editora
     * starts the program itself (see the class comment).
     */
    public static Map<String, Object> request(
            String type,
            String program,
            String cwd,
            String workspaceFolder,
            List<String> runtime,
            List<String> args,
            boolean clientConsole) {
        return request(type, program, cwd, workspaceFolder, runtime, args, clientConsole, Map.of());
    }

    /**
     * As above, with {@code env} set on top of the inherited environment (a {@code null} value unsets a variable).
     * A blank {@code program} launches {@code runtime} itself — a package manager's {@code run <script>} — and
     * js-debug attaches to the Node processes it starts.
     */
    public static Map<String, Object> request(
            String type,
            String program,
            String cwd,
            String workspaceFolder,
            List<String> runtime,
            List<String> args,
            boolean clientConsole,
            Map<String, String> env) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type == null || type.isBlank() ? "pwa-node" : type);
        m.put("name", "Editora (Launch)");
        m.put("request", "launch");
        if (notBlank(program)) {
            m.put("program", program);
        }
        if (notBlank(cwd)) {
            m.put("cwd", cwd);
        }
        String folder = notBlank(workspaceFolder) ? workspaceFolder : cwd;
        if (notBlank(folder)) {
            m.put("__workspaceFolder", folder);
        }
        if (args != null && !args.isEmpty()) {
            m.put("args", List.copyOf(args));
        }
        if (runtime != null && !runtime.isEmpty() && notBlank(runtime.get(0))) {
            m.put("runtimeExecutable", runtime.get(0));
            if (runtime.size() > 1) {
                m.put("runtimeArgs", List.copyOf(runtime.subList(1, runtime.size())));
            }
        }
        if (env != null && !env.isEmpty()) {
            m.put("env", new LinkedHashMap<>(env));
        }
        m.put("skipFiles", List.of(NODE_INTERNALS));
        if (clientConsole) {
            m.put("console", "integratedTerminal");
        } else {
            m.put("console", "internalConsole");
            m.put("outputCapture", "std");
        }
        m.put("stopOnEntry", false);
        return m;
    }

    /**
     * Why {@code fileName} cannot be launched under a Node.js of {@code nodeVersion} (an i18n key), or null when it
     * can (or the version is unknown — Node then says so itself). JSX/TSX is not something Node runs; TypeScript
     * runs only on a Node that strips types (22.18+, or 23.6+).
     */
    public static @Nullable String launchProblemKey(String fileName, @Nullable String nodeVersion) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jsx") || name.endsWith(".tsx")) {
            return "debug.error.jsxNotRunnable";
        }
        boolean ts = name.endsWith(".ts") || name.endsWith(".mts") || name.endsWith(".cts");
        if (ts
                && nodeVersion != null
                && NodeRuntimes.parseVersion(nodeVersion).isPresent()
                && !NodeRuntimes.stripsTypes(nodeVersion)) {
            return "debug.error.tsNeedsNode";
        }
        return null;
    }

    /** Whether a Node.js of {@code version} can run the js-debug server; an unknown version is given the chance. */
    public static boolean runsAdapter(@Nullable String version) {
        Optional<int[]> v = version == null ? Optional.empty() : NodeRuntimes.parseVersion(version);
        return v.isEmpty() || v.get()[0] >= MIN_ADAPTER_NODE_MAJOR;
    }

    /** The {@code vNN.N.N} a {@code node --version} printed, or null. */
    public static @Nullable String version(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\\R")) {
            String s = line.strip();
            if (NodeRuntimes.parseVersion(s).isPresent() && s.matches("v?\\d+(\\.\\d+){0,2}")) {
                return s.startsWith("v") ? s : "v" + s;
            }
        }
        return null;
    }

    /** Lines Node's inspector prints for the debugger, not for the user. */
    private static final List<Pattern> INSPECTOR_NOISE = List.of(
            Pattern.compile("^Debugger attached\\.\\s*$"),
            Pattern.compile("^Waiting for the debugger to disconnect\\.\\.\\.\\s*$"),
            Pattern.compile("^Debugger listening on (?:ws|wss)://\\S+\\s*$"),
            Pattern.compile("^For help, see: https://nodejs\\.org/\\S*inspector\\S*\\s*$"),
            // js-debug's bootloader in a Node process that has no inspector (node --test's own runner process)
            Pattern.compile("^Error in the js-debug bootloader, please report to \\S+: "
                    + "This Environment was initialized without a V8::Inspector\\s*$"));

    /**
     * {@code text} without the inspector's own lines ("Debugger attached.", "Waiting for the debugger to
     * disconnect...", …), which reach a program's stderr under a debugger. Other text is kept as it came, line
     * endings included; {@code text} itself when nothing was removed.
     */
    public static String withoutInspectorNoise(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean removed = false;
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl + 1;
            String line = text.substring(start, end);
            String bare = line.replace("\r", "").replace("\n", "");
            boolean noise = false;
            for (Pattern p : INSPECTOR_NOISE) {
                if (p.matcher(bare).matches()) {
                    noise = true;
                    break;
                }
            }
            if (noise) {
                removed = true;
            } else {
                out.append(line);
            }
            start = end;
        }
        return removed ? out.toString() : text;
    }

    /** A breakpoint condition js-debug could not compile: the condition, the 1-based line, and its reason. */
    public record ConditionError(String condition, int line, String reason) {}

    private static final Pattern CONDITION_ERROR =
            Pattern.compile("Syntax error setting breakpoint with condition \"(.*?)\" on line (\\d+): (.*?)"
                    + "(?=Syntax error setting breakpoint|\\R|$)");

    /**
     * js-debug's report of a breakpoint condition that is not valid JavaScript — sent as program output, not as a
     * failed breakpoint — or empty.
     */
    public static Optional<ConditionError> conditionError(String text) {
        if (text == null || !text.contains("Syntax error setting breakpoint with condition")) {
            return Optional.empty();
        }
        Matcher m = CONDITION_ERROR.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(new ConditionError(
                m.group(1), Integer.parseInt(m.group(2)), m.group(3).strip()));
    }

    /** Where a Node.js inspector listens. */
    public record Address(String host, int port) {}

    /** The inspector port Node uses when {@code --inspect} names none. */
    public static final int DEFAULT_INSPECTOR_PORT = 9229;

    /**
     * Reads what was typed into Debug ▸ Attach for a Node.js: {@code 9229}, {@code host:9229}, {@code [::1]:9229},
     * or the {@code ws://host:port/…} URL Node prints ("Debugger listening on ws://…"). Blank: localhost on
     * {@link #DEFAULT_INSPECTOR_PORT}. Empty for anything else, or a port outside 1–65535.
     */
    public static Optional<Address> parseAddress(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return Optional.of(new Address("localhost", DEFAULT_INSPECTOR_PORT));
        }
        Matcher ws = Pattern.compile("^wss?://([^/]+)(?:/.*)?$").matcher(t);
        if (ws.matches()) {
            t = ws.group(1);
        }
        String host = "localhost";
        String port = t;
        if (t.startsWith("[")) {
            int close = t.indexOf(']');
            if (close < 0) {
                return Optional.empty();
            }
            host = t.substring(1, close);
            String rest = t.substring(close + 1);
            if (!rest.startsWith(":")) {
                return Optional.empty();
            }
            port = rest.substring(1);
        } else if (t.indexOf(':') >= 0) {
            int colon = t.lastIndexOf(':');
            host = t.substring(0, colon).strip();
            port = t.substring(colon + 1);
            if (host.isEmpty() || host.indexOf(':') >= 0) {
                return Optional.empty();
            }
        }
        try {
            int p = Integer.parseInt(port.strip());
            return p >= 1 && p <= 65535 ? Optional.of(new Address(host, p)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether {@code file} is a test file by Node's own conventions ({@code node --test}): {@code *.test.*},
     * {@code *.spec.*}, {@code test-*}/{@code *-test}/{@code *_test} names, or anything under a {@code test/} or
     * {@code __tests__/} folder — JavaScript and TypeScript alike.
     */
    public static boolean isTestFile(java.nio.file.Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.matches(".*\\.(?:[cm]?[jt]s|[jt]sx)$")) {
            return false;
        }
        String stem = name.substring(0, name.indexOf('.') < 0 ? name.length() : name.indexOf('.'));
        if (name.matches(".*\\.(?:test|spec)\\.[^.]+$")
                || stem.equals("test")
                || stem.startsWith("test-")
                || stem.endsWith("-test")
                || stem.endsWith("_test")) {
            return true;
        }
        for (java.nio.file.Path part : file) {
            String p = part.toString();
            if (p.equals("test") || p.equals("__tests__")) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern TEST_CALL = Pattern.compile(
            "\\b(?:test|it|describe|suite)(?:\\.(?:only|skip|todo))?\\s*\\(\\s*(['\"`])((?:\\\\.|(?!\\1).)*)\\1");

    /**
     * The name of the {@code test(…)}/{@code it(…)} (or {@code describe(…)}) call at or above 0-based {@code line} of
     * {@code content} — the test the caret is in — or empty when there is none above it.
     */
    public static Optional<String> testNameAt(String content, int line) {
        if (content == null || line < 0) {
            return Optional.empty();
        }
        String[] lines = content.split("\\R", -1);
        for (int i = Math.min(line, lines.length - 1); i >= 0; i--) {
            Matcher m = TEST_CALL.matcher(lines[i]);
            String found = null;
            while (m.find()) {
                found = m.group(2);
            }
            if (found != null && !found.isEmpty()) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    /** {@code name} as a JavaScript regular expression matching it literally ({@code --test-name-pattern}). */
    public static String literalPattern(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if ("\\^$.|?*+()[]{}/".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** {@code runtime} with {@code more} Node arguments after its own (for {@code runtimeArgs}). */
    public static List<String> withNodeArgs(List<String> runtime, List<String> more) {
        List<String> out = new ArrayList<>(runtime == null ? List.of() : runtime);
        if (more != null) {
            out.addAll(more);
        }
        return out;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
