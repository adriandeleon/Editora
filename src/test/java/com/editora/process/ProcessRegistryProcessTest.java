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
}
