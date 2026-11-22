package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.build.BuildTool;
import com.editora.test.GradleTaskOutcomes;
import com.editora.test.ParsedSuite;
import com.editora.test.TapParser;
import com.editora.test.TestDebug;
import com.editora.test.TestResultParser;
import com.editora.test.TestRunRecognizer;
import org.jspecify.annotations.Nullable;

/**
 * What one claimed test run's output lines come to, worked out where the output is read, not on the FX thread
 * (PERF-01, Pauta 6.2.0): the result parser ({@code go test -json}, Cargo, TAP, the Node spec reporter) reads each
 * line, the npm sniff picks the parser, and the console text and the line facts the run needs (a JDWP banner, a
 * Gradle task line) are found here. The FX thread then applies the {@link Line} it is handed.
 *
 * <p>One stage per run. Its lines arrive one at a time and in order (the output pump prepares a sink's lines
 * under one lock), so its state needs no lock of its own; the FX thread reads that state only after the run's last
 * line was handed over (its exit), or reads a {@link Line}.
 */
final class TestLineStage {

    /** What a line came to: applied on the FX thread by {@code TestRunCoordinator}. */
    record Line(
            boolean surefireBanner,
            int jdwpPort,
            boolean noTestsMatching,
            boolean gradleTaskLine,
            @Nullable String reusedGradleTask,
            List<ParsedSuite> suites,
            @Nullable TestResultParser parser,
            boolean nodeTestRun,
            boolean tapDecided,
            @Nullable String shown) {}

    private final @Nullable BuildTool tool;
    private final boolean fileBased;
    private final boolean npm;
    private final int sniffLimit;
    private @Nullable TestResultParser parser;
    private boolean tapDecided;
    private boolean nodeTestRun;
    private final List<String> npmSniff = new ArrayList<>();

    TestLineStage(
            @Nullable BuildTool tool,
            @Nullable TestResultParser parser,
            boolean fileBased,
            boolean npm,
            int sniffLimit) {
        this.tool = tool;
        this.parser = parser;
        this.fileBased = fileBased;
        this.npm = npm;
        this.sniffLimit = sniffLimit;
    }

    /** Reads {@code line} (on the thread that read it from the process). */
    Line take(String line, boolean stderr) {
        boolean banner = TestDebug.isSurefireTestsBanner(line);
        int port = TestDebug.jdwpPort(line);
        if (fileBased) {
            boolean noTests = tool == BuildTool.MAVEN && TestRunRecognizer.isNoTestsMatchingLine(line);
            boolean taskLine = tool == BuildTool.GRADLE && GradleTaskOutcomes.isTaskLine(line);
            String reused = tool == BuildTool.GRADLE ? GradleTaskOutcomes.reusedTask(line) : null;
            return new Line(
                    banner,
                    port,
                    noTests,
                    taskLine,
                    reused,
                    List.of(),
                    parser,
                    nodeTestRun,
                    tapDecided,
                    shown(line, stderr));
        }
        List<ParsedSuite> suites = new ArrayList<>();
        if (npm && !tapDecided) {
            npmSniff.add(line);
            boolean tap = TapParser.looksLikeTap(npmSniff);
            if (!tap && com.editora.test.NodeSpecParser.looksLikeSpec(npmSniff)) {
                parser = new com.editora.test.NodeSpecParser(); // node --test's default reporter on a pipe
                nodeTestRun = true;
            }
            if (tap || nodeTestRun) {
                tapDecided = true;
                TestResultParser p = parser; // TAP's from the start, or the spec parser just chosen
                for (String buffered : npmSniff) {
                    if (p != null) {
                        suites.addAll(p.onLine(buffered, false));
                    }
                }
                npmSniff.clear();
            } else if (npmSniff.size() > sniffLimit) {
                npmSniff.clear(); // keep sniffing cheaply; the exit handler shows the fallback banner
            }
        } else {
            TestResultParser p = parser; // a build tool's run always has one
            if (p != null) {
                suites.addAll(p.onLine(line, stderr));
            }
        }
        return new Line(
                banner,
                port,
                false,
                false,
                null,
                List.copyOf(suites),
                parser,
                nodeTestRun,
                tapDecided,
                shown(line, stderr));
    }

    /** What the raw Output console shows for {@code raw}: the parser's text for it, or the line itself. */
    private @Nullable String shown(String raw, boolean stderr) {
        TestResultParser p = parser;
        return p != null ? p.consoleLine(raw, stderr) : raw;
    }
}
