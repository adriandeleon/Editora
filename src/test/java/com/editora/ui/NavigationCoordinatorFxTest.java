package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.ListCell;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import com.editora.editor.UndoHistory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keyboard "jump to" pickers and the back/forward trail in a real window: what each picker lists, what
 * choosing a row does, and what the jump commands say when there is nowhere to go.
 */
@Tag("fx")
class NavigationCoordinatorFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static NavigationCoordinator nav(WindowRig w) {
        return w.field("navigation");
    }

    private static String numbered(int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            sb.append("line ").append(i).append('\n');
        }
        return sb.toString();
    }

    private static int caretLine(WindowRig w) throws Exception {
        return FxTestSupport.callOnFx(() -> w.active().getArea().getCurrentParagraph());
    }

    /** Jumps to a 0-based line of {@code file} the way a search hit or a definition does, and waits for it. */
    private static void jump(WindowRig w, Path file, int line0) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                w.controller, "openAndGoto", new Class<?>[] {Path.class, int.class, int.class}, file, line0, 0));
        w.await(
                "the jump to line " + (line0 + 1),
                () -> w.isActive(file) && w.active().getArea().getCurrentParagraph() == line0);
        w.async.awaitFx();
    }

    @Test
    void recentFilesListsNameAndFolderAndOpensTheChosenOne(@TempDir Path dir) throws Exception {
        Path first = Files.writeString(dir.resolve("first.txt"), "one\n");
        Path second = Files.writeString(dir.resolve("second.txt"), "two\n");
        try (WindowRig w = new WindowRig()) {
            w.open(first);
            w.open(second);
            QuickOpen<Path> picker = nav(w).recentPalette;
            FxTestSupport.runOnFx(() -> {
                picker.show(w.stage());
                assertTrue(picker.isShown());
                assertEquals(List.of("second.txt|" + dir, "first.txt|" + dir), WindowRig.rows(picker), "newest first");
                WindowRig.choose(picker, first);
                assertFalse(picker.isShown());
            });
            w.await("the chosen recent file", () -> w.isActive(first));
        }
    }

    @Test
    void jumpToStructureListsTheOutlineAndMovesTheCaret(@TempDir Path dir) throws Exception {
        Path notes = Files.writeString(dir.resolve("notes.md"), "# Top\n\ntext\n\n## Inner\n\nmore\n");
        try (WindowRig w = new WindowRig()) {
            w.open(notes);
            QuickOpen<StructurePanel.Outline> picker = nav(w).structurePalette;
            w.await("the outline", () -> {
                StructurePanel panel = w.field("structurePanel");
                return panel.outline().size() == 2;
            });
            FxTestSupport.runOnFx(() -> {
                picker.show(w.stage());
                assertEquals(List.of("Top|heading", "Inner|heading"), WindowRig.rows(picker));
                WindowRig.choose(picker, WindowRig.items(picker).get(1));
            });
            assertEquals(4, caretLine(w));
        }
    }

    @Test
    void jumpToOpenFileMarksUnsavedFilesAndActivatesTheChosenTab(@TempDir Path dir) throws Exception {
        Path clean = Files.writeString(dir.resolve("clean.txt"), "one\n");
        Path edited = Files.writeString(dir.resolve("edited.txt"), "two\n");
        try (WindowRig w = new WindowRig()) {
            w.open(clean);
            EditorBuffer editedBuffer = w.open(edited);
            FxTestSupport.runOnFx(() -> editedBuffer.getArea().appendText("changed"));
            NavigationCoordinator nav = nav(w);
            QuickOpen<Tab> picker = nav.openFilesPalette;
            FxTestSupport.runOnFx(() -> {
                picker.show(w.stage());
                Tab cleanTab = w.tabFor(clean);
                Tab editedTab = w.tabFor(edited);
                assertTrue(WindowRig.items(picker).containsAll(List.of(cleanTab, editedTab)));
                assertEquals("clean.txt|" + dir, WindowRig.row(picker, cleanTab));
                assertEquals("• edited.txt|" + dir, WindowRig.row(picker, editedTab), "unsaved, like its tab");

                ListCell<Tab> dirtyRow = WindowRig.cell(picker, editedTab);
                assertFalse(dirtyRow.getGraphic().lookupAll(".dirty-name").isEmpty(), "and styled like a dirty tab");
                assertTrue(WindowRig.cell(picker, cleanTab)
                        .getGraphic()
                        .lookupAll(".dirty-name")
                        .isEmpty());

                // A non-buffer tab (Welcome, a diff) has a title but no folder and is never "unsaved".
                Tab plain = new Tab();
                assertEquals("", nav.bufferTitle(plain));
                assertEquals("", nav.bufferTitle(null));
                assertEquals("", nav.bufferParentDir(plain));
                assertFalse(nav.isTabDirty(plain));

                WindowRig.choose(picker, cleanTab);
            });
            w.await("the chosen tab", () -> w.isActive(clean));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theToolWindowPickersListWhatCanBeOpenedAndOpenIt() throws Exception {
        try (WindowRig w = new WindowRig()) {
            NavigationCoordinator nav = nav(w);
            ToolWindowManager toolWindows = w.field("toolWindows");
            FxTestSupport.runOnFx(() -> {
                nav.toolWindowPalette.show(w.stage());
                List<ToolWindow> listed = WindowRig.items(nav.toolWindowPalette);
                assertFalse(listed.isEmpty());
                ToolWindow structure = listed.stream()
                        .filter(tw -> "tool.structure".equals(tw.getCommandId()))
                        .findFirst()
                        .orElseThrow();
                String row = WindowRig.row(nav.toolWindowPalette, structure);
                assertTrue(row.startsWith(structure.getTitle() + "|"), row);
                java.util.Map<String, String> chords = (java.util.Map<String, String>)
                        FxTestSupport.call(w.controller, "invertBindings", new Class<?>[0]);
                assertEquals(
                        structure.getTitle() + "|" + chords.getOrDefault("tool.structure", ""),
                        row,
                        "the detail column is the window's own chord");

                assertFalse(toolWindows.isOpen(structure));
                WindowRig.choose(nav.toolWindowPalette, structure);
                assertTrue(toolWindows.isOpen(structure), "choosing a tool window opens it");

                nav.splitToolWindowPalette.show(w.stage());
                List<ToolWindow> splittable = WindowRig.items(nav.splitToolWindowPalette);
                assertFalse(splittable.contains(structure), "the open window cannot be split with itself");
                for (ToolWindow candidate : splittable) {
                    assertTrue(toolWindows.canSplitWith(candidate), candidate.getTitle());
                    assertTrue(
                            WindowRig.row(nav.splitToolWindowPalette, candidate).startsWith(candidate.getTitle()));
                }
                nav.splitToolWindowPalette.hide();
            });
        }
    }

    @Test
    void theUndoHistoryPickerListsCheckpointsByTheirLineAndTime(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("draft.txt"), "first version\n");
        try (WindowRig w = new WindowRig()) {
            EditorBuffer buffer = w.open(file);
            QuickOpen<UndoHistory.Checkpoint> picker = nav(w).undoHistoryPalette;
            FxTestSupport.runOnFx(() -> {
                picker.show(w.stage());
                List<UndoHistory.Checkpoint> checkpoints = WindowRig.items(picker);
                assertEquals(1, checkpoints.size(), "opening the picker captures a baseline");
                UndoHistory.Checkpoint baseline = checkpoints.get(0);
                assertEquals("first version\n", baseline.text());
                assertEquals(
                        "the caret's line|" + NavigationCoordinator.undoCheckpointTime(baseline),
                        WindowRig.row(
                                picker,
                                new UndoHistory.Checkpoint(8, baseline.epochMillis(), "the caret's line", "x", 0)),
                        "a checkpoint is labelled with the line the caret was on");
                assertTrue(
                        NavigationCoordinator.undoCheckpointTime(baseline).matches("\\d\\d:\\d\\d:\\d\\d"),
                        "the capture time, to the second");
                assertEquals(
                        tr("undoHistory.blankLine") + "|" + NavigationCoordinator.undoCheckpointTime(baseline),
                        WindowRig.row(picker, new UndoHistory.Checkpoint(9, baseline.epochMillis(), "", "\n", 0)),
                        "a checkpoint taken on an empty line still has a label");

                buffer.getArea().replaceText("second version\n");
                WindowRig.choose(picker, baseline);
                assertEquals("first version\n", buffer.getContent(), "choosing a checkpoint restores its text");
            });
        }
    }

    @Test
    void backAndForwardWalkTheJumpTrailAndSayWhenThereIsNowhereToGo(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), numbered(40));
        Path b = Files.writeString(dir.resolve("b.txt"), numbered(40));
        try (WindowRig w = new WindowRig()) {
            w.run("nav.back");
            assertEquals(tr("status.nav.noBack"), w.status());
            w.run("nav.forward");
            assertEquals(tr("status.nav.noForward"), w.status());
            w.run("nav.recentLocations");
            assertEquals(tr("status.nav.noRecentLocations"), w.status());
            assertFalse(
                    FxTestSupport.callOnFx(() -> nav(w).recentLocationsPalette.isShown()));

            w.open(a);
            jump(w, a, 9);
            jump(w, b, 19);

            w.run("nav.back");
            w.await("back in a.txt", () -> w.isActive(a) && w.active().getArea().getCurrentParagraph() == 9);
            w.async.awaitFx();
            w.run("nav.forward");
            w.await(
                    "forward in b.txt",
                    () -> w.isActive(b) && w.active().getArea().getCurrentParagraph() == 19);
            w.async.awaitFx();
            w.run("nav.forward");
            assertEquals(tr("status.nav.noForward"), w.status());
            assertFalse(FxTestSupport.callOnFx(() -> nav(w).navigating), "a refused jump leaves recording on");
        }
    }

    @Test
    void recentLocationsShowsTheLineTextPreviewsWithoutRecordingAndRestoresOnCancel(@TempDir Path dir)
            throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), numbered(40));
        Path b = Files.writeString(dir.resolve("b.txt"), "\n" + numbered(40));
        try (WindowRig w = new WindowRig()) {
            NavigationCoordinator nav = nav(w);
            w.open(a);
            jump(w, a, 9);
            jump(w, b, 0); // a blank line
            jump(w, b, 20);
            int recorded = FxTestSupport.callOnFx(() -> nav.navHistory.recent().size());
            QuickOpen<NavigationHistory.Location> picker = nav.recentLocationsPalette;

            w.run("nav.recentLocations");
            FxTestSupport.runOnFx(() -> {
                assertTrue(picker.isShown());
                List<String> rows = WindowRig.rows(picker);
                assertEquals("line 20|b.txt:21", rows.get(0), "newest first: the text there, then file:line");
                assertTrue(rows.contains(tr("nav.recentLocations.blankLine") + "|b.txt:1"), rows.toString());
                assertTrue(rows.contains("line 10|a.txt:10"), rows.toString());

                NavigationHistory.Location inA = WindowRig.items(picker).stream()
                        .filter(loc -> loc.path().equals(a) && loc.line() == 9)
                        .findFirst()
                        .orElseThrow();
                WindowRig.highlight(picker, inA);
            });
            w.await(
                    "the previewed location",
                    () -> w.isActive(a) && w.active().getArea().getCurrentParagraph() == 9);
            FxTestSupport.runOnFx(() -> {
                assertEquals(recorded, nav.navHistory.recent().size(), "browsing the picker is not itself history");
                picker.hide(); // dismissed, not used
            });
            w.await(
                    "the place the picker was opened from",
                    () -> w.isActive(b) && w.active().getArea().getCurrentParagraph() == 20);
            assertNull(FxTestSupport.callOnFx(() -> nav.previewOrigin));

            w.run("nav.recentLocations");
            FxTestSupport.runOnFx(() -> WindowRig.choose(
                    picker,
                    WindowRig.items(picker).stream()
                            .filter(loc -> loc.path().equals(a) && loc.line() == 9)
                            .findFirst()
                            .orElseThrow()));
            w.await(
                    "the chosen location",
                    () -> w.isActive(a) && w.active().getArea().getCurrentParagraph() == 9);
        }
    }

    @Test
    void aPreviewOfAFileThatIsGoneOrOfNothingIsQuietlySkipped(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), numbered(10));
        Path gone = dir.resolve("deleted-since.txt");
        Path closed = Files.writeString(dir.resolve("closed.txt"), numbered(10));
        try (WindowRig w = new WindowRig()) {
            NavigationCoordinator nav = nav(w);
            w.open(a);
            FxTestSupport.runOnFx(() -> {
                nav.previewLocation(null);
                nav.previewLocation(new NavigationHistory.Location(gone, 3, 0));
                assertTrue(w.isActive(a), "nothing to show: the editor stays where it is");
                assertNull(w.tabFor(gone));
                assertEquals("", nav.lineTextAt(gone, 3), "a file that is not open has no line text to offer");
                assertEquals("", nav.lineTextAt(a, 99), "nor has a line past the end");
                assertEquals("line 4", nav.lineTextAt(a, 3));
                assertNull(nav.withSnippet(null));

                nav.previewLocation(new NavigationHistory.Location(closed, 4, 0));
            });
            w.await(
                    "the previewed file to open",
                    () -> w.isActive(closed) && w.active().getArea().getCurrentParagraph() == 4);
            assertFalse(FxTestSupport.callOnFx(() -> nav.suppressNavRecord));
        }
    }

    @Test
    void aLongLineIsCutShortInTheTrail(@TempDir Path dir) throws Exception {
        String longLine = "x".repeat(NavigationCoordinator.MAX_LOCATION_SNIPPET + 30);
        Path file = Files.writeString(dir.resolve("minified.js"), "  short  \n" + longLine + "\n");
        try (WindowRig w = new WindowRig()) {
            w.open(file);
            FxTestSupport.runOnFx(() -> {
                NavigationCoordinator nav = nav(w);
                assertEquals("short", nav.lineTextAt(file, 0), "trimmed");
                assertEquals("x".repeat(NavigationCoordinator.MAX_LOCATION_SNIPPET) + "…", nav.lineTextAt(file, 1));
                NavigationHistory.Location bare = new NavigationHistory.Location(file, 0, 0);
                assertEquals("short", nav.withSnippet(bare).snippet());
                NavigationHistory.Location kept = new NavigationHistory.Location(file, 0, 0, "already described");
                assertSame(kept, nav.withSnippet(kept));
                assertEquals("minified.js:1", NavigationCoordinator.locationLabel(bare));

                int before = nav.navHistory.recent().size();
                nav.recordJump(bare, null);
                assertEquals(before, nav.navHistory.recent().size(), "a jump to nowhere is not recorded");
            });
        }
    }

    @Test
    void findFileOpensAnExistingFileOrStartsANewOneAtThatPath(@TempDir Path dir) throws Exception {
        Path existing = Files.writeString(dir.resolve("existing.txt"), "here\n");
        Path fresh = dir.resolve("fresh.txt");
        try (WindowRig w = new WindowRig()) {
            NavigationCoordinator nav = nav(w);
            assertEquals(
                    Path.of(System.getProperty("user.home", ".")),
                    FxTestSupport.callOnFx(nav::finderStartDir),
                    "with nothing open the finder starts at home");

            FxTestSupport.runOnFx(() -> nav.findFileChosen(existing));
            w.await("the existing file", () -> w.isActive(existing));
            assertEquals(dir, FxTestSupport.callOnFx(nav::finderStartDir), "then beside the active file");

            FxTestSupport.runOnFx(() -> nav.findFileChosen(fresh));
            assertEquals(tr("status.newFile", "fresh.txt"), w.status());
            FxTestSupport.runOnFx(() -> {
                assertEquals(fresh, w.active().getPath(), "a new buffer that will be written there on save");
                assertEquals("", w.active().getContent());
            });
            assertFalse(Files.exists(fresh), "nothing is created on disk until then");
            Tab freshTab = FxTestSupport.callOnFx(() -> w.tabFor(fresh));

            w.open(existing);
            FxTestSupport.runOnFx(() -> nav.findFileChosen(fresh));
            FxTestSupport.runOnFx(() -> {
                assertSame(freshTab, w.tabFor(fresh), "naming it again goes back to its tab, not a second one");
                assertEquals(fresh, w.active().getPath());
            });
        }
    }

    @Test
    void relatedFileSaysWhenThereIsNoneOpensALoneCounterpartAndOffersSeveral(@TempDir Path dir) throws Exception {
        Path plain = Files.writeString(dir.resolve("LICENSE"), "text\n");
        Path lonely = Files.writeString(dir.resolve("Lonely.java"), "class Lonely {}\n");
        Path widget = Files.writeString(dir.resolve("Widget.java"), "class Widget {}\n");
        Path widgetTest = Files.writeString(dir.resolve("WidgetTest.java"), "class WidgetTest {}\n");
        Path page = Files.writeString(dir.resolve("page.ts"), "export {}\n");
        Path css = Files.writeString(dir.resolve("page.css"), "a {}\n");
        Path scss = Files.writeString(dir.resolve("page.scss"), "a {}\n");
        try (WindowRig w = new WindowRig()) {
            NavigationCoordinator nav = nav(w);
            w.run("nav.relatedFile");
            assertEquals(tr("status.related.noFile"), w.status());

            w.open(plain);
            w.run("nav.relatedFile");
            if (com.editora.search.RelatedFiles.candidates("LICENSE").isEmpty()) {
                assertEquals(tr("status.related.none", "LICENSE"), w.status());
            }

            w.open(lonely);
            FxTestSupport.runOnFx(
                    () -> nav.openRelated(lonely, com.editora.search.RelatedFiles.candidates("Lonely.java")));
            assertEquals(tr("status.related.none", "Lonely.java"), w.status());

            w.open(widget);
            FxTestSupport.runOnFx(
                    () -> nav.openRelated(widget, com.editora.search.RelatedFiles.candidates("Widget.java")));
            w.await("the test beside the class", () -> w.isActive(widgetTest));

            w.open(page);
            FxTestSupport.runOnFx(() -> {
                nav.openRelated(page, com.editora.search.RelatedFiles.candidates("page.ts"));
                assertTrue(nav.relatedPalette.isShown(), "two stylesheets: only the user knows which was meant");
                assertEquals(List.of(css, scss), WindowRig.items(nav.relatedPalette));
                assertEquals(
                        "page.css|"
                                + FxTestSupport.call(
                                        w.controller, "homeCollapsed", new Class<?>[] {String.class}, css.toString()),
                        WindowRig.row(nav.relatedPalette, css));
                WindowRig.choose(nav.relatedPalette, scss);
            });
            w.await("the chosen counterpart", () -> w.isActive(scss));
        }
    }

    @Test
    void goToLineTakesALineOrLineAndColumnClampsThemAndRejectsNonsense(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("lines.txt"), numbered(20));
        try (WindowRig w = new WindowRig()) {
            EditorBuffer buffer = w.open(file);
            NavigationCoordinator nav = nav(w);
            FxTestSupport.runOnFx(() -> nav.handleGoToLine(" 7 ", buffer.getArea(), 21));
            assertEquals(tr("status.gotoResult", 7, 1), w.status());
            assertEquals(6, caretLine(w));

            FxTestSupport.runOnFx(() -> nav.handleGoToLine("3:4", buffer.getArea(), 21));
            assertEquals(tr("status.gotoResult", 3, 4), w.status());
            assertEquals(3, (int) FxTestSupport.callOnFx(() -> buffer.getArea().getCaretColumn()));

            FxTestSupport.runOnFx(() -> nav.handleGoToLine("999:999", buffer.getArea(), 21));
            assertEquals(tr("status.gotoResult", 21, 1), w.status(), "past the end: the last line, its last column");

            FxTestSupport.runOnFx(() -> nav.handleGoToLine("5:0", buffer.getArea(), 21));
            assertEquals(tr("status.gotoResult", 5, 1), w.status(), "column 0 is the first column");

            FxTestSupport.runOnFx(() -> nav.handleGoToLine("2:", buffer.getArea(), 21));
            assertEquals(tr("status.gotoResult", 2, 1), w.status(), "a trailing colon is just the line");

            FxTestSupport.runOnFx(() -> nav.handleGoToLine("twelve", buffer.getArea(), 21));
            assertEquals(tr("status.gotoInvalid", "twelve"), w.status());
            FxTestSupport.runOnFx(() -> nav.handleGoToLine("4:x", buffer.getArea(), 21));
            assertEquals(tr("status.gotoInvalid", "4:x"), w.status());
            assertEquals(1, caretLine(w), "an invalid target leaves the caret where it was");
        }
    }

    @Test
    void theSnippetPickerInsertsTheChosenSnippetAtTheCaret(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Main.java"), "");
        try (WindowRig w = new WindowRig()) {
            EditorBuffer buffer = w.open(file);
            QuickOpen<com.editora.snippet.Snippet> picker = nav(w).snippetPalette;
            FxTestSupport.runOnFx(() -> {
                picker.show(w.stage());
                List<com.editora.snippet.Snippet> offered = WindowRig.items(picker);
                assertFalse(offered.isEmpty(), "Java ships with snippets");
                com.editora.snippet.Snippet first = offered.get(0);
                assertEquals(
                        com.editora.snippet.SnippetPreview.label(first) + "|"
                                + com.editora.snippet.SnippetPreview.detail(first),
                        WindowRig.row(picker, first));

                javafx.scene.control.TextField input = FxTestSupport.field(picker, "input");
                input.setText(first.prefix());
                assertTrue(WindowRig.items(picker).contains(first), "a snippet is found by its prefix");

                WindowRig.choose(picker, first);
                assertFalse(buffer.getContent().isEmpty(), "the snippet's body landed in the buffer");
            });
        }
    }
}
