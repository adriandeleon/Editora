package com.editora.ui;

import java.nio.file.Path;
import java.util.Map;

import com.editora.dap.LaunchJson;
import com.editora.dap.ServerReadyAction;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What a launch.json configuration asks for besides its launch: its {@code serverReadyAction}, watched in the program's
 * output, and its {@code postDebugTask}, run once the session ends. Set on the session just before the configuration
 * is started; it counts from the session's next start ({@link #started}) to that start's end, so an end still to be
 * reported for the launch it replaced does not run the task. FX thread only.
 */
final class LaunchFollowUp {

    final String name;
    final @Nullable ServerReadyAction serverReady;
    final String postDebugTask;
    final Path launchJson;
    final Path workspace;
    final LaunchJson.File parsed;
    final Map<String, String> answers;
    final @Nullable EditorBuffer buffer;

    /** Why its {@code serverReadyAction} is not followed, for the console; empty when it is (or there is none). */
    String problem = "";

    /** The session has started the launch this is for. */
    boolean started;

    /** The session the server's address started ({@code debugWithChrome}, {@code startDebugging}), or null. */
    @Nullable
    DebugSession child;

    /** {@link #child}'s {@link DebugSession#runtimeGeneration} when it was asked to start. */
    long childAt;

    LaunchFollowUp(
            String name,
            @Nullable ServerReadyAction serverReady,
            String postDebugTask,
            Path launchJson,
            Path workspace,
            LaunchJson.File parsed,
            Map<String, String> answers,
            @Nullable EditorBuffer buffer) {
        this.name = name;
        this.serverReady = serverReady;
        this.postDebugTask = postDebugTask;
        this.launchJson = launchJson;
        this.workspace = workspace;
        this.parsed = parsed;
        this.answers = answers;
        this.buffer = buffer;
    }

    /** Whether {@link #child} still runs the launch the address started (as {@link DebugCompound#holds} counts). */
    boolean childHolds() {
        DebugSession c = child;
        return c != null && c.runtimeGeneration <= childAt + 2;
    }
}
