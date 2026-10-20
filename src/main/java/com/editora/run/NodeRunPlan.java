package com.editora.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * A planned Node.js launch — Run File on a {@code .js/.mjs/.cjs/.ts/.mts/.cts} file or a {@code node}-shebang
 * script, a {@code node} run configuration, or a test file's ▶ ({@code node --test}). Pure: the caller resolves
 * the Node to use ({@link NodeRuntimes}, off the FX thread) and, for a TypeScript file, asks it for its version;
 * this decides the argv and the environment.
 *
 * <ul>
 *   <li><b>Interpreter.</b> A shebang that names {@code node} (bare, by path, or through {@code env}) keeps its
 *       flags but runs under the resolved Node — the file says "Node", the resolver says which one. A shebang
 *       that names another runner ({@code deno}, {@code bun}, {@code tsx}, {@code ts-node}) is honoured as
 *       written. A run configuration's Interpreter always wins (it is already the resolved runtime).</li>
 *   <li><b>TypeScript.</b> Plain Node strips types from 22.18 / 23.6 on; an older Node gets {@link #NEEDS_STRIP}
 *       instead of a confusing syntax error. An unknown version runs (the program says what is wrong).</li>
 *   <li><b>Flags.</b> {@code --enable-source-maps}, so a compiled file's stack names its source; a test file
 *       gets {@code --test --test-reporter=tap}, which Test Results reads.</li>
 *   <li><b>Environment.</b> {@code FORCE_COLOR=1} — the Run console draws colours — unless {@code NO_COLOR} is
 *       set; the shebang's {@code env NAME=value} lines; the configuration's variables last (they win).</li>
 * </ul>
 *
 * @param argv the full command line, or empty when {@link #error} says why it cannot run
 * @param env variables to set on top of the inherited environment ({@code null} value = unset)
 * @param chdir an {@code env -C DIR} folder from the shebang, else null
 * @param error an i18n key when the file cannot run as planned, else null
 */
public record NodeRunPlan(
        List<String> argv,
        Map<String, String> env,
        @Nullable String chdir,
        @Nullable String error) {

    /** i18n key: a TypeScript file on a Node too old to strip types. */
    public static final String NEEDS_STRIP = "status.run.nodeNeedsTypeStripping";

    public static final String SOURCE_MAPS = "--enable-source-maps";

    /** The editor languages Run File treats as Node programs (JSX/TSX need a bundler: not here). */
    public static boolean isNodeLanguage(String language) {
        return "javascript".equals(language) || "typescript".equals(language);
    }

    /**
     * Whether a file named {@code name} in a Node language gets a Run ▶ — every one but a declaration file
     * ({@code .d.ts}), which holds no code.
     */
    public static boolean runnableName(String name) {
        if (name == null) {
            return true;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return !(n.endsWith(".d.ts") || n.endsWith(".d.mts") || n.endsWith(".d.cts"));
    }

    /** Whether {@code name} is a TypeScript source Node has to strip types from. */
    public static boolean isTypeScript(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".ts") || n.endsWith(".mts") || n.endsWith(".cts");
    }

    /**
     * Whether {@code file} is a test file whose ▶ runs {@code node --test}: {@code *.test.*} or {@code *.spec.*},
     * or anything under a {@code test/}, {@code tests/} or {@code __tests__/} folder below {@code root} (its
     * package root, else its project; null = only the name counts).
     */
    public static boolean isTestFile(Path file, Path root) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.matches(".+\\.(test|spec)\\.[cm]?[jt]s")) {
            return true;
        }
        if (!name.matches(".+\\.[cm]?[jt]s")) {
            return false; // an extensionless shebang script is a script, wherever it lives
        }
        Path parent = file.toAbsolutePath().normalize().getParent();
        Path top = root == null ? null : root.toAbsolutePath().normalize();
        for (Path dir = parent; dir != null && (top == null || dir.startsWith(top)); dir = dir.getParent()) {
            if (top != null && dir.equals(top)) {
                break;
            }
            String d = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (d.equals("test") || d.equals("tests") || d.equals("__tests__")) {
                return true;
            }
            if (top == null) {
                break; // with no root only the file's own folder counts
            }
        }
        return false;
    }

    /**
     * What to run.
     *
     * @param target the file as the machine running it names it
     * @param fileName the file's own name (TypeScript, test and shebang decisions)
     * @param args program arguments (ignored for a test run)
     * @param firstLine the file's first line (its shebang), or null
     * @param runtime the Node to use (from {@link NodeRuntimes}, or a host's {@code node} tool)
     * @param fromConfiguration whether {@code runtime} is a run configuration's own Interpreter (then a node
     *     shebang's interpreter does not replace it, nor does another runner's)
     * @param version what {@code runtime --version} printed, or null when not asked or unknown
     * @param nodeOptions a run configuration's Node arguments, already tokenized (they replace a shebang's flags)
     * @param test whether to run the file's tests ({@code node --test})
     * @param noColor whether {@code NO_COLOR} is set where the program runs
     * @param userEnv a run configuration's variables (they win)
     * @param existsHere for a run on this machine, whether an absolute interpreter path exists; null on a host
     */
    public record Request(
            String target,
            String fileName,
            List<String> args,
            String firstLine,
            NodeRuntimes.NodeRuntime runtime,
            boolean fromConfiguration,
            String version,
            List<String> nodeOptions,
            boolean test,
            boolean noColor,
            Map<String, String> userEnv,
            Predicate<String> existsHere) {
        public Request {
            args = args == null ? List.of() : List.copyOf(args);
            nodeOptions = nodeOptions == null ? List.of() : List.copyOf(nodeOptions);
            userEnv = userEnv == null ? Map.of() : userEnv;
        }
    }

    /** Plans the launch (see the class comment). */
    public static NodeRunPlan plan(Request r) {
        ShellCommand.Launch shebang = ShellCommand.launch(r.firstLine(), r.fileName(), "", "", r.existsHere());
        boolean hasShebang = r.firstLine() != null && r.firstLine().strip().startsWith("#!");
        List<String> interpreter = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        boolean otherRunner = false;
        if (hasShebang && !r.fromConfiguration()) {
            String named = shebang.interpreter().get(0);
            List<String> shebangFlags =
                    shebang.interpreter().subList(1, shebang.interpreter().size());
            if (isNodeName(named)) {
                interpreter.addAll(r.runtime().command());
                flags.addAll(shebangFlags);
            } else {
                otherRunner = true; // deno / bun / tsx / ts-node: as the file asks
                interpreter.addAll(shebang.interpreter());
            }
        } else {
            interpreter.addAll(r.runtime().command());
        }
        if (!r.nodeOptions().isEmpty()) {
            flags.clear();
            flags.addAll(r.nodeOptions());
        }
        if (!otherRunner && isTypeScript(r.fileName()) && r.version() != null) {
            if (NodeRuntimes.parseVersion(r.version()).isPresent() && !NodeRuntimes.stripsTypes(r.version())) {
                return new NodeRunPlan(List.of(), Map.of(), null, NEEDS_STRIP);
            }
        }
        List<String> argv = new ArrayList<>(interpreter);
        if (!otherRunner) {
            if (!flags.contains(SOURCE_MAPS) && !interpreter.contains(SOURCE_MAPS)) {
                argv.add(SOURCE_MAPS);
            }
            argv.addAll(flags);
            if (r.test()) {
                argv.add("--test");
                argv.add("--test-reporter=tap");
            }
        }
        argv.add(r.target());
        if (!r.test()) {
            argv.addAll(r.args());
        }
        Map<String, String> env = new LinkedHashMap<>();
        if (!r.noColor() && !r.userEnv().containsKey("NO_COLOR")) {
            env.put("FORCE_COLOR", "1");
        }
        env.putAll(shebang.env());
        env.putAll(r.userEnv());
        return new NodeRunPlan(List.copyOf(argv), java.util.Collections.unmodifiableMap(env), shebang.chdir(), null);
    }

    /** Whether an interpreter name or path is Node itself ({@code node}, {@code nodejs}, {@code node.exe}). */
    public static boolean isNodeName(String interpreter) {
        if (interpreter == null || interpreter.isBlank()) {
            return false;
        }
        String s = interpreter.replace('\\', '/');
        String base = s.substring(s.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (base.endsWith(".exe")) {
            base = base.substring(0, base.length() - 4);
        }
        return base.equals("node") || base.equals("nodejs");
    }

    /** The program names whose Stop sends SIGINT first (Node and the package managers that start it). */
    public static boolean interruptsOnStop(String executable) {
        if (executable == null || executable.isBlank()) {
            return false;
        }
        String s = executable.replace('\\', '/');
        String base = s.substring(s.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        base = base.replaceFirst("\\.(exe|cmd|bat)$", "");
        return switch (base) {
            case "node", "nodejs", "npm", "npx", "pnpm", "yarn", "bun", "deno", "tsx" -> true;
            default -> false;
        };
    }
}
