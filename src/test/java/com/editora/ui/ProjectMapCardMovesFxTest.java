package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moving around inside a Map card (Map code browser): Go to Line numbered as the file numbers its lines, the matching
 * bracket, a definition's start, a file card's caret stepping between the declarations of its file's outline, and the
 * wash of the other uses of the name at the caret.
 */
@Tag("fx")
class ProjectMapCardMovesFxTest {

    @TempDir
    Path temp;

    private Path root;
    private final AsyncTestScope async = new AsyncTestScope();
    private Fixture map;
    private final List<String> statuses = new CopyOnWriteArrayList<>();

    ProjectMapCardMovesFxTest() throws Exception {}

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void close() throws Exception {
        if (map != null) {
            FxTestSupport.runOnFx(() -> {
                map.view().dispose();
                map.stage().close();
            });
        }
        async.close();
    }

    @Test
    void goToLineInAFileCardMovesItsCaretAndClosesWithEnterOrEscape() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        FxTestSupport.runOnFx(() -> {
            MapCardMoves moves = card.moves();
            card.editor().moveTo(2, 0);
            assertTrue(card.runMarkerCommand(MapCardMoves.GO_TO_LINE), "Go to Line typed in the card is the card's");
            assertTrue(moves.lineBarShown());
            assertTrue(moves.lineField().isFocused(), "the keyboard is in its field");
            assertEquals("3", moves.lineField().getText(), "the caret's line to start from");
            int lines = card.editor().getParagraphs().size();
            assertEquals(tr("project.map.preview.goToLine.range", 1, lines), moves.lineRangeText());

            assertTrue(card.runMarkerCommand(MapCardMoves.GO_TO_LINE), "again: the bar stays, its field refilled");
            javafx.scene.layout.VBox header = FxTestSupport.field(card, "header");
            assertEquals(
                    1,
                    header.getChildren().stream()
                            .filter(child -> child.getStyleClass().contains("project-map-preview-line"))
                            .count());
            moves.lineField().setText("21:9");
            key(moves.lineField(), KeyCode.DIGIT1, false);
            assertTrue(moves.lineBarShown(), "typing stays in the bar");
            key(moves.lineField(), KeyCode.ENTER, false);
            assertFalse(moves.lineBarShown(), "Enter goes and closes the bar");
            assertEquals(20, card.editor().getCurrentParagraph());
            assertEquals(8, card.editor().getCaretColumn());
            assertTrue(card.editor().isFocused(), "the keyboard is back in the text");

            moves.showLineBar();
            moves.lineField().setText("a line");
            key(moves.lineField(), KeyCode.ENTER, false);
            assertTrue(moves.lineBarShown(), "what is not a line number leaves the bar open");
            assertTrue(moves.lineField().getPseudoClassStates().stream()
                    .anyMatch(state -> state.getPseudoClassName().equals("missing")));
            assertEquals(20, card.editor().getCurrentParagraph(), "and the caret where it was");
            key(moves.lineField(), KeyCode.ESCAPE, false);
            assertFalse(moves.lineBarShown(), "Escape closes the bar");
            assertTrue(previews().containsValue(card), "and not the card");

            moves.showLineBar();
            moves.lineField().setText("9999");
            key(moves.lineField(), KeyCode.ENTER, false);
            assertEquals(lines - 1, card.editor().getCurrentParagraph(), "past the end: the last line");

            card.editor().replaceText("");
            assertTrue(card.runMarkerCommand(MapCardMoves.GO_TO_LINE), "taken with no text too");
            assertFalse(moves.lineBarShown(), "but nothing to go to");
        });
    }

    @Test
    void theCardMenuOffersGoToLineAndAFileCardsDeclarationMoves() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(card, "rebuildEditorContextMenu", new Class<?>[] {int.class}, 0);
            javafx.scene.control.ContextMenu menu = FxTestSupport.field(card, "editorContextMenu");
            List<String> labels = menu.getItems().stream()
                    .map(MenuItem::getText)
                    .filter(text -> text != null)
                    .toList();
            assertTrue(labels.contains(tr("project.map.preview.goToLine.menu")), labels.toString());
            assertTrue(labels.contains(tr("project.map.preview.nextDeclaration.menu")), labels.toString());
            menu.getItems().stream()
                    .filter(item -> tr("project.map.preview.goToLine.menu").equals(item.getText()))
                    .findFirst()
                    .orElseThrow()
                    .fire();
            assertTrue(card.moves().lineBarShown());
            key(card.editor(), KeyCode.ESCAPE, false);
            assertFalse(card.moves().lineBarShown(), "Escape in the text closes the bar first");
            assertTrue(previews().containsValue(card), "and the card stays");
        });
    }

    @Test
    void goToLineInADeclarationCardIsNumberedAsTheFileNumbersIt() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview fileCard = openFileCard(big);
        ProjectMapCardStructure column = showStructure(fileCard);
        ProjectMapPreview card = openDeclaration(column, "m1(int)");
        FxTestSupport.runOnFx(() -> {
            MapCardMoves moves = card.moves();
            int first = card.range().startLine();
            int last = first + card.editor().getParagraphs().size() - 1;
            card.editor().moveTo(0);
            assertTrue(card.runMarkerCommand(MapCardMoves.GO_TO_LINE));
            assertEquals(String.valueOf(first + 1), moves.lineField().getText());
            assertEquals(tr("project.map.preview.goToLine.range", first + 1, last + 1), moves.lineRangeText());
            moves.goToLine(String.valueOf(first + 3));
            assertEquals(2, card.editor().getCurrentParagraph(), "the file's line, in the card's lines");
            moves.showLineBar();
            moves.goToLine("1");
            assertEquals(0, card.editor().getCurrentParagraph(), "before the declaration: its first line");
        });
    }

    @Test
    void matchingBracketAndBeginningOfDefunMoveInTheCardsText() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        FxTestSupport.runOnFx(() -> {
            String text = card.editor().getText();
            int open = text.indexOf("public class Big {") + "public class Big {".length();
            card.editor().moveTo(open);
            assertTrue(card.runMarkerCommand(MapCardMoves.MATCHING_BRACKET));
            assertEquals(text.lastIndexOf('}') + 1, card.editor().getCaretPosition(), "past the class's }");
            assertTrue(card.runMarkerCommand(MapCardMoves.MATCHING_BRACKET));
            assertEquals(open, card.editor().getCaretPosition(), "and back");
            card.editor().moveTo(0);
            assertTrue(card.runMarkerCommand(MapCardMoves.MATCHING_BRACKET), "no bracket at the caret: taken");
            assertEquals(0, card.editor().getCaretPosition(), "nothing moves");

            card.editor().moveTo(text.indexOf("return a"));
            assertTrue(card.runMarkerCommand(MapCardMoves.BEGINNING_OF_DEFUN));
            assertEquals(text.indexOf("public class Big"), card.editor().getCaretPosition());
            assertTrue(card.runMarkerCommand(MapCardMoves.END_OF_DEFUN));
            assertTrue(card.editor().getCaretPosition() > text.indexOf("public class Big"));
            assertFalse(card.runMarkerCommand("nav.somethingElse"), "other commands are the keymap's");
        });
    }

    @Test
    void altPageDownInAFileCardMovesItsCaretBetweenTheFilesDeclarations() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        FxTestSupport.runOnFx(() -> {
            Object claimed = card.getProperties().get(com.editora.command.KeyDispatcher.CLAIMED_KEYS);
            assertEquals(ProjectMapPreview.DECLARATION_CARD_KEYS, claimed, "a file card takes Alt+Page Up/Down too");
            card.editor().moveTo(0);
            card.editor().requestFocus();
            key(card.editor(), KeyCode.PAGE_DOWN, true); // the outline is asked for, the caret moves when it comes
        });
        int classLine = lineOf(card, "public class Big");
        await("the caret on the class", () -> card.editor().getCurrentParagraph() == classLine);
        FxTestSupport.runOnFx(() -> {
            assertEquals(
                    card.editor().getParagraph(classLine).getText().indexOf("Big"),
                    card.editor().getCaretColumn(),
                    "on its name");
            key(card.editor(), KeyCode.PAGE_DOWN, true);
            int m0 = lineOf(card, "int m0(");
            assertEquals(m0, card.editor().getCurrentParagraph(), "the outline is held now: at once");
            assertEquals(
                    card.editor().getParagraph(m0).getText().indexOf("m0"),
                    card.editor().getCaretColumn());
            key(card.editor(), KeyCode.PAGE_UP, true);
            assertEquals(classLine, card.editor().getCurrentParagraph());
            statuses.clear();
            key(card.editor(), KeyCode.PAGE_UP, true);
            assertEquals(classLine, card.editor().getCurrentParagraph());
            assertTrue(statuses.contains(tr("status.projectMap.noDeclarationBefore")), statuses.toString());

            card.editor().moveTo(lineOf(card, "int m11("), 0);
            card.editor().moveTo(card.editor().getCurrentParagraph() + 2, 0);
            key(card.editor(), KeyCode.PAGE_UP, true);
            assertEquals(lineOf(card, "int m11("), card.editor().getCurrentParagraph(), "back to the method it is in");
            card.stepDeclaration(1);
            assertTrue(statuses.contains(tr("status.projectMap.noDeclarationAfter")), statuses.toString());
        });
    }

    @Test
    void aPythonCardMovesByItsDefsAndClasses() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path py = Files.writeString(
                root.resolve("tool.py"),
                "import os\n\n\nclass Tool:\n    def run(self):\n        return 1\n\n    def stop(self):\n"
                        + "        return 2\n\n\ndef main():\n    Tool().run()\n");
        map = openMap();
        ProjectMapPreview card = openFileCard(py);
        FxTestSupport.runOnFx(() -> {
            String text = card.editor().getText();
            card.editor().moveTo(text.indexOf("return 2"));
            assertTrue(card.runMarkerCommand(MapCardMoves.BEGINNING_OF_DEFUN));
            assertEquals(7, card.editor().getCurrentParagraph(), "Python's def, not column 0");
            card.editor().moveTo(0);
            card.stepDeclaration(1);
        });
        await("the caret on class Tool", () -> card.editor().getCurrentParagraph() == 3);
        FxTestSupport.runOnFx(() -> {
            card.stepDeclaration(1);
            assertEquals(4, card.editor().getCurrentParagraph(), "then its first method");
            assertEquals(8, card.editor().getCaretColumn(), "on run");
        });
    }

    @Test
    void aFileWithNoOutlineSaysSo() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path notes = Files.writeString(root.resolve("notes.txt"), "one\ntwo\n");
        map = openMap();
        ProjectMapPreview card = openFileCard(notes);
        FxTestSupport.runOnFx(() -> {
            card.stepDeclaration(1);
            assertTrue(statuses.contains(tr("status.projectMap.noDeclarations")), statuses.toString());
        });
    }

    @Test
    void theOtherUsesOfTheNameAtTheCaretAreWashedOnceTheCaretRests() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        int keyword = FxTestSupport.callOnFx(() -> card.editor().getText().indexOf("return a") + 2);
        await(
                "the card is coloured",
                () -> !card.editor().getStyleOfChar(keyword).isEmpty());
        FxTestSupport.runOnFx(() -> {
            card.editor().requestFocus();
            card.editor().moveTo(card.editor().getText().indexOf("a += 0"));
        });
        await("the uses of a are washed", () -> card.moves().usesShown());
        FxTestSupport.runOnFx(() -> {
            card.editor().moveTo(keyword); // on return: a keyword
            assertFalse(card.moves().usesShown(), "a caret move clears the wash at once");
        });
        Thread.sleep(400);
        FxTestSupport.runOnFx(() -> assertFalse(card.moves().usesShown(), "a keyword's uses are not washed"));
    }

    // --- fixture -----------------------------------------------------------------------------------------

    private record Fixture(ProjectMapView view, Scene scene, Stage stage, Region surface) {}

    private Path writeBig() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        return Files.writeString(root.resolve("Big.java"), ProjectMapCardsReview3FxTest.source());
    }

    private static int lineOf(ProjectMapPreview card, String start) {
        List<String> lines = card.editor().getText().lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(start)) {
                return i;
            }
        }
        throw new AssertionError("no line " + start);
    }

    private Fixture openMap() throws Exception {
        if (root == null) {
            root = Files.createDirectories(temp.resolve("project")).toRealPath();
        }
        Path project = root;
        return FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            view.setOnStatus(statuses::add);
            Stage stage = new Stage();
            Scene scene = new Scene(view, 1400, 900);
            scene.getStylesheets()
                    .add(ProjectMapCardMovesFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage.setScene(scene);
            stage.show();
            view.applyCss();
            view.layout();
            view.setRememberedFlow(ProjectMapView.FlowDirection.LEFT_TO_RIGHT.name(), ignored -> {});
            view.setRememberedNavigation(true, false, (keep, focus) -> {});
            view.setRoot(project);
            return new Fixture(view, scene, stage, FxTestSupport.field(view, "surface"));
        });
    }

    private ProjectMapPreview openFileCard(Path file) throws Exception {
        await("row " + file.getFileName(), () -> rows().contains(file));
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(map.surface(), "setSelected", new Class<?>[] {Path.class}, file);
            FxTestSupport.call(map.view(), "previewSelection", new Class<?>[] {Path.class}, file);
        });
        await(
                "card " + file.getFileName(),
                () -> previews().containsKey(file)
                        && !previews().get(file).editor().getText().isEmpty());
        return FxTestSupport.callOnFx(() -> previews().get(file));
    }

    private ProjectMapCardStructure showStructure(ProjectMapPreview card) throws Exception {
        FxTestSupport.runOnFx(() -> card.setStructureShown(true));
        await("the column lists the methods", () -> {
            ProjectMapCardStructure column = cardStructures().get(card);
            return column != null
                    && column.visibleRows().stream()
                            .anyMatch(row -> row.node() != null
                                    && row.node().symbol().label().equals("m11(int)"));
        });
        return FxTestSupport.callOnFx(() -> cardStructures().get(card));
    }

    private ProjectMapPreview openDeclaration(ProjectMapCardStructure column, String label) throws Exception {
        Path[] key = new Path[1];
        FxTestSupport.runOnFx(() -> {
            var tree = column.tree();
            for (int i = 0; i < tree.getExpandedItemCount(); i++) {
                ProjectMapStructure.Node node = tree.getTreeItem(i).getValue().node();
                if (node != null && node.symbol().label().equals(label)) {
                    key[0] = node.path();
                    tree.getSelectionModel().select(i);
                    column.focus();
                    Event.fireEvent(
                            tree,
                            new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                    return;
                }
            }
            throw new AssertionError("no row " + label);
        });
        await(
                "declaration card " + label,
                () -> previews().containsKey(key[0])
                        && !previews().get(key[0]).editor().getText().isEmpty());
        return FxTestSupport.callOnFx(() -> previews().get(key[0]));
    }

    private List<Path> rows() {
        List<?> boxes = FxTestSupport.field(map.surface(), "boxes");
        return boxes.stream()
                .map(box -> ((ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0])).path())
                .toList();
    }

    private Map<Path, ProjectMapPreview> previews() {
        return FxTestSupport.field(map.view(), "previews");
    }

    private Map<ProjectMapPreview, ProjectMapCardStructure> cardStructures() {
        return FxTestSupport.field(map.view(), "cardStructures");
    }

    /** A key press on {@code target}, with Alt held for {@code alt}. */
    private static void key(Node target, KeyCode code, boolean alt) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, alt, false));
    }

    private void await(String what, BooleanSupplier condition) throws Exception {
        SaveGuardsFxTest.awaitOnFx(async, what, condition);
    }
}
