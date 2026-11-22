package com.editora.ui;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.editora.build.BuildTool;
import com.editora.config.RunConfiguration;
import com.editora.run.BuildBeforeLaunch;
import com.editora.run.RunTrust;
import org.jspecify.annotations.Nullable;

/**
 * Debug's guard for the build facts a launch asks of the disk on the FX thread — a build tool's wrapper, trust
 * summary and executable, the build tool of a folder, a Java file's project root (PERF-01, Pauta 6.0.1). When all are
 * known the launch goes on at once; else they are looked at off the FX thread and the launch step runs again when
 * they land. A second launch asked meanwhile is refused (the status says a program is busy), so nothing starts twice.
 * Nothing is guessed, and nothing is looked at on the FX thread. The asks here have no effect but the asking.
 */
final class DebugFacts {

    /** What {@code ops.buildExecutable(tool, root)} asks of the disk ({@code Ops.buildExecutableFacts}). */
    private final BiConsumer<BuildTool, Path> executableFacts;

    private final Runnable busy;

    /**
     * Who has a look under way (a session): a second launch of the same one is refused until it lands, while another
     * one's (a compound's next member) looks at the same time.
     */
    private final java.util.Set<Object> looking = new java.util.HashSet<>();

    DebugFacts(BiConsumer<BuildTool, Path> executableFacts, Runnable busy) {
        this(executableFacts, busy, why -> {});
    }

    DebugFacts(BiConsumer<BuildTool, Path> executableFacts, Runnable busy, Consumer<Exception> failed) {
        this.executableFacts = executableFacts;
        this.busy = busy;
        this.failed = failed;
    }

    /**
     * Says why a look failed (a remote host gone, a folder that cannot be read). Its launch stops there: what failed
     * is not known after it either, so looking again started another look, forever, without a word.
     */
    private final Consumer<Exception> failed;

    /**
     * Whether everything {@code asks} asks of the disk is known now. If not, it is looked at off the FX thread and
     * {@code again} runs on the FX thread when it lands; a second ask of {@code who}'s while its look is under way is
     * refused.
     */
    boolean known(String name, Object who, Runnable asks, Runnable again) {
        if (com.editora.perf.Looked.allKnown(asks)) {
            return true;
        }
        if (!looking.add(who)) {
            busy.run();
            return false;
        }
        DebugPlanning.offFx(
                name,
                () -> {
                    asks.run();
                    return Boolean.TRUE;
                },
                done -> {
                    looking.remove(who);
                    if (done.failure() != null) {
                        failed.accept(done.failure());
                    } else {
                        again.run();
                    }
                });
        return false;
    }

    /** What {@code ops.buildExecutable(tool, root)} asks. */
    Runnable executable(BuildTool tool, Path root) {
        return () -> executableFacts.accept(tool, root);
    }

    /** What the trust question of {@code gate} asks. */
    Runnable gate(RunTrust.@Nullable Gate gate) {
        return () -> {
            if (gate != null) {
                executableFacts.accept(gate.tool(), gate.root());
            }
        };
    }

    /** A file's launch: its gate, and for a Java file its project and that project's build files. */
    Runnable fileLaunch(RunTrust.@Nullable Gate gate, String language, Path path) {
        return () -> {
            gate(gate).run();
            if ("java".equals(language)) {
                Path found = JavaProjectRoot.find(path);
                if (found != null) {
                    RunOpsHost.isMavenProject(found);
                }
            }
        };
    }

    /** A Java configuration's launch: the build tool at {@code buildRoot}, its executable, the step's tool. */
    Runnable javaConfig(RunConfiguration cfg, @Nullable Path buildRoot, Path root) {
        return () -> {
            buildRoot(buildRoot).run();
            BuildBeforeLaunch.Symbolic symbolic = BuildBeforeLaunch.symbolic(cfg.beforeLaunch());
            if (symbolic != null) {
                executableFacts.accept(symbolic.tool(), buildRoot != null ? buildRoot : root);
            }
        };
    }

    /** The build tool at {@code buildRoot} and its executable. */
    Runnable buildRoot(@Nullable Path buildRoot) {
        return () -> {
            if (buildRoot != null) {
                BuildTool tool = BuildBeforeLaunch.toolAt(buildRoot);
                if (tool != null) {
                    executableFacts.accept(tool, buildRoot);
                }
            }
        };
    }

    /** A main-class launch from {@code routing}: its project, that project's build files and build tool. */
    Runnable mainClass(Path routing) {
        return () -> {
            Path buildRoot = JavaProjectRoot.find(routing);
            if (buildRoot != null) {
                RunOpsHost.isMavenProject(buildRoot);
                buildRoot(buildRoot).run();
            }
        };
    }
}
