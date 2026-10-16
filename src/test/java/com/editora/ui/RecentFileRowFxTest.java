package com.editora.ui;

import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Recent Files dropdown rows line their project pills up and fit the names they show. */
@Tag("fx")
class RecentFileRowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Laid(List<RecentFileRow> rows, List<Label> names, List<Label> pills, List<Button> removes) {}

    /** Lays out one row per {@code {name, project-or-null}} pair, all sharing one {@link RecentFileRow.Columns}. */
    private static Laid layOut(String[][] entries) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            RecentFileRow.Columns columns = new RecentFileRow.Columns();
            List<RecentFileRow> rows = new java.util.ArrayList<>();
            List<Label> names = new java.util.ArrayList<>();
            List<Label> pills = new java.util.ArrayList<>();
            List<Button> removes = new java.util.ArrayList<>();
            VBox box = new VBox();
            for (String[] entry : entries) {
                Label name = new Label(entry[0]);
                Label pill = null;
                if (entry[1] != null) {
                    pill = new Label(entry[1]);
                    pill.getStyleClass().add("recent-project-name");
                }
                Button remove = new Button("x");
                RecentFileRow row = new RecentFileRow(columns, name, pill, remove);
                rows.add(row);
                names.add(name);
                pills.add(pill);
                removes.add(remove);
                box.getChildren().add(row);
            }
            Scene scene = new Scene(box);
            scene.getStylesheets()
                    .add(RecentFileRow.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            box.applyCss();
            box.resize(box.prefWidth(-1), box.prefHeight(-1));
            box.layout();
            return new Laid(rows, names, pills, removes);
        });
    }

    @Test
    void pillsStartAtTheSameXWhateverTheNameLength() throws Exception {
        Laid laid = layOut(new String[][] {
            {"pom.xml", "Editora"}, {"CsvCellEdit.java", "Editora"}, {"README.md", null}, {"AppTest.java", "test"}
        });
        double x = laid.pills().get(0).getBoundsInParent().getMinX();
        assertEquals(x, laid.pills().get(1).getBoundsInParent().getMinX(), 0.5);
        assertEquals(x, laid.pills().get(3).getBoundsInParent().getMinX(), 0.5);
        // The pill column starts past the widest name.
        assertTrue(x >= laid.names().get(1).getBoundsInParent().getMaxX() + RecentFileRow.GAP - 0.5);
    }

    @Test
    void namesUnderTheCapAreShownWhole() throws Exception {
        String longName = "2026-10-12-editora-0-21-0-release.md";
        Laid laid = layOut(new String[][] {{longName, null}, {"pom.xml", "Editora"}});
        Label name = laid.names().get(0);
        double pref = FxTestSupport.callOnFx(() -> name.prefWidth(-1));
        assertTrue(pref < RecentFileRow.MAX_NAME_WIDTH, "test name should fit under the cap");
        assertEquals(pref, name.getWidth(), 0.5, "a name under the cap is not ellipsized");
    }

    @Test
    void anOverlongNameIsCappedSoTheMenuStaysNarrow() throws Exception {
        String huge = "x".repeat(400) + ".txt";
        Laid laid = layOut(new String[][] {{huge, "Editora"}});
        assertEquals(RecentFileRow.MAX_NAME_WIDTH, laid.names().get(0).getWidth(), 0.5);
    }

    @Test
    void removeButtonsAreRightAlignedAcrossRows() throws Exception {
        Laid laid = layOut(new String[][] {{"a.txt", null}, {"much-longer-name.java", "Editora"}});
        double right0 = laid.removes().get(0).getBoundsInParent().getMaxX();
        double right1 = laid.removes().get(1).getBoundsInParent().getMaxX();
        assertEquals(right0, right1, 0.5);
        assertEquals(laid.rows().get(0).getWidth(), right0, 0.5);
    }
}
