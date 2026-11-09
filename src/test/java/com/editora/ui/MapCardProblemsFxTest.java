package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.editora.search.ProjectFileList;
import com.editora.text.LspDiagnostic;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.fxmisc.richtext.event.MouseOverTextEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Map code browser gap 8: a card shows its file's problems — the server's diagnostics, as the editor underlines them
 * — counts them in its status, steps through them on Next and Previous Problem, and shows the problems and the
 * documentation where the mouse rests.
 */
@Tag("fx")
class MapCardProblemsFxTest {

    @TempDir
    Path temp;

    /** Where the mouse popups are put: off the test's window, away from wherever the real pointer is. */
    private static final Point2D CORNER = new Point2D(0, 0);

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- pure -------------------------------------------------------------------------------------------------

    @Test
    void problemsMoveOntoACardsLinesAndAreCutToIt() {
        LspDiagnostic above = problem(0, 2, 1, 3, LspDiagnostic.Severity.ERROR, "above");
        LspDiagnostic inside = problem(5, 4, 5, 9, LspDiagnostic.Severity.WARNING, "inside");
        LspDiagnostic across = problem(3, 6, 6, 2, LspDiagnostic.Severity.ERROR, "across");
        LspDiagnostic below = problem(9, 0, 9, 1, LspDiagnostic.Severity.ERROR, "below");

        // a declaration card showing the file's lines 4 to 6
        List<LspDiagnostic> shown = ProjectMapPreview.onCard(List.of(above, inside, across, below), 4, 3);
        assertEquals(
                List.of("inside", "across"),
                shown.stream().map(LspDiagnostic::message).toList());
        LspDiagnostic moved = shown.getFirst();
        assertEquals(
                List.of(1, 4, 1, 9), List.of(moved.startLine(), moved.startCol(), moved.endLine(), moved.endCol()));
        LspDiagnostic cut = shown.get(1);
        assertEquals(List.of(0, 0, 2, 2), List.of(cut.startLine(), cut.startCol(), cut.endLine(), cut.endCol()));
        assertEquals(LspDiagnostic.Severity.ERROR, cut.severity());

        LspDiagnostic past = ProjectMapPreview.onCard(
                        List.of(problem(5, 1, 8, 2, LspDiagnostic.Severity.ERROR, "x")), 4, 3)
                .getFirst();
        assertEquals(2, past.endLine(), "cut at the card's last line");
        assertEquals(Integer.MAX_VALUE, past.endCol(), "to its end");
        assertEquals(List.of(), ProjectMapPreview.onCard(List.of(inside), 0, 0), "no lines, no marks");
        assertEquals(1, ProjectMapPreview.onCard(List.of(inside), 0, 10).size(), "a whole-file card: as they are");
    }

    @Test
    void theStatusCountsErrorsAndWarningsButNotFadedTags() {
        assertNull(ProjectMapPreview.problemsSummary(List.of()));
        LspDiagnostic error = problem(0, 0, 0, 1, LspDiagnostic.Severity.ERROR, "e");
        LspDiagnostic warning = problem(0, 0, 0, 1, LspDiagnostic.Severity.WARNING, "w");
        LspDiagnostic info = problem(0, 0, 0, 1, LspDiagnostic.Severity.INFO, "i");
        LspDiagnostic unused = new LspDiagnostic(
                0, 0, 0, 1, LspDiagnostic.Severity.HINT, "x is not accessed", null, "Pyright", null, true);
        assertNull(ProjectMapPreview.problemsSummary(List.of(info, unused)), "notes and faded tags are not counted");
        assertEquals(tr("project.map.preview.errors", 2), ProjectMapPreview.problemsSummary(List.of(error, error)));
        assertEquals(tr("project.map.preview.warnings", 1), ProjectMapPreview.problemsSummary(List.of(warning)));
        assertEquals(
                tr("project.map.preview.errors", 1) + ", " + tr("project.map.preview.warnings", 1),
                ProjectMapPreview.problemsSummary(List.of(error, warning, info)));
        assertEquals("1 error", tr("project.map.preview.errors", 1));
        assertEquals("3 warnings", tr("project.map.preview.warnings", 3));
    }

    @Test
    void theMouseRestsOnAWholeNameAndAProblemSaysWhereItComesFrom() {
        assertArrayEquals(new int[] {4, 9}, ProjectMapPreview.wordAround("int count = 1;", 6));
        assertArrayEquals(new int[] {0, 3}, ProjectMapPreview.wordAround("int count = 1;", 0));
        assertNull(ProjectMapPreview.wordAround("int count = 1;", 10), "on '='");
        assertNull(ProjectMapPreview.wordAround("int", 3), "past the line");
        assertNull(ProjectMapPreview.wordAround("int", -1));
        assertEquals(
                "Bad  (javac)",
                ProjectMapPreview.problemText(new LspDiagnostic(
                        0, 0, 0, 1, LspDiagnostic.Severity.ERROR, "Bad", null, "javac", null, false)));
        assertEquals("Bad", ProjectMapPreview.problemText(problem(0, 0, 0, 1, LspDiagnostic.Severity.ERROR, "Bad")));
    }

    // --- the server side ----------------------------------------------------------------------------------------

    @Test
    void aWatchedFileIsLentAndItsProblemsComeThoughOnlyOpenFilesAreChecked() throws Exception {
        Path root = Files.createTempDirectory(temp, "lsp").toRealPath();
        String appText = "class App {\n  int add(int a) { return b; }\n}\n";
        Path app = Files.writeString(root.resolve("App.java"), appText);
        Path open = Files.writeString(root.resolve("Open.java"), "class Open {}\n");
        Path notes = Files.writeString(root.resolve("notes.txt"), "notes\n");
        List<List<LspDiagnostic>> told = new CopyOnWriteArrayList<>();
        LspManager manager = new LspManager((f, d) -> {}, (t, m) -> {});
        try {
            List<FakeLanguageServer> fakes = LspTestHooks.useFakeSessions(manager, new ServerCapabilities());
            manager.configure(true, Map.of("java", "jdtls"));
            manager.setOpenDocumentDiagnosticsOnly(true); // the Problems window's default
            ProjectMapLinkSearch search =
                    new ProjectMapLinkSearch(manager, noFiles(), Runnable::run, Runnable::run, () -> 0L);
            assertNull(
                    FxTestSupport.callOnFx(() -> search.watchProblems(app, appText, told::add)),
                    "no server runs yet: nothing is started");

            FxTestSupport.runOnFx(() -> manager.openDocument(open, root, "java", "class Open {}\n"));
            FakeLanguageServer fake = fakes.getFirst();
            String appUri = LspTestHooks.uri(app);
            assertNull(
                    FxTestSupport.callOnFx(() -> search.watchProblems(notes, "notes\n", told::add)),
                    "a file no server serves");
            Runnable watch = FxTestSupport.callOnFx(() -> search.watchProblems(app, appText, told::add));
            assertTrue(watch != null);
            waitFor(() -> fake.opened.stream()
                    .anyMatch(p -> appUri.equals(p.getTextDocument().getUri())));
            assertEquals(List.of(List.of()), told, "what is known now: nothing yet");

            LspTestHooks.publishDiagnostics(manager, open, publish(appUri, "b cannot be resolved"));
            waitFor(() -> told.size() == 2);
            assertEquals("b cannot be resolved", told.get(1).getFirst().message(), "lent, so not dropped");
            assertEquals(
                    1, FxTestSupport.callOnFx(() -> manager.diagnosticsOf(app)).size());

            // A second watch of the same file starts with what is known.
            List<List<LspDiagnostic>> second = new CopyOnWriteArrayList<>();
            Runnable other = FxTestSupport.callOnFx(() -> search.watchProblems(app, appText, second::add));
            assertEquals(1, second.size());
            assertEquals("b cannot be resolved", second.getFirst().getFirst().message());
            FxTestSupport.runOnFx(other);

            FxTestSupport.runOnFx(watch);
            waitFor(() -> fake.closed.stream()
                    .anyMatch(p -> appUri.equals(p.getTextDocument().getUri())));
            assertEquals(List.of(), FxTestSupport.callOnFx(() -> manager.diagnosticsOf(app)), "forgotten");
            LspTestHooks.publishDiagnostics(manager, open, publish(appUri, "again"));
            Thread.sleep(100);
            FxTestSupport.drainFx();
            assertEquals(2, told.size(), "no longer told");
            FxTestSupport.runOnFx(watch); // a second stop does nothing
        } finally {
            manager.shutdownAll();
        }
    }

    // --- the card -----------------------------------------------------------------------------------------------

    /** What a card asks of the Map, recorded. */
    private static final class Links implements ProjectMapPreview.LinkActions {
        final List<String> watched = new ArrayList<>();
        final List<String> said = new ArrayList<>();
        final List<String> asked = new ArrayList<>();
        int stopped;

        @Nullable
        Consumer<List<LspDiagnostic>> problems;

        @Nullable
        String docs = "**add** adds";

        boolean serves = true;

        /** Holds the answers back, to give them late. */
        boolean late;

        final List<Consumer<@Nullable String>> held = new ArrayList<>();

        @Override
        public void show(ProjectMapPreview card, MapCardLinks.Kind kind, int line, int column, boolean atPlace) {}

        @Override
        public void documentation(ProjectMapPreview card, int line, int column, Consumer<@Nullable String> answer) {
            asked.add(line + ":" + column);
            if (late) {
                held.add(answer);
            } else {
                answer.accept(docs);
            }
        }

        @Override
        public @Nullable Runnable watchProblems(
                ProjectMapPreview card, Path file, String text, Consumer<List<LspDiagnostic>> told) {
            if (!serves) {
                return null;
            }
            watched.add(file.getFileName() + ":" + text.length());
            problems = told;
            return () -> stopped++;
        }

        @Override
        public void say(String message) {
            said.add(message);
        }
    }

    @Test
    void aDeclarationCardMarksCountsAndStepsThroughItsOwnProblems() throws Exception {
        String text = "class App {\n  int a = b;\n  int add() {\n    return c + d;\n  }\n}\n";
        Path file = Files.writeString(temp.resolve("App.java"), text);
        Links links = new Links();
        ProjectMapPreview[] made = new ProjectMapPreview[1];
        Stage[] stage = new Stage[1];
        FxTestSupport.runOnFx(() -> {
            ProjectMapPreview card = new ProjectMapPreview(p -> {});
            made[0] = card;
            stage[0] = show(card);
            card.setLinkActions(links);
            card.showRange(
                    file,
                    new ProjectMapPreview.Range("add", 2, 2, 4),
                    new ProjectMapPreview.Content(text, false),
                    null);
        });
        ProjectMapPreview card = made[0];
        waitForFx(card::watchesProblems);
        FxTestSupport.runOnFx(() -> {
            assertEquals(List.of("App.java:" + text.length()), links.watched, "watched once, the buffer's text");
            links.problems.accept(List.of(
                    problem(1, 10, 1, 11, LspDiagnostic.Severity.ERROR, "b?"),
                    problem(3, 11, 3, 12, LspDiagnostic.Severity.ERROR, "c?"),
                    problem(3, 15, 3, 16, LspDiagnostic.Severity.ERROR, "d?"),
                    problem(4, 2, 4, 3, LspDiagnostic.Severity.WARNING, "w")));
            assertEquals(
                    List.of("c?", "d?", "w"),
                    card.cardProblems().stream().map(LspDiagnostic::message).toList(),
                    "the field's problem is not the method's");
            assertEquals(1, card.cardProblems().getFirst().startLine(), "on the card's own lines");
            assertTrue(status(card).contains(tr("project.map.preview.errors", 2)), status(card));
            assertTrue(status(card).contains(tr("project.map.preview.warnings", 1)), status(card));

            card.editor().moveTo(0, 0);
            assertTrue(card.runMarkerCommand(ProjectMapPreview.NEXT_PROBLEM_COMMAND));
            assertEquals(1, card.editor().getCurrentParagraph());
            assertEquals(11, card.editor().getCaretColumn());
            assertEquals(tr("status.lsp.problem", 4, "c?"), links.said.getLast(), "the file's line");
            assertTrue(card.runMarkerCommand(ProjectMapPreview.NEXT_PROBLEM_COMMAND));
            assertEquals(15, card.editor().getCaretColumn(), "errors first: the warning waits");
            assertTrue(card.runMarkerCommand(ProjectMapPreview.NEXT_PROBLEM_COMMAND));
            assertEquals(11, card.editor().getCaretColumn(), "round again");
            assertTrue(card.runMarkerCommand(ProjectMapPreview.PREVIOUS_PROBLEM_COMMAND));
            assertEquals(15, card.editor().getCaretColumn(), "back, wrapping");

            links.problems.accept(List.of(problem(4, 2, 4, 3, LspDiagnostic.Severity.WARNING, "w")));
            assertTrue(card.runMarkerCommand(ProjectMapPreview.NEXT_PROBLEM_COMMAND));
            assertEquals(2, card.editor().getCurrentParagraph(), "no error: the warnings");

            links.problems.accept(List.of());
            assertFalse(status(card).contains(tr("project.map.preview.warnings", 1)));
            assertTrue(card.runMarkerCommand(ProjectMapPreview.PREVIOUS_PROBLEM_COMMAND), "never the tab's");
            assertEquals(tr("project.map.preview.noProblems"), links.said.getLast());
            assertFalse(card.runMarkerCommand("lsp.notAProblemCommand"));

            // The same buffer, edited: still the one watch.
            card.refresh(new ProjectMapPreview.Content(text.replace("c + d", "c - d"), false));
            assertEquals(1, links.watched.size());
            card.dispose();
            assertEquals(1, links.stopped, "closing the card lets the file go");
            assertFalse(card.watchesProblems());
            stage[0].close();
        });
    }

    @Test
    void aDiskCardLendsItsTextAnewWhenItChangesAndWaitsForAServer() throws Exception {
        Path file = Files.writeString(temp.resolve("Use.java"), "class Use {}\n");
        Links links = new Links();
        links.serves = false;
        ProjectMapPreview[] made = new ProjectMapPreview[1];
        Stage[] stage = new Stage[1];
        FxTestSupport.runOnFx(() -> {
            ProjectMapPreview card = new ProjectMapPreview(p -> {});
            made[0] = card;
            stage[0] = show(card);
            card.setLinkActions(links);
            card.showFile(file, null, null);
        });
        ProjectMapPreview card = made[0];
        waitForFx(() -> card.editor().getLength() > 0);
        FxTestSupport.runOnFx(() -> {
            assertFalse(card.watchesProblems(), "no server: nothing watched");
            links.serves = true;
            card.refresh(null); // the once-a-second look: a server runs now
            assertTrue(card.watchesProblems());
            assertEquals(List.of("Use.java:13"), links.watched);
            links.problems.accept(List.of(problem(0, 6, 0, 9, LspDiagnostic.Severity.ERROR, "x")));
            assertEquals(1, card.cardProblems().size());
        });
        Files.writeString(file, "class Use { int x; }\n");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        FxTestSupport.runOnFx(() -> card.refresh(null));
        waitForFx(() -> links.watched.size() == 2);
        FxTestSupport.runOnFx(() -> {
            assertEquals("Use.java:21", links.watched.getLast(), "the new text lent");
            assertEquals(1, links.stopped, "the old loan ended");
            assertEquals(List.of(), card.cardProblems(), "the old text's marks went with it");
            card.setLinkActions(null);
            assertFalse(card.watchesProblems(), "no Map links: nothing watched");
            assertEquals(2, links.stopped);
            card.dispose();
            stage[0].close();
        });
    }

    @Test
    void theMouseRestingShowsTheProblemsThereAndTheDocumentationAndGoesWhenItMovesOff() throws Exception {
        String text = "class App {\n  int a = b;\n}\n";
        Path file = Files.writeString(temp.resolve("App.java"), text);
        Links links = new Links();
        ProjectMapPreview[] made = new ProjectMapPreview[1];
        Stage[] stage = new Stage[1];
        boolean[] coloured = new boolean[1];
        FxTestSupport.runOnFx(() -> {
            ProjectMapPreview card = new ProjectMapPreview(p -> {});
            made[0] = card;
            stage[0] = show(card);
            card.setLinkActions(links);
            card.setOnStylesShown(() -> coloured[0] = true);
            card.showFile(file, new ProjectMapPreview.Content(text, false), null);
        });
        ProjectMapPreview card = made[0];
        waitForFx(card::watchesProblems);
        // Coloured, and scrolled to its opening: a scroll closes the popup, as the editor's does.
        waitForFx(() -> coloured[0]);
        FxTestSupport.drainFx();
        int onB = text.indexOf("b;");
        waitForFx(() -> card.editor().getCharacterBoundsOnScreen(onB, onB + 1).isPresent()); // laid out and shown
        FxTestSupport.runOnFx(() -> {
            links.problems.accept(List.of(problem(1, 10, 1, 11, LspDiagnostic.Severity.ERROR, "b cannot be resolved")));
            card.editor().fireEvent(MouseOverTextEvent.beginAt(CORNER, onB));
            assertEquals(List.of("1:10"), links.asked, "the documentation of the name rested on, in the file's lines");
            assertTrue(card.mouseHoverText().contains("b cannot be resolved"), card.mouseHoverText());
            assertTrue(card.mouseHoverText().contains("adds"), "and the documentation under it");

            // moving within the name keeps it
            card.editor().fireEvent(MouseOverTextEvent.end());
            var b = card.editor().getCharacterBoundsOnScreen(onB, onB + 1).orElseThrow();
            Point2D inB = card.editor()
                    .localToScene(card.editor().screenToLocal(b.getMinX() + 1, b.getMinY() + b.getHeight() / 2));
            card.editor().fireEvent(mouse(MouseEvent.MOUSE_MOVED, inB.getX(), inB.getY()));
            assertTrue(card.mouseHoverText() != null);
        });
        Thread.sleep(450);
        FxTestSupport.drainFx();
        assertTrue(FxTestSupport.callOnFx(() -> card.mouseHoverText() != null), "a move within the name keeps it");
        // Moving off the name: it goes in a moment (unless the pointer is over it).
        FxTestSupport.runOnFx(() -> card.editor().fireEvent(mouse(MouseEvent.MOUSE_MOVED, 2, 2)));
        waitForFx(() -> card.mouseHoverText() == null || card.mousePopupHovered());
        FxTestSupport.runOnFx(() -> {
            card.editor().fireEvent(mouse(MouseEvent.MOUSE_MOVED, 2, 2)); // nothing rested on now: nothing to do
            card.editor().fireEvent(mouse(MouseEvent.MOUSE_EXITED)); // nor on leaving
            card.hideHoverForTest();
            card.editor().fireEvent(MouseOverTextEvent.beginAt(CORNER, onB));
            assertTrue(card.mouseHoverText() != null);
        });
        FxTestSupport.runOnFx(() -> card.editor().fireEvent(mouse(MouseEvent.MOUSE_EXITED)));
        // The popup stays while the pointer is over it: on a real display it may be.
        waitForFx(() -> card.mouseHoverText() == null || card.mousePopupHovered());
        FxTestSupport.runOnFx(() -> {
            card.hideHoverForTest();
            assertNull(card.mouseHoverText());

            // Off a name: only the problems there, and no question asked.
            links.problems.accept(List.of(
                    problem(1, 7, 1, 8, LspDiagnostic.Severity.WARNING, "spaces"),
                    problem(1, 7, 1, 8, LspDiagnostic.Severity.INFO, "a note"),
                    problem(1, 7, 1, 8, LspDiagnostic.Severity.HINT, "a hint")));
            card.editor().fireEvent(MouseOverTextEvent.beginAt(CORNER, text.indexOf("= b")));
            assertEquals(2, links.asked.size());
            assertEquals("spaces\na note\na hint\n", card.mouseHoverText(), "the problems alone");

            // Somewhere with neither: what showed goes.
            links.docs = null;
            card.editor().fireEvent(MouseOverTextEvent.beginAt(CORNER, text.indexOf("class")));
            assertEquals(3, links.asked.size());
            assertNull(card.mouseHoverText());
            assertFalse(card.hoverShown());

            // Quick Documentation asked with the keyboard is not replaced by the mouse (while it is up: a popup
            // may close by itself when the test's window is not the focused one).
            card.showHover("typed", url -> {});
            links.docs = "**mouse**";
            boolean typedUp = card.hoverShown();
            card.editor().fireEvent(MouseOverTextEvent.beginAt(CORNER, onB));
            if (typedUp) {
                assertEquals(3, links.asked.size(), "not even asked");
                assertNull(card.mouseHoverText());
            }
            card.dispose();
            stage[0].close();
        });
    }

    @Test
    void anAnswerAfterTheMouseMovedOffOrTheCardClosedIsDroppedAndAMissingFileIsNotLent() throws Exception {
        String text = "class App {\n  int a = b;\n}\n";
        Path file = Files.writeString(temp.resolve("Late.java"), text);
        Links links = new Links();
        links.late = true;
        ProjectMapPreview[] made = new ProjectMapPreview[2];
        Stage[] stage = new Stage[1];
        boolean[] coloured = new boolean[1];
        FxTestSupport.runOnFx(() -> {
            ProjectMapPreview card = new ProjectMapPreview(p -> {});
            made[0] = card;
            stage[0] = show(card);
            card.setLinkActions(links);
            card.setOnStylesShown(() -> coloured[0] = true);
            card.showFile(file, new ProjectMapPreview.Content(text, false), null);
            ProjectMapPreview gone = new ProjectMapPreview(p -> {});
            made[1] = gone;
            ((Pane) stage[0].getScene().getRoot()).getChildren().add(gone);
            gone.setLinkActions(links);
            gone.showFile(temp.resolve("Gone.java"), null, null);
        });
        ProjectMapPreview card = made[0];
        waitForFx(() -> coloured[0]);
        Thread.sleep(200);
        FxTestSupport.runOnFx(() -> {
            assertFalse(made[1].watchesProblems(), "a missing file is not lent");
            int onB = text.indexOf("b;");
            card.editor().fireEvent(MouseOverTextEvent.beginAt(CORNER, onB));
            card.editor().fireEvent(mouse(MouseEvent.MOUSE_EXITED)); // off before it answers
            links.held.removeFirst().accept("**late**");
            assertNull(card.mouseHoverText(), "the answer for an earlier rest is dropped");

            card.editor().fireEvent(MouseOverTextEvent.beginAt(CORNER, onB));
            card.showHover("typed", url -> {}); // Quick Documentation asked meanwhile
            boolean typedUp = card.hoverShown();
            links.held.removeFirst().accept("**late**");
            if (typedUp) {
                assertNull(card.mouseHoverText(), "Quick Documentation is not replaced");
            }

            card.hideHoverForTest();
            card.editor().fireEvent(MouseOverTextEvent.beginAt(CORNER, onB));
            card.dispose();
            links.held.removeFirst().accept("**late**");
            assertNull(card.mouseHoverText(), "nor shown on a closed card");
            made[1].dispose();
            stage[0].close();
        });
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static String status(ProjectMapPreview card) {
        javafx.scene.control.Label label = FxTestSupport.field(card, "status");
        return label.getText();
    }

    private static ProjectIndex noFiles() {
        return new ProjectIndex() {
            @Override
            public ProjectMapSearchResult search(
                    Path at,
                    String query,
                    Kinds kinds,
                    int max,
                    Freshness freshness,
                    java.util.function.BooleanSupplier cancelled) {
                return ProjectMapSearchResult.EMPTY;
            }

            @Override
            public ProjectMapSearchResult searchListed(Path at, String query, Kinds kinds, int max) {
                return ProjectMapSearchResult.EMPTY;
            }

            @Override
            public void whenListed(Path at, Runnable then) {
                then.run();
            }

            @Override
            public ProjectFileList.@Nullable Snapshot snapshot(Path at) {
                return null;
            }
        };
    }

    private static Stage show(ProjectMapPreview card) {
        Stage stage = new Stage();
        Pane host = new Pane(card);
        stage.setScene(new Scene(host, 1000, 700));
        stage.show();
        return stage;
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type) {
        return mouse(type, 1, 1);
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, double x, double y) {
        return new MouseEvent(
                type,
                x,
                y,
                x,
                y,
                MouseButton.NONE,
                0,
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

    private static LspDiagnostic problem(
            int startLine, int startCol, int endLine, int endCol, LspDiagnostic.Severity severity, String message) {
        return new LspDiagnostic(startLine, startCol, endLine, endCol, severity, message, null, "", null, false);
    }

    private static PublishDiagnosticsParams publish(String uri, String message) {
        Diagnostic d = new Diagnostic(new org.eclipse.lsp4j.Range(new Position(1, 26), new Position(1, 27)), message);
        d.setSeverity(DiagnosticSeverity.Error);
        return new PublishDiagnosticsParams(uri, List.of(d));
    }

    private static void waitForFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out");
    }

    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out");
            }
            FxTestSupport.drainFx();
            Thread.sleep(10);
        }
    }
}
