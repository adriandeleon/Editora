package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * Hot code replace in a Java session: priming the build, applying the changes through java-debug and reading
 * its answer.
 *
 * <p>Moved out of {@link DapManager} for size: the code is the manager's, reaching its session state through
 * {@link #dap}, and runs on the same threads with the same session checks as it did there.
 */
final class HotCodeReplace {
    private final DapManager dap;

    HotCodeReplace(DapManager dap) {
        this.dap = dap;
    }

    void onHotCodeReplace(long epoch, String changeType, String message) {
        dap.fx(() -> {
            if (dap.isCurrent(epoch)) {
                dap.outputPump.flush(); // after the program output that preceded it
                dap.codeReplaceNotices.accept(changeType, message);
            }
        });
    }

    /** The session whose jdtls build {@link #primeCodeReplace} has started; -1 for none. */
    private long codeReplacePrimedEpoch = -1;

    /**
     * Gives jdtls a build to compare saved changes with. A program Editora compiled itself (a Java file outside
     * a build project) runs classes jdtls never built: without an earlier build, jdtls's first one reports every
     * class as new rather than changed, and java-debug then has nothing to replace. Building once at session
     * start fixes that. Only then: that build writes to jdtls's own output in its workspace, not the user's
     * folder (a build project's output is built before its launch, see the launch path). Once per session.
     */
    void primeCodeReplace() {
        if (dap.compilationDirectory == null
                || dap.debugFile == null
                || !dap.canReplaceCode()
                || codeReplacePrimedEpoch == dap.sessionEpoch) {
            return;
        }
        codeReplacePrimedEpoch = dap.sessionEpoch;
        dap.buildJavaWorkspace(dap.debugFile, false, built -> {});
    }

    /**
     * Apply Code Changes: has jdtls build {@code file}'s workspace (incrementally — Editora runs it without
     * autobuild, so nothing else compiles a saved file), then asks java-debug to load the rebuilt classes into
     * the running program. A build with compile errors replaces nothing. {@code cb} runs on the FX thread,
     * exactly once — with {@link DapManager.CodeReplaceResult#NO_SESSION} when the session ended meanwhile.
     */
    void applyCodeChanges(@Nullable Path file, Consumer<DapManager.CodeReplaceOutcome> cb) {
        DapClient c = dap.client;
        if (c == null || !dap.canReplaceCode() || file == null) {
            cb.accept(new DapManager.CodeReplaceOutcome(DapManager.CodeReplaceResult.NO_SESSION, List.of(), ""));
            return;
        }
        long epoch = dap.sessionEpoch;
        dap.buildJavaWorkspace(file, false, built -> {
            if (!dap.isCurrent(epoch, c)) {
                cb.accept(ended()); // the caller waits for an answer (review D101)
                return;
            }
            switch (built) {
                case WITH_ERRORS ->
                    cb.accept(new DapManager.CodeReplaceOutcome(
                            dap.javacEpoch == epoch
                                    ? DapManager.CodeReplaceResult.JAVAC_CLASSES
                                    : DapManager.CodeReplaceResult.COMPILE_ERRORS,
                            List.of(),
                            ""));
                case SUCCEEDED ->
                    c.redefineClasses()
                            .whenComplete((r, e) -> dap.fx(() -> {
                                cb.accept(
                                        dap.isCurrent(epoch, c)
                                                ? codeReplaceOutcome(r, e == null ? null : DapManager.msg(e))
                                                : ended());
                            }));
                default ->
                    cb.accept(new DapManager.CodeReplaceOutcome(
                            DapManager.CodeReplaceResult.BUILD_FAILED, List.of(), ""));
            }
        });
    }

    /** The outcome of a replace whose session ended before it finished. */
    private static DapManager.CodeReplaceOutcome ended() {
        return new DapManager.CodeReplaceOutcome(DapManager.CodeReplaceResult.NO_SESSION, List.of(), "");
    }

    /** Pure: the outcome of a {@code redefineClasses} answer, or of the request failing with {@code error}. */
    static DapManager.CodeReplaceOutcome codeReplaceOutcome(DapModels.CodeReplace reply, @Nullable String error) {
        if (error != null) {
            return new DapManager.CodeReplaceOutcome(DapManager.CodeReplaceResult.FAILED, List.of(), error);
        }
        if (reply == null || (!reply.failed() && reply.changedClasses().isEmpty())) {
            return new DapManager.CodeReplaceOutcome(DapManager.CodeReplaceResult.NOTHING_CHANGED, List.of(), "");
        }
        // java-debug words "JVM contains obsolete methods" as an error although the classes were replaced: a method
        // that was running when the code changed (main's loop, typically) goes on with its old code. That is a
        // replace that worked, with a note (review 2, B103) — it used to read "not applied, restart".
        if (!reply.failed() || !reply.changedClasses().isEmpty() || reply.obsoleteMethods()) {
            return new DapManager.CodeReplaceOutcome(
                    DapManager.CodeReplaceResult.APPLIED, reply.changedClasses(), reply.error());
        }
        return new DapManager.CodeReplaceOutcome(
                DapManager.CodeReplaceResult.FAILED, reply.changedClasses(), reply.error());
    }
}
