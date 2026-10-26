package com.editora.ui;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.stage.Stage;

import com.editora.report.ProblemReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Help → Report a Problem: the report is shown for review, and what the user leaves in it is what is posted. */
@Tag("fx")
class ReportProblemWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private Stage stage;

    @AfterEach
    void close() throws Exception {
        FxTestSupport.runOnFx(() -> {
            if (stage != null) {
                stage.hide();
            }
        });
    }

    @Test
    void theEditedReportIsCopiedAndFilledIntoTheIssue() throws Exception {
        AtomicInteger built = new AtomicInteger();
        List<String> opened = new CopyOnWriteArrayList<>();
        ReportProblemWindow window = FxTestSupport.callOnFx(() -> new ReportProblemWindow(
                () -> new ProblemReport.Assembled(
                        "Editora 1.0", "report #" + built.incrementAndGet() + "\nsecret line", 2),
                opened::add));
        FxTestSupport.runOnFx(() -> window.show(null));
        stage = FxTestSupport.field(window, "stage");
        TextArea area = FxTestSupport.field(window, "area");
        Label summary = FxTestSupport.field(window, "summary");
        Label status = FxTestSupport.field(window, "status");
        awaitText(area, "report #1\nsecret line");

        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("report.title"), stage.getTitle());
            assertTrue(area.isEditable());
            assertEquals(tr("report.redacted", 2), summary.getText());

            area.setText("report #1"); // the user deletes what they would rather not share
            button(tr("report.openIssue")).fire();
            assertEquals("report #1", Clipboard.getSystemClipboard().getString());
            assertEquals(tr("report.openedInline"), status.getText());
        });
        assertEquals(1, opened.size());
        String url = URLDecoder.decode(opened.get(0), StandardCharsets.UTF_8);
        assertTrue(url.contains("&report=report #1"), url);
        assertFalse(url.contains("secret line"), "the deleted line is not posted");
        assertTrue(url.contains("&environment=Editora 1.0"), url);

        FxTestSupport.runOnFx(() -> window.show(null)); // shown again: the logs have grown, so it is rebuilt
        awaitText(area, "report #2\nsecret line");
    }

    @Test
    void aReportTooLongForTheLinkIsPastedFromTheClipboard() throws Exception {
        String big = "x".repeat(ProblemReport.MAX_URL_LENGTH);
        List<String> opened = new CopyOnWriteArrayList<>();
        ReportProblemWindow window = FxTestSupport.callOnFx(
                () -> new ReportProblemWindow(() -> new ProblemReport.Assembled("Editora 1.0", big, 0), opened::add));
        FxTestSupport.runOnFx(() -> window.show(null));
        stage = FxTestSupport.field(window, "stage");
        TextArea area = FxTestSupport.field(window, "area");
        Label summary = FxTestSupport.field(window, "summary");
        Label status = FxTestSupport.field(window, "status");
        awaitText(area, big);

        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("report.redactedNone"), summary.getText());
            button(tr("report.openIssue")).fire();
            assertEquals(big, Clipboard.getSystemClipboard().getString());
            assertEquals(tr("report.openedPaste"), status.getText());
        });
        assertTrue(URLDecoder.decode(opened.get(0), StandardCharsets.UTF_8)
                .endsWith("&report=" + ReportProblemWindow.PASTE_HINT));
    }

    private static void awaitText(TextArea area, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (expected.equals(FxTestSupport.callOnFx(area::getText))) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(expected, FxTestSupport.callOnFx(area::getText));
    }

    private Button button(String label) {
        return stage.getScene().getRoot().lookupAll(".button").stream()
                .map(Node.class::cast)
                .filter(n -> n instanceof Button b && label.equals(b.getText()))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + label + "' button"));
    }
}
