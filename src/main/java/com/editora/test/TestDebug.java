package com.editora.test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.build.BuildTool;
import org.jspecify.annotations.Nullable;

/**
 * "Debug this test": the JVM build tools can fork the test JVM suspended on a JDWP port — Maven via
 * {@code -Dmaven.surefire.debug}, Gradle via {@code --debug-jvm}. Gradle prints the standard
 * {@code Listening for transport dt_socket at address: 5005} line, which {@link #jdwpPort} recognizes so the
 * coordinator can attach the debugger to the waiting JVM; Surefire 3 withholds that line until the fork has
 * run, so for Maven the port is chosen up front and polled instead ({@link JdwpAttachRetry}). Pure.
 *
 * <p>Only Maven/Gradle are supported (Go/Cargo/npm have no equivalent one-flag suspend-and-wait).
 */
public final class TestDebug {

    private TestDebug() {}

    /**
     * The Maven flag that forks the test JVM suspended on a JDWP port Editora chose ({@link #mavenDebugArg}):
     * surefire's own default is a fixed 5005 (review A15), and a system-picked port ({@code :0}) is only ever
     * announced through a banner Surefire 3 withholds until the suspended JVM has run (review D1). For Gradle
     * an init script picks ports and prints the banner, and {@code --debug-jvm} (always 5005) is the fallback.
     */
    private static final String MAVEN_DEBUG_PREFIX = "-Dmaven.surefire.debug=";

    /** {@code -Dmaven.surefire.debug=-agentlib:jdwp=…,suspend=y,address=127.0.0.1:<port>}. */
    public static String mavenDebugArg(int port) {
        return MAVEN_DEBUG_PREFIX + com.editora.build.BuildDebug.jdwpAgent(port);
    }

    /** Surefire's own banner, printed once the build reaches the test phase: the attach wait restarts there. */
    public static boolean isSurefireTestsBanner(String line) {
        return line != null && line.contains("T E S T S");
    }

    private static final String GRADLE_DEBUG = "--debug-jvm";

    /** Standard JDWP banner printed by a suspended JVM, e.g. {@code … at address: 5005} (or {@code *:5005}). */
    private static final Pattern JDWP =
            Pattern.compile("Listening for transport dt_socket at address:\\s*(?:[^\\s:]*:)?(\\d{2,5})");

    /**
     * Task args that run one test class/method under a suspended debuggee, or an empty list when the tool
     * can't ({@code null} {@code methodName} debugs the whole class). Maven forks the JVM on {@code mavenPort},
     * a free loopback port chosen beforehand ({@link JdwpAttachRetry#freeLoopbackPort}); {@code gradleInitScript},
     * when given, debugs Gradle's test JVMs on ports the system picks (see {@code GradleDebugInit}) instead of
     * {@code --debug-jvm}'s fixed 5005.
     */
    public static List<String> debugTaskArgs(
            BuildTool tool,
            String className,
            String methodName,
            java.nio.file.@Nullable Path gradleInitScript,
            int mavenPort) {
        List<String> base = TestRunRecognizer.singleTestTask(tool, className, methodName);
        if (base.isEmpty()) {
            return List.of(); // non-JVM tool — no supported suspend-and-wait flag
        }
        List<String> args = new ArrayList<>(base);
        if (tool != BuildTool.GRADLE) {
            args.add(mavenDebugArg(mavenPort));
        } else if (gradleInitScript != null) {
            args.addAll(com.editora.build.GradleDebugInit.args(gradleInitScript));
        } else {
            args.add(GRADLE_DEBUG);
        }
        return List.copyOf(args);
    }

    /** The Gradle task whose forked JVMs a test debug run debugs. */
    public static final String GRADLE_TEST_TASK = "test";

    /** The port from a JDWP "Listening for transport…" line, or {@code -1} when the line isn't one. */
    public static int jdwpPort(String line) {
        if (line == null || line.isEmpty()) {
            return -1;
        }
        Matcher m = JDWP.matcher(line);
        if (!m.find()) {
            return -1;
        }
        try {
            int port = Integer.parseInt(m.group(1));
            return port > 0 && port <= 65535 ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
