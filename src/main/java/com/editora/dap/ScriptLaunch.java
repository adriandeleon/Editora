package com.editora.dap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a Ruby or PHP script is debugged, as VS Code's rdbg and PHP Debug extensions start it. Pure.
 *
 * <ul>
 *   <li><b>Ruby</b>: the script runs under {@code rdbg} ({@link DebugLanguages#RUBY}'s command), which is the debug
 *       adapter: its words follow the command ({@code -- ruby script.rb args}); the {@code launch} request only tells
 *       rdbg that it runs on this machine.
 *   <li><b>PHP</b>: vscode-php-debug starts {@code php} with Xdebug told to connect to the port it listens on
 *       ({@code port: 0}: a free one, put where {@code ${port}} stands).
 * </ul>
 */
public final class ScriptLaunch {

    private ScriptLaunch() {}

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
