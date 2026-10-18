package com.editora.dap;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchConfigTest {

    @Test
    void launchEmitsRequiredFieldsAndOmitsBlankOptionals() {
        Map<String, Object> m =
                LaunchConfig.launch("com.example.Main", "", List.of(), List.of(), "", "", List.of(), "", false);
        assertEquals("java", m.get("type"));
        assertEquals("launch", m.get("request"));
        assertEquals("com.example.Main", m.get("mainClass"));
        assertEquals("internalConsole", m.get("console"));
        assertEquals(false, m.get("stopOnEntry"));
        // Blank/empty optionals are omitted entirely.
        assertFalse(m.containsKey("projectName"));
        assertFalse(m.containsKey("classPaths"));
        assertFalse(m.containsKey("modulePaths"));
        assertFalse(m.containsKey("javaExec"));
        assertFalse(m.containsKey("cwd"));
        assertFalse(m.containsKey("args"));
        assertFalse(m.containsKey("vmArgs"));
    }

    @Test
    void launchEmitsVmArgsWhenProvided() {
        Map<String, Object> m =
                LaunchConfig.launch("M", "", List.of(), List.of(), "", "", List.of(), "-Xmx512m -Dk=v", false);
        assertEquals("-Xmx512m -Dk=v", m.get("vmArgs")); // verbatim string, not tokenized
    }

    @Test
    void launchIncludesProvidedOptionals() {
        Map<String, Object> m = LaunchConfig.launch(
                "p.Main",
                "proj",
                List.of("/cp/a.jar", "/cp/b.jar"),
                List.of("/mp/m.jar"),
                "/usr/bin/java",
                "/work",
                List.of("--flag", "x"),
                "",
                true);
        assertEquals("proj", m.get("projectName"));
        assertEquals(List.of("/cp/a.jar", "/cp/b.jar"), m.get("classPaths"));
        assertEquals(List.of("/mp/m.jar"), m.get("modulePaths"));
        assertEquals("/usr/bin/java", m.get("javaExec"));
        assertEquals("/work", m.get("cwd"));
        assertEquals("--flag x", m.get("args")); // java-debug's args is ONE string (an array is never answered)
        assertEquals(true, m.get("stopOnEntry"));
    }

    /**
     * java-debug declares {@code LaunchArguments.args} as a String: a JSON array makes it throw while decoding
     * the launch request, which it never answers. This test used to pin the array (on the false premise that
     * the adapter accepts one). The string must still keep an argument with spaces as one argument — a bare
     * space-join would not: [hello world, second] and [hello, world, second] join to the same text.
     */
    @Test
    void javaLaunchSendsArgsAsOneQuotedStringSoAnArgumentWithSpacesStaysOneArgument() {
        List<String> argv = List.of("hello world", "second");
        Map<String, Object> m = LaunchConfig.launch("A", "", List.of(), List.of(), "", "", argv, "", false);
        assertEquals("\"hello world\" second", m.get("args"), "one string, quoted — same on every OS for this argv");

        // debugpy / js-debug take a real argv array; that sibling is unchanged.
        Map<String, Object> p = LaunchConfig.program("python", "/x/s.py", "/x", "python3", argv, false);
        assertEquals(argv, p.get("args"));
    }

    /** The encodings checked against java-debug 0.53.2's own tokenizer (non-Windows branch). */
    @Test
    void javaArgsQuotingForTheAdaptersPosixTokenizer() {
        assertEquals("--name \"hello world\"", LaunchConfig.javaArgs(List.of("--name", "hello world"), false));
        assertEquals("plain --flag=x", LaunchConfig.javaArgs(List.of("plain", "--flag=x"), false));
        assertEquals(
                "\"C:\\\\dir\\\\file.txt\" \"C:\\\\Program Files\\\\x\\\\\"",
                LaunchConfig.javaArgs(List.of("C:\\dir\\file.txt", "C:\\Program Files\\x\\"), false));
        assertEquals("\"\" last", LaunchConfig.javaArgs(List.of("", "last"), false));
        assertEquals(
                "\"it's\" \"say \\\"hi\\\"\" -Dk=v",
                LaunchConfig.javaArgs(List.of("it's", "say \"hi\"", "-Dk=v"), false));
        assertEquals("\"tab\there\"", LaunchConfig.javaArgs(List.of("tab\there"), false));
    }

    /** The adapter's Windows branch follows CommandLineToArgvW: backslashes are literal unless before a quote. */
    @Test
    void javaArgsQuotingForTheAdaptersWindowsTokenizer() {
        assertEquals("--name \"hello world\"", LaunchConfig.javaArgs(List.of("--name", "hello world"), true));
        assertEquals(
                "C:\\dir\\file.txt \"C:\\Program Files\\x\\\\\"",
                LaunchConfig.javaArgs(List.of("C:\\dir\\file.txt", "C:\\Program Files\\x\\"), true));
        assertEquals(
                "it's \"say \\\"hi\\\"\" -Dk=v", LaunchConfig.javaArgs(List.of("it's", "say \"hi\"", "-Dk=v"), true));
    }

    @Test
    void attachDefaultsBlankHostToLocalhost() {
        Map<String, Object> m = LaunchConfig.attach("", 5005);
        assertEquals("java", m.get("type"));
        assertEquals("attach", m.get("request"));
        assertEquals("localhost", m.get("hostName"));
        assertEquals(5005, m.get("port"));
    }

    @Test
    void attachKeepsExplicitHost() {
        Map<String, Object> m = LaunchConfig.attach("10.0.0.5", 9000);
        assertEquals("10.0.0.5", m.get("hostName"));
        assertEquals(9000, m.get("port"));
        assertTrue(m.containsKey("request"));
    }

    @Test
    void programForPythonEmitsInterpreterAsPython() {
        Map<String, Object> m = LaunchConfig.program("python", "/work/app.py", "/work", "/usr/bin/python3", true);
        assertEquals("python", m.get("type"));
        assertEquals("launch", m.get("request"));
        assertEquals("/work/app.py", m.get("program"));
        assertEquals("/work", m.get("cwd"));
        assertEquals("/usr/bin/python3", m.get("python")); // interpreter under the "python" key
        assertFalse(m.containsKey("runtimeExecutable"));
        assertEquals("internalConsole", m.get("console"));
        assertEquals(true, m.get("stopOnEntry"));
    }

    @Test
    void programForNodeEmitsRuntimeExecutable() {
        Map<String, Object> m = LaunchConfig.program("pwa-node", "/work/app.js", "/work", "/usr/local/bin/node", false);
        assertEquals("pwa-node", m.get("type"));
        assertEquals("/work/app.js", m.get("program"));
        assertEquals("/usr/local/bin/node", m.get("runtimeExecutable")); // node under "runtimeExecutable"
        assertFalse(m.containsKey("python"));
        assertEquals(false, m.get("stopOnEntry"));
    }

    @Test
    void programOmitsBlankCwdAndInterpreter() {
        Map<String, Object> m = LaunchConfig.program("python", "/a.py", "", "", false);
        assertEquals("/a.py", m.get("program"));
        assertFalse(m.containsKey("cwd"));
        assertFalse(m.containsKey("python"));
        assertFalse(m.containsKey("runtimeExecutable"));
    }

    /** An attach names no main class, so the project is the only way java-debug can evaluate anything. */
    @Test
    void attachCarriesTheProjectNameWhenKnown() {
        assertEquals("myproj", LaunchConfig.attach("localhost", 5005, "myproj").get("projectName"));
        assertFalse(LaunchConfig.attach("localhost", 5005, " ").containsKey("projectName"));
        assertFalse(LaunchConfig.attach("localhost", 5005, null).containsKey("projectName"));
        assertFalse(LaunchConfig.attach("localhost", 5005).containsKey("projectName"));
    }

    /** The usual launch fits on a command line: nothing is sent and java-debug keeps its default. */
    @Test
    void anOrdinaryLaunchDoesNotShortenTheCommandLine() {
        Map<String, Object> m = LaunchConfig.launch(
                "com.app.Main", "p", List.of("/a.jar", "/classes"), List.of(), "", "/proj", List.of(), "", false);
        assertFalse(m.containsKey("shortenCommandLine"));
    }

    /** A class path too long for one argument (any OS) is moved into an argfile instead of failing to start. */
    @Test
    void aClassPathLongerThanTheArgumentLimitIsShortened() {
        List<String> jars = new java.util.ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            jars.add("/home/user/.m2/repository/org/example/some-library/1.0." + i + "/some-library-1.0." + i + ".jar");
        }
        Map<String, Object> m =
                LaunchConfig.launch("com.app.Main", "p", jars, List.of(), "", "/proj", List.of(), "", false);
        assertEquals("argfile", m.get("shortenCommandLine"));
    }

    /** The launch that gives the program a standard input: the adapter hands its command line to the client. */
    @Test
    void aLaunchInTheClientsConsoleAsksForRunInTerminalAndKeepsEverythingElse() {
        Map<String, Object> plain = LaunchConfig.launch(
                "demo.Echo", "p1", List.of("/cp"), List.of(), "/jdk/bin/java", "/work", List.of("a"), "", false);
        Map<String, Object> client = LaunchConfig.inClientConsole(new java.util.LinkedHashMap<>(plain));
        assertEquals("integratedTerminal", client.get("console"));
        client.remove("console");
        Map<String, Object> rest = new java.util.LinkedHashMap<>(plain);
        rest.remove("console");
        assertEquals(rest, client);
    }

    /**
     * A remote host's java-debug splits {@code args} by POSIX rules and its JDK is not on this machine: a
     * Windows client used to quote for Windows, and read a local {@code release} file for a host's java
     * (review D8).
     */
    @Test
    void aRemoteLaunchIsShapedForTheHostNotForThisMachine(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path jdk8 = dir.resolve("jdk8");
        java.nio.file.Files.createDirectories(jdk8.resolve("bin"));
        java.nio.file.Files.writeString(jdk8.resolve("release"), "JAVA_VERSION=\"1.8.0_402\"\n");
        String java = jdk8.resolve("bin").resolve("java").toString();
        List<String> manyJars = new java.util.ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            manyJars.add("/home/u/.m2/repository/org/example/lib" + i + "/1.0/lib" + i + "-1.0.jar");
        }
        Map<String, Object> remote = LaunchConfig.launch(
                "demo.App",
                "",
                manyJars,
                List.of(),
                java,
                "/w",
                List.of("C:\\dir with space\\x"),
                "",
                Map.of(),
                false,
                LaunchConfig.Target.REMOTE_POSIX);
        assertEquals("\"C:\\\\dir with space\\\\x\"", remote.get("args"), "quoted for the host's POSIX tokenizer");
        assertEquals("argfile", remote.get("shortenCommandLine"), "a host's java is not judged by a local file");
        Map<String, Object> local = LaunchConfig.launch(
                "demo.App",
                "",
                manyJars,
                List.of(),
                java,
                "/w",
                List.of(),
                "",
                Map.of(),
                false,
                new LaunchConfig.Target(false, true));
        assertEquals("jarmanifest", local.get("shortenCommandLine"), "this machine's JDK 8 is read here");
    }

    @Test
    void aUserAttachToANodeInspectorNamesItsHostAndKeepsItPaused() {
        java.util.Map<String, Object> m =
                LaunchConfig.nodeAttach("pwa-node", "localhost:9229", "localhost", 9229, java.util.List.of(), false);
        org.junit.jupiter.api.Assertions.assertEquals("localhost", m.get("address"));
        org.junit.jupiter.api.Assertions.assertEquals(9229, m.get("port"));
        org.junit.jupiter.api.Assertions.assertEquals(false, m.get("continueOnAttach"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "127.0.0.1",
                LaunchConfig.nodeAttach("pwa-node", "x", 1234, java.util.List.of())
                        .get("address"));
    }
}
