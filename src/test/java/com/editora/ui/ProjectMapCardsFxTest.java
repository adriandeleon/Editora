package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import com.editora.config.FileIdentity;
import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import com.editora.config.Settings;
import com.editora.config.TextAnchor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Floating preview cards, Personal Note cards and Print / PDF output of the Project Map. */
class ProjectMapCardsFxTest {

    private static final int WHITE = 0xFFFFFFFF;

    @TempDir
    Path temp;

    private Path root;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- Print / PDF -------------------------------------------------------------------------------------

    @Test
    void outputPaintsTheWholeMapBeyondTheLiveViewportInALightPalette() throws Exception {
        root = projectRoot();
        Path sub = Files.createDirectory(root.resolve("sub"));
        for (int i = 0; i < 60; i++) {
            Files.writeString(root.resolve(String.format("File%02d.java", i)), "class A {}");
        }
        Files.writeString(sub.resolve("Deep.java"), "class Deep {}");
        MapFixture map = openMap(340, 260, path -> null, sub);
        try {
            waitForRow(map, sub.resolve("Deep.java"));
            FxTestSupport.runOnFx(() -> {
                // A dark theme on screen: the page must still be ink on white.
                map.scene.setUserAgentStylesheet(Themes.stylesheetFor("Primer Dark"));
                map.view.applyCss();
                map.view.layout();
                Rectangle background = FxTestSupport.field(map.surface, "bgProbe");
                assertTrue(((Color) background.getFill()).getBrightness() < 0.3, "the live theme is dark");

                Canvas canvas = FxTestSupport.field(map.surface, "canvas");
                double liveWidth = canvas.getWidth();
                double liveHeight = canvas.getHeight();
                double liveZoom = FxTestSupport.field(map.surface, "zoom");
                double liveOffsetX = FxTestSupport.field(map.surface, "offsetX");
                double liveOffsetY = FxTestSupport.field(map.surface, "offsetY");

                AtomicReference<ProjectMapOutput> job = new AtomicReference<>();
                map.view.setOutputActions(job::set, ignored -> {});
                FxTestSupport.call(map.view, "setOutputEnabled", new Class<?>[] {boolean.class}, true);
                FxTestSupport.<Button>field(map.view, "printButton").fire();
                // A page large enough for the whole map: one image, at one point per map pixel.
                ProjectMapOutput.Rendered rendered = job.get().render(20_000, 20_000);

                assertEquals(1, rendered.pages().size());
                assertFalse(rendered.plan().tiled());
                Image image = rendered.pages().getFirst();
                double scale = rendered.plan().renderScale();
                assertTrue(image.getWidth() > liveWidth * scale, "the map is wider than the viewport");
                assertTrue(image.getHeight() > liveHeight * scale, "the map is taller than the viewport");
                assertTrue(image.getHeight() > 2_048, "tall enough to be assembled from several renders");

                PixelReader pixels = image.getPixelReader();
                assertEquals(WHITE, pixels.getArgb(1, 1), "the page is white whatever the live theme is");
                int width = (int) image.getWidth();
                int height = (int) image.getHeight();
                // The defect: only the top-left region the size of the live canvas was painted.
                assertTrue(
                        hasInk(pixels, (int) (liveWidth * scale) + 1, 0, width, height),
                        "columns right of the live viewport must be painted");
                assertTrue(
                        hasInk(pixels, 0, (int) (liveHeight * scale) + 1, width, height),
                        "rows below the live viewport must be painted");
                assertTrue(hasInk(pixels, 0, height - 200, width, height), "the last rows of the map are painted");
                assertTrue(
                        hasInk(pixels, 0, 2_048, width, 2_300),
                        "the second render of the page is stitched in under the first");

                assertSame(canvas, FxTestSupport.field(map.surface, "canvas"), "painting returns to the live canvas");
                assertEquals(liveWidth, canvas.getWidth(), 0.001);
                assertEquals(liveHeight, canvas.getHeight(), 0.001);
                assertEquals(liveZoom, (double) FxTestSupport.field(map.surface, "zoom"), 0.001);
                assertEquals(liveOffsetX, (double) FxTestSupport.field(map.surface, "offsetX"), 0.001);
                assertEquals(liveOffsetY, (double) FxTestSupport.field(map.surface, "offsetY"), 0.001);
                assertTrue(
                        ((Color) FxTestSupport.<Rectangle>field(map.surface, "bgProbe")
                                                .getFill())
                                        .getBrightness()
                                < 0.3,
                        "the live palette is untouched");
            });
        } finally {
            map.close();
        }
    }

    @Test
    void outputIsTiledAtALegibleScaleAndSaysSo() throws Exception {
        root = projectRoot();
        for (int i = 0; i < 60; i++) {
            Files.writeString(root.resolve(String.format("File%02d.java", i)), "class A {}");
        }
        MapFixture map = openMap(700, 500, path -> null);
        try {
            waitForRow(map, root.resolve("File59.java"));
            FxTestSupport.runOnFx(() -> {
                AtomicReference<ProjectMapOutput> job = new AtomicReference<>();
                map.view.setOutputActions(ignored -> {}, job::set);
                FxTestSupport.call(map.view, "setOutputEnabled", new Class<?>[] {boolean.class}, true);
                FxTestSupport.<Button>field(map.view, "exportPdfButton").fire();
                assertFalse(job.get().landscape(), "one tall column belongs on a portrait page");
                ProjectMapOutput.Rendered rendered = job.get().render(540, 720);

                ProjectMapOutputPlan.Plan plan = rendered.plan();
                assertTrue(plan.tiled(), "sixty rows do not fit one Letter page at a readable size");
                assertEquals(ProjectMapOutputPlan.MIN_PAPER_SCALE, plan.paperScale(), 1e-9);
                assertEquals(plan.pages().size(), rendered.pages().size());
                assertTrue(rendered.pages().size() >= 3);
                for (Image page : rendered.pages()) {
                    assertTrue(page.getWidth() * rendered.pointsPerPixel() <= 540.5, "a page fits the paper's width");
                    assertTrue(page.getHeight() * rendered.pointsPerPixel() <= 720.5, "and its height");
                    assertTrue(
                            hasInk(page.getPixelReader(), 0, 0, (int) page.getWidth(), (int) page.getHeight()),
                            "every page shows part of the map");
                }
                assertEquals(
                        tr("status.projectMap.outputTiled", plan.pages().size(), plan.columns(), plan.rows()),
                        ExportCoordinator.projectMapOutputNote(plan));
            });
        } finally {
            map.close();
        }
    }

    @Test
    void pdfExportRendersOnlyAfterADestinationWasChosenAndReportsTheTiling() throws Exception {
        root = projectRoot();
        for (int i = 0; i < 60; i++) {
            Files.writeString(root.resolve(String.format("File%02d.java", i)), "class A {}");
        }
        MapFixture map = openMap(700, 500, path -> null);
        Path pdf = temp.resolve("map.pdf");
        List<String> statuses = new CopyOnWriteArrayList<>();
        CoordinatorHostStub host = new CoordinatorHostStub() {
            private final Settings settings = new Settings();

            @Override
            public Settings settings() {
                return settings;
            }

            @Override
            public void setStatus(String message) {
                statuses.add(message);
            }
        };
        AtomicReference<java.io.File> destination = new AtomicReference<>();
        AtomicInteger renders = new AtomicInteger();
        ExportCoordinator[] exports = new ExportCoordinator[1];
        try {
            waitForRow(map, root.resolve("File59.java"));
            AtomicReference<ProjectMapOutputPlan.Plan> plan = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> {
                exports[0] = new ExportCoordinator(
                        host, null, null, null, path -> fail("opens nothing"), chooser -> destination.get());
                AtomicReference<ProjectMapOutput> job = new AtomicReference<>();
                map.view.setOutputActions(ignored -> {}, job::set);
                FxTestSupport.call(map.view, "setOutputEnabled", new Class<?>[] {boolean.class}, true);
                FxTestSupport.<Button>field(map.view, "exportPdfButton").fire();
                ProjectMapOutput counting = new ProjectMapOutput() {
                    @Override
                    public boolean landscape() {
                        return job.get().landscape();
                    }

                    @Override
                    public Rendered render(double pageWidth, double pageHeight) {
                        renders.incrementAndGet();
                        Rendered rendered = job.get().render(pageWidth, pageHeight);
                        plan.set(rendered.plan());
                        return rendered;
                    }
                };

                exports[0].exportProjectMapPdf(counting, "map"); // the dialog is cancelled
                assertEquals(0, renders.get(), "a cancelled export must not have rendered the map");

                destination.set(pdf.toFile());
                exports[0].exportProjectMapPdf(counting, "map");
                assertEquals(1, renders.get());
            });
            String note = tr(
                    "status.projectMap.outputTiled",
                    plan.get().pages().size(),
                    plan.get().columns(),
                    plan.get().rows());
            waitFor(() -> statuses.contains(note), "the export must say that the map was split across pages");
            assertEquals(note, statuses.getLast(), "the note is what stays in the status bar");
            assertTrue(statuses.contains(tr("status.pdf.exported", pdf.toString())));
            try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                assertEquals(plan.get().pages().size(), document.getNumberOfPages());
                PDRectangle box = document.getPage(0).getMediaBox();
                assertTrue(box.getHeight() > box.getWidth(), "a tall map stays on portrait pages");
            }
        } finally {
            FxTestSupport.runOnFx(() -> {
                if (exports[0] != null) {
                    exports[0].shutdown();
                }
            });
            map.close();
        }
    }

    // --- Placement ---------------------------------------------------------------------------------------

    @Test
    void cardsKeepAUsableSizeInAToolWindowWidePanelAndNeverPanTheMap() throws Exception {
        root = projectRoot();
        Path file = Files.writeString(root.resolve("Narrow.java"), "class Narrow {}\n");
        for (double panelWidth : new double[] {320, 208}) {
            NoteStore notes = new NoteStore(file, "a note");
            MapFixture map =
                    openMap(panelWidth, 560, path -> new ProjectMapPreview.Content("class Narrow {}\n", false));
            try {
                waitForRow(map, file);
                FxTestSupport.runOnFx(() -> {
                    map.view.setNotePreviewActions(notes);
                    double offsetX = FxTestSupport.field(map.surface, "offsetX");
                    double offsetY = FxTestSupport.field(map.surface, "offsetY");
                    double hostWidth = map.host.getWidth();
                    double room = hostWidth - ProjectMapPreview.EDGE_MARGIN * 2;

                    openNotes(map, file);
                    ProjectMapNotePreview note = noteCards(map).get(file);
                    assertNotNull(note);
                    assertEquals(
                            Math.min(ProjectMapNotePreview.MIN_WIDTH, room),
                            Math.min(ProjectMapNotePreview.MIN_WIDTH, note.getWidth()),
                            0.5,
                            () -> "at " + panelWidth + " px the note card must not collapse: " + note.getWidth());
                    assertTrue(note.getHeight() >= ProjectMapNotePreview.MIN_HEIGHT);
                    assertInside(note, map.host);

                    openPreview(map, file);
                    ProjectMapPreview code = codeCards(map).get(file);
                    assertNotNull(code);
                    assertEquals(
                            Math.min(ProjectMapPreview.MIN_WIDTH, room),
                            Math.min(ProjectMapPreview.MIN_WIDTH, code.getWidth()),
                            0.5,
                            () -> "at " + panelWidth + " px the code card keeps its minimum: " + code.getWidth());
                    assertTrue(code.getHeight() >= ProjectMapPreview.MIN_HEIGHT);
                    assertInside(code, map.host);

                    assertEquals(offsetX, (double) FxTestSupport.field(map.surface, "offsetX"), 0.001, "no pan");
                    assertEquals(offsetY, (double) FxTestSupport.field(map.surface, "offsetY"), 0.001, "no pan");
                });
            } finally {
                map.close();
            }
        }
    }

    @Test
    void aLoadThatLandsLateGrowsTheCardWhereItStandsWithoutMovingItOrTheMap() throws Exception {
        root = projectRoot();
        Path file = Files.writeString(root.resolve("Wide.txt"), ("y".repeat(300) + "\n").repeat(20));
        MapFixture map = openMap(1_400, 800, path -> null);
        try {
            waitForRow(map, file);
            double[] placed = new double[4];
            FxTestSupport.runOnFx(() -> {
                openPreview(map, file);
                ProjectMapPreview card = codeCards(map).get(file);
                assertEquals(tr("project.map.preview.loading"), statusOf(card), "the file is still being read");
                // While it loads, the user drags the card away and pans the map.
                HBox titleBar = FxTestSupport.field(card, "titleBar");
                FxTestSupport.invokeWith(card, "dragPressed", MouseEvent.class, mouse(titleBar, 30, 12, true));
                card.relocate(100, 200);
                placed[0] = card.getLayoutX();
                placed[1] = card.getLayoutY();
                placed[2] = card.getWidth();
                placed[3] = FxTestSupport.<Double>field(map.surface, "offsetX") - 175;
                setField(map.surface, "offsetX", placed[3]);
            });
            ProjectMapPreview card = FxTestSupport.callOnFx(() -> codeCards(map).get(file));
            waitFor(() -> statusOf(card).isEmpty(), "the file must finish loading");
            FxTestSupport.runOnFx(() -> {
                assertEquals(100, card.getLayoutX(), 0.001, "a dragged card stays where it was put");
                assertEquals(200, card.getLayoutY(), 0.001);
                assertEquals(placed[3], (double) FxTestSupport.field(map.surface, "offsetX"), 0.001, "no second pan");
                assertTrue(card.getWidth() > placed[2], "long lines still widen the card");
                assertTrue(
                        card.getWidth() < 1_000,
                        () -> "but not to the whole panel for one long line: " + card.getWidth());
                assertInside(card, map.host);
            });
        } finally {
            map.close();
        }
    }

    // --- Content -----------------------------------------------------------------------------------------

    @Test
    void closedFilesAreDecodedLikeTheEditorAndCrlfFilesAreHighlighted() throws Exception {
        root = projectRoot();
        Path latin = root.resolve("latin.txt");
        Files.write(latin, "café naïve über\n".getBytes(StandardCharsets.ISO_8859_1));
        Path crlf = Files.writeString(root.resolve("Crlf.java"), "class Crlf {\r\n    int value = 1;\r\n}\r\n");
        MapFixture map = openMap(1_400, 800, path -> ProjectMapPreview.Content.closed(null));
        try {
            waitForRow(map, crlf);
            FxTestSupport.runOnFx(() -> {
                openPreview(map, latin);
                openPreview(map, crlf);
            });
            CodeArea latinText =
                    FxTestSupport.callOnFx(() -> codeCards(map).get(latin).editor());
            CodeArea crlfText =
                    FxTestSupport.callOnFx(() -> codeCards(map).get(crlf).editor());
            waitFor(() -> !latinText.getText().isEmpty() && !crlfText.getText().isEmpty(), "both files must load");
            FxTestSupport.runOnFx(() -> {
                assertEquals("café naïve über\n", latinText.getText());
                assertEquals("class Crlf {\n    int value = 1;\n}\n", crlfText.getText());
            });
            waitFor(
                    () -> crlfText.getStyleSpans(0, crlfText.getLength()).stream()
                            .anyMatch(span -> !span.getStyle().isEmpty()),
                    "a CRLF file must get its syntax highlighting");
        } finally {
            map.close();
        }
    }

    @Test
    void aFailedImageClearsTheLoadingStatus() throws Exception {
        root = projectRoot();
        Path broken = Files.writeString(root.resolve("broken.png"), "this is not a PNG");
        MapFixture map = openMap(1_200, 800, path -> null);
        try {
            waitForRow(map, broken);
            FxTestSupport.runOnFx(() -> openPreview(map, broken));
            ProjectMapPreview card = FxTestSupport.callOnFx(() -> codeCards(map).get(broken));
            waitFor(() -> card.editor().getPlaceholder() instanceof Label, "the failure must be shown");
            FxTestSupport.runOnFx(() -> {
                assertEquals(
                        tr("project.map.preview.failed"), ((Label) card.editor().getPlaceholder()).getText());
                assertEquals("", statusOf(card), "\"Loading…\" must not stay beside the failure message");
            });
        } finally {
            map.close();
        }
    }

    @Test
    void anOpenBuffersEditsReachItsCardWithoutAnyNotification() throws Exception {
        root = projectRoot();
        Path file = Files.writeString(root.resolve("Live.java"), "class Live {}\n");
        AtomicReference<String> buffer = new AtomicReference<>("class Live {}\n// one\n// two\n");
        MapFixture map = openMap(1_200, 800, path -> new ProjectMapPreview.Content(buffer.get(), false));
        try {
            waitForRow(map, file);
            FxTestSupport.runOnFx(() -> {
                openPreview(map, file);
                assertEquals(buffer.get(), codeCards(map).get(file).editor().getText());
            });
            ProjectMapPreview card = FxTestSupport.callOnFx(() -> codeCards(map).get(file));
            FxTestSupport.runOnFx(() -> card.editor().moveTo(1, 3));
            buffer.set("class Live {}\n// one\n// edited\n// two\n");
            waitFor(() -> card.editor().getText().equals(buffer.get()), "the card must follow the buffer");
            FxTestSupport.runOnFx(() -> assertEquals(
                    1,
                    card.editor().getCurrentParagraph(),
                    "only the changed range is replaced: the reader stays put"));
        } finally {
            map.close();
        }
    }

    @Test
    void aClosedFilesCardFollowsTheDiskAndSaysWhenTheFileIsGone() throws Exception {
        root = projectRoot();
        Path file = Files.writeString(root.resolve("Disk.txt"), "version one\n");
        MapFixture map = openMap(1_200, 800, path -> ProjectMapPreview.Content.closed(null));
        try {
            waitForRow(map, file);
            FxTestSupport.runOnFx(() -> openPreview(map, file));
            ProjectMapPreview card = FxTestSupport.callOnFx(() -> codeCards(map).get(file));
            waitFor(() -> card.editor().getText().equals("version one\n"), "the file must load");

            Files.writeString(file, "version two, changed on disk\n");
            FxTestSupport.runOnFx(map.view::filesChangedOnDisk); // the Project panel's watcher: a rewrite
            waitFor(() -> card.editor().getText().equals("version two, changed on disk\n"), "the card must reload");

            Files.delete(file);
            FxTestSupport.runOnFx(map.view::refresh); // the watcher again: the listing changed
            waitFor(() -> tr("project.map.preview.missing").equals(statusOf(card)), "a deleted file must be marked");
            FxTestSupport.runOnFx(() -> {
                assertTrue(FxTestSupport.<Button>field(card, "open").isDisable(), "there is nothing left to open");
                assertEquals("version two, changed on disk\n", card.editor().getText(), "the last text stays readable");
                assertTrue(card.getStyleClass().contains("project-map-preview-missing"));
            });
        } finally {
            map.close();
        }
    }

    // --- Notes -------------------------------------------------------------------------------------------

    @Test
    void noteCardsCompareEditsWithTheLastSavedBodyAndFollowTheStore() throws Exception {
        root = projectRoot();
        Path file = Files.writeString(root.resolve("Noted.java"), "class Noted {}\n");
        NoteStore notes = new NoteStore(file, "original");
        List<String> statuses = new ArrayList<>();
        MapFixture map = openMap(1_200, 800, path -> null);
        try {
            waitForRow(map, file);
            FxTestSupport.runOnFx(() -> {
                map.view.setNotePreviewActions(notes);
                map.view.setOnStatus(statuses::add);
                openNotes(map, file);
                TextArea editor = noteEditor(map, file);

                editor.setText("edited B");
                saveWithShortcut(editor);
                editor.setText("original");
                saveWithShortcut(editor);
                assertEquals(List.of("edited B", "original"), notes.saves, "going back to the first text is an edit");
                assertEquals("original", notes.body());

                editor.setText("   ");
                saveWithShortcut(editor);
                assertEquals(2, notes.saves.size(), "a blank note is not saved");
                assertEquals("original", editor.getText(), "its text comes back instead of diverging silently");
                assertEquals(List.of(tr("status.projectMap.noteBlankRestored")), statuses);

                // The note is edited in the Notes panel while the card is open.
                notes.replaceBody("changed elsewhere");
                map.view.refreshStates();
            });
            TextArea editor = FxTestSupport.callOnFx(() -> noteEditor(map, file));
            waitFor(() -> "changed elsewhere".equals(editor.getText()), "an open card must show the new body");
            FxTestSupport.runOnFx(() -> {
                editor.setText("edited after the refresh");
                saveWithShortcut(editor);
                assertEquals("edited after the refresh", notes.body());

                notes.clear(); // deleted in the Notes panel
                map.view.refreshStates();
            });
            waitFor(() -> noteCards(map).isEmpty(), "a card whose notes were deleted closes");
            assertEquals("edited after the refresh", notes.saves.getLast(), "and saves nothing on its way out");
        } finally {
            map.close();
        }
    }

    // --- Keyboard ----------------------------------------------------------------------------------------

    @Test
    void escapeClosesAFocusedCardAndTabLeavesThePreviewText() throws Exception {
        root = projectRoot();
        Path file = Files.writeString(root.resolve("Keys.java"), "class Keys {}\n");
        NoteStore notes = new NoteStore(file, "a note");
        MapFixture map = openMap(1_200, 800, path -> new ProjectMapPreview.Content("class Keys {}\n", false));
        try {
            waitForRow(map, file);
            FxTestSupport.runOnFx(() -> {
                showWindow(map);
                map.view.setNotePreviewActions(notes);
                openPreview(map, file);
                ProjectMapPreview card = codeCards(map).get(file);
                CodeArea editor = card.editor();
                assertEquals(tr("project.map.preview.editorAccessible", "Keys.java"), editor.getAccessibleText());
                assertFalse(editor.isEditable());

                editor.requestFocus();
                assertSame(editor, map.scene.getFocusOwner());
                KeyEvent tab = key(KeyCode.TAB);
                Event.fireEvent(editor, tab);
                assertTrue(
                        map.scene.getFocusOwner() != editor,
                        "Tab must move focus out of the read-only text instead of being swallowed by it");

                editor.requestFocus();
                Event.fireEvent(editor, key(KeyCode.ESCAPE));
                assertTrue(codeCards(map).isEmpty(), "Escape closes the focused card");
                assertSame(map.surface, map.scene.getFocusOwner(), "and focus returns to the map");

                openNotes(map, file);
                map.view.applyCss();
                map.view.layout(); // the scroll pane's skin is what puts the note editors in the scene
                TextArea note = noteEditor(map, file);
                note.requestFocus();
                note.setText("typed, then Escape");
                Event.fireEvent(note, key(KeyCode.ESCAPE));
                assertTrue(noteCards(map).isEmpty(), "Escape closes a note card too");
                assertEquals("typed, then Escape", notes.body(), "closing it saves the edit, as its button does");
                assertSame(map.surface, map.scene.getFocusOwner());
            });
        } finally {
            map.close();
        }
    }

    // --- Lifecycle ---------------------------------------------------------------------------------------

    @Test
    void codeAndNoteCardsShareOneLimitAndScrollingCountsAsUse() throws Exception {
        root = projectRoot();
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            files.add(Files.writeString(root.resolve("F" + i + ".java"), "class F" + i + " {}\n"));
        }
        NoteStore notes = new NoteStore(files.get(0), "note");
        MapFixture map = openMap(1_400, 900, path -> new ProjectMapPreview.Content("text\n", false));
        try {
            waitForRow(map, files.get(8));
            FxTestSupport.runOnFx(() -> {
                map.view.setNotePreviewActions(notes);
                for (int i = 0; i < 6; i++) {
                    openPreview(map, files.get(i));
                }
                notes.add(files.get(1), "another note");
                openNotes(map, files.get(0));
                openNotes(map, files.get(1));
                assertEquals(6, codeCards(map).size());
                assertEquals(2, noteCards(map).size());

                // The oldest card is being read with the wheel: it must not be the one that goes.
                ProjectMapPreview oldest = codeCards(map).get(files.get(0));
                Event.fireEvent(oldest.editor(), scroll(oldest));

                openPreview(map, files.get(6));
                assertEquals(8, codeCards(map).size() + noteCards(map).size(), "eight cards in all, not eight each");
                assertTrue(codeCards(map).containsKey(files.get(0)), "the card in use survives");
                assertFalse(codeCards(map).containsKey(files.get(1)), "the least recently used one is replaced");

                notes.add(files.get(8), "another");
                openNotes(map, files.get(8));
                assertEquals(8, codeCards(map).size() + noteCards(map).size());
                assertFalse(codeCards(map).containsKey(files.get(2)), "a note card evicts across kinds as well");
            });
        } finally {
            map.close();
        }
    }

    @Test
    void collapsingAFolderClosesItsCardsHoweverItIsCollapsed() throws Exception {
        root = projectRoot();
        Path sub = Files.createDirectory(root.resolve("sub"));
        Path inside = Files.writeString(sub.resolve("Inside.java"), "class Inside {}\n");
        Path outside = Files.writeString(root.resolve("Outside.java"), "class Outside {}\n");
        NoteStore notes = new NoteStore(inside, "a note");
        MapFixture map = openMap(1_400, 900, path -> new ProjectMapPreview.Content("text\n", false), sub);
        try {
            waitForRow(map, inside);
            FxTestSupport.runOnFx(() -> {
                map.view.setNotePreviewActions(notes);
                openPreview(map, inside);
                openPreview(map, outside);
                openNotes(map, inside);
                // Not through the column's close button, which always closed them: the folder is simply no
                // longer expanded when the next listing lands.
                FxTestSupport.<Set<Path>>field(map.view, "expanded").remove(sub);
                FxTestSupport.invoke(map.view, "reload");
            });
            waitFor(() -> !codeCards(map).containsKey(inside), "the collapsed folder's preview must close");
            FxTestSupport.runOnFx(() -> {
                assertTrue(noteCards(map).isEmpty(), "and its note card");
                assertTrue(codeCards(map).containsKey(outside), "cards elsewhere stay");
            });
        } finally {
            map.close();
        }
    }

    @Test
    void cardsKeepTheirPlaceAcrossATreeAndMapSwitch() throws Exception {
        root = projectRoot();
        Path file = Files.writeString(root.resolve("Kept.java"), "class Kept {}\n");
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel value = new ProjectPanel(path -> {}, (from, to) -> {}, path -> {}, path -> false);
            value.setRoot(root);
            return value;
        });
        try {
            ProjectMapView mapView = FxTestSupport.callOnFx(() -> FxTestSupport.field(panel, "mapView"));
            Region surface = FxTestSupport.callOnFx(() -> FxTestSupport.field(mapView, "surface"));
            FxTestSupport.runOnFx(() -> {
                Scene scene = new Scene(panel, 1_200, 800);
                scene.getStylesheets().add(stylesheet());
                FxTestSupport.<ToggleButton>field(panel, "mapModeButton").fire();
                panel.applyCss();
                panel.resize(1_200, 800);
                panel.layout();
            });
            waitFor(() -> hasRow(surface, file), "the map must list the project");
            double[] placed = new double[2];
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(surface, "setSelected", new Class<?>[] {Path.class}, file);
                FxTestSupport.call(mapView, "previewSelection", new Class<?>[] {Path.class}, file);
                ProjectMapPreview card = FxTestSupport.<Map<Path, ProjectMapPreview>>field(mapView, "previews")
                        .get(file);
                card.relocate(123, 77);
                placed[0] = card.getLayoutX();
                placed[1] = card.getLayoutY();

                FxTestSupport.<ToggleButton>field(panel, "treeMode").fire();
                FxTestSupport.<ToggleButton>field(panel, "mapModeButton").fire();

                ProjectMapPreview after = FxTestSupport.<Map<Path, ProjectMapPreview>>field(mapView, "previews")
                        .get(file);
                assertSame(card, after, "the card is still open after Tree and back");
                assertEquals(placed[0], after.getLayoutX(), 0.001);
                assertEquals(placed[1], after.getLayoutY(), 0.001);
            });
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    // --- Helpers -----------------------------------------------------------------------------------------

    /** A Project Map in a scene of its own, listing {@link #root} from disk. */
    private record MapFixture(
            ProjectMapView view, Scene scene, Region surface, StackPane host, javafx.stage.Stage[] stage) {
        void close() throws Exception {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                if (stage[0] != null) {
                    stage[0].close();
                }
            });
        }
    }

    /** Puts a map's scene in a shown window, for the tests that need a focus owner. */
    private static void showWindow(MapFixture map) {
        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.setScene(map.scene);
        stage.show();
        map.stage[0] = stage;
    }

    private Path projectRoot() throws Exception {
        return Files.createDirectories(temp.resolve("project")).toRealPath();
    }

    private MapFixture openMap(
            double width, double height, Function<Path, ProjectMapPreview.Content> content, Path... expandedFolders)
            throws Exception {
        return FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, content);
            Scene scene = new Scene(view, width, height);
            scene.getStylesheets().add(stylesheet());
            view.resize(width, height);
            view.applyCss();
            view.layout();
            view.setRememberedFlow(ProjectMapView.FlowDirection.LEFT_TO_RIGHT.name(), ignored -> {});
            view.setRoot(root);
            if (expandedFolders.length > 0) {
                FxTestSupport.<Set<Path>>field(view, "expanded").addAll(List.of(expandedFolders));
                FxTestSupport.invoke(view, "reload");
            }
            Region surface = FxTestSupport.field(view, "surface");
            return new MapFixture(
                    view, scene, surface, (StackPane) view.getChildren().getLast(), new javafx.stage.Stage[1]);
        });
    }

    private static String stylesheet() {
        return ProjectMapCardsFxTest.class
                .getResource("/com/editora/styles/app.css")
                .toExternalForm();
    }

    private static void waitForRow(MapFixture map, Path path) throws Exception {
        waitFor(() -> hasRow(map.surface, path), "the map must list " + path.getFileName());
        FxTestSupport.runOnFx(() -> {
            map.view.layout();
            // Let the first fit and any column centring settle before a test reads the viewport.
        });
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
    }

    private static boolean hasRow(Region surface, Path path) {
        List<?> boxes = FxTestSupport.field(surface, "boxes");
        return boxes.stream()
                .anyMatch(box -> ((ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]))
                        .path()
                        .equals(path));
    }

    private static void waitFor(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertTrue(FxTestSupport.callOnFx(condition::getAsBoolean), message);
    }

    private static void openPreview(MapFixture map, Path file) {
        FxTestSupport.call(map.surface, "setSelected", new Class<?>[] {Path.class}, file);
        FxTestSupport.call(map.view, "previewSelection", new Class<?>[] {Path.class}, file);
    }

    private static void openNotes(MapFixture map, Path path) {
        FxTestSupport.call(map.view, "previewNotes", new Class<?>[] {Path.class}, path);
    }

    private static Map<Path, ProjectMapPreview> codeCards(MapFixture map) {
        return FxTestSupport.field(map.view, "previews");
    }

    private static Map<Path, ProjectMapNotePreview> noteCards(MapFixture map) {
        return FxTestSupport.field(map.view, "notePreviews");
    }

    private static TextArea noteEditor(MapFixture map, Path path) {
        VBox notes = FxTestSupport.field(noteCards(map).get(path), "notes");
        return (TextArea) notes.getChildren().getFirst();
    }

    private static String statusOf(ProjectMapPreview card) {
        return FxTestSupport.<Label>field(card, "status").getText();
    }

    private static void saveWithShortcut(TextArea editor) {
        editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, true, false, true));
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    private static ScrollEvent scroll(Region target) {
        return new ScrollEvent(
                ScrollEvent.SCROLL,
                10,
                10,
                10,
                10,
                false,
                false,
                false,
                false,
                false,
                false,
                0,
                -40,
                0,
                -40,
                ScrollEvent.HorizontalTextScrollUnits.NONE,
                0,
                ScrollEvent.VerticalTextScrollUnits.NONE,
                0,
                0,
                new PickResult(target, 10, 10));
    }

    private static MouseEvent mouse(Region target, double x, double y, boolean pressed) {
        return new MouseEvent(
                pressed ? MouseEvent.MOUSE_PRESSED : MouseEvent.MOUSE_DRAGGED,
                x,
                y,
                x,
                y,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                new PickResult(target, x, y));
    }

    private static void setField(Object target, String name, double value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setDouble(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertInside(Region card, Region host) {
        assertTrue(
                card.getLayoutX() >= 0 && card.getLayoutX() + card.getWidth() <= host.getWidth() + 0.5,
                () -> "the card must lie inside the panel: x " + card.getLayoutX() + ", width " + card.getWidth()
                        + ", panel " + host.getWidth());
        assertTrue(
                card.getLayoutY() >= 0 && card.getLayoutY() + card.getHeight() <= host.getHeight() + 0.5,
                () -> "the card must lie inside the panel: y " + card.getLayoutY() + ", height " + card.getHeight()
                        + ", panel " + host.getHeight());
    }

    /** True when the region holds a pixel that is not the white page. */
    private static boolean hasInk(PixelReader pixels, int fromX, int fromY, int toX, int toY) {
        for (int y = Math.max(0, fromY); y < toY; y += 2) {
            for (int x = Math.max(0, fromX); x < toX; x += 2) {
                if (pixels.getArgb(x, y) != WHITE) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The Personal Notes of a few files, as the window's notes coordinator would serve them. */
    private static final class NoteStore implements ProjectPanel.MarkerActions {
        private final Map<Path, List<PersonalNote>> notes = new HashMap<>();
        private final List<String> saves = new ArrayList<>();
        private final Path first;

        NoteStore(Path file, String body) {
            first = file;
            add(file, body);
        }

        void add(Path file, String body) {
            notes.computeIfAbsent(file, ignored -> new ArrayList<>())
                    .add(PersonalNote.create(
                            FileIdentity.of(file),
                            NoteScope.LINE,
                            new TextAnchor(0, 0, 0, 0, "", "", ""),
                            body,
                            List.of()));
        }

        String body() {
            return notes.get(first).getFirst().body();
        }

        void replaceBody(String body) {
            notes.get(first).replaceAll(note -> note.withBody(body));
        }

        void clear() {
            notes.clear();
        }

        @Override
        public boolean personalNotesEnabled() {
            return true;
        }

        @Override
        public boolean hasBookmarks(Path file) {
            return false;
        }

        @Override
        public boolean hasPersonalNotes(Path file) {
            return notes.containsKey(file);
        }

        @Override
        public void addBookmark(Path file) {}

        @Override
        public void addPersonalNote(Path file) {}

        @Override
        public List<PersonalNote> personalNotes(Path path) {
            return List.copyOf(notes.getOrDefault(path, List.of()));
        }

        @Override
        public void updatePersonalNote(Path path, PersonalNote note, String body) {
            saves.add(body);
            List<PersonalNote> list = notes.get(path);
            if (list != null) {
                list.replaceAll(candidate -> candidate.id().equals(note.id()) ? candidate.withBody(body) : candidate);
            }
        }
    }
}
