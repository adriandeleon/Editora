package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in ({@code -Dlsp.probe=true}), real jdtls + java-debug, through the window as a user drives it (Java debug
 * review 3, C208): F5 in a plain-folder project builds it with jdtls before launching, an edit runs on the next F5,
 * and a compile error the program reaches stops the launch. The folder has two packages — jdtls gives such a folder
 * a nested source root, and its build's errors used to refuse a program javac compiles (review 3, A201).
 */
@Tag("probe")
@Tag("fx")
class PlainFolderDebugProbeFxTest {

    private static final String MAIN = """
            package com.acme.app;

            import com.acme.util.Util;

            public class Main {
                public static void main(String[] args) {
                    System.out.println("twice " + Util.twice(21));
                }
            }
            """;

    /** 0-based: the {@code println} line of {@link #MAIN}. */
    private static final int PRINT_LINE = 6;

    private static final String UTIL = """
            package com.acme.util;

            public class Util {
                public static int twice(int x) {
                    return 2 * x;
                }
            }
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private interface Check {
        boolean holds() throws Exception;
    }

    @Test
    void aTwoPackagePlainFolderIsDebuggedRunsItsEditsAndStopsOnACompileError(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = Path.of(System.getProperty("user.home"), ".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", Path.of(System.getProperty("user.home")))
                .orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null);
        Path cfg = Files.createDirectories(dir.resolve("config"));
        FxWindowFixture fx = FxWindowFixture.create(cfg, shared -> {
            var s = shared.getSettings();
            s.setLspSupport(true);
            s.setJavaLspEnabled(true);
            s.setJavaLspCommand(jdtls.toString());
            s.setDebugSupport(true);
            s.setJavaDebugPluginPath(plugin.toString());
        });
        MainController c = fx.controller;
        DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
        DapManager dap = FxTestSupport.field(c, "dapManager");
        CommandRegistry registry = FxTestSupport.field(c, "registry");
        try {
            Path root = fx.useProject();
            Path main = root.resolve("src/com/acme/app/Main.java");
            Path util = root.resolve("src/com/acme/util/Util.java");
            Files.createDirectories(main.getParent());
            Files.createDirectories(util.getParent());
            Files.writeString(main, MAIN);
            Files.writeString(util, UTIL);
            FxTestSupport.runOnFx(() -> c.openAndNavigate(main, PRINT_LINE + 1));
            FxTestSupport.runOnFx(() -> c.activeBuffer().toggleBreakpoint(PRINT_LINE));
            await(debug, "Java debugging to be ready", 600, () -> FxTestSupport.callOnFx(debug::debugEffective));
            Thread.sleep(Long.getLong("importWait", 15000)); // let jdtls import the folder

            // F5: built, launched, stopped at the breakpoint — not refused for jdtls's nested source root
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
            await(
                    debug,
                    "a stop at the breakpoint",
                    900,
                    () -> FxTestSupport.callOnFx(dap::state) == DapManager.State.SUSPENDED);
            FxTestSupport.runOnFx(() -> registry.run("debug.continue"));
            await(debug, "the program's output", 300, () -> console(debug).contains("twice 42"));
            await(debug, "the end of the session", 300, () -> !FxTestSupport.callOnFx(dap::isActive));

            // An edit in the other package, saved in the editor, runs on the next F5.
            edit(c, registry, util, "2 * x", "3 * x");
            FxTestSupport.runOnFx(() -> c.openAndNavigate(main, PRINT_LINE + 1));
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
            await(
                    debug,
                    "a stop in the edited program",
                    900,
                    () -> FxTestSupport.callOnFx(dap::state) == DapManager.State.SUSPENDED);
            // javac compiled this session's classes, because jdtls can't build the folder: a save while paused says
            // so, and does not blame the user's code (review 3 verify).
            edit(c, registry, util, "return 3 * x;", "return 3 * x; // paused");
            await(debug, "the code-changes notice", 300, () -> console(debug).contains(tr("debug.hcr.javacClasses")));
            assertTrue(!console(debug).contains(tr("debug.hcr.compileErrors")), console(debug));
            FxTestSupport.runOnFx(() -> registry.run("debug.continue"));
            await(debug, "the edited code's output", 300, () -> console(debug).contains("twice 63"));
            await(debug, "the end of the session", 300, () -> !FxTestSupport.callOnFx(dap::isActive));

            // A compile error the program reaches stops the launch: nothing runs, and the header says why.
            edit(c, registry, util, "return 3 * x;", "return \"x\";");
            FxTestSupport.runOnFx(() -> c.openAndNavigate(main, PRINT_LINE + 1));
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
            Label header = FxTestSupport.field(debug.panel(), "status");
            await(
                    debug,
                    "the refused launch",
                    900,
                    () -> tr("debugpanel.state.buildFailed").equals(FxTestSupport.callOnFx(header::getText)));
            assertTrue(console(debug).contains("Util.java"), console(debug));
            assertTrue(!console(debug).contains("twice"), "nothing ran: " + console(debug));
            assertEquals(DapManager.State.INACTIVE, FxTestSupport.callOnFx(dap::state));
        } finally {
            FxTestSupport.runOnFx(dap::stop);
            for (int i = 0; i < 100 && FxTestSupport.callOnFx(dap::isActive); i++) {
                Thread.sleep(100);
            }
            fx.dispose();
            String path = dir.toString();
            for (int i = 0; i < 300 && usingFolder(path); i++) {
                Thread.sleep(100); // jdtls writes its workspace under dir for a moment after it is asked to stop
            }
        }
    }

    /** Opens {@code file}, replaces {@code from} with {@code to} in the editor once it is loaded, and saves it. */
    private static void edit(MainController c, CommandRegistry registry, Path file, String from, String to)
            throws Exception {
        FxTestSupport.runOnFx(() -> c.openAndNavigate(file, 1));
        for (int i = 0; i < 100; i++) { // the tab's text is read off the FX thread
            boolean loaded = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = c.activeBuffer();
                return b != null
                        && file.equals(b.getPath())
                        && b.getArea().getText().contains(from);
            });
            if (loaded) {
                break;
            }
            Thread.sleep(100);
        }
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = c.activeBuffer();
            int at = b.getArea().getText().indexOf(from);
            assertTrue(at >= 0, from);
            b.getArea().replaceText(at, at + from.length(), to);
            registry.run("file.save");
        });
        FxTestSupport.drainFx();
    }

    private static void await(DebugCoordinator debug, String what, int tenths, Check check) throws Exception {
        for (int i = 0; i < tenths; i++) {
            if (check.holds()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(what + " never happened; console:\n" + console(debug));
    }

    private static String console(DebugCoordinator debug) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CodeArea a = FxTestSupport.field(debug.panel(), "console");
            return a.getText();
        });
    }

    /** Whether a process this test started still names {@code path} on its command line. */
    private static boolean usingFolder(String path) {
        return ProcessHandle.current()
                .descendants()
                .filter(ProcessHandle::isAlive)
                .anyMatch(p -> p.info().commandLine().orElse("").contains(path));
    }
}
