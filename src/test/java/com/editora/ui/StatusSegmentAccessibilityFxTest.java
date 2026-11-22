package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACC-02 (Pauta 6.2.0): every status-bar segment that does something on a click is a button to a screen reader,
 * which presses it through {@link AccessibleAction#FIRE}, and a new status message is told to the screen reader.
 */
@Tag("fx")
class StatusSegmentAccessibilityFxTest {

    /** A registry that records what it was asked to run instead of running anything. */
    private static final class Recording extends CommandRegistry {
        final List<String> ran = new ArrayList<>();

        @Override
        public boolean run(String id) {
            ran.add(id);
            return true;
        }
    }

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static StatusBar bar(Recording registry) {
        Settings settings = new Settings();
        EditorBuffer buffer = new EditorBuffer();
        buffer.setContent("hello\n");
        StatusBar bar = new StatusBar(() -> buffer, registry, () -> settings);
        bar.attach(buffer);
        StackPane root = new StackPane(bar);
        new Scene(root, 1600, 40);
        root.applyCss();
        root.layout();
        return bar;
    }

    private static List<Label> clickable(Node node, List<Label> out) {
        if (node instanceof Label label
                && (label.getOnMouseClicked() != null || label instanceof com.editora.editor.Pressable.Owner)) {
            out.add(label);
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> clickable(child, out));
        }
        return out;
    }

    @Test
    void everyClickableSegmentIsAButtonAScreenReaderCanPress() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Recording registry = new Recording();
            StatusBar bar = bar(registry);
            bar.addPluginSegment("Plugin", "plugin.run", "Plugin: run");
            bar.setOnHttpEnvironmentClicked(() -> registry.ran.add("http environment menu"));
            List<Label> segments = clickable(bar, new ArrayList<>());
            assertTrue(segments.size() >= 25, "the bar's clickable segments: " + segments.size());
            for (Label segment : segments) {
                String where = segment.getStyleClass() + " '" + segment.getText() + "'";
                assertTrue(segment instanceof StatusSegment, where + " is a StatusSegment");
                assertEquals(AccessibleRole.BUTTON, segment.getAccessibleRole(), where);
                assertTrue(segment.isFocusTraversable(), where + " is reached with Tab");
                assertTrue(segment.getStyleClass().contains("status-segment-clickable"), where);

                registry.ran.clear();
                segment.executeAccessibleAction(AccessibleAction.FIRE);
                assertEquals(1, registry.ran.size(), where + " runs its command when a screen reader presses it");

                registry.ran.clear();
                segment.fireEvent(
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                segment.fireEvent(
                        new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false));
                assertEquals(2, registry.ran.size(), where + " runs on Enter and on Space");

                registry.ran.clear();
                segment.fireEvent(click(MouseButton.PRIMARY));
                assertEquals(1, registry.ran.size(), where + " runs on a click");
            }
            // The plugin's runs its own command; the echo and the unread-errors marker open the Message Log.
            registry.ran.clear();
            for (Label segment : segments) {
                if (segment.getText().equals("Plugin")
                        || segment.getStyleClass().contains("status-message")
                        || segment.getStyleClass().contains("text-zoom-percent")) {
                    segment.executeAccessibleAction(AccessibleAction.FIRE);
                }
            }
            assertEquals(List.of("view.messageLog", "view.textZoomReset", "plugin.run"), registry.ran);
        });
    }

    @Test
    void aSegmentIgnoresAModifiedKeyAndADisabledOne() throws Exception {
        FxTestSupport.runOnFx(() -> {
            int[] runs = {0};
            StatusSegment segment = new StatusSegment(() -> runs[0]++);
            segment.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, true, false, false));
            segment.fireEvent(click(MouseButton.SECONDARY));
            assertEquals(0, runs[0], "Ctrl+Enter and a right-click are not a press");
            segment.setDisable(true);
            segment.executeAccessibleAction(AccessibleAction.FIRE);
            assertEquals(0, runs[0], "a disabled segment does nothing");
        });
        assertTrue(StatusSegment.isActivationKey(KeyCode.SPACE, false));
        assertFalse(StatusSegment.isActivationKey(KeyCode.SPACE, true));
        assertFalse(StatusSegment.isActivationKey(KeyCode.TAB, false));
    }

    @Test
    void aNewStatusMessageIsToldToTheScreenReader() throws Exception {
        FxTestSupport.runOnFx(() -> {
            StatusBar bar = bar(new Recording());
            StatusSegment echo = FxTestSupport.field(bar, "echo");
            assertEquals(AccessibleRole.BUTTON, echo.getAccessibleRole());
            assertEquals(
                    tr("statusbar.tip.messageLog"),
                    echo.queryAccessibleAttribute(AccessibleAttribute.TEXT),
                    "idle, the echo reads as what it opens");

            int before = echo.announcements;
            bar.setMessage(tr("status.saved", "notes.md"));
            assertEquals(before + 1, echo.announcements, "the message is announced");
            assertNull(echo.getAccessibleText(), "the echo reads as its message");
            assertEquals(tr("status.saved", "notes.md"), echo.queryAccessibleAttribute(AccessibleAttribute.TEXT));
            assertEquals(tr("statusbar.tip.messageLog"), echo.getAccessibleHelp(), "and says what pressing it does");

            bar.setMessage(tr("status.saved", "notes.md"));
            assertEquals(before + 2, echo.announcements, "the same message said again is said again");

            bar.setMessage("first line\nsecond line");
            assertEquals(
                    StatusBar.echoLine("first line\nsecond line"),
                    echo.queryAccessibleAttribute(AccessibleAttribute.TEXT));

            int quiet = echo.announcements;
            bar.setMessage(null);
            assertEquals(quiet, echo.announcements, "clearing the line says nothing");
            assertEquals(tr("statusbar.tip.messageLog"), echo.queryAccessibleAttribute(AccessibleAttribute.TEXT));
        });
    }

    private static MouseEvent click(MouseButton button) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0,
                0,
                0,
                0,
                button,
                1,
                false,
                false,
                false,
                false,
                button == MouseButton.PRIMARY,
                false,
                button == MouseButton.SECONDARY,
                false,
                false,
                true,
                null);
    }
}
