package com.editora.ui;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.agent.AcpJson;
import com.editora.config.FileIdentity;
import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import com.editora.dap.DapModels;
import com.editora.editor.structured.MarkwhenTimeline;
import com.editora.markwhen.MarkwhenParser;
import com.editora.maven.PomUpgrades;
import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-08 (Pauta 6.0.1), the lists that make a node per item: each either is a virtualized list (the newer-versions
 * dialog) or shows a number of items it names and says how many there are past them (Parallel Stacks, the note card,
 * the agent's plan, the hover card's problems, the Markwhen timeline).
 */
@Tag("fx")
class NodePerItemCapsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void listCapSaysHowManyItLeftOut() {
        assertEquals(List.of(1, 2), ListCap.head(List.of(1, 2, 3), 2));
        assertEquals(List.of(1), ListCap.head(List.of(1), 2));
        assertNull(ListCap.note(5, 5), "nothing left out, nothing said");
        assertEquals(tr("list.capped", 5, 6), ListCap.note(6, 5));
    }

    @Test
    void parallelStacksDrawANamedNumberOfBoxesAndFramesAndSaySo() throws Exception {
        // 300 threads, each its own way up from one shared root frame and 40 frames deep there: 301 boxes.
        List<DapModels.ThreadStack> threads = new ArrayList<>();
        Path app = Path.of("/app/Pool.java");
        int[] ids = {0};
        for (int t = 0; t < 300; t++) {
            List<DapModels.StackFrameInfo> outermostFirst = new ArrayList<>();
            outermostFirst.add(new DapModels.StackFrameInfo(++ids[0], "Thread.run", app, 0, 0, null, "", null, null));
            for (int f = 0; f < 40; f++) {
                outermostFirst.add(
                        new DapModels.StackFrameInfo(++ids[0], "t" + t + "f" + f, app, f, 0, null, "", null, null));
            }
            List<DapModels.StackFrameInfo> topFirst = new ArrayList<>(outermostFirst);
            java.util.Collections.reverse(topFirst);
            threads.add(new DapModels.ThreadStack(
                    new DapModels.ThreadStatus(t + 1, "thread-" + t, true, "", t == 0), topFirst));
        }
        ParallelStacks stacks = new ParallelStacks(new ThreadDump("app", LocalTime.NOON, false, threads));
        assertEquals(301, stacks.boxes().size());
        FxTestSupport.runOnFx(() -> {
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 200, 200));
            owner.show();
            DebugParallelStacksWindow window = new DebugParallelStacksWindow(new DebugParallelStacksWindow.Actions() {
                @Override
                public void openFrame(DapModels.StackFrameInfo frame) {}

                @Override
                public void takeAgain() {}

                @Override
                public boolean canTakeAgain() {
                    return false;
                }
            });
            try {
                window.show(owner, stacks);
                Pane canvas = window.canvas();
                assertEquals(
                        DebugParallelStacksWindow.MAX_BOXES,
                        canvas.lookupAll(".parallel-stacks-box").size(),
                        "boxes drawn");
                assertEquals(
                        DebugParallelStacksWindow.MAX_BOXES - 1,
                        canvas.lookupAll(".parallel-stacks-edge").size(),
                        "one tree: an edge to every drawn box but the root");
                for (Node box : canvas.lookupAll(".parallel-stacks-box")) {
                    int frames = ((VBox) box).getChildren().size() - 1; // less the heading
                    assertTrue(frames <= DebugParallelStacksWindow.MAX_BOX_FRAMES + 1, "frames in a box: " + frames);
                }
                List<String> more = canvas.lookupAll(".parallel-stacks-frame-more").stream()
                        .map(n -> ((Label) n).getText())
                        .distinct()
                        .toList();
                assertEquals(
                        List.of(tr("parallelstacks.moreFrames", 40 - DebugParallelStacksWindow.MAX_BOX_FRAMES)), more);
                assertTrue(
                        window.note()
                                .getText()
                                .contains(tr("parallelstacks.capped", DebugParallelStacksWindow.MAX_BOXES, 301)),
                        window.note().getText());
                assertTrue(window.note().isVisible());
                assertTrue(stacks.text().contains("t299f0"), "Copy has the whole tree");
            } finally {
                window.hide();
                owner.hide();
            }
        });
    }

    @Test
    void theNewerVersionsListIsVirtualizedAndSpaceTicksARow() throws Exception {
        List<PomUpgrades.Row> rows = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            rows.add(new PomUpgrades.Row(PomUpgrades.Kind.DEPENDENCY, "org.d:dep-" + i, "1.0", "2.0", i % 7 == 0));
        }
        FxTestSupport.runOnFx(() -> {
            MavenUpgradeList upgrades = new MavenUpgradeList(rows, r -> r.key() + " " + r.current() + " → " + r.next());
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(upgrades.node()), 600, 400));
            stage.show();
            try {
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                ListView<MavenUpgradeList.Item> list = upgrades.listForTest();
                assertEquals(5_001, list.getItems().size(), "a heading and every newer version");
                int boxes = list.lookupAll(".maven-version-row").size();
                assertTrue(boxes > 3 && boxes < 60, "check boxes with nodes: " + boxes);
                assertEquals(
                        tr("dialog.mavenVersions.group.dependency"),
                        ((Label) list.lookup(".maven-version-group")).getText());
                assertTrue(list.lookup(".maven-version-major") != null, "the major tag is beside a major jump");
                assertTrue(upgrades.anySelectedProperty().get());
                assertEquals(5_000, upgrades.chosen().size(), "every row starts ticked");

                // A click on a box clears its row; Space on the row the keyboard is on does too.
                CheckBox first = (CheckBox) list.lookup(".maven-version-row");
                first.setSelected(false);
                list.requestFocus();
                list.getSelectionModel().select(2);
                Event.fireEvent(
                        list, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE, false, false, false, false));
                List<PomUpgrades.Row> chosen = upgrades.chosen();
                assertEquals(4_998, chosen.size());
                assertFalse(chosen.contains(rows.get(0)));
                assertFalse(chosen.contains(rows.get(1)));
                list.scrollTo(5_000);
                stage.getScene().getRoot().layout();
                assertTrue(list.lookupAll(".maven-version-row").size() < 60, "scrolled, still the rows on screen");
            } finally {
                stage.hide();
            }
        });
    }

    @Test
    void theNoteCardHoldsANamedNumberOfEditors() throws Exception {
        Path folder = Path.of("/project/src");
        List<PersonalNote> notes = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            notes.add(PersonalNote.create(FileIdentity.of(folder), NoteScope.FOLDER, null, "note " + i, List.of()));
        }
        FxTestSupport.runOnFx(() -> {
            ProjectMapNotePreview card = new ProjectMapNotePreview((note, body) -> {});
            card.showNotes(folder, notes, new ProjectMapPreview.Placement(0, 0, 400, 300, false));
            VBox column = FxTestSupport.field(card, "notes"); // in a scroll pane, which has no skin out of a window
            assertEquals(
                    ProjectMapNotePreview.MAX_NOTES,
                    column.lookupAll(".project-map-note-preview-editor").size());
            Label capped = (Label) column.lookup(".list-capped");
            assertEquals(tr("project.map.notes.capped", ProjectMapNotePreview.MAX_NOTES, 60), capped.getText());
            // A refresh with the same notes keeps the editors (and any edit in progress); a removed note rebuilds.
            TextArea firstEditor = (TextArea) column.lookup(".project-map-note-preview-editor");
            card.refreshNotes(notes);
            assertTrue(firstEditor == column.lookup(".project-map-note-preview-editor"), "kept");
            card.refreshNotes(notes.subList(0, 10));
            assertEquals(
                    10, column.lookupAll(".project-map-note-preview-editor").size());
            assertNull(column.lookup(".list-capped"), "nothing left out, nothing said");
        });
    }

    @Test
    void thePlanAndTheHoverProblemsShowANamedNumberOfLines() throws Exception {
        FxTestSupport.runOnFx(() -> {
            AgentPanel panel = new AgentPanel(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, path -> {});
            List<AcpJson.PlanEntry> plan = new ArrayList<>();
            for (int i = 0; i < 1_000; i++) {
                plan.add(new AcpJson.PlanEntry("step " + i, "pending"));
            }
            panel.setPlan(plan);
            VBox planBox = FxTestSupport.field(panel, "planBox");
            assertEquals(AgentPanel.MAX_PLAN_ENTRIES + 1, planBox.getChildren().size());
            assertEquals(
                    tr("list.capped", AgentPanel.MAX_PLAN_ENTRIES, 1_000),
                    ((Label) planBox.getChildren().getLast()).getText());

            List<LspDiagnostic> problems = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                problems.add(new LspDiagnostic(
                        0, 0, 0, 1, LspDiagnostic.Severity.ERROR, "bad " + i, null, "x", null, false, false));
            }
            VBox card = (VBox) CaretProblems.node(problems);
            assertEquals(CaretProblems.MAX_SHOWN + 1, card.getChildren().size());
            assertEquals(
                    tr("list.capped", CaretProblems.MAX_SHOWN, 500),
                    ((Label) card.getChildren().getLast()).getText());
        });
    }

    @Test
    void aTimelineOfHeadingsCountsThemAgainstItsCap() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1_200; i++) {
            text.append("# Group ").append(i).append('\n');
        }
        text.append("2026-01-01: one event\n");
        Node node =
                FxTestSupport.callOnFx(() -> MarkwhenTimeline.build(MarkwhenParser.parse(text.toString()), 1.0, 800));
        String shown = FxTestSupport.callOnFx(() -> String.join(
                "\n",
                node.lookupAll(".label").stream()
                        .map(n -> ((Label) n).getText())
                        .toList()));
        assertTrue(shown.contains(tr("markwhen.timeline.tooManyRows", 1_201, 1_000)), shown);
        assertFalse(shown.contains("Group 1199"), "no heading is drawn");
    }
}
