package com.editora.ui;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A long echo message must never displace or squeeze the status bar's state segments. An {@link
 * javafx.scene.layout.HBox} that overflows shrinks every child toward its minimum, and a {@link Label}'s
 * minimum is its ellipsis — so before {@code pinSegmentWidths} a 200-character message (the echo cap) ate
 * into "LSP: jdtls" / "Editable" / the caret position, or pushed them past the right edge.
 *
 * <p>Only a real layout pass shows this: the model is fine either way, which is why this drives a sized
 * scene rather than asserting on text.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatusBarEchoLayoutFxTest {

    private static final double BAR_WIDTH = 900;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Lays out a status bar of {@link #BAR_WIDTH} carrying {@code message}, then runs {@code probe}. */
    private static <T> T withBar(String message, java.util.function.Function<StatusBar, T> probe) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = new Settings();
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent("hello\n");
            StatusBar sb = new StatusBar(() -> buffer, new CommandRegistry(), () -> settings);
            sb.attach(buffer);
            sb.setLsp("jdtls");
            sb.setMessage(message);
            StackPane root = new StackPane(sb);
            new Scene(root, BAR_WIDTH, 40);
            root.applyCss();
            root.layout();
            return probe.apply(sb);
        });
    }

    private static final String LONG_MESSAGE = "Reloaded pom.xml from disk after an external change, and then "
            + "reindexed the project because the dependency graph moved underneath the open editors again";

    @Test
    void aLongMessageDoesNotShrinkTheStateSegments() throws Exception {
        double[] widths = withBar(LONG_MESSAGE, sb -> {
            Label lsp = FxTestSupport.field(sb, "lsp");
            Label position = FxTestSupport.field(sb, "position");
            return new double[] {
                lsp.getWidth(), lsp.prefWidth(-1), position.getWidth(), position.prefWidth(-1),
            };
        });
        // ">= pref" rather than "== pref": layout snaps to whole pixels, so a laid-out width lands just
        // above its preferred one. What matters is that neither segment was shrunk below it.
        assertTrue(widths[0] >= widths[1] - 0.5, "the LSP segment keeps its full width: " + widths[0]);
        assertTrue(widths[2] >= widths[3] - 0.5, "so does the caret position: " + widths[2]);
    }

    @Test
    void aLongMessageIsTruncatedRatherThanOverflowingTheBar() throws Exception {
        double[] probe = withBar(LONG_MESSAGE, sb -> {
            Label echo = FxTestSupport.field(sb, "echo");
            Label size = FxTestSupport.field(sb, "size"); // the last segment on the right
            double rightEdge = size.getBoundsInParent().getMaxX();
            return new double[] {echo.getWidth(), echo.prefWidth(-1), rightEdge};
        });
        assertTrue(probe[0] < probe[1], "the echo gives up the space (ellipsized), rather than the segments");
        assertTrue(probe[2] <= BAR_WIDTH + 0.5, "nothing is pushed past the right edge, was " + probe[2]);
    }

    @Test
    void aShortMessageStillShowsInFull() throws Exception {
        double[] probe = withBar("Saved", sb -> {
            Label echo = FxTestSupport.field(sb, "echo");
            return new double[] {echo.getWidth(), echo.prefWidth(-1)};
        });
        assertTrue(probe[0] >= probe[1] - 0.5, "with room to spare the echo is not clipped: " + probe[0]);
    }
}
