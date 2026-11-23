package com.editora.dap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DapManagerTest {

    /**
     * DAP events reach the manager only through a session's own host, bound to that session's epoch. The
     * manager used to be a {@link DapClient.Host} itself, with public event methods that read the epoch at
     * call time: any caller of those would have applied a late event of an ended session to the next one.
     */
    @Test
    void theManagerTakesNoDapEventsExceptThroughASessionsHost() {
        assertFalse(DapClient.Host.class.isAssignableFrom(DapManager.class), "DapManager is not itself a Host");
        for (java.lang.reflect.Method m : DapManager.class.getMethods()) {
            for (java.lang.reflect.Method event : DapClient.Host.class.getMethods()) {
                assertFalse(
                        m.getName().equals(event.getName())
                                && java.util.Arrays.equals(m.getParameterTypes(), event.getParameterTypes()),
                        () -> "a public DAP event entry point with no session guard: " + m);
            }
        }
    }

    /**
     * bashdb's entry stop is shown only when the user put a plain breakpoint on that line: a conditional one, a hit
     * count, a logpoint, a recording or filtered one would have it shown as a pause the user never asked for.
     */
    @Test
    void onlyAPlainBreakpointOnTheFirstLineKeepsBashdbsEntryStop() {
        Path script = Path.of("work", "run.sh").toAbsolutePath();
        java.util.function.Function<DapModels.LineBreakpoint, Boolean> at = b -> DapManager.plainBreakpointAt(
                java.util.List.of(new DapModels.FileBreakpoints(script, java.util.List.of(b))), script, 0);
        assertTrue(at.apply(new DapModels.LineBreakpoint(0, null, null)));
        assertTrue(at.apply(new DapModels.LineBreakpoint(0, " ", "")), "blank is none");
        assertFalse(at.apply(new DapModels.LineBreakpoint(0, "x > 1", null)), "a condition");
        assertFalse(at.apply(new DapModels.LineBreakpoint(0, null, "at {x}")), "a logpoint");
        assertFalse(at.apply(new DapModels.LineBreakpoint(0, null, null, "3", 0, false)), "a hit count");
        assertFalse(at.apply(new DapModels.LineBreakpoint(0, null, null, null, 0, true)), "a recording breakpoint");
        assertFalse(at.apply(new DapModels.LineBreakpoint(4, null, null)), "another line");
        assertTrue(
                DapManager.plainBreakpointAt(
                        java.util.List.of(new DapModels.FileBreakpoints(
                                script, java.util.List.of(new DapModels.LineBreakpoint(0, null, null)))),
                        script.getParent().resolve("../work/run.sh"),
                        0),
                "the same file however it is named");
    }

    @Test
    void protocolRequestsHaveABoundedDeadline() {
        CompletableFuture<String> silent = new CompletableFuture<>();
        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class,
                () -> DapClient.withTimeout(silent, Duration.ofMillis(20)).join());
        assertInstanceOf(TimeoutException.class, failure.getCause());
    }

    @Test
    void mainClassFromFileUsesPackagePlusBaseName(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("Main.java");
        Files.writeString(f, "// a comment\npackage com.example.app;\n\npublic class Main { void main() {} }\n");
        assertEquals("com.example.app.Main", DapManager.mainClassFromFile(f));
    }

    @Test
    void mainClassFromFileDefaultPackageIsJustTheBaseName(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("Hello.java");
        Files.writeString(f, "public class Hello { public static void main(String[] a) {} }\n");
        assertEquals("Hello", DapManager.mainClassFromFile(f));
    }

    @Test
    void mainClassFromFileHandlesMissingFile(@TempDir Path dir) {
        // Unreadable file → default package, base name only (no crash).
        assertEquals("Gone", DapManager.mainClassFromFile(dir.resolve("Gone.java")));
    }

    @Test
    void mainClassFromFileUsesDeclaredClassNotFileName(@TempDir Path dir) throws IOException {
        // Class name differs from the file name — the declared type wins (the real cause of the
        // "Main class 'Hello' doesn't exist" error when HelloWorld lived in Hello.java).
        Path f = dir.resolve("Hello.java");
        Files.writeString(f, "public class HelloWorld { public static void main(String[] a) {} }\n");
        assertEquals("HelloWorld", DapManager.mainClassFromFile(f));
    }

    @Test
    void mainClassFromFileNullForNoName() {
        assertNull(DapManager.mainClassFromFile(null));
    }

    @Test
    void shebangIsBlankedWithoutMovingDebugLines() {
        String source = "#!/usr/bin/env -S java --source 25\nvoid main() {\n  int value = 41;\n}\n";
        String compiled = DapManager.withoutShebang(source);
        assertEquals(source.length(), compiled.length());
        assertEquals(source.substring(source.indexOf('\n')), compiled.substring(compiled.indexOf('\n')));
        assertTrue(compiled.startsWith(" "));
    }

    @Test
    void temporaryCompilationRemovesSourcesAndNestedClasses(@TempDir Path dir) throws IOException {
        Path compilation = Files.createDirectory(dir.resolve("editora-dap-test"));
        Files.writeString(compilation.resolve("launcher.java"), "void main() {}\n");
        Path nested = Files.createDirectories(compilation.resolve("example"));
        Files.writeString(nested.resolve("Launcher.class"), "compiled");

        DapManager.removeCompilationDirectory(compilation);

        assertTrue(Files.notExists(compilation));
    }

    @Test
    void selectedJdkCompilesAndRunsCompactSourceWithDebugSymbols(@TempDir Path dir) throws IOException {
        assumeTrue(Runtime.version().feature() >= 25);
        Path file = dir.resolve("Hello.java");
        Files.writeString(file, "class Helper {}\nvoid main() { IO.println(Support.message()); }\n");
        Files.writeString(
                dir.resolve("Support.java"), "class Support { static String message() { return \"debug-ready\"; } }\n");
        Path out = Files.createDirectory(dir.resolve("classes"));
        String javaExec = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();

        assertEquals("Hello", DapManager.compactMainClassFromFile(file));
        ProcessRunner.Result compiled = DapManager.compileStandalone(file, out, javaExec, java.util.Map.of());
        assertTrue(compiled.ok(), compiled.err());
        assertTrue(Files.isRegularFile(out.resolve("Hello.class")));
        ProcessRunner.Result launched = ProcessRunner.run(
                dir, Duration.ofSeconds(10), java.util.List.of(javaExec, "-cp", out.toString(), "Hello"));
        assertTrue(launched.ok(), launched.err());
        assertTrue(launched.out().contains("debug-ready"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    // --- run-to-cursor temp breakpoint merge ---------------------------------------------------

    @Test
    void withTempLineAddsTempToExistingFileBreakpoints() {
        Path f = Path.of("/x/A.java");
        var existing = java.util.List.of(
                new DapModels.FileBreakpoints(f, java.util.List.of(new DapModels.LineBreakpoint(3, null, null))));
        DapModels.FileBreakpoints merged = DapManager.withTempLine(existing, f, 10);
        assertEquals(f, merged.file());
        assertEquals(2, merged.breakpoints().size());
        assertEquals(10, merged.breakpoints().get(1).line());
    }

    @Test
    void withTempLineMakesABreakpointAlreadyOnTheLinePlainForTheTrip() {
        // B13: a false condition or a logpoint on the cursor line used to let Run to Cursor run past it
        Path f = Path.of("/x/A.java");
        var existing = java.util.List.of(new DapModels.FileBreakpoints(
                f,
                java.util.List.of(
                        new DapModels.LineBreakpoint(10, "i > 3", null),
                        new DapModels.LineBreakpoint(12, null, "log {i}"))));
        DapModels.FileBreakpoints toLine10 = DapManager.withTempLine(existing, f, 10);
        assertEquals(2, toLine10.breakpoints().size(), "no duplicate line");
        var at10 = toLine10.breakpoints().stream()
                .filter(b -> b.line() == 10)
                .findFirst()
                .orElseThrow();
        assertEquals(null, at10.condition(), "plain: it stops whatever the condition says");
        DapModels.FileBreakpoints toLog = DapManager.withTempLine(existing, f, 12);
        var at12 = toLog.breakpoints().stream()
                .filter(b -> b.line() == 12)
                .findFirst()
                .orElseThrow();
        assertEquals(null, at12.logMessage(), "a logpoint never suspends: it must stop this time");
    }

    @Test
    void withTempLineIgnoresOtherFilesAndHandlesEmpty() {
        Path f = Path.of("/x/A.java");
        var other = java.util.List.of(new DapModels.FileBreakpoints(
                Path.of("/x/B.java"), java.util.List.of(new DapModels.LineBreakpoint(1, null, null))));
        DapModels.FileBreakpoints merged = DapManager.withTempLine(other, f, 5);
        assertEquals(1, merged.breakpoints().size());
        assertEquals(5, merged.breakpoints().get(0).line());
        assertEquals(1, DapManager.withTempLine(null, f, 5).breakpoints().size());
    }

    /**
     * {@code vscode.java.resolveJavaExecutable} answers a bare path, which lsp4j hands back as a plain
     * String — not JSON. Parsing it as JSON threw on every debug launch and logged a stack trace, then fell
     * back to the same value, so the reply was always right and the log said it was broken.
     */
    @Test
    void aPlainStringReplyIsTheValueNotJsonText() {
        assertEquals(
                "/home/u/.sdkman/candidates/java/17.0.20-tem/bin/java",
                FxlessAccess.asString("/home/u/.sdkman/candidates/java/17.0.20-tem/bin/java"));
        assertEquals(
                "C:\\Program Files\\Java\\jdk-25\\bin\\java.exe", // backslashes are not JSON escapes here
                FxlessAccess.asString("C:\\Program Files\\Java\\jdk-25\\bin\\java.exe"));
    }

    @Test
    void aJsonStringReplyStillUnwrapsToItsValue() {
        assertEquals("/usr/bin/java", FxlessAccess.asString(new com.google.gson.JsonPrimitive("/usr/bin/java")));
        assertNull(FxlessAccess.asString(null));
        assertNull(FxlessAccess.asString(new com.google.gson.JsonArray())); // not a single value
    }

    @Test
    void mainClassFromFileKeepsANonAsciiTypeName(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("Café.java");
        Files.writeString(f, "package demo;\n\npublic class Café {\n  public static void main(String[] a) {}\n}\n");
        assertEquals("demo.Café", DapManager.mainClassFromFile(f));
    }

    /** CodeLLDB's launcher, started for it through runInTerminal, is not the program (its exit is not the program's). */
    @Test
    void codelldbsLauncherIsTheAdaptersNotTheProgram() {
        assertTrue(DapManager.isAdapterLauncher(
                java.util.List.of("/home/u/.editora/plugins/dap/rust/extension/bin/codelldb-launch", "--connect=x")));
        assertTrue(DapManager.isAdapterLauncher(java.util.List.of("C:\\codelldb\\bin\\codelldb-launch.exe")));
        assertFalse(DapManager.isAdapterLauncher(java.util.List.of("/usr/bin/java", "Echo.java")));
        assertFalse(DapManager.isAdapterLauncher(java.util.List.of("/tmp/codelldb-launch-test/tally")));
        assertFalse(DapManager.isAdapterLauncher(java.util.List.of()));
    }

    @Test
    void aPathGetsItsFirstFoldersOnceAndGoBinariesAreLookedForWhereGoPutsThem() {
        assertEquals(
                "/a:/b:/c", DapManager.pathWithFirst(java.util.Arrays.asList("/a", null, " ", "/b"), "/b:/c", ":"));
        assertEquals("/a", DapManager.pathWithFirst(java.util.List.of("/a"), null, ":"));
        assertEquals("/a", DapManager.pathWithFirst(java.util.List.of("/a"), "", ":"));
        Path home = Path.of("/home/u");
        String sep = java.io.File.pathSeparator;
        assertEquals(
                java.util.List.of(
                        Path.of("/gobin"), Path.of("/gp1", "bin"), Path.of("/gp2", "bin"), home.resolve("go/bin")),
                DapManager.goBinDirs(
                        java.util.Map.of("GOBIN", " /gobin ", "GOPATH", "/gp1" + sep + " " + sep + "/gp2"), home));
        assertEquals(
                java.util.List.of(home.resolve("go/bin")),
                DapManager.goBinDirs(java.util.Map.of("GOBIN", " ", "GOPATH", ""), home));
    }

    @Test
    void aHostPythonCommandIsReadWithItsEnvironment() {
        DapManager.HostPython withEnv =
                DapManager.HostPython.parse(java.util.List.of("env", "A=1", "B_2=x=y", "python3", "-X", "dev"));
        assertEquals("python3", withEnv.executable());
        assertEquals(java.util.List.of("-X", "dev"), withEnv.args());
        assertEquals(java.util.Map.of("A", "1", "B_2", "x=y"), withEnv.env());
        DapManager.HostPython onlyEnv = DapManager.HostPython.parse(java.util.List.of("env", "A=1"));
        assertEquals(DapServerRegistry.DEFAULT_PYTHON_INTERPRETER, onlyEnv.executable());
        assertEquals(java.util.List.of(), onlyEnv.args());
        DapManager.HostPython plain = DapManager.HostPython.parse(java.util.List.of("py"));
        assertEquals("py", plain.executable());
        assertEquals(java.util.Map.of(), plain.env());
        assertEquals(
                DapServerRegistry.DEFAULT_PYTHON_INTERPRETER,
                DapManager.HostPython.parse(java.util.List.of()).executable());
    }

    @Test
    void theListeningPortIsTheFirstAnnouncedOrNoneWhenTheAdapterSaysNothing() {
        java.io.InputStream twice =
                new java.io.ByteArrayInputStream("starting\nlistening at ::1:4711\nlistening at ::1:9999\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(4711, DapManager.listeningPort(twice, Duration.ofSeconds(10)));
        java.io.InputStream silent =
                new java.io.ByteArrayInputStream("nothing\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(-1, DapManager.listeningPort(silent, Duration.ofSeconds(10)));
    }

    @Test
    void aPythonLaunchServesAWebAppByItsServerItsConfigurationOrItsFile() {
        PythonLaunch.Target script = PythonLaunch.Target.script("/w/app.py", java.util.List.of(), "/w");
        String flask = "from flask import Flask\napp = Flask(__name__)\n";
        assertTrue(DapManager.servesWeb(
                new PythonLaunch.Target(null, "flask", java.util.List.of(), null, null), null, null));
        assertTrue(DapManager.servesWeb(script, null, java.util.Map.of("module", "uvicorn")));
        assertTrue(DapManager.servesWeb(script, null, java.util.Map.of("program", "/w/manage.py")));
        assertFalse(DapManager.servesWeb(script, null, java.util.Map.of("module", 3, "program", 4)));
        assertFalse(DapManager.servesWeb(script, null, null), "no text to read");
        assertTrue(DapManager.servesWeb(script, flask, null), "a Flask app that runs itself");
        assertFalse(DapManager.servesWeb(script, "print(1)\n", null));
        assertFalse(DapManager.servesWeb(
                new PythonLaunch.Target(null, "app", java.util.List.of(), null, null), flask, null));
        assertFalse(DapManager.servesWeb(
                new PythonLaunch.Target(null, null, java.util.List.of(), null, null), flask, null));

        assertEquals(java.util.List.of(), DapManager.pythonRuntime(java.util.Map.of()));
        assertEquals(java.util.List.of(), DapManager.pythonRuntime(java.util.Map.of("python", " ")));
        assertEquals(
                java.util.List.of("py"),
                DapManager.pythonRuntime(java.util.Map.of("python", "py", "pythonArgs", "-X")));
        assertEquals(
                java.util.List.of("py", "-X", "dev"),
                DapManager.pythonRuntime(
                        java.util.Map.of("python", "py", "pythonArgs", java.util.List.of("-X", "dev"))));
    }

    private static DapModels.Instruction at(String address) {
        return new DapModels.Instruction(address, "", "nop", "", null, 0);
    }

    @Test
    void instructionsAroundAnAddressAndAddressesInBothBases() {
        java.util.List<DapModels.Instruction> listing =
                java.util.List.of(at("0x10"), at("junk"), at("0x12"), at("19"), at("0x14"));
        assertEquals(
                java.util.List.of(at("junk"), at("0x12"), at("19")), DapManager.aroundAddress(listing, "18", 1, 3));
        assertEquals(java.util.List.of(at("0x10"), at("junk")), DapManager.aroundAddress(listing, "0x10", 5, 2));
        assertEquals(java.util.List.of(at("0x14")), DapManager.aroundAddress(listing, "0X14", 0, 9));
        assertNull(DapManager.aroundAddress(listing, "0x11", 1, 3), "decoded out of step");
        assertNull(DapManager.aroundAddress(listing, "nonsense", 1, 3));
        assertNull(DapManager.parseAddress(null));
        assertNull(DapManager.parseAddress(" "));
        assertNull(DapManager.parseAddress("0xzz"));
        assertEquals(-1L, DapManager.parseAddress("0xffffffffffffffff"), "an unsigned 64-bit address");
    }

    @Test
    void aPlainBreakpointIsOneWithNothingThatDecidesWhetherItStops() {
        Path file = Path.of("/s/run.sh");
        DapModels.LineBreakpoint plain = new DapModels.LineBreakpoint(3, null, " ", null);
        DapModels.LineBreakpoint conditional = new DapModels.LineBreakpoint(4, "x > 1", null, null);
        DapModels.LineBreakpoint logging = new DapModels.LineBreakpoint(5, null, "hi", null);
        DapModels.LineBreakpoint counted = new DapModels.LineBreakpoint(6, null, null, "3");
        DapModels.LineBreakpoint recording = new DapModels.LineBreakpoint(7, null, null, null, 0, true);
        DapModels.LineBreakpoint filtered = new DapModels.LineBreakpoint(
                8, null, null, null, 0, false, new com.editora.config.StopFilters("main", "", ""));
        java.util.List<DapModels.FileBreakpoints> all = java.util.List.of(
                new DapModels.FileBreakpoints(Path.of("/s/other.sh"), java.util.List.of(plain)),
                new DapModels.FileBreakpoints(
                        Path.of("/s/./run.sh"),
                        java.util.List.of(plain, conditional, logging, counted, recording, filtered)));
        assertTrue(DapManager.plainBreakpointAt(all, file, 3));
        for (int line = 4; line <= 9; line++) {
            assertFalse(DapManager.plainBreakpointAt(all, file, line), "line " + line);
        }
        assertFalse(DapManager.plainBreakpointAt(java.util.List.of(), file, 3));
    }

    @Test
    void codeLldbsLauncherIsKnownByItsFileName() {
        assertTrue(DapManager.isAdapterLauncher(java.util.List.of("C:\\lldb\\codelldb-launch.EXE", "x")));
        assertTrue(DapManager.isAdapterLauncher(java.util.List.of("codelldb-launch")));
        assertFalse(DapManager.isAdapterLauncher(java.util.List.of("/bin/codelldb")));
        assertFalse(DapManager.isAdapterLauncher(java.util.List.of()));
        assertFalse(DapManager.isAdapterLauncher(null));
        assertFalse(DapManager.isAdapterLauncher(java.util.Arrays.asList((String) null)));
    }

    @Test
    void aFailuresMessageIsWhatTheAdapterSaid() {
        assertEquals("", DapManager.msg(null));
        assertEquals(
                "boom",
                DapManager.msg(
                        new java.util.concurrent.ExecutionException(new CompletionException(new IOException("boom")))));
        assertEquals("java.io.IOException", DapManager.msg(new IOException()), "no message: the exception");
        assertEquals("bare", DapManager.msg(new CompletionException("bare", null)));
        org.eclipse.lsp4j.jsonrpc.ResponseErrorException refused = new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(1, "not here", null));
        assertEquals("not here", DapManager.msg(new CompletionException(refused)));
        assertEquals(
                com.editora.i18n.Messages.tr("status.debug.noAnswer", DapClient.requestTimeoutSeconds()),
                DapManager.msg(new TimeoutException()));
        assertEquals(
                com.editora.i18n.Messages.tr(
                        "debug.error.debugpyNoStart", String.valueOf(DapClient.requestTimeoutSeconds()), "py3"),
                DapManager.launchFailure(
                        new RuntimeException(new TimeoutException()), java.util.Map.of("python", "py3")),
                "a debugpy that never answered: its interpreter is named");
        assertEquals(
                com.editora.i18n.Messages.tr("debug.error.launchFailed", "gone"),
                DapManager.launchFailure(new RuntimeException(new IOException("gone")), java.util.Map.of()));
    }

    @Test
    void codeReplaceModesAndOutcomes() {
        assertEquals(DapManager.CodeReplaceMode.MANUAL, DapManager.CodeReplaceMode.of(" Manual "));
        assertEquals(DapManager.CodeReplaceMode.AUTO, DapManager.CodeReplaceMode.of("sometimes"));
        assertEquals(DapManager.CodeReplaceMode.AUTO, DapManager.CodeReplaceMode.of(null));
        assertEquals(
                DapManager.CodeReplaceResult.FAILED,
                DapManager.codeReplaceOutcome(new DapModels.CodeReplace(java.util.List.of(), ""), "no answer")
                        .result());
        assertEquals(
                DapManager.CodeReplaceResult.NOTHING_CHANGED,
                DapManager.codeReplaceOutcome(null, null).result());
        assertEquals(
                DapManager.CodeReplaceResult.NOTHING_CHANGED,
                DapManager.codeReplaceOutcome(new DapModels.CodeReplace(java.util.List.of(), ""), null)
                        .result());
        assertEquals(
                DapManager.CodeReplaceResult.APPLIED,
                DapManager.codeReplaceOutcome(new DapModels.CodeReplace(java.util.List.of("a.B"), ""), null)
                        .result());
        assertEquals(
                DapManager.CodeReplaceResult.APPLIED,
                DapManager.codeReplaceOutcome(
                                new DapModels.CodeReplace(java.util.List.of(), "JVM contains obsolete methods"), null)
                        .result());
        DapManager.CodeReplaceOutcome refused =
                DapManager.codeReplaceOutcome(new DapModels.CodeReplace(java.util.List.of(), "schema change"), null);
        assertEquals(DapManager.CodeReplaceResult.FAILED, refused.result());
        assertEquals("schema change", refused.message());
    }
}
