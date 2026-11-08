package com.editora.dap;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which running programs Attach to Process lists, and where each listens for a debugger. */
class LocalProcessesTest {

    private static LocalProcesses.Target of(String command, String... args) {
        return LocalProcesses.classify(42, command, List.of(args));
    }

    @Test
    void aJvmListensWhereItsJdwpAgentSays() {
        var t = of(
                "/usr/lib/jvm/jdk-25/bin/java",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005",
                "-cp",
                "app.jar:lib/*",
                "com.example.Main",
                "arg");
        assertEquals(LocalProcesses.Kind.JAVA, t.kind());
        assertEquals("com.example.Main", t.name(), "the classpath is an option's value, not the main class");
        assertEquals("localhost:5005", t.address(), "a wildcard host is this machine");

        assertEquals(
                "127.0.0.1:8000",
                of(
                                "C:\\Java\\bin\\java.exe",
                                "-Xrunjdwp:transport=dt_socket,server=y,address=127.0.0.1:8000",
                                "-jar",
                                "build\\app.jar")
                        .address());
        assertEquals("app.jar", of("javaw", "-jar", "/opt/app.jar").name());
        assertEquals("demo.App", of("java", "-p", "mods", "-m", "demo/demo.App").name());
        assertEquals(
                "localhost:9",
                of("java", "-agentlib:jdwp=server=y,address=[::]:9", "M").address());
    }

    @Test
    void aJvmThatConnectsOutOrChoseItsPortIsNotAttachable() {
        assertFalse(of("java", "-agentlib:jdwp=transport=dt_socket,server=n,address=localhost:5005", "M")
                .attachable());
        assertFalse(of("java", "-agentlib:jdwp=transport=dt_socket,server=y,address=0", "M")
                .attachable());
        assertFalse(of("java", "-agentlib:jdwp=transport=dt_socket,server=y,address=127.0.0.1:0", "M")
                .attachable());
        var plain = of("java", "-Xmx1g", "com.example.Main");
        assertFalse(plain.attachable());
        assertEquals("", plain.address());
        assertEquals("com.example.Main", plain.name());
    }

    @Test
    void nodeListensWhenItsInspectorIsOn() {
        assertEquals(
                "localhost:9229", of("/usr/bin/node", "--inspect", "server.js").address());
        assertEquals("server.js", of("node", "--inspect", "/srv/server.js").name());
        assertEquals(
                "127.0.0.1:9333",
                of("node.exe", "--inspect-brk=127.0.0.1:9333", "a.js").address());
        assertEquals("localhost:9230", of("node", "--inspect-wait=9230", "a.js").address());
        assertEquals(
                "localhost:9444",
                of("node", "--inspect-port=9444", "--inspect", "a.js").address());
        assertFalse(of("node", "--inspect-port=9444", "a.js").attachable(), "a port alone does not listen");
        assertFalse(of("node", "a.js").attachable());
    }

    @Test
    void pythonListensWhenDebugpyDoes() {
        var t = of("/usr/bin/python3.13", "-m", "debugpy", "--listen", "0.0.0.0:5678", "--wait-for-client", "app.py");
        assertEquals(LocalProcesses.Kind.PYTHON, t.kind());
        assertEquals("localhost:5678", t.address());
        assertEquals("app.py", t.name());
        assertEquals(
                "web",
                of("python", "-m", "debugpy", "--listen", "5678", "-m", "web").name());
        assertEquals(
                "localhost:5678",
                of("pythonw.exe", "-m", "debugpy", "--listen", "5678", "x.py").address());
        assertFalse(of("python3", "-m", "debugpy", "--connect", "5678", "x.py").attachable(), "it connects out");
        var plain = of("python3", "-X", "frozen_modules=off", "tool.py");
        assertFalse(plain.attachable());
        assertEquals("tool.py", plain.name(), "-X takes a value");
        assertEquals("pytest", of("python3", "-m", "pytest", "-q").name());
    }

    @Test
    void otherProgramsAreNotListed() {
        assertNull(of("/bin/bash", "-c", "java -version"));
        assertNull(of("/usr/bin/javac", "A.java"));
        assertNull(of("", ""));
    }

    @Test
    void addressesAndWindowsCommandLines() {
        assertArrayEquals(new String[] {"localhost", "5005"}, LocalProcesses.hostPort("5005"));
        assertArrayEquals(new String[] {"::1", "5005"}, LocalProcesses.hostPort("[::1]:5005"));
        assertArrayEquals(new String[] {"build.test", "8000"}, LocalProcesses.hostPort("build.test:8000"));
        assertNull(LocalProcesses.hostPort("[::1]5005"));
        assertEquals(
                List.of("C:\\Program Files\\Java\\bin\\java.exe", "-cp", "a b.jar", "Main"),
                LocalProcesses.splitWindows("\"C:\\Program Files\\Java\\bin\\java.exe\" -cp \"a b.jar\"  Main"));
        assertEquals(
                Map.of(12L, "java -jar a.jar", 30L, "node --inspect x.js"),
                LocalProcesses.parseWindowsList("12\tjava -jar a.jar\r\nWARNING: x\n30\tnode --inspect x.js\n"));
    }

    @Test
    void listeningProgramsComeFirst() {
        var idle = new LocalProcesses.Target(1, LocalProcesses.Kind.JAVA, "B", "localhost", 0);
        var node = new LocalProcesses.Target(2, LocalProcesses.Kind.NODE, "a.js", "localhost", 9229);
        var java = new LocalProcesses.Target(3, LocalProcesses.Kind.JAVA, "Z", "localhost", 5005);
        assertEquals(List.of(java, node, idle), LocalProcesses.sorted(List.of(idle, node, java)));
    }

    /** A real JVM listening for a debugger is found by its command line — on Windows through Win32_Process. */
    @Test
    void aRunningJvmListeningForADebuggerIsFound(@TempDir Path dir) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Path source = dir.resolve("Sleeper.java");
        Files.writeString(
                source,
                "class Sleeper { public static void main(String[] a) throws Exception {"
                        + " Thread.sleep(60_000); } }");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(
                        java,
                        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + port,
                        source.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            LocalProcesses.Target found = null;
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (found == null && System.nanoTime() < end) {
                for (LocalProcesses.Target t : LocalProcesses.scan()) {
                    if (t.pid() == p.pid()) {
                        found = t;
                    }
                }
                if (found == null) {
                    Thread.sleep(200);
                }
            }
            assertTrue(found != null, "the JVM is listed");
            assertEquals(LocalProcesses.Kind.JAVA, found.kind());
            assertEquals("127.0.0.1:" + port, found.address());
            long self = ProcessHandle.current().pid();
            assertTrue(LocalProcesses.scan().stream().noneMatch(t -> t.pid() == self), "never this program");
        } finally {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
        }
    }
}
