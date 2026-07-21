package com.editora.test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.build.BuildTool;

/**
 * "Debug this test": the JVM build tools can fork the test JVM suspended on a JDWP port — Maven via
 * {@code -Dmaven.surefire.debug}, Gradle via {@code --debug-jvm}. Both then print the standard
 * {@code Listening for transport dt_socket at address: 5005} line, which {@link #jdwpPort} recognizes so the
 * coordinator can attach the debugger to the waiting JVM. Pure.
 *
 * <p>Only Maven/Gradle are supported (Go/Cargo/npm have no equivalent one-flag suspend-and-wait).
 */
public final class TestDebug {

    private TestDebug() {}

    /** The Maven/Gradle flag that forks the test JVM suspended on a JDWP port. */
    private static final String MAVEN_DEBUG = "-Dmaven.surefire.debug";

    private static final String GRADLE_DEBUG = "--debug-jvm";

    /** Standard JDWP banner printed by a suspended JVM, e.g. {@code … at address: 5005} (or {@code *:5005}). */
    private static final Pattern JDWP =
            Pattern.compile("Listening for transport dt_socket at address:\\s*(?:[^\\s:]*:)?(\\d{2,5})");

    /**
     * Task args that run one test class/method under a suspended debuggee, or an empty list when the tool
     * can't ({@code null} {@code methodName} debugs the whole class).
     */
    public static List<String> debugTaskArgs(BuildTool tool, String className, String methodName) {
        List<String> base = TestRunRecognizer.singleTestTask(tool, className, methodName);
        if (base.isEmpty()) {
            return List.of(); // non-JVM tool — no supported suspend-and-wait flag
        }
        List<String> args = new ArrayList<>(base);
        args.add(tool == BuildTool.GRADLE ? GRADLE_DEBUG : MAVEN_DEBUG);
        return List.copyOf(args);
    }

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
