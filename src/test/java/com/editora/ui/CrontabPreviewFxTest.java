package com.editora.ui;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.editora.cron.Crontab;
import com.editora.editor.structured.CrontabPreview;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Renders the crontab preview node on the FX thread and checks its decoded content. */
@Tag("fx")
class CrontabPreviewFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void rendersDecodedSchedulesAndError() throws Exception {
        String text = """
                MAILTO=ops@acme.com
                */15 * * * *    /opt/poll.sh
                30 2 * * 1-5    /opt/backup.sh
                @reboot         /opt/warmcache.sh
                99 * * * *      /opt/oops.sh
                """;
        List<String> labels = FxTestSupport.callOnFx(() -> {
            VBox node = CrontabPreview.content(Crontab.parse(text), LocalDateTime.of(2026, 7, 10, 9, 41), 700);
            node.applyCss();
            List<String> out = new ArrayList<>();
            collect(node, out);
            return out;
        });

        String all = String.join("\n", labels);
        assertTrue(all.contains("MAILTO = ops@acme.com"), all);
        assertTrue(all.contains("/opt/backup.sh"), all);
        assertTrue(all.contains("At 02:30, Monday through Friday"), all);
        assertTrue(all.contains("Every 15 minutes"), all);
        assertTrue(all.contains("At system startup"), all); // @reboot
        assertTrue(labels.stream().anyMatch(s -> s.contains("99")), all); // the bad line's field error
    }

    /**
     * PERF-08 (Pauta 6.0.1): the live preview is a virtualized list. A crontab of 5,000 jobs builds the rows on
     * screen, with the content the preview always had, in file order; the export's picture still holds them all.
     */
    @Test
    void theLivePreviewOfFiveThousandJobsBuildsOnlyTheRowsOnScreen() throws Exception {
        StringBuilder text = new StringBuilder("MAILTO=ops@acme.com\n");
        for (int i = 0; i < 5_000; i++) {
            text.append(i % 60).append(" 2 * * *    /opt/job-").append(i).append(".sh\n");
        }
        Crontab crontab = Crontab.parse(text.toString());
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 9, 41);
        FxTestSupport.runOnFx(() -> {
            Node live = CrontabPreview.build(crontab, now);
            javafx.stage.Stage stage = new javafx.stage.Stage();
            javafx.scene.Scene scene = new javafx.scene.Scene(new javafx.scene.layout.StackPane(live), 700, 500);
            scene.getStylesheets()
                    .add(com.editora.editor.UiZoom.sheet(CrontabPreviewFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm()));
            stage.setScene(scene);
            stage.show();
            try {
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                javafx.scene.control.ListView<?> list = (javafx.scene.control.ListView<?>) live;
                assertEquals(5_002, list.getItems().size(), "the header, the assignment and every job are rows");
                int jobs = live.lookupAll(".crontab-job").size();
                assertTrue(jobs > 0 && jobs < 60, "job rows with nodes: " + jobs);
                assertFalse(list.isFocusTraversable(), "a read-out, as the column of labels was");

                List<String> shown = new ArrayList<>();
                collect(live, shown);
                String all = String.join("\n", shown);
                assertTrue(shown.get(0).replaceAll("[^0-9]", "").startsWith("5000"), shown.get(0));
                assertEquals("MAILTO = ops@acme.com", shown.get(1));
                assertEquals("/opt/job-0.sh", shown.get(2), "the first job follows, in file order");
                assertTrue(all.contains("At 02:00"), all);
                assertFalse(all.contains("/opt/job-4999.sh"), "a job far down has no node yet");

                list.scrollTo(5_001);
                scene.getRoot().layout();
                // ListView.scrollTo puts the row at the top by estimated row heights; with macOS's taller rows it
                // stopped one row short of the end (rows 4994..5000). The flow's own scrollTo brings the row fully
                // into view, laid out until it has its node.
                javafx.scene.control.skin.VirtualFlow<?> flow =
                        (javafx.scene.control.skin.VirtualFlow<?>) live.lookup(".virtual-flow");
                List<String> end = new ArrayList<>();
                for (int pass = 0; pass < 5 && !end.contains("/opt/job-4999.sh"); pass++) {
                    if (pass > 0 && flow != null) {
                        flow.scrollTo(5_001);
                    }
                    scene.getRoot().applyCss();
                    scene.getRoot().layout();
                    end.clear();
                    collect(live, end);
                }
                assertTrue(
                        end.contains("/opt/job-4999.sh"),
                        "scrolled to the end, the last job is there to read; list " + list.getWidth() + "x"
                                + list.getHeight() + ", rows "
                                + (flow == null || flow.getFirstVisibleCell() == null
                                        ? "none"
                                        : flow.getFirstVisibleCell().getIndex() + ".."
                                                + flow.getLastVisibleCell().getIndex())
                                + ", last labels " + end.subList(Math.max(0, end.size() - 4), end.size()));
                assertTrue(live.lookupAll(".crontab-job").size() < 60, "and still only the rows on screen");

                // The picture an export takes holds every job: it is drawn once, off screen.
                VBox whole = CrontabPreview.content(crontab, now, 700);
                assertEquals(5_002, whole.getChildren().size());
            } finally {
                stage.hide();
            }
        });
    }

    private static void collect(Node node, List<String> out) {
        if (node instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }
}
