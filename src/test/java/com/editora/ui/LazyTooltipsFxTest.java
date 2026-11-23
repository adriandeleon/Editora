package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import com.editora.editor.ControlNames;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A new window builds no {@link Tooltip} before its first paint: each is a popup window with a scene of its own,
 * and the window used to build a few hundred (status bar, find bar, toolbar, stripe), almost none of them ever
 * hovered. A control keeps its tooltip's text ({@link ControlNames#tip}) and builds the tooltip when the pointer
 * first enters it.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LazyTooltipsFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aFreshWindowBuildsNoTooltipUntilHover() throws Exception {
        List<String> built = new ArrayList<>();
        FxTestSupport.runOnFx(() -> ControlNames.traceTooltips(built));
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            settle();
            assertNoneBuilt(built);
            hoverBuildsTheTooltip(fx);
        } finally {
            FxTestSupport.runOnFx(() -> ControlNames.traceTooltips(null));
        }
    }

    @Test
    void aWindowOpenedOnAFileBuildsNoTooltipUntilHover() throws Exception {
        Path dir = Files.createTempDirectory("editora-lazy-tooltips");
        Path file = dir.resolve("Sample.java");
        Files.writeString(file, "class Sample {\n    int x = 1;\n}\n");
        List<String> built = new ArrayList<>();
        FxTestSupport.runOnFx(() -> ControlNames.traceTooltips(built));
        try (FxWindowFixture fx = FxWindowFixture.create(
                dir, false, false, false, List.of(new MainController.OpenTarget(file, -1, -1)), true, c -> {})) {
            FxTestSupport.awaitFxCondition(
                    () -> FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[0]) != null,
                    "the file to open",
                    java.time.Duration.ofSeconds(10));
            settle();
            assertNoneBuilt(built);
            hoverBuildsTheTooltip(fx);
        } finally {
            FxTestSupport.runOnFx(() -> ControlNames.traceTooltips(null));
        }
    }

    /** A control's text is there before the hover, and its tooltip, with the shared delay, after it. */
    @Test
    void aTipIsBuiltOnFirstEnterAndFollowsLaterText() throws Exception {
        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.Button button = new javafx.scene.control.Button();
            int before = ControlNames.tooltipsBuilt();
            ControlNames.tip(button, "First");
            assertNull(button.getTooltip(), "no tooltip before the pointer comes");
            assertEquals("First", ControlNames.tipText(button));
            assertEquals("First", button.getAccessibleHelp(), "a screen reader reads the text meanwhile");
            ControlNames.tip(button, "Second");
            assertEquals("Second", ControlNames.tipText(button));
            assertEquals("Second", button.getAccessibleHelp());

            Event.fireEvent(button, entered());
            Tooltip tooltip = button.getTooltip();
            assertNotNull(tooltip, "the first enter builds it");
            assertEquals("Second", tooltip.getText());
            assertEquals(ControlNames.TOOLTIP_DELAY, tooltip.getShowDelay());
            assertNull(button.getAccessibleHelp(), "the tooltip supplies the help once it is built");
            assertEquals(before + 1, ControlNames.tooltipsBuilt());

            ControlNames.tip(button, "Third");
            assertEquals("Third", tooltip.getText(), "a built tooltip is updated, not replaced");
            Event.fireEvent(button, entered());
            assertEquals(before + 1, ControlNames.tooltipsBuilt(), "and the next enter builds no other");

            ControlNames.tip(button, null);
            assertNull(button.getTooltip(), "no text, no tooltip");
            assertNull(ControlNames.tipText(button));
        });
    }

    /** Help the control was given of its own stays its own. */
    @Test
    void ownHelpIsKept() throws Exception {
        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.Label label = new javafx.scene.control.Label("x");
            label.setAccessibleHelp("Its own");
            ControlNames.tip(label, "Tip");
            assertEquals("Its own", label.getAccessibleHelp());
            Event.fireEvent(label, entered());
            assertEquals("Its own", label.getAccessibleHelp());
            assertEquals("Tip", label.getTooltip().getText());
        });
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            FxTestSupport.runOnFx(() -> {});
        }
    }

    private static void assertNoneBuilt(List<String> built) throws Exception {
        // A window shown under the pointer gets a real hover: what that builds is no start-up cost of a window.
        List<String> seen = FxTestSupport.callOnFx(
                () -> built.stream().filter(s -> !s.startsWith("hover ")).toList());
        assertTrue(
                seen.isEmpty(),
                () -> seen.size() + " tooltip(s) built before any hover; give the control its text with"
                        + " ControlNames.tip (or name) instead of a Tooltip:\n" + String.join("\n", seen));
    }

    /** Some control of the window with a tooltip still to build gets it on the pointer's first entry. */
    private static void hoverBuildsTheTooltip(FxWindowFixture fx) throws Exception {
        Stage stage = FxTestSupport.field(fx.controller, "stage");
        FxTestSupport.runOnFx(() -> {
            Control pending = null;
            for (Node n : stage.getScene().getRoot().lookupAll("*")) {
                if (n instanceof Control c && c.getTooltip() == null && ControlNames.tipText(c) != null) {
                    pending = c;
                    break;
                }
            }
            assertNotNull(pending, "the window has controls whose tooltip waits for a hover");
            String text = ControlNames.tipText(pending);
            Event.fireEvent(pending, entered());
            assertNotNull(pending.getTooltip(), "hovering " + pending + " builds its tooltip");
            assertEquals(text, pending.getTooltip().getText());
        });
    }

    private static MouseEvent entered() {
        return new MouseEvent(
                MouseEvent.MOUSE_ENTERED,
                0,
                0,
                0,
                0,
                MouseButton.NONE,
                0,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null);
    }
}
