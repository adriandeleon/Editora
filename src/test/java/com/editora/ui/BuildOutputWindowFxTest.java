package com.editora.ui;

import java.util.List;

import com.editora.command.CommandRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the build-output <b>consolidation</b>: the five build tools (Maven/npm/Cargo/Go/Gradle) share a single
 * "Build Output" console tool window instead of registering one console each. Built against a real window
 * ({@link FxWindowFixture}) so it exercises {@code MainController}'s actual tool-window registration — the wiring
 * a {@code BuildCoordinator} unit test can't see.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildOutputWindowFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private List<String> registeredIds() throws Exception {
        ToolWindowManager tw = FxTestSupport.field(fx.controller, "toolWindows");
        return FxTestSupport.callOnFx(() ->
                tw.getRegisteredToolWindows().stream().map(ToolWindow::getId).toList());
    }

    @Test
    void exactlyOneSharedBuildOutputWindowIsRegistered() throws Exception {
        List<String> ids = registeredIds();
        long consoles = ids.stream().filter("buildOutput"::equals).count();
        assertEquals(1, consoles, "one shared Build Output console, got ids: " + ids);
    }

    @Test
    void noPerToolOutputWindowsSurvive() throws Exception {
        List<String> ids = registeredIds();
        List<String> perTool = ids.stream()
                .filter(id -> id.endsWith("Output") && !id.equals("buildOutput"))
                .toList();
        assertTrue(perTool.isEmpty(), "no per-tool *Output windows should remain, found: " + perTool);
    }

    @Test
    void theSharedWindowHasTheBuildOutputTitle() throws Exception {
        ToolWindowManager tw = FxTestSupport.field(fx.controller, "toolWindows");
        String title = FxTestSupport.callOnFx(() -> tw.getRegisteredToolWindows().stream()
                .filter(w -> "buildOutput".equals(w.getId()))
                .map(ToolWindow::getTitle)
                .findFirst()
                .orElse(null));
        assertEquals("Build Output", title);
    }

    @Test
    void theSharedToggleCommandIsRegistered() throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        assertTrue(
                FxTestSupport.callOnFx(() -> registry.get("tool.buildOutput").isPresent()),
                "the tool.buildOutput palette command exists");
    }
}
