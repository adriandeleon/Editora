package com.editora.dap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a Ruby, PHP, Bash or Lua script is debugged, as VS Code's rdbg, PHP Debug, Bash Debug and Local Lua Debugger
 * extensions start it. Pure.
 *
 * <ul>
 *   <li><b>Ruby</b>: the script runs under {@code rdbg} ({@link DebugLanguages#RUBY}'s command), which is the debug
 *       adapter: its words follow the command ({@code -- ruby script.rb args}); the {@code launch} request only tells
 *       rdbg that it runs on this machine.
 *   <li><b>PHP</b>: vscode-php-debug starts {@code php} with Xdebug told to connect to the port it listens on
 *       ({@code port: 0}: a free one, put where {@code ${port}} stands).
 *   <li><b>Bash</b>: vscode-bash-debug runs the script under the {@code bashdb} it ships ({@code bashdb_dir}, next to its
 *       {@code out/bashDebug.js}), talking to it through two named pipes it makes with {@code mkfifo}; the request names
 *       each program by its absolute path ({@link #withBashTools}). Only a Bash script ({@link #isBashScript}): bashdb
 *       is Bash's.
 *   <li><b>Lua</b>: local-lua-debugger starts the interpreter with {@code -e "require('lldebugger').runFile(…)"},
 *       its {@code lldebugger.lua} found through the {@code extensionPath} the request names (VS Code's extension fills
 *       it in; here {@link #withLuaRuntime}, from where the adapter is), and talks to it over a pipe (FIFOs; named pipes
 *       on Windows), so the script's own output reaches the Debug console and is not read as the debugger's.
 * </ul>
 */
public final class ScriptLaunch {

    private ScriptLaunch() {}

    /** How a script of a language is debugged: on that adapter, with this request and these words after its command. */
    public record Plan(DebugLanguage language, Map<String, Object> launch, List<String> tail) {}

    /**
     * The plan for script {@code script} of {@code language} ({@code ruby}, {@code php}, {@code bash} or {@code lua}) in
     * {@code cwd}.
     */
    public static Plan plan(String language, Path script, List<String> args, Path cwd) {
        return switch (language) {
            case "ruby" -> new Plan(DebugLanguages.RUBY, rubyLaunch(script, args, cwd), rubyTail(script, args));
            case "bash" -> new Plan(DebugLanguages.BASH, bashLaunch(script, args, cwd), List.of());
            case "lua" -> new Plan(DebugLanguages.LUA, luaLaunch(script, args, cwd), List.of());
            default -> new Plan(DebugLanguages.PHP, phpLaunch(script, args, cwd), List.of());
        };
    }

    /** Shells whose scripts are not Bash: bashdb cannot run them. */
    private static final java.util.Set<String> OTHER_SHELLS =
            java.util.Set.of("zsh", "fish", "ksh", "mksh", "csh", "tcsh", "dash", "pwsh", "nu", "xonsh", "elvish");

    /**
     * Whether a shell file ({@code file}, its text {@code text}) is a Bash script bashdb can debug: its
     * shebang names {@code bash} or {@code sh} (or none, as a {@code .sh} file is run with), and its name is no other
     * shell's ({@code .zsh}, {@code .fish}).
     */
    public static boolean isBashScript(@org.jspecify.annotations.Nullable Path file, String text) {
        Path leaf = file == null ? null : file.getFileName();
        String name = leaf == null ? "" : leaf.toString().toLowerCase(java.util.Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && OTHER_SHELLS.contains(name.substring(dot + 1))) {
            return false;
        }
        String first = text == null ? "" : text.lines().findFirst().orElse("");
        if (!first.startsWith("#!")) {
            return true;
        }
        String[] words = first.substring(2).strip().split("\\s+");
        String program = words.length == 0 ? "" : words[0];
        if (program.endsWith("/env") && words.length > 1) {
            program = words[words.length > 2 && words[1].startsWith("-") ? 2 : 1];
        }
        String shell = program.substring(program.lastIndexOf('/') + 1);
        return shell.equals("bash") || shell.equals("sh");
    }

    private static final java.util.regex.Pattern BASH_VARIABLE =
            java.util.regex.Pattern.compile("\\s*\\$(?:\\{(\\w+)\\}|(\\w+))\\s*");

    /** A Bash variable written {@code $name} or {@code ${name}}, named bare; any other expression as it is. */
    public static String bashName(String expression) {
        java.util.regex.Matcher m = BASH_VARIABLE.matcher(expression == null ? "" : expression);
        if (!m.matches()) {
            return expression;
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    /** vscode-bash-debug's {@code launch} of {@code script} with {@code args}, run in {@code cwd}. */
    public static Map<String, Object> bashLaunch(Path script, List<String> args, Path cwd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", DebugLanguages.BASH.launchType());
        m.put("request", "launch");
        m.put("program", script.toString());
        m.put("args", List.copyOf(args));
        m.put("argsString", "");
        m.put("cwd", cwd.toString());
        m.put("env", Map.of());
        m.put("terminalKind", "debugConsole");
        m.put("showDebugOutput", false);
        m.put("trace", false);
        return m;
    }

    /**
     * {@code launch} with the programs vscode-bash-debug needs named by absolute path (it checks each exists): the
     * {@code bashdb} it ships, beside {@code adapter} ({@code <extension>/out/bashDebug.js} → {@code
     * <extension>/bashdb_dir}), and {@code bash}, {@code cat}, {@code mkfifo} and {@code pkill} as {@code resolve} finds
     * them. What the request already names (a launch.json's own) is kept.
     */
    public static Map<String, Object> withBashTools(
            Map<String, Object> launch, Path adapter, java.util.function.UnaryOperator<String> resolve) {
        Map<String, Object> out = new LinkedHashMap<>(launch);
        Path out1 = adapter.toAbsolutePath().getParent();
        Path extension = out1 == null ? null : out1.getParent();
        if (extension != null) {
            Path lib = extension.resolve("bashdb_dir");
            out.putIfAbsent("pathBashdb", lib.resolve("bashdb").toString());
            out.putIfAbsent("pathBashdbLib", lib.toString());
        }
        out.putIfAbsent("pathBash", resolve.apply("bash"));
        out.putIfAbsent("pathCat", resolve.apply("cat"));
        out.putIfAbsent("pathMkfifo", resolve.apply("mkfifo"));
        out.putIfAbsent("pathPkill", resolve.apply("pkill"));
        return out;
    }

    /** The Lua interpreter a launch names until {@link #withLuaRuntime} finds the one installed. */
    public static final String LUA = "lua";

    /** The interpreters looked for, in order, when no {@code lua} is on PATH (distributions name it by version). */
    public static final List<String> LUA_INTERPRETERS =
            List.of("lua", "lua5.4", "lua5.3", "luajit", "lua5.2", "lua5.1");

    /** local-lua-debugger's {@code launch} of {@code script} with {@code args}, run in {@code cwd}. */
    public static Map<String, Object> luaLaunch(Path script, List<String> args, Path cwd) {
        Map<String, Object> program = new LinkedHashMap<>();
        program.put("lua", LUA);
        program.put("file", script.toString());
        program.put("communication", "pipe"); // the script's stdout is its own, not the debugger's channel
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", DebugLanguages.LUA.launchType());
        m.put("request", "launch");
        m.put("name", "Editora (Lua)");
        m.put("program", program);
        if (!args.isEmpty()) {
            m.put("args", List.copyOf(args));
        }
        m.put("cwd", cwd.toString());
        m.put("stopOnEntry", false);
        return m;
    }

    /**
     * {@code launch} with what VS Code's extension adds before local-lua-debugger sees it: {@code extensionPath}, the
     * extension folder above {@code adapter} ({@code <extension>/extension/debugAdapter.js}), where its
     * {@code debugger/lldebugger.lua} is; {@code workspacePath} (the folder a relative {@code cwd} is read from); and,
     * when the request names the bare {@code lua}, the first of {@link #LUA_INTERPRETERS} {@code resolve} finds (an
     * absolute path), else {@code lua} as it was. What the request already names (a launch.json's own) is kept.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> withLuaRuntime(
            Map<String, Object> launch, Path adapter, java.util.function.UnaryOperator<String> resolve) {
        Map<String, Object> out = new LinkedHashMap<>(launch);
        Path scripts = adapter.toAbsolutePath().getParent();
        Path extension = scripts == null ? null : scripts.getParent();
        if (extension != null) {
            out.putIfAbsent("extensionPath", extension.toString());
        }
        Object cwd = out.get("cwd");
        out.putIfAbsent("workspacePath", cwd != null ? cwd.toString() : String.valueOf(adapter.toAbsolutePath()));
        out.putIfAbsent("cwd", out.get("workspacePath"));
        if (out.get("program") instanceof Map<?, ?> program && LUA.equals(program.get("lua"))) {
            Map<String, Object> p = new LinkedHashMap<>((Map<String, Object>) program);
            for (String name : LUA_INTERPRETERS) {
                String found = resolve.apply(name);
                if (found != null && Path.of(found).isAbsolute()) {
                    p.put("lua", found);
                    break;
                }
            }
            out.put("program", p);
        }
        return out;
    }

    /** What follows rdbg's command for {@code script}: {@code -- ruby <script> <args>}. */
    public static List<String> rubyTail(Path script, List<String> args) {
        List<String> out = new ArrayList<>(List.of("--", "ruby", script.toString()));
        out.addAll(args);
        return out;
    }

    /** rdbg's {@code launch} for {@code script}, run in {@code cwd} on this machine's files. */
    public static Map<String, Object> rubyLaunch(Path script, List<String> args, Path cwd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", DebugLanguages.RUBY.launchType());
        m.put("request", "launch");
        m.put("script", script.toString());
        m.put("args", List.copyOf(args));
        m.put("cwd", cwd.toString());
        m.put("localfs", true);
        return m;
    }

    /** rdbg's {@code attach}, to a program already under {@code rdbg --open} (it goes on running when it ends). */
    public static Map<String, Object> rubyAttach() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", DebugLanguages.RUBY.launchType());
        m.put("request", "attach");
        m.put("localfs", true);
        return m;
    }

    /** What tells Xdebug to debug the script and connect to the adapter's port ({@code ${port}}). */
    public static final List<String> XDEBUG_ARGS = List.of(
            "-dxdebug.mode=debug",
            "-dxdebug.start_with_request=yes",
            "-dxdebug.client_host=127.0.0.1",
            "-dxdebug.client_port=${port}");

    /**
     * Xdebug's settings as environment variables, which reach every PHP the launch starts — a PHPUnit runner's
     * wrapper's, a server's — where the {@code -d} switches reach only the first: debug mode, a session started for
     * every script and request, and the adapter's port ({@code ${port}}).
     */
    public static final Map<String, String> XDEBUG_ENV = Map.of(
            "XDEBUG_MODE", "debug",
            "XDEBUG_SESSION", "editora",
            "XDEBUG_CONFIG", "client_host=127.0.0.1 client_port=${port}");

    /** The port Xdebug connects to by default (Xdebug 3), where Listen for Xdebug waits. */
    public static final int XDEBUG_PORT = 9003;

    /**
     * vscode-php-debug's {@code launch} for {@code command} ({@code php …}, or a PHPUnit runner) in {@code cwd}: with
     * Xdebug's switches after {@code php} when {@code php} says the program is PHP itself, and its settings in the
     * environment for any PHP it starts. Each script run or request served is a thread of the session.
     */
    public static Map<String, Object> phpCommand(List<String> command, boolean php, Path cwd) {
        List<String> runtimeArgs = new ArrayList<>();
        if (php) {
            runtimeArgs.addAll(XDEBUG_ARGS);
        }
        runtimeArgs.addAll(command.subList(1, command.size()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", DebugLanguages.PHP.launchType());
        m.put("request", "launch");
        m.put("port", 0);
        m.put("runtimeExecutable", command.getFirst());
        m.put("runtimeArgs", List.copyOf(runtimeArgs));
        m.put("cwd", cwd.toString());
        m.put("env", XDEBUG_ENV);
        return m;
    }

    /**
     * VS Code's "Listen for Xdebug": vscode-php-debug waits on {@code port} for Xdebug to connect from a PHP the user
     * starts — a web server's request, a script run with {@code XDEBUG_SESSION} — until the session is stopped.
     */
    public static Map<String, Object> phpListen(int port) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", DebugLanguages.PHP.launchType());
        m.put("request", "launch");
        m.put("name", "Listen for Xdebug");
        m.put("port", port);
        return m;
    }

    /** vscode-php-debug's {@code launch} for {@code program}: {@code php} with Xdebug on, in {@code cwd}. */
    public static Map<String, Object> phpLaunch(Path program, List<String> args, Path cwd) {
        return ExternalAdapter.launchRequest(
                DebugLanguages.PHP.launchType(),
                program.toString(),
                args,
                cwd.toString(),
                Map.of(),
                false,
                Map.of("port", 0, "runtimeExecutable", "php", "runtimeArgs", XDEBUG_ARGS));
    }
}
