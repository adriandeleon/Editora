package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import com.editora.build.BuildAction;
import com.editora.build.BuildActionsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build tasks tree in a showing window: what its rows render as, the Emacs-style keys inside it, the
 * mouse, and the checkbox of a toggle row.
 */
@Tag("fx")
class BuildActionsTreeKeysFxTest {

    /** Two sections; "Plugins" starts folded; checking "ci" reveals a third. */
    private static final class Provider implements BuildActionsProvider {
        @Override
        public List<BuildAction.Section> sections(Set<String> active) {
            List<BuildAction.Section> out = new ArrayList<>();
            out.add(new BuildAction.Section(
                    "Lifecycle",
                    List.of(
                            new BuildAction.Task("clean", List.of("clean"), "Remove the build output"),
                            new BuildAction.Task("package", List.of("package")),
                            new BuildAction.Toggle("ci", "ci", "(active by default)"),
                            new BuildAction.Toggle("release", "release"))));
            out.add(new BuildAction.Section(
                    "Plugins", List.of(new BuildAction.Task("versions:display", List.of("versions:display"))), true));
            if (active.contains("ci")) {
                out.add(new BuildAction.Section(
                        "ci goals", List.of(new BuildAction.Task("ci:verify", List.of("verify")))));
            }
            return out;
        }

        @Override
        public List<String> toggleArgs(Set<String> active) {
            return active.isEmpty() ? List.of() : List.of("-P" + String.join(",", active));
        }
    }

    private Stage stage;
    private BuildActionsTree panel;
    private final List<String> ran = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        ran.clear();
        FxTestSupport.runOnFx(() -> {
            panel = new BuildActionsTree();
            panel.setOnRun((taskArgs, toggleArgs) -> ran.add(taskArgs + " " + toggleArgs));
            panel.setProvider(new Provider());
            stage = new Stage();
            stage.setScene(new Scene(panel, 500, 600));
            stage.show();
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(stage::hide);
    }

    @SuppressWarnings("unchecked")
    private TreeView<Object> tree() {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
    }

    private void key(KeyCode code, boolean ctrl) throws Exception {
        FxTestSupport.runOnFx(
                () -> tree().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false)));
    }

    private int selected() throws Exception {
        return FxTestSupport.callOnFx(() -> tree().getSelectionModel().getSelectedIndex());
    }

    private void select(int row) throws Exception {
        FxTestSupport.runOnFx(() -> tree().getSelectionModel().select(row));
    }

    /** What each visible row shows: a section's title, a task's label, or a checkbox with its state. */
    private List<String> rows() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            panel.applyCss();
            panel.layout();
            TreeMap<Integer, String> out = new TreeMap<>();
            for (Node n : tree().lookupAll(".tree-cell")) {
                TreeCell<?> cell = (TreeCell<?>) n;
                if (cell.isEmpty()) {
                    continue;
                }
                String text;
                if (cell.getGraphic() instanceof CheckBox box) {
                    assertTrue(cell.getStyleClass().contains("build-tasks-toggle"));
                    text = (box.isSelected() ? "☑ " : "☐ ") + box.getText();
                } else if (cell.getStyleClass().contains("build-tasks-section")) {
                    text = "[" + cell.getText() + "]";
                } else {
                    assertTrue(cell.getStyleClass().contains("build-tasks-task"));
                    text = cell.getText()
                            + (cell.getTooltip() == null
                                    ? ""
                                    : " (" + cell.getTooltip().getText() + ")");
                }
                out.put(cell.getIndex(), text);
            }
            return new ArrayList<>(out.values());
        });
    }

    private CheckBox checkBox(String startsWith) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            panel.applyCss();
            panel.layout();
            for (Node n : tree().lookupAll(".tree-cell")) {
                TreeCell<?> cell = (TreeCell<?>) n;
                if (!cell.isEmpty()
                        && cell.getGraphic() instanceof CheckBox box
                        && box.getText().startsWith(startsWith)) {
                    return box;
                }
            }
            throw new AssertionError("no checkbox " + startsWith);
        });
    }

    @Test
    void rowsRenderAsSectionsTasksAndCheckboxes() throws Exception {
        assertEquals(
                List.of(
                        "[Lifecycle]",
                        "clean (Remove the build output)",
                        "package",
                        "☐ ci  (active by default)",
                        "☐ release",
                        "[Plugins]"),
                rows());
    }

    @Test
    void checkingAToggleRevealsItsRowsAndIsPassedToTheRun() throws Exception {
        CheckBox ci = checkBox("ci");
        FxTestSupport.runOnFx(() -> {
            ci.setSelected(true);
            ci.fire(); // fire() flips it and raises the action; start from the opposite state
        });
        // fire() toggled it back off: nothing revealed.
        assertFalse(rows().contains("[ci goals]"));
        CheckBox again = checkBox("ci");
        FxTestSupport.runOnFx(again::fire);
        List<String> withCi = rows();
        assertTrue(withCi.contains("☑ ci  (active by default)"), withCi.toString());
        assertTrue(withCi.contains("[ci goals]"), withCi.toString());
        assertTrue(withCi.contains("ci:verify"), withCi.toString());

        select(withCi.indexOf("package"));
        key(KeyCode.ENTER, false);
        assertEquals(List.of("[package] [-Pci]"), ran);

        // Unchecked again, the revealed section goes and so does the argument.
        FxTestSupport.runOnFx(checkBox("ci")::fire);
        assertFalse(rows().contains("[ci goals]"));
        select(rows().indexOf("package"));
        key(KeyCode.M, true);
        assertEquals("[package] []", ran.get(1));
    }

    @Test
    void enterRunsOnlyATask() throws Exception {
        select(0); // the Lifecycle section
        key(KeyCode.ENTER, false);
        select(3); // a toggle
        key(KeyCode.ENTER, false);
        assertEquals(List.of(), ran);
        select(1);
        key(KeyCode.ENTER, false);
        assertEquals(List.of("[clean] []"), ran);
        // A key that is not one of the panel's is left for the tree.
        key(KeyCode.X, true);
        key(KeyCode.N, false);
        assertEquals(1, selected());
        assertEquals(1, ran.size());
    }

    @Test
    void controlFAndBExpandCollapseAndStepBetweenASectionAndItsRows() throws Exception {
        int plugins = rows().indexOf("[Plugins]");
        select(plugins);
        key(KeyCode.F, true);
        assertTrue(rows().contains("versions:display"), "C-f opens a folded section");
        assertEquals(plugins, selected());
        key(KeyCode.F, true);
        assertEquals(plugins + 1, selected(), "and then steps into it");

        key(KeyCode.B, true);
        assertEquals(plugins, selected(), "C-b on a row goes to its section");
        key(KeyCode.B, true);
        assertFalse(rows().contains("versions:display"), "C-b on an open section folds it");
        key(KeyCode.B, true);
        assertEquals(plugins - 1, selected(), "and on a folded one moves up");

        // On a task, C-f just moves down.
        select(1);
        key(KeyCode.F, true);
        assertEquals(2, selected());
    }

    @Test
    void movingWrapsAndStartsFromAnEndWhenNothingIsSelected() throws Exception {
        int last = rows().size() - 1;
        FxTestSupport.runOnFx(() -> tree().getSelectionModel().clearSelection());
        key(KeyCode.N, true);
        assertEquals(0, selected());
        key(KeyCode.P, true);
        assertEquals(last, selected(), "up from the first row wraps to the last");
        key(KeyCode.N, true);
        assertEquals(0, selected());

        FxTestSupport.runOnFx(() -> tree().getSelectionModel().clearSelection());
        key(KeyCode.P, true);
        assertEquals(last, selected());
        FxTestSupport.runOnFx(() -> tree().getSelectionModel().clearSelection());
        key(KeyCode.B, true);
        assertEquals(last, selected(), "C-b with nothing selected behaves as up");
    }

    @Test
    void aDoubleClickRunsTheSelectedTask() throws Exception {
        select(2);
        FxTestSupport.runOnFx(() -> Event.fireEvent(tree(), click(MouseButton.PRIMARY, 1)));
        FxTestSupport.runOnFx(() -> Event.fireEvent(tree(), click(MouseButton.SECONDARY, 2)));
        assertEquals(List.of(), ran);
        FxTestSupport.runOnFx(() -> Event.fireEvent(tree(), click(MouseButton.PRIMARY, 2)));
        assertEquals(List.of("[package] []"), ran);
    }

    private static MouseEvent click(MouseButton button, int count) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                5,
                5,
                5,
                5,
                button,
                count,
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

    @Test
    void theClearButtonEmptiesTheFilterAndFocusLandsOnIt() throws Exception {
        TextField filter = FxTestSupport.field(panel, "filterField");
        Button clear = (Button) FxTestSupport.callOnFx(() -> panel.lookup(".project-filter-clear"));
        assertFalse(FxTestSupport.callOnFx(clear::isVisible), "nothing to clear yet");
        FxTestSupport.runOnFx(() -> filter.setText("pack"));
        assertTrue(FxTestSupport.callOnFx(clear::isVisible));
        assertEquals(List.of("[Lifecycle]", "package"), rows());

        FxTestSupport.runOnFx(clear::fire);
        assertEquals("", FxTestSupport.callOnFx(filter::getText));
        assertEquals(6, rows().size());

        FxTestSupport.runOnFx(() -> {
            tree().getSelectionModel().clearSelection();
            panel.focusFirstItem();
        });
        assertEquals(0, selected(), "the first row is selected so Enter in the filter has something to run");
        FxTestSupport.runOnFx(panel::focusContent);
        assertTrue(FxTestSupport.callOnFx(filter::isFocused));
    }

    @Test
    void anEmptyTreeIgnoresTheKeysAndASecondActionReplacesTheFirst() throws Exception {
        List<String> pressed = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            panel.setSecondaryAction("Load all tasks…", () -> pressed.add("first"));
            panel.setSecondaryAction("Load all tasks…", () -> pressed.add("second"));
            panel.setProvider(null);
            panel.focusFirstItem();
        });
        key(KeyCode.N, true);
        key(KeyCode.ENTER, false);
        assertEquals(-1, selected());
        assertEquals(List.of(), ran);
        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree().getRoot());
        assertTrue(root == null || root.getChildren().isEmpty());

        javafx.scene.layout.Pane toolbar = FxTestSupport.field(panel, "toolbar");
        Button secondary = FxTestSupport.field(panel, "secondaryButton");
        assertEquals(
                1,
                FxTestSupport.callOnFx(() -> toolbar.getChildren().stream()
                        .filter(n -> n == secondary)
                        .count()));
        FxTestSupport.runOnFx(secondary::fire);
        assertEquals(List.of("second"), pressed, "the replaced action's button is gone, not left beside the new one");
    }
}
