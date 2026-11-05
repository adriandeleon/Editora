package com.editora.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review A102: Debug ▶ (debug.start) in a JUnit test file debugs the test at the caret, or the whole class. */
@Tag("fx")
class DebugTestFileFxTest {

    private static final String TEST = """
            package demo;

            import org.junit.jupiter.api.Test;

            class CalculatorTest {

                @Test
                void addsNumbers() {
                    int x = 1 + 1;
                }

                @Test
                void subtractsNumbers() {
                    int y = 2 - 1;
                }
            }
            """;

    @BeforeAll
    static void initFx() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private FxWindowFixture fx;
    private final List<Boolean> debugged = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        fx = FxWindowFixture.create(Files.createDirectories(dir.resolve("config")), shared -> {});
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.dispose();
    }

    private EditorBuffer open(String name, String source) throws Exception {
        Path file = Files.createDirectories(dir.resolve("src/test/java/demo")).resolve(name);
        Files.writeString(file, source);
        MainController c = fx.controller;
        FxTestSupport.runOnFx(() -> c.openAndNavigate(file, 1));
        EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
        DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
        FxTestSupport.runOnFx(() -> debug.setTestDebugger(debugged::add));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (FxTestSupport.callOnFx(() -> b.testClassTarget() == null)) {
            assertTrue(System.nanoTime() < deadline, "the load's test scan never landed");
            Thread.sleep(10);
        }
        return b;
    }

    private void debugAt(EditorBuffer b, int paragraph) throws Exception {
        FxTestSupport.runOnFx(() -> {
            b.getArea().moveTo(paragraph, 0);
            CommandRegistry reg = FxTestSupport.field(fx.controller, "registry");
            reg.run("debug.start");
        });
    }

    @Test
    void debugInATestMethodDebugsThatTest() throws Exception {
        EditorBuffer b = open("CalculatorTest.java", TEST);
        debugAt(b, 13); // inside subtractsNumbers
        assertEquals(List.of(false), debugged, "the test at the caret, not the project's main class");
    }

    @Test
    void debugAboveTheClassDebugsTheWholeClass() throws Exception {
        EditorBuffer b = open("CalculatorTest.java", TEST);
        debugAt(b, 2); // the import line
        assertEquals(List.of(true), debugged);
    }

    @Test
    void aTestFileWithItsOwnMainIsStillLaunched() throws Exception {
        String withMain = TEST.replace(
                "class CalculatorTest {\n",
                "class CalculatorTest {\n    public static void main(String[] args) {}\n");
        EditorBuffer b = open("CalculatorTest.java", withMain);
        debugAt(b, 14);
        assertEquals(List.of(), debugged);
    }
}
