package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build tool's actions dropdown: what it lists, how the search narrows it, what the keys and the mouse
 * do, and that a toggle composes with the task run after it instead of acting on its own.
 */
@Tag("fx")
class BuildActionsPopupFxTest {

    private static final BuildActionsPopup.Labels LABELS =
            new BuildActionsPopup.Labels("Maven", "Search goals…", "Run custom…");

    /** A Maven-like project: two goals, two profiles, and a section that only exists while "ci" is checked. */
    private static final class Provider implements BuildActionsProvider {
        final List<String> loaded = new ArrayList<>();
        int queries;

        @Override
        public List<BuildAction.Section> sections(Set<String> active) {
            queries++;
            List<BuildAction.Section> out = new ArrayList<>();
            List<BuildAction.Row> lifecycle = new ArrayList<>();
            lifecycle.add(new BuildAction.Task("clean", List.of("clean"), "Remove the build output"));
            lifecycle.add(new BuildAction.Task("package", List.of("package")));
            for (String task : loaded) {
                lifecycle.add(new BuildAction.Task(task, List.of(task)));
            }
            out.add(new BuildAction.Section("Lifecycle", lifecycle));
            out.add(new BuildAction.Section("Nothing here", List.of()));
            out.add(new BuildAction.Section(
                    "Profiles",
                    List.of(
                            new BuildAction.Toggle("ci", "ci", " (active by default)"),
                            new BuildAction.Toggle("release", "release"))));
            if (active.contains("ci")) {
                out.add(new BuildAction.Section(
                        "ci goals", List.of(new BuildAction.Task("ci:verify", List.of("verify", "-DskipITs=false")))));
            }
            return out;
        }

        @Override
        public List<String> toggleArgs(Set<String> active) {
            return active.isEmpty() ? List.of() : List.of("-P" + String.join(",", active));
        }

        @Override
        public void addLoadedTasks(List<String> tasks) {
            loaded.addAll(tasks);
        }
    }

    private Stage stage;
    private StackPane root;
    private Button anchor;
    private OverlayHost overlay;
    private BuildActionsPopup popup;
    private Provider provider;
    private final List<String> ran = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        provider = new Provider();
        ran.clear();
        FxTestSupport.runOnFx(() -> {
            anchor = new Button("Maven");
            root = new StackPane(anchor);
            overlay = new OverlayHost();
            overlay.install(root);
            stage = new Stage();
            stage.setScene(new Scene(root, 900, 700));
            stage.show();
            popup = new BuildActionsPopup(LABELS);
            popup.setOverlayHost(overlay);
            popup.setOnRunCustom(() -> ran.add("custom"));
            popup.setOnRun((taskArgs, toggleArgs) -> ran.add(taskArgs + " " + toggleArgs));
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            overlay.hide();
            stage.hide();
        });
    }

    private ListView<?> list() {
        return FxTestSupport.field(popup, "list");
    }

    private TextField search() {
        return FxTestSupport.field(popup, "search");
    }

    /** What each row shows, top to bottom; a header is marked with brackets, a checkbox with its state. */
    private List<String> rows() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            root.applyCss();
            root.layout();
            TreeMap<Integer, String> out = new TreeMap<>();
            for (Node n : list().lookupAll(".list-cell")) {
                ListCell<?> cell = (ListCell<?>) n;
                if (cell.isEmpty()) {
                    continue;
                }
                String text;
                if (cell.getGraphic() == null) {
                    assertTrue(cell.isDisabled(), "a header cannot be selected by a click");
                    assertTrue(cell.getStyleClass().contains("branch-popup-header"));
                    text = "[" + cell.getText() + "]";
                } else if (cell.getGraphic() instanceof CheckBox box) {
                    text = (box.isSelected() ? "☑ " : "☐ ") + box.getText();
                } else {
                    StringBuilder sb = new StringBuilder();
                    collect(cell.getGraphic(), sb);
                    text = sb.toString();
                    assertFalse(cell.getStyleClass().contains("branch-popup-header"));
                }
                out.put(cell.getIndex(), text);
            }
            return new ArrayList<>(out.values());
        });
    }

    private static void collect(Node node, StringBuilder out) {
        if (node instanceof Labeled labeled) {
            out.append(labeled.getText());
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    private int selected() throws Exception {
        return FxTestSupport.callOnFx(() -> list().getSelectionModel().getSelectedIndex());
    }

    private void key(KeyCode code) throws Exception {
        FxTestSupport.runOnFx(() -> Event.fireEvent(
                search(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)));
    }

    private void select(int index) throws Exception {
        FxTestSupport.runOnFx(() -> list().getSelectionModel().select(index));
    }

    private void show() throws Exception {
        FxTestSupport.runOnFx(() -> popup.show(stage, provider));
    }

    @Test
    void listsTheOwnActionsThenEachNonEmptySection() throws Exception {
        FxTestSupport.runOnFx(() -> popup.setSecondaryAction("Load all tasks…", () -> ran.add("load")));
        assertFalse(popup.isShown());
        assertFalse(popup.justHidden());
        show();

        assertTrue(popup.isShown());
        assertEquals(
                List.of(
                        "Run custom…",
                        "Load all tasks…",
                        "[Lifecycle]",
                        "clean",
                        "package",
                        "[Profiles]",
                        "☐ ci (active by default)",
                        "☐ release"),
                rows());
        assertEquals(0, selected(), "the first row that can be run");
        Label hint = FxTestSupport.field(popup, "hint");
        assertFalse(FxTestSupport.callOnFx(hint::getText).isBlank(), "the key legend is filled in on show");

        // Only a task with a description gets a tooltip.
        List<String> tooltips = FxTestSupport.callOnFx(() -> {
            List<String> out = new ArrayList<>();
            for (Node n : list().lookupAll(".list-cell")) {
                ListCell<?> cell = (ListCell<?>) n;
                if (cell.getTooltip() != null) {
                    out.add(cell.getIndex() + ":" + cell.getTooltip().getText());
                }
            }
            return out;
        });
        assertEquals(List.of("3:Remove the build output"), tooltips);
    }

    @Test
    void theSearchKeepsMatchingRowsUnderTheirHeaders() throws Exception {
        show();
        FxTestSupport.runOnFx(() -> search().setText(" PACK "));
        assertEquals(List.of("[Lifecycle]", "package"), rows());
        assertEquals(1, selected(), "the header is not the selection");

        FxTestSupport.runOnFx(() -> search().setText("rel"));
        assertEquals(List.of("[Profiles]", "☐ release"), rows());

        FxTestSupport.runOnFx(() -> search().setText("zzzz"));
        assertEquals(List.of(), rows());
        assertEquals(-1, selected());
        key(KeyCode.ENTER); // nothing selected: nothing runs, and the popup stays
        assertEquals(List.of(), ran);
        assertTrue(popup.isShown());

        FxTestSupport.runOnFx(() -> search().setText(""));
        assertEquals(7, rows().size());
    }

    @Test
    void arrowKeysStepOverHeadersAndEnterRunsTheTask() throws Exception {
        show();
        key(KeyCode.DOWN);
        assertEquals(2, selected(), "from Run custom over the Lifecycle header to clean");
        key(KeyCode.DOWN);
        assertEquals(3, selected());
        key(KeyCode.DOWN);
        assertEquals(5, selected(), "over the Profiles header");
        key(KeyCode.UP);
        assertEquals(3, selected());

        key(KeyCode.ENTER);
        assertEquals(List.of("[package] []"), ran);
        assertFalse(popup.isShown(), "running a task closes the popup");
        assertTrue(popup.justHidden());
        assertNull(FxTestSupport.callOnFx(() -> root.lookup(".command-palette")));
    }

    @Test
    void aToggleStaysOpenRevealsItsRowsAndIsPassedToTheNextRun() throws Exception {
        show();
        int before = provider.queries;
        select(5); // ci
        key(KeyCode.ENTER);

        assertTrue(popup.isShown(), "a toggle does not act on its own");
        assertEquals(List.of(), ran);
        assertTrue(provider.queries > before, "the provider is asked again with the toggle on");
        assertEquals(
                List.of(
                        "Run custom…",
                        "[Lifecycle]",
                        "clean",
                        "package",
                        "[Profiles]",
                        "☑ ci (active by default)",
                        "☐ release",
                        "[ci goals]",
                        "ci:verify"),
                rows());
        assertEquals(5, selected(), "the selection stays on the toggle that was flipped");

        select(6);
        key(KeyCode.ENTER); // a second profile
        select(8);
        key(KeyCode.ENTER); // the goal that only exists while ci is on
        assertEquals(List.of("[verify, -DskipITs=false] [-Pci,release]"), ran);
        assertFalse(popup.isShown());

        // Shown again, it starts from nothing checked.
        show();
        assertEquals(7, rows().size());
        select(5);
        key(KeyCode.ENTER);
        key(KeyCode.ENTER); // and off again
        assertEquals("☐ ci (active by default)", rows().get(5));
        select(2);
        key(KeyCode.ENTER);
        assertEquals("[clean] []", ran.get(1));
    }

    @Test
    void runCustomClosesFirstTheSecondActionDoesNot() throws Exception {
        FxTestSupport.runOnFx(() -> popup.setSecondaryAction("Load all tasks…", () -> {
            ran.add("load");
            provider.addLoadedTasks(List.of("integration-test"));
            popup.rerender();
        }));
        show();
        select(1);
        key(KeyCode.ENTER);
        assertEquals(List.of("load"), ran);
        assertTrue(popup.isShown(), "loading repopulates the list in place");
        assertEquals("integration-test", rows().get(5));

        select(0);
        key(KeyCode.ENTER);
        assertEquals(List.of("load", "custom"), ran);
        assertFalse(popup.isShown());
    }

    @Test
    void aPrimaryClickActivatesTheRowUnderIt() throws Exception {
        show();
        select(3);
        FxTestSupport.runOnFx(() -> Event.fireEvent(list(), click(MouseButton.SECONDARY)));
        assertEquals(List.of(), ran, "a right click is not a run");
        FxTestSupport.runOnFx(() -> Event.fireEvent(list(), click(MouseButton.PRIMARY)));
        assertEquals(List.of("[package] []"), ran);
    }

    private static MouseEvent click(MouseButton button) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                5,
                5,
                5,
                5,
                button,
                1,
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
    void escapeClosesWithoutRunning() throws Exception {
        FxTestSupport.runOnFx(() -> popup.show(stage, anchor, provider)); // the toolbar entry point
        assertTrue(popup.isShown());
        assertEquals(7, FxTestSupport.callOnFx(() -> list().getItems().size()));
        assertEquals("Run custom…", rows().get(0));
        key(KeyCode.ESCAPE);
        assertFalse(popup.isShown());
        assertEquals(List.of(), ran);
    }

    @Test
    void withoutAnOverlayToShowInItStaysHidden() throws Exception {
        BuildActionsPopup detached = FxTestSupport.callOnFx(() -> new BuildActionsPopup(LABELS));
        FxTestSupport.runOnFx(() -> {
            detached.rerender(); // never shown: no provider to ask
            detached.show(stage, provider);
            detached.show(stage, anchor, provider);
            detached.hide();
        });
        assertFalse(detached.isShown());
        assertNull(FxTestSupport.callOnFx(() -> root.lookup(".command-palette")));
    }
}
