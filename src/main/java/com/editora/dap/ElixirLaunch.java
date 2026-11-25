package com.editora.dap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.editora.run.ElixirRun;

/**
 * ElixirLS's debug requests ({@link DebugLanguages#ELIXIR}): a {@code mix_task} launch runs a Mix task in the project's
 * folder under Erlang's debugger, the project's modules interpreted. Probed with ElixirLS 0.31.1 on Elixir 1.18 / OTP
 * 27: {@code mix test test/x_test.exs:4} stops in a project module with its variables ({@code a}, {@code b}, {@code
 * sum}), the test at that line alone run; a launch without a Mix project fails ({@code Mix.NoProjectError}), so a lone
 * script is not debugged. Pure.
 */
public final class ElixirLaunch {

    private ElixirLaunch() {}

    /** ElixirLS's configuration type. */
    public static final String TYPE = "mix_task";

    /** The adapter's script in an ElixirLS release: {@code debug_adapter.sh}, {@code debug_adapter.bat} on Windows. */
    public static String adapterFile() {
        return com.editora.Os.isWindows() ? "debug_adapter.bat" : "debug_adapter.sh";
    }

    /**
     * The {@code launch} for {@code plan}, a Mix task's ({@link ElixirRun.Kind#TESTS}, {@link ElixirRun.Kind#MIX_RUN},
     * {@link ElixirRun.Kind#PHOENIX}).
     */
    public static Map<String, Object> launch(ElixirRun.Plan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", TYPE);
        m.put("name", "Editora (Elixir)");
        m.put("request", "launch");
        m.put("task", plan.task());
        m.put("taskArgs", plan.taskArgs());
        m.put(
                "projectDir",
                Objects.requireNonNull(plan.project(), "a Mix task needs its project")
                        .toString());
        m.put("startApps", plan.kind() == ElixirRun.Kind.TESTS); // mix test starts them; mix run does itself
        if (plan.kind() == ElixirRun.Kind.PHOENIX) {
            // phx.server returns once the endpoint is up; the server runs on. Without this ElixirLS reports the session
            // ended right then (probed with ElixirLS 0.31.1 and Phoenix 1.8), and Editora would end it.
            m.put("exitAfterTaskReturns", false);
        }
        if (!plan.requireFiles().isEmpty()) {
            m.put("requireFiles", plan.requireFiles());
        }
        return m;
    }
}
