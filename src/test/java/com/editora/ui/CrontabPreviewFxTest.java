package com.editora.ui;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.editora.cron.Crontab;
import com.editora.editor.CrontabPreview;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
