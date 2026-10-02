package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.search.SearchQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layout of the Find bar and the Find in Files panel: fields use the space there is, nothing is
 * ellipsized to "…", and a narrow window wraps instead of squeezing.
 */
@Tag("fx")
class FindLayoutFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static <T extends Node> List<T> all(Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> all(c, type, out));
        }
        return out;
    }

    private static Scene sceneFor(Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets()
                .add(FindLayoutFxTest.class
                        .getResource("/com/editora/styles/app.css")
                        .toExternalForm());
        return scene;
    }

    /** No caption-bearing control in {@code root} is narrower than its text needs. */
    private static void assertNothingEllipsized(Node root, String where) {
        for (Labeled l : all(root, Labeled.class, new ArrayList<>())) {
            if (!l.isVisible()
                    || !l.isManaged()
                    || l.getText() == null
                    || l.getText().isEmpty()) {
                continue;
            }
            if (l.getParent() instanceof ComboBox || l.getStyleClass().contains("search-scope-path")) {
                continue; // a path / the combo's own cell elide by design
            }
            assertTrue(
                    l.getWidth() + 0.5 >= l.prefWidth(-1),
                    where + ": \"" + l.getText() + "\" is " + l.getWidth() + "px, needs " + l.prefWidth(-1));
        }
    }

    @Test
    void findBarFieldsGrowAndTheBarWrapsWhenNarrow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent("alpha beta");
            FindReplaceBar bar = new FindReplaceBar(() -> buffer, s -> {});
            VBox root = new VBox(bar, buffer.getNode());
            Stage stage = new Stage();
            stage.setScene(sceneFor(root, 1440, 400));
            stage.show();
            try {
                bar.show(false);
                root.applyCss();
                root.layout();
                TextField find = FxTestSupport.field(bar, "findField");
                TextField replace = FxTestSupport.field(bar, "replaceField");
                WrapRow row = all(bar, WrapRow.class, new ArrayList<>()).get(0);
                assertEquals(1, row.lineCount());
                assertTrue(find.getWidth() > 300, "the find field uses the bar, was " + find.getWidth());
                assertTrue(replace.getWidth() > 300, "the replace field uses the bar, was " + replace.getWidth());
                assertNothingEllipsized(bar, "1440px");
                double oneLine = bar.getHeight();

                // Options are named toggle buttons bound to the state-holding checkboxes.
                List<ToggleButton> toggles = all(bar, ToggleButton.class, new ArrayList<>());
                assertEquals(5, toggles.size());
                for (ToggleButton t : toggles) {
                    assertTrue(
                            t.getAccessibleText() != null
                                    && t.getAccessibleText().length() > 3,
                            t.getText());
                    assertTrue(t.getTooltip() != null, t.getText());
                }
                assertTrue(all(bar, CheckBox.class, new ArrayList<>()).isEmpty(), "no bare 'Aa' checkboxes on screen");
                CheckBox caseSensitive = FxTestSupport.field(bar, "caseSensitive");
                find.setText("alpha");
                toggles.get(0).fire();
                assertTrue(caseSensitive.isSelected(), "the toggle drives the option");
                caseSensitive.setSelected(false);
                assertFalse(toggles.get(0).isSelected(), "and follows it");

                stage.setWidth(620);
                root.resize(620, 400);
                root.applyCss();
                root.layout();
                assertTrue(row.lineCount() >= 2, "a narrow bar wraps");
                assertTrue(bar.getHeight() > oneLine, "and grows taller rather than clipping");
                assertNothingEllipsized(bar, "620px");
                assertTrue(find.getWidth() >= 140, "find field keeps a usable width, was " + find.getWidth());
            } finally {
                stage.hide();
            }
        });
    }

    @Test
    void findInFilesGivesTheQueryItsOwnRowAtTheDockWidth() throws Exception {
        FxTestSupport.runOnFx(() -> {
            SearchPanel panel = new SearchPanel(new SearchPanel.Actions() {
                @Override
                public void search(SearchQuery query, String includeGlobs, String excludeGlobs) {}

                @Override
                public void openMatch(Path file, int line, int col, boolean focusEditor) {}

                @Override
                public void replaceAll(
                        SearchQuery query,
                        String includeGlobs,
                        String excludeGlobs,
                        String replacement,
                        List<Path> files) {}

                @Override
                public void recordSearch(String query) {}
            });
            Stage stage = new Stage();
            stage.setScene(sceneFor(panel, 300, 500)); // the right dock's default width
            stage.show();
            try {
                panel.applyCss();
                panel.layout();
                ComboBox<String> query = FxTestSupport.field(panel, "queryCombo");
                assertTrue(query.getWidth() > 240, "the query spans the panel, was " + query.getWidth());
                assertNothingEllipsized(panel, "300px");
                List<ToggleButton> toggles = all(panel, ToggleButton.class, new ArrayList<>());
                assertEquals(3, toggles.size());
                for (ToggleButton t : toggles) {
                    assertTrue(
                            t.getAccessibleText() != null
                                    && t.getAccessibleText().length() > 3,
                            t.getText());
                }
                // The two glob fields stack at this width, so each can show its prompt.
                TextField include = FxTestSupport.field(panel, "includeField");
                TextField exclude = FxTestSupport.field(panel, "excludeField");
                assertTrue(
                        exclude.localToScene(0, 0).getY()
                                > include.localToScene(0, 0).getY(),
                        "globs stack");
                assertTrue(include.getWidth() > 240);
                // The Replace All button keeps its caption; the field keeps a usable width.
                Button replaceAll = all(panel, Button.class, new ArrayList<>()).stream()
                        .filter(b -> com.editora.i18n.Messages.tr("search.replaceAll")
                                .equals(b.getText()))
                        .findFirst()
                        .orElseThrow();
                assertTrue(replaceAll.getWidth() + 0.5 >= replaceAll.prefWidth(-1));
                TextField replaceField = FxTestSupport.field(panel, "replaceField");
                assertTrue(replaceField.getWidth() >= 150, "replace field was " + replaceField.getWidth());

                // Wide panel: the glob fields share one row again.
                stage.setWidth(700);
                panel.resize(700, 500);
                panel.layout();
                assertEquals(
                        include.localToScene(0, 0).getY(),
                        exclude.localToScene(0, 0).getY(),
                        0.5,
                        "globs side by side");
                assertTrue(all(panel, Label.class, new ArrayList<>()).size() > 0);
            } finally {
                stage.hide();
            }
        });
    }
}
