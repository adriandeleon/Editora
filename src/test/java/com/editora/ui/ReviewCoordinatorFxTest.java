package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.config.ReviewThread;
import com.editora.config.SharedConfig;
import com.editora.editor.EditorBuffer;
import com.editora.review.ReviewKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReviewCoordinator} in a window of its own with a real overlay and a real {@code reviews.json}: a comment
 * started from the editor, its marker after its line, its card, a reply, a suggestion applied, moving between
 * threads, threads following a saved file, and the tool window's rows. The repository is the test folder, as the
 * window's Git lookup would answer.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.extension.ExtendWith(OffFxInPlace.class)
class ReviewCoordinatorFxTest {

    private static final String TEXT = "class A {\n    int a = 1;\n    int b = 2;\n    int c = 3;\n}\n";

    @TempDir
    Path dir;

    private Path repo;
    private Stage stage;
    private StackPane root;
    private OverlayHost overlay;
    private SharedConfig shared;
    private ReviewCoordinator coordinator;
    private EditorBuffer active;
    private final List<EditorBuffer> created = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private String promptAnswer = "";

    private final CoordinatorHostStub host = new CoordinatorHostStub() {
        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            List.copyOf(created).forEach(action);
        }

        @Override
        public void setStatus(String message) {
            events.add("status: " + message);
        }

        @Override
        public void promptText(String title, String label, String initial, Consumer<String> onAccept) {
            events.add("prompt: " + title);
            onAccept.accept(promptAnswer);
        }

        @Override
        public OverlayHost overlayHost() {
            return overlay;
        }
    };

    private final ReviewCoordinator.Ops ops = new ReviewCoordinator.Ops() {
        @Override
        public void inRepositoryOf(Path file, Consumer<Path> withRoot) {
            withRoot.accept(repo);
        }

        @Override
        public void openAndNavigate(Path file, int line) {
            events.add("open " + file.getFileName() + ":" + line);
        }

        @Override
        public EditorBuffer bufferForPath(Path file) {
            return created.stream()
                    .filter(b -> file.equals(b.getPath()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public void installEmacsKeys(javafx.scene.control.TextInputControl control) {}
    };

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
        FxTestSupport.runOnFx(() -> {
            root = new StackPane();
            stage = new Stage();
            stage.setScene(new Scene(root, 900, 700));
            stage.show();
            overlay = new OverlayHost();
            overlay.install(root);
        });
    }

    @AfterAll
    void closeStage() throws Exception {
        FxTestSupport.runOnFx(stage::close);
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createDirectories(dir.resolve("repo"));
        Files.createDirectories(repo.resolve("src"));
        shared = new SharedConfig(Files.createDirectories(dir.resolve("config")), false);
        shared.load();
        events.clear();
        coordinator = FxTestSupport.callOnFx(() -> new ReviewCoordinator(host, shared, ops));
    }

    @AfterEach
    void tidy() throws Exception {
        FxTestSupport.runOnFx(() -> {
            if (overlay.isShowing()) {
                overlay.hide();
            }
            created.forEach(b -> {
                root.getChildren().remove(b.getArea());
                b.dispose();
            });
            created.clear();
        });
        shared.shutdown();
    }

    // --- plumbing ----------------------------------------------------------------------------------

    private void fx(Runnable r) throws Exception {
        FxTestSupport.runOnFx(r);
    }

    /** An open buffer of {@code repo/src/A.java}, shown in the stage so its lines have bounds on screen. */
    private EditorBuffer open(String text) throws Exception {
        Path file = repo.resolve("src/A.java");
        Files.writeString(file, text);
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(file);
            b.setContent(text);
            created.add(b);
            coordinator.wire(b);
            root.getChildren().add(0, b.getArea());
            active = b;
            return b;
        });
    }

    private String repoKey() {
        return ReviewKeys.repoKey(repo);
    }

    private List<ReviewThread> stored() {
        return shared.reviewThreads(repoKey());
    }

    private Node card() {
        assertTrue(overlay.isShowing(), "a card is showing");
        StackPane overlayRoot = FxTestSupport.field(overlay, "overlayRoot");
        return overlayRoot.getChildren().get(1);
    }

    private Button button(String label) {
        return (Button) card().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && label.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + label + "' button"));
    }

    /** Types {@code text} into the shown dialog or card's text area and presses {@code label}. */
    private void answer(String text, String label) throws Exception {
        fx(() -> {
            ((TextArea) card().lookup(".text-area")).setText(text);
            button(label).fire();
        });
    }

    private String dialogText() throws Exception {
        return FxTestSupport.callOnFx(() -> ((TextArea) card().lookup(".text-area")).getText());
    }

    private List<String> labelsOn(EditorBuffer b, int line) throws Exception {
        return FxTestSupport.callOnFx(() -> b.reviewLensesOn(line).stream()
                .map(EditorBuffer.ReviewLens::label)
                .toList());
    }

    private void select(EditorBuffer b, int fromLine, int toLine) throws Exception {
        fx(() -> {
            var a = b.getArea();
            a.selectRange(
                    a.getAbsolutePosition(fromLine, 0), a.getAbsolutePosition(toLine, a.getParagraphLength(toLine)));
        });
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    void aCommentStartedOnTheCaretsLineIsStoredAndMarkedAfterIt() throws Exception {
        EditorBuffer b = open(TEXT);
        fx(() -> b.getArea().moveTo(2, 3));
        fx(coordinator::addComment);
        assertEquals("", dialogText());
        answer("Why b?", tr("review.dialog.save"));
        List<ReviewThread> threads = stored();
        assertEquals(1, threads.size());
        ReviewThread t = threads.get(0);
        assertEquals("src/A.java", t.path());
        assertEquals(2, t.anchor().line());
        assertEquals("    int b = 2;", t.anchor().selectedText());
        assertEquals(List.of("💬 1 · Why b?"), labelsOn(b, 2));
        assertTrue(events.contains("status: " + tr("status.review.added")));
    }

    @Test
    void aSuggestionStartsWithTheSelectedLinesAndApplyingItEditsAndResolves() throws Exception {
        EditorBuffer b = open(TEXT);
        select(b, 1, 2);
        fx(coordinator::suggestChange);
        assertEquals("```suggestion\n    int a = 1;\n    int b = 2;\n```", dialogText());
        answer("Merge these.\n\n```suggestion\n    int ab = 12;\n```", tr("review.dialog.save"));
        ReviewThread t = stored().get(0);
        assertEquals("Merge these.", t.root().body());
        assertEquals("    int ab = 12;", t.root().suggestion());
        assertEquals(2, t.anchor().endLine(), "the marker goes after the last line");
        assertEquals(1, labelsOn(b, 2).size());

        fx(() -> coordinator.applySuggestion(b, repoKey(), t, t.root().suggestion()));
        assertEquals("class A {\n    int ab = 12;\n    int c = 3;\n}\n", FxTestSupport.callOnFx(b::getContent));
        assertEquals(ReviewThread.State.RESOLVED, stored().get(0).state());
        assertEquals(List.of(), labelsOn(b, 1), "a resolved thread has no marker");
        fx(() -> b.getArea().undo());
        assertEquals(TEXT, FxTestSupport.callOnFx(b::getContent), "one undo takes it back");
    }

    @Test
    void aSuggestionWhoseLinesChangedIsNotApplied() throws Exception {
        EditorBuffer b = open(TEXT);
        select(b, 2, 2);
        fx(coordinator::suggestChange);
        answer("```suggestion\n    int b = 20;\n```", tr("review.dialog.save"));
        ReviewThread t = stored().get(0);
        fx(() -> b.getArea().replaceText(b.getContent().replace("int b = 2;", "int b = 3;")));
        fx(() -> coordinator.applySuggestion(b, repoKey(), t, t.root().suggestion()));
        assertTrue(events.contains("status: " + tr("status.review.outdated")), events.toString());
        assertTrue(FxTestSupport.callOnFx(b::getContent).contains("int b = 3;"));
        assertEquals(ReviewThread.State.OPEN, stored().get(0).state());
    }

    @Test
    void theCardShowsTheThreadAndTakesAReply() throws Exception {
        EditorBuffer b = open(TEXT);
        fx(() -> b.getArea().moveTo(1, 0));
        fx(coordinator::addComment);
        answer("Rename a.", tr("review.dialog.save"));
        fx(() -> b.getArea().layout());
        fx(() -> coordinator.showThread(b, stored().get(0).id()));
        FxTestSupport.drainFx(); // a line not laid out yet: scrolled to, and the card opens a pulse later
        assertTrue(FxTestSupport.callOnFx(overlay::isShowing), "the card opened at the line: " + events);
        assertTrue(FxTestSupport.callOnFx(() -> card().lookupAll(".label").stream()
                .anyMatch(n -> n instanceof javafx.scene.control.Label l && "Rename a.".equals(l.getText()))));
        answer("Done in the next commit.", tr("review.card.reply"));
        assertEquals(2, stored().get(0).comments().size());
        assertEquals(List.of("💬 2 · Rename a."), labelsOn(b, 1));

        fx(() -> coordinator.showThread(b, stored().get(0).id()));
        FxTestSupport.drainFx();
        fx(() -> button(tr("review.card.resolve")).fire());
        assertEquals(ReviewThread.State.RESOLVED, stored().get(0).state());
    }

    @Test
    void dismissingKeepsTheReason() throws Exception {
        EditorBuffer b = open(TEXT);
        fx(coordinator::addComment);
        answer("Nit: spacing.", tr("review.dialog.save"));
        fx(() -> b.getArea().layout());
        fx(() -> coordinator.showThread(b, stored().get(0).id()));
        FxTestSupport.drainFx();
        promptAnswer = "house style";
        fx(() -> button(tr("review.card.dismiss")).fire());
        assertEquals(ReviewThread.State.DISMISSED, stored().get(0).state());
        assertEquals("house style", stored().get(0).stateNote());
    }

    @Test
    void nextAndPreviousMoveBetweenMarkersAndWrapAround() throws Exception {
        EditorBuffer b = open(TEXT);
        for (int line : new int[] {1, 3}) {
            fx(() -> b.getArea().moveTo(line, 0));
            fx(coordinator::addComment);
            answer("on " + line, tr("review.dialog.save"));
        }
        fx(() -> b.getArea().moveTo(0, 0));
        fx(() -> coordinator.jump(true));
        assertEquals(1, (int) FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()));
        fx(() -> coordinator.jump(true));
        assertEquals(3, (int) FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()));
        fx(() -> coordinator.jump(true));
        assertEquals(1, (int) FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()), "wraps to the first");
        fx(() -> coordinator.jump(false));
        assertEquals(3, (int) FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()), "and back");
    }

    @Test
    void aSavedFileKeepsItsThreadsOnTheirMovedLines() throws Exception {
        EditorBuffer b = open(TEXT);
        fx(() -> b.getArea().moveTo(3, 0));
        fx(coordinator::addComment);
        answer("c?", tr("review.dialog.save"));
        fx(() -> b.getArea().insertText(0, "// header\n// two\n"));
        assertEquals(List.of("💬 1 · c?"), labelsOn(b, 5), "the marker followed the edit at once");
        assertEquals(3, stored().get(0).anchor().line(), "the store waits for a save");
        fx(() -> coordinator.saved(b));
        assertEquals(5, stored().get(0).anchor().line());
    }

    @Test
    void aFileOpenedLaterShowsItsThreads() throws Exception {
        EditorBuffer first = open(TEXT);
        fx(() -> first.getArea().moveTo(2, 0));
        fx(coordinator::addComment);
        answer("Why b?", tr("review.dialog.save"));
        fx(() -> {
            root.getChildren().remove(first.getArea());
            first.dispose();
            created.remove(first);
        });
        EditorBuffer again = open("// added while it was closed\n" + TEXT);
        fx(() -> coordinator.restore(again));
        assertEquals(List.of("💬 1 · Why b?"), labelsOn(again, 3), "found again where its text is now");
        assertNull(ReviewCoordinator.placeAmong(dir.resolve("elsewhere/A.java"), List.of(repoKey())));
    }

    @Test
    void theToolWindowListsTheRepositorysThreadsAndActsOnTheSelectedOne() throws Exception {
        EditorBuffer b = open(TEXT);
        fx(() -> b.getArea().moveTo(1, 0));
        fx(coordinator::addComment);
        answer("Rename a.", tr("review.dialog.save"));
        ReviewsPanel panel = FxTestSupport.callOnFx(coordinator::panel);
        fx(panel::refresh);
        @SuppressWarnings({"unchecked", "rawtypes"})
        javafx.scene.control.TreeView<Object> tree = (javafx.scene.control.TreeView) panel.tree();
        assertEquals(
                1,
                (int) FxTestSupport.callOnFx(() -> tree.getRoot().getChildren().size()),
                "one file");
        fx(() -> tree.getSelectionModel()
                .select(tree.getRoot().getChildren().get(0).getChildren().get(0)));
        assertTrue(FxTestSupport.callOnFx(() -> panel.runMenuRow("review.toggleSelectedResolved")));
        assertEquals(ReviewThread.State.RESOLVED, stored().get(0).state());
        assertEquals(
                0,
                (int) FxTestSupport.callOnFx(() -> tree.getRoot().getChildren().size()),
                "resolved: hidden");
        fx(panel::toggleShowClosed);
        assertEquals(
                1,
                (int) FxTestSupport.callOnFx(() -> tree.getRoot().getChildren().size()),
                "shown on request");
        assertFalse(ReviewsPanel.matches(stored().get(0), "nothing like it"));
        assertTrue(ReviewsPanel.matches(stored().get(0), "rename"));
        assertNotNull(ReviewCoordinator.deleteQuestion());
    }
}
