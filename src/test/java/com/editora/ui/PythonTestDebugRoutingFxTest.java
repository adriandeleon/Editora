package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.test.JavaTestScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Debug Test on a Python test's ▶ (the editor menu of a test line, as for a JUnit test) goes to the Python
 * debugger. It went to the Java path, which looked for Maven or Gradle and said there was no build tool.
 */
@Tag("fx")
class PythonTestDebugRoutingFxTest {

    @TempDir
    Path dir;

    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            fx.close();
        }
    }

    @Test
    void aPythonTestGlyphsDebugTestReachesThePythonDebugger() throws Exception {
        Path test = Files.createDirectories(dir.resolve("tests")).resolve("test_cart.py");
        Files.writeString(test, "class TestCart:\n    def test_add(self):\n        assert 1\n");
        fx = FxWindowFixture.create(Files.createDirectories(dir.resolve("cfg")), shared -> {
            shared.getSettings().setDebugSupport(true);
            shared.getSettings().setPythonDebugEnabled(false); // a fixed answer, whatever is installed here
        });
        MainController c = fx.controller;
        FxTestSupport.runOnFx(() -> c.openAndNavigate(test, 2));
        FxTestSupport.drainFx();

        FxTestSupport.runOnFx(() ->
                c.testNavigation.debugSingleTest(new JavaTestScanner.TestTarget(1, "TestCart", "test_add", false)));

        String echo = FxTestSupport.callOnFx(
                () -> ((javafx.scene.control.Label) FxTestSupport.field(c.statusBar(), "echo")).getText());
        assertEquals(tr("status.debug.pythonOff"), echo);
    }
}
