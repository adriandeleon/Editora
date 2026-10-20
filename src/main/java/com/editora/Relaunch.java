package com.editora;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * Starting Editora again after it quits ("Restart now" for a setting that applies only at startup).
 *
 * <p>{@link #arm()} remembers this process's own command line; when the JVM then exits, a shutdown hook starts
 * that command again with {@value #WAIT_FOR} naming this process, and the new process waits for this one to be
 * gone ({@link #awaitPreviousInstance()}, the first thing {@code App.main} does) before it claims the config
 * folder — otherwise it would find this editor still running and hand its launch over to it. The quit itself
 * goes through the normal quit path, so unsaved files are asked about and a cancelled quit disarms the restart.
 */
public final class Relaunch {

    /** The environment variable naming the process a relaunched Editora waits for. */
    static final String WAIT_FOR = "EDITORA_RELAUNCH_WAIT_PID";

    private static final Logger LOG = Logger.getLogger(Relaunch.class.getName());
    private static volatile @Nullable List<String> armed;
    private static volatile boolean hooked;

    private Relaunch() {}

    /** This process's command line, or empty when the platform does not tell it. */
    static Optional<List<String>> commandLine() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        Optional<String> command = info.command();
        if (command.isEmpty()) {
            return Optional.empty();
        }
        List<String> line = new ArrayList<>();
        line.add(command.get());
        info.arguments().ifPresent(args -> line.addAll(List.of(args)));
        return Optional.of(line);
    }

    /** Whether a restart can be offered: the command line of this process is known. */
    public static boolean available() {
        return commandLine().isPresent();
    }

    /** Starts this command line again once the JVM exits. False (and nothing armed) when it is not known. */
    public static synchronized boolean arm() {
        Optional<List<String>> line = commandLine();
        if (line.isEmpty()) {
            return false;
        }
        armed = line.get();
        if (!hooked) {
            hooked = true;
            Runtime.getRuntime().addShutdownHook(new Thread(Relaunch::startArmed, "relaunch"));
        }
        return true;
    }

    /** Forgets an armed restart (the quit was cancelled). */
    public static void disarm() {
        armed = null;
    }

    private static void startArmed() {
        List<String> armedLine = armed;
        if (armedLine == null) {
            return;
        }
        // A "Restart now" after enabling plugins in a --disable-plugins session is meant to load them.
        List<String> line = new ArrayList<>(armedLine);
        line.removeIf("--disable-plugins"::equals);
        try {
            ProcessBuilder pb = new ProcessBuilder(line)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            com.editora.process.ProcessRunner.applyUserEnv(pb); // the editor runs in the user's own locale
            pb.environment().put(WAIT_FOR, Long.toString(ProcessHandle.current().pid()));
            pb.start();
        } catch (java.io.IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not start Editora again", e);
        }
    }

    /**
     * In a relaunched process: waits (at most 30 s) for the process it replaces to exit, so the config folder and
     * the single-instance endpoint are free. Does nothing for an ordinary launch.
     */
    static void awaitPreviousInstance() {
        awaitExit(System.getenv(WAIT_FOR), 30);
    }

    /** Waits up to {@code seconds} for process {@code pid} to exit; returns at once for a blank or unknown pid. */
    static void awaitExit(String pid, long seconds) {
        if (pid == null || pid.isBlank()) {
            return;
        }
        try {
            Optional<ProcessHandle> previous = ProcessHandle.of(Long.parseLong(pid.strip()));
            if (previous.isPresent()) {
                previous.get().onExit().get(seconds, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) { // NumberFormatException, ExecutionException, TimeoutException: start anyway
            LOG.log(Level.FINE, "Not waiting for the previous Editora", e);
        }
    }
}
