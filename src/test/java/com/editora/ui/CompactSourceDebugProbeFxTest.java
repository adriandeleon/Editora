package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugAdapterLocator;
import com.editora.lsp.LspManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in, real JDT LS + java-debug check of compact-source breakpoints, locals, and stepping. */
@Tag("probe")
@Tag("fx")
class CompactSourceDebugProbeFxTest {

    private record Stop(String reason, List<DapModels.StackFrameInfo> frames, String error) {}

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void breakpointVariablesAndStepOver(@TempDir Path dir) throws Exception {
        probe(dir, false);
    }

    @Test
    void extensionlessShebangBreakpointAndStepOver(@TempDir Path dir) throws Exception {
        probe(dir, true);
    }

    private void probe(Path dir, boolean shebang) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(Runtime.version().feature() >= 25);
        Path jdtls = Path.of(System.getProperty("user.home"), ".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", Path.of(System.getProperty("user.home")))
                .orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null);

        Path project = Files.createDirectory(dir.resolve("project"));
        Path file = project.resolve(shebang ? "launcher" : "Hello.java");
        String source = (shebang ? "#!/usr/bin/env -S java --source 25\n" : "")
                + "void main() {\n    int value = 41;\n    value++;\n    IO.println(value);\n}\n";
        int breakpointLine = shebang ? 3 : 2;
        Files.writeString(file, source);
        var ready = new java.util.concurrent.CountDownLatch(1);
        var events = new LinkedBlockingQueue<Stop>();
        LspManager lsp = new LspManager((p, ds) -> {}, (type, message) -> {
            if ("ServiceReady".equals(type)) ready.countDown();
        });
        DapManager dap = new DapManager(lsp);
        dap.setListener(new DapManager.Listener() {
            @Override
            public void onState(DapManager.State state) {}

            @Override
            public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
                events.add(new Stop(reason, frames, null));
            }

            @Override
            public void onOutput(String text, String category) {}

            @Override
            public void onError(String message) {
                events.add(new Stop(null, List.of(), message));
            }
        });
        try {
            FxTestSupport.runOnFx(() -> {
                dap.configure(true, plugin.toString());
                lsp.setDebugBundles(dap.bundlePaths());
                lsp.setJdtlsWorkspaceBase(dir.resolve("jdtls-workspaces"));
                lsp.configure(true, Map.of("java", jdtls.toString()));
                lsp.openDocument(file, project, "java", source);
                dap.setBreakpointsSupplier(() -> List.of(new DapModels.FileBreakpoints(
                        file, List.of(new DapModels.LineBreakpoint(breakpointLine, null, null)))));
            });
            assertTrue(ready.await(70, TimeUnit.SECONDS), "JDT LS did not become ready");
            String javaExec = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                    .toString();
            FxTestSupport.runOnFx(() -> {
                if (shebang) {
                    dap.startCompactShebang(file, 25, javaExec);
                } else {
                    dap.startCompactSource(file, javaExec);
                }
            });

            Stop first = events.poll(90, TimeUnit.SECONDS);
            assertNotNull(first, "the compact-source breakpoint was not hit");
            assertEquals(null, first.error(), first.error());
            assertEquals("breakpoint", first.reason());
            assertFalse(first.frames().isEmpty(), "the adapter returned no stack frame");
            assertEquals(breakpointLine, first.frames().getFirst().line());
            assertEquals(file, first.frames().getFirst().file());
            assertEquals("41", localValue(dap, first.frames().getFirst().id(), "value"));

            FxTestSupport.runOnFx(dap::stepOver);
            Stop second = events.poll(30, TimeUnit.SECONDS);
            assertNotNull(second, "step-over did not suspend again");
            assertEquals(null, second.error(), second.error());
            assertEquals("step", second.reason());
            assertFalse(second.frames().isEmpty());
            assertEquals(breakpointLine + 1, second.frames().getFirst().line());
            assertEquals(file, second.frames().getFirst().file());
            assertEquals("42", localValue(dap, second.frames().getFirst().id(), "value"));
        } finally {
            var processes = ProcessHandle.current()
                    .descendants()
                    .filter(process -> java.util.Arrays.stream(
                                    process.info().arguments().orElse(new String[0]))
                            .anyMatch(argument -> argument.startsWith(dir.toString())))
                    .toList();
            FxTestSupport.runOnFx(() -> {
                dap.shutdown();
                lsp.shutdownAll();
            });
            for (var process : processes) process.onExit().get(10, TimeUnit.SECONDS);
        }
    }

    private static String localValue(DapManager dap, int frameId, String name) throws Exception {
        var scopes = new CompletableFuture<List<DapModels.ScopeInfo>>();
        FxTestSupport.runOnFx(() -> dap.scopes(frameId, scopes::complete));
        for (var scope : scopes.get(20, TimeUnit.SECONDS)) {
            var variables = new CompletableFuture<List<DapModels.VariableInfo>>();
            FxTestSupport.runOnFx(() -> dap.variables(scope.variablesReference(), variables::complete));
            for (var variable : variables.get(20, TimeUnit.SECONDS)) {
                if (name.equals(variable.name())) return variable.value();
            }
        }
        throw new AssertionError("No local variable named " + name);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
