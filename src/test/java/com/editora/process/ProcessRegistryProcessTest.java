package com.editora.process;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledOnOs(OS.WINDOWS)
class ProcessRegistryProcessTest {

    public static final class ExitDuringGrace {
        public static void main(String[] args) throws Exception {
            ProcessRegistry.installShutdownHook();
            Process parent = new ProcessBuilder(
                            "/usr/bin/python3",
                            "-u",
                            "-c",
                            "import subprocess,sys,time; "
                                    + "p=subprocess.Popen([sys.executable,'-u','-c',"
                                    + "'import signal,os,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);"
                                    + "print(os.getpid(),flush=True);time.sleep(30)'],stdout=subprocess.PIPE,text=True); "
                                    + "print(p.stdout.readline().strip(),flush=True);time.sleep(30)")
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(parent.getInputStream()))) {
                System.out.println(reader.readLine());
                System.out.flush();
            }
            ProcessRegistry.track(parent);
            ProcessRegistry.killTree(parent);
            parent.waitFor(3, TimeUnit.SECONDS);
            Thread.sleep(50);
            System.exit(0);
        }
    }

    @Test
    void forcePhaseRetainsAChildAfterItsParentExits() throws Exception {
        Process parent = new ProcessBuilder(
                        "/usr/bin/python3",
                        "-u",
                        "-c",
                        "import subprocess,sys,time; "
                                + "p=subprocess.Popen([sys.executable,'-u','-c',"
                                + "'import signal,os,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);"
                                + "print(os.getpid(),flush=True);time.sleep(30)'],stdout=subprocess.PIPE,text=True); "
                                + "print(p.stdout.readline().strip(),flush=True);time.sleep(30)")
                .start();
        long childPid;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(parent.getInputStream()))) {
            childPid = Long.parseLong(reader.readLine());
        }
        ProcessHandle child = ProcessHandle.of(childPid).orElseThrow();

        try {
            ProcessRegistry.track(parent);
            ProcessRegistry.killTree(parent);
            assertTrue(parent.waitFor(3, TimeUnit.SECONDS), "the wrapper should accept TERM");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (child.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertFalse(child.isAlive(), "the retained descendant must receive the forced phase");
        } finally {
            child.destroyForcibly();
            parent.destroyForcibly();
        }
    }

    @Test
    void shutdownHookOwnsCapturedChildrenDuringTheGracePeriod() throws Exception {
        Process helper = new ProcessBuilder(
                        java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                                .toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        ExitDuringGrace.class.getName())
                .start();
        ProcessHandle child = null;
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(helper.getInputStream()))) {
                child = ProcessHandle.of(Long.parseLong(reader.readLine())).orElseThrow();
            }
            assertTrue(helper.waitFor(8, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (child.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertFalse(child.isAlive(), "the forked JVM shutdown hook must force-kill the captured child");
        } finally {
            if (child != null) {
                child.destroyForcibly();
            }
            helper.destroyForcibly();
        }
    }

    /**
     * A server that was just sent {@code shutdown}/{@code exit} is waited for (bounded) by the shutdown
     * hook instead of being force-killed in the middle of saving its state.
     */
    @Test
    void anExpectedExitIsAwaitedWithinABound() throws Exception {
        Process reader = new ProcessBuilder("/usr/bin/python3", "-c", "import sys; sys.stdin.read()").start();
        try {
            ProcessRegistry.expectExit(reader);
            assertFalse(
                    ProcessRegistry.awaitExpectedExits(50),
                    "a process that is still running must not be waited on forever");

            reader.getOutputStream().close(); // its own way out: end of input
            assertTrue(ProcessRegistry.awaitExpectedExits(10_000), "an exiting process should be awaited");
            assertTrue(reader.waitFor(5, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertEquals(0, reader.exitValue(), "it left by itself, not by a kill");
        } finally {
            reader.destroyForcibly();
        }
    }

    /** Once its owner gives up and kills it, a process is no longer something the hook waits for. */
    @Test
    void killTreeEndsTheWaitForAnExpectedExit() throws Exception {
        Process reader = new ProcessBuilder("/usr/bin/python3", "-c", "import sys; sys.stdin.read()").start();
        try {
            ProcessRegistry.expectExit(reader);
            ProcessRegistry.killTree(reader);
            assertTrue(ProcessRegistry.awaitExpectedExits(0), "a killed process must not hold the shutdown hook");
        } finally {
            reader.destroyForcibly();
        }
    }
}
