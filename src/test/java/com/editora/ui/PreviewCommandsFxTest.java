package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Markdown, CSV and preview commands of {@link PreviewCoordinator}, run through the command registry in
 * a real window: tables (insert, table of contents, to and from CSV, export), CSV alignment, the Markdown
 * lint fixes and per-rule switch, and what each command says on a buffer it does not apply to.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreviewCommandsFxTest {

    @TempDir
    Path dir;

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private PreviewCoordinator previews;
    private File destination;
    private int counter;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        previews = FxTestSupport.field(fx.controller, "previews");
        FxTestSupport.runOnFx(() -> fx.controller.exports.chooseDestination = chooser -> destination);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @BeforeEach
    void reset() throws Exception {
        destination = null;
        FxTestSupport.runOnFx(() -> {
            OverlayHost overlay = FxTestSupport.field(fx.controller, "overlayHost");
            if (overlay.isShowing()) {
                overlay.hide();
            }
            fx.shared.getSettings().setMarkdownLint(true);
            fx.shared.getSettings().setMarkdownLintDisabledRules(List.of());
            Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, ""));
        });
    }

    /** A saved file in a folder of its own, open and active, the caret at its start. */
    private EditorBuffer document(String name, String text) throws Exception {
        Path folder = Files.createDirectory(dir.resolve("doc" + counter++));
        Path file = Files.writeString(folder.resolve(name), text);
        return FxTestSupport.callOnFx(() -> {
            try {
                EditorBuffer b = new EditorBuffer();
                b.setPath(file);
                b.setContent(text);
                b.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
                FxTestSupport.call(
                        fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
                b.getArea().moveTo(0);
                return b;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    private void caret(EditorBuffer b, int offset) throws Exception {
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(offset));
    }

    private String status() throws Exception {
        return SaveDecisionsFxTest.lastMessage(fx);
    }

    private String clipboard() throws Exception {
        return FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString());
    }

    private void clipboard(String text) throws Exception {
        FxTestSupport.runOnFx(() -> Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, text)));
    }

    private void mark(String label) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(fx.controller, "setStatus", String.class, label));
    }

    // --- commands on the wrong kind of buffer -----------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "markdown.insertTable, status.notMarkdown",
        "markdown.reflowTable, status.notMarkdown",
        "markdown.toc, status.notMarkdown",
        "markdown.tableFromCsv, status.notMarkdown",
        "markdown.tableToCsv, status.notMarkdown",
        "markdown.tableExportCsv, status.notMarkdown",
        "markdown.openLink, status.notMarkdown",
        "markdown.bold, status.notMarkdown",
        "markdownLint.fix, status.markdownLint.notMarkdown",
        "typst.bold, status.typst.notTypst",
        "csv.copyAsMarkdownTable, status.csv.notCsv",
        "csv.align, status.csv.notCsv",
        "csv.shrink, status.csv.notCsv",
        "markwhen.toggleView, status.markwhen.notMarkwhen",
        "structured.toggleView, status.structured.notStructured",
        "pom.toggleView, status.pom.notPom",
    })
    void aCommandForAnotherKindOfFileSaysSoAndChangesNothing(String id, String message) throws Exception {
        EditorBuffer b = document("Main.java", "class Main {}\n");
        mark("about to run " + id);
        run(id);
        assertEquals(tr(message), status());
        assertEquals("class Main {}\n", text(b));
        assertFalse(FxPrompts.showing(fx.controller), id + " opened a prompt it could not act on");
    }

    // --- tables -----------------------------------------------------------------------------------------

    @Test
    void insertTableFromThePaletteAsksForASizeAndRefusesAnythingElse() throws Exception {
        EditorBuffer b = document("notes.md", "");
        run("markdown.insertTable");
        assertEquals("3x3", FxPrompts.text(fx.controller));
        FxPrompts.answer(fx.controller, "big");
        assertEquals(tr("table.size.invalid"), status());
        assertEquals("", text(b));

        run("markdown.insertTable");
        FxPrompts.cancel(fx.controller);
        assertEquals("", text(b));

        run("markdown.insertTable");
        FxPrompts.answer(fx.controller, "3x2");
        List<String> lines = text(b).strip().lines().toList();
        assertEquals(4, lines.size(), "a header, its divider and two more rows: " + lines);
        for (String line : lines) {
            assertEquals(3, line.chars().filter(c -> c == '|').count(), "two columns: " + line);
        }
    }

    @Test
    void theGridPickerHighlightsTheHoveredSizeAndInsertsItOnClick() throws Exception {
        EditorBuffer b = document("notes.md", "");
        FxTestSupport.runOnFx(previews::markdownInsertTable);
        Node card = FxTestSupport.callOnFx(() -> {
            OverlayHost overlay = FxTestSupport.field(fx.controller, "overlayHost");
            StackPane root = FxTestSupport.field(overlay, "overlayRoot");
            return root.getChildren().get(1);
        });
        List<Rectangle> cells = FxTestSupport.callOnFx(() -> card.lookupAll(".table-size-cell").stream()
                .map(n -> (Rectangle) n)
                .toList());
        assertEquals(PreviewCoordinator.TABLE_PICKER_MAX_ROWS * PreviewCoordinator.TABLE_PICKER_MAX_COLS, cells.size());
        Label heading = (Label) FxTestSupport.callOnFx(() -> card.lookup(".table-size-label"));

        // Row 2, column 3 of the grid (0-based 1, 2).
        Rectangle target = cells.get(1 * PreviewCoordinator.TABLE_PICKER_MAX_COLS + 2);
        FxTestSupport.runOnFx(() -> Event.fireEvent(target, mouse(MouseEvent.MOUSE_ENTERED)));
        assertEquals(tr("table.picker.size", 2, 3), FxTestSupport.callOnFx(heading::getText));
        long lit = FxTestSupport.callOnFx(() -> cells.stream()
                .filter(c -> c.getStyleClass().contains("table-size-cell-on"))
                .count());
        assertEquals(6, lit, "the 2 × 3 block is highlighted");

        FxTestSupport.runOnFx(() -> Event.fireEvent(target, mouse(MouseEvent.MOUSE_CLICKED)));
        assertFalse(FxPrompts.showing(fx.controller));
        List<String> lines = text(b).strip().lines().toList();
        assertEquals(3, lines.size(), "two rows (one is the header) and the divider: " + lines);
        assertEquals(4, lines.getFirst().chars().filter(c -> c == '|').count(), "three columns");
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type) {
        return new MouseEvent(
                type,
                0,
                0,
                0,
                0,
                MouseButton.PRIMARY,
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
    void theTableOfContentsIsInsertedOnceThereAreHeadings() throws Exception {
        EditorBuffer plain = document("plain.md", "just text\n");
        run("markdown.toc");
        assertEquals(tr("status.markdown.tocNoHeadings"), status());
        assertEquals("just text\n", text(plain));

        EditorBuffer b = document("guide.md", "\n# Guide\n\n## Install\n\n## Use\n");
        run("markdown.toc");
        assertEquals(tr("status.markdown.tocDone"), status());
        String withToc = text(b);
        assertTrue(withToc.contains("[Install](#install)") && withToc.contains("[Use](#use)"), withToc);
    }

    @Test
    void aTableIsCopiedAsCsvAndCsvBecomesATable() throws Exception {
        EditorBuffer b = document("data.md", "| a | b |\n|---|---|\n| 1 | 2 |\n\nprose\n");
        caret(b, 3);
        run("markdown.tableToCsv");
        assertEquals(tr("status.markdown.csvCopied"), status());
        assertEquals("a,b\n1,2", clipboard().strip());

        caret(b, text(b).indexOf("prose"));
        run("markdown.tableToCsv");
        assertEquals(tr("status.markdown.notTable"), status());
        run("markdown.reflowTable");
        assertEquals(tr("status.markdown.notTable"), status());
        run("markdown.tableExportCsv");
        assertEquals(tr("status.markdown.notTable"), status());
        run("markdown.openLink");
        assertEquals(tr("status.markdown.noLink"), status());

        clipboard("");
        run("markdown.tableFromCsv");
        assertEquals(tr("status.markdown.csvEmpty"), status());

        EditorBuffer target = document("import.md", "");
        clipboard("x,y\n7,8\n");
        run("markdown.tableFromCsv");
        List<String> lines = text(target).strip().lines().toList();
        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.getFirst().contains("x") && lines.getLast().contains("8"), lines.toString());
    }

    @Test
    void aTableIsExportedToACsvFileThroughTheSaveDialog() throws Exception {
        EditorBuffer b = document("data.md", "| a | b |\n|---|---|\n| 1 | 2 |\n");
        caret(b, 3);
        destination = dir.resolve("out.csv").toFile();
        run("markdown.tableExportCsv");
        assertEquals("a,b\n1,2", Files.readString(destination.toPath()).strip());
        assertEquals(tr("status.csv.exported", "out.csv"), status());

        mark("before");
        FxTestSupport.runOnFx(() -> {
            previews.exportMarkdownTableFile(null, "csv");
            previews.exportMarkdownTableFile("  ", "xlsx");
        });
        assertEquals(tr("status.markdown.notTable"), status(), "no table text, nothing to export");

        mark("before");
        FxTestSupport.runOnFx(() -> previews.exportMarkdownTableFile("a,b\n1,2\n", "pdf"));
        assertEquals("before", status(), "an unknown format is ignored");
    }

    // --- CSV --------------------------------------------------------------------------------------------

    @Test
    void aCsvIsCopiedAsAMarkdownTable() throws Exception {
        document("table.csv", "name,qty\npear,10\n");
        run("csv.copyAsMarkdownTable");
        assertEquals(tr("status.csv.copied"), status());
        List<String> lines = clipboard().strip().lines().toList();
        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.getFirst().contains("name") && lines.get(1).contains("---"), lines.toString());

        document("empty.csv", "");
        run("csv.copyAsMarkdownTable");
        assertEquals(tr("status.csv.empty"), status());
    }

    @Test
    void aligningPadsTheColumnsAndShrinkingRemovesThePaddingAgain() throws Exception {
        String original = "name,qty\npineapple,5\nfig,100\n";
        EditorBuffer b = document("table.csv", original);
        run("csv.shrink");
        assertEquals(tr("status.csv.shrinkNoChange"), status());

        run("csv.align");
        assertEquals(tr("status.csv.aligned"), status());
        List<String> aligned = text(b).lines().toList();
        assertEquals(
                1, aligned.stream().map(l -> l.indexOf(',')).distinct().count(), "the delimiters line up: " + aligned);
        run("csv.align");
        assertEquals(tr("status.csv.alignNoChange"), status());

        run("csv.shrink");
        assertEquals(tr("status.csv.shrunk"), status());
        assertEquals(original, text(b));
    }

    @Test
    void aCsvThatCannotBeRealignedLineByLineIsLeftAlone() throws Exception {
        String multiline = "a,b\n\"two\nlines\",2\n";
        EditorBuffer b = document("multi.csv", multiline);
        run("csv.align");
        assertEquals(tr("status.csv.multiline"), status());
        assertEquals(multiline, text(b));

        document("empty.csv", "");
        run("csv.align");
        assertEquals(tr("status.csv.empty"), status());

        EditorBuffer readOnly = document("ro.csv", "name,qty\npineapple,5\n");
        FxTestSupport.runOnFx(() -> readOnly.setViewMode(true));
        run("csv.align");
        assertTrue(status().startsWith(tr("status.bufferReadOnly")), status());
        assertEquals("name,qty\npineapple,5\n", text(readOnly));
    }

    // --- markdown lint ----------------------------------------------------------------------------------

    @Test
    void lintFixRewritesTheFixableIssuesAndSaysWhenThereAreNone() throws Exception {
        EditorBuffer b = document("lint.md", "# Title\n\ntext with trailing spaces   \n");
        run("markdownLint.fix");
        assertEquals(tr("status.markdownLint.fixed", 1), status());
        assertEquals("# Title\n\ntext with trailing spaces\n", text(b));

        run("markdownLint.fix");
        assertEquals(tr("status.markdownLint.fixNone"), status());

        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setMarkdownLint(false);
            b.getArea().appendText("more   \n");
        });
        run("markdownLint.fix");
        assertEquals(tr("status.markdownLint.off"), status());
        assertTrue(text(b).endsWith("more   \n"), "lint is off: nothing is rewritten");
    }

    @Test
    void aRuleSwitchedOffInSettingsOrInTheProjectsConfigIsNotFixed() throws Exception {
        String original = "# Title\n\ntrailing   \n";
        EditorBuffer b = document("lint.md", original);
        FxTestSupport.runOnFx(() -> fx.shared.getSettings().setMarkdownLintDisabledRules(List.of(" md009 ", "", "  ")));
        assertEquals(Set.of("MD009"), FxTestSupport.callOnFx(() -> previews.effectiveMarkdownLintDisabled(b)));
        run("markdownLint.fix");
        assertEquals(tr("status.markdownLint.fixNone"), status());
        assertEquals(original, text(b));

        // The nearest .markdownlint.json above the file counts too, and is re-read when it changes.
        FxTestSupport.runOnFx(() -> fx.shared.getSettings().setMarkdownLintDisabledRules(List.of()));
        Path sub =
                Files.createDirectories(b.getPath().getParent().resolve("docs").resolve("deep"));
        Path nested = Files.writeString(sub.resolve("nested.md"), original);
        Path config = Files.writeString(b.getPath().getParent().resolve(".markdownlint.json"), "{\"MD009\": false}");
        assertEquals(Set.of("MD009"), FxTestSupport.callOnFx(() -> previews.markdownLintConfigDisabled(nested)));
        assertEquals(Set.of("MD009"), FxTestSupport.callOnFx(() -> previews.effectiveMarkdownLintDisabled(b)));
        assertEquals(Set.of(), FxTestSupport.callOnFx(() -> previews.effectiveMarkdownLintDisabled(null)));

        Files.writeString(config, "{\"MD009\": false, \"MD012\": false}");
        Files.setLastModifiedTime(
                config,
                java.nio.file.attribute.FileTime.fromMillis(
                        Files.getLastModifiedTime(config).toMillis() + 5_000));
        assertEquals(
                Set.of("MD009", "MD012"), FxTestSupport.callOnFx(() -> previews.markdownLintConfigDisabled(nested)));

        Files.delete(config);
        assertEquals(Set.of(), FxTestSupport.callOnFx(() -> previews.markdownLintConfigDisabled(nested)));
    }

    @Test
    void aRuleIsSwitchedOffAndOnAgainFromItsPicker() throws Exception {
        document("lint.md", "# Title\n");
        run("markdownLint.toggleRule");
        List<String> rows = FxPrompts.rows(fx.controller);
        assertTrue(rows.contains("MD009"), rows.toString());
        FxPrompts.choose(fx.controller, rows.indexOf("MD009"));
        assertEquals(tr("status.markdownLint.ruleDisabled", "MD009"), status());
        assertEquals(List.of("MD009"), fx.shared.getSettings().getMarkdownLintDisabledRules());
        assertTrue(FxTestSupport.callOnFx(() -> previews.markdownLintRuleDisabled("md009")));

        FxTestSupport.runOnFx(() -> previews.toggleMarkdownLintRule("md009"));
        assertEquals(tr("status.markdownLint.ruleEnabled", "md009"), status());
        assertEquals(List.of(), fx.shared.getSettings().getMarkdownLintDisabledRules());
        assertFalse(FxTestSupport.callOnFx(() -> previews.markdownLintRuleDisabled("MD009")));
    }

    // --- preview views ----------------------------------------------------------------------------------

    @Test
    void aTimelineSwitchesBetweenItsTimelineAndCalendarViews() throws Exception {
        EditorBuffer b = document("plan.mw", "2024-01-01: Kickoff\n");
        assertTrue(FxTestSupport.callOnFx(b::isMarkwhen));
        run("markwhen.toggleView");
        assertEquals(tr("status.markwhen.viewCalendar"), status());
        run("markwhen.toggleView");
        assertEquals(tr("status.markwhen.viewTimeline"), status());
    }

    @Test
    void aJsonDocumentThatIsNotOpenApiKeepsItsTree() throws Exception {
        document("data.json", "{\"a\": 1}\n");
        run("structured.toggleView");
        assertEquals(tr("status.structured.notOpenApi"), status());
    }

    // --- markdown lint: acting on issues (C4, C5, C11, C16, C18) -----------------------------------------

    /** Waits until the lint service has answered everything asked of it so far. */
    private void lintSettled() throws Exception {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        FxTestSupport.runOnFx(
                () -> previews.markdownLintService.validate(new Object(), "x", Set.of(), d -> done.countDown()));
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "the lint service never answered");
        FxTestSupport.drainFx();
    }

    /** A Markdown document whose squiggles are in place. */
    private EditorBuffer linted(String name, String text) throws Exception {
        EditorBuffer b = document(name, text);
        lintSettled();
        return b;
    }

    /** The buffer's diagnostics once the debounced re-lint after an edit has left {@code expected} of them. */
    private List<com.editora.markdown.MarkdownLint.Diagnostic> diagnosticsAfterRelint(EditorBuffer b, int expected)
            throws Exception {
        List<com.editora.markdown.MarkdownLint.Diagnostic> now = List.of();
        for (int attempt = 0; attempt < 100; attempt++) {
            lintSettled();
            now = FxTestSupport.callOnFx(b::markdownLintDiagnostics);
            if (now.size() == expected) {
                break;
            }
            Thread.sleep(50);
        }
        return now;
    }

    private void caretAt(EditorBuffer b, int line, int column) throws Exception {
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(line, column));
    }

    private int[] caretOf(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(
                () -> new int[] {b.getArea().getCurrentParagraph(), b.getArea().getCaretColumn()});
    }

    @Test
    void lintFixKeepsTheCaretOnItsLineAndCountsWhatIsLeft() throws Exception {
        EditorBuffer b = linted("lint.md", "# Title\n\n\n\nline three   \nhttps://bare.example\n");
        caretAt(b, 4, 4);
        run("markdownLint.fix");
        assertEquals("# Title\n\nline three\nhttps://bare.example\n", text(b));
        assertArrayEquals(new int[] {2, 4}, caretOf(b), "the caret follows its line, not the end of the file");
        assertEquals(tr("status.markdownLint.fixedManual", 3, 1), status());

        run("markdownLint.fix");
        assertEquals(tr("status.markdownLint.fixNoneManual", 1), status());

        FxTestSupport.runOnFx(() -> b.getArea().undo());
        assertEquals("# Title\n\n\n\nline three   \nhttps://bare.example\n", text(b), "one undo step");
    }

    @Test
    void nextAndPreviousIssueWalkTheFileAndWrap() throws Exception {
        EditorBuffer b = linted("lint.md", "# T\n\na   \n\nb   \n");
        String message = "MD009: " + tr("mdlint.msg.MD009");
        run("markdownLint.next");
        assertArrayEquals(new int[] {2, 1}, caretOf(b));
        assertEquals(tr("status.markdownLint.issue", message, 1, 2), status());
        run("markdownLint.next");
        assertArrayEquals(new int[] {4, 1}, caretOf(b));
        run("markdownLint.next");
        assertArrayEquals(new int[] {2, 1}, caretOf(b), "wraps to the first");
        run("markdownLint.previous");
        assertArrayEquals(new int[] {4, 1}, caretOf(b), "and back to the last");
        assertEquals(tr("status.markdownLint.issue", message, 2, 2), status());

        linted("clean.md", "# T\n");
        run("markdownLint.next");
        assertEquals(tr("status.markdownLint.noIssues"), status());
    }

    @Test
    void oneIssueIsFixedAtTheCaret() throws Exception {
        EditorBuffer b = linted("lint.md", "# T\n\na   \n\nb   \nhttps://bare.example\n");
        caretAt(b, 2, 0);
        run("markdownLint.fixAtCaret");
        assertEquals("# T\n\na\n\nb   \nhttps://bare.example\n", text(b), "only that one");
        assertEquals(2, diagnosticsAfterRelint(b, 2).size());

        caretAt(b, 5, 3);
        run("markdownLint.fixAtCaret");
        assertEquals(tr("status.markdownLint.notFixable", "MD034"), status());

        caretAt(b, 0, 0);
        run("markdownLint.fixAtCaret");
        assertEquals(tr("status.markdownLint.noIssueHere"), status());
    }

    @Test
    void anIssueIsSilencedForItsLine() throws Exception {
        EditorBuffer b = linted("lint.md", "# T\n\n- see https://bare.example now\n");
        caretAt(b, 2, 10);
        run("markdownLint.disableLine");
        assertEquals("# T\n\n<!-- markdownlint-disable-next-line MD034 -->\n- see https://bare.example now\n", text(b));
        assertEquals(tr("status.markdownLint.lineDisabled", "MD034"), status());
        assertArrayEquals(new int[] {3, 10}, caretOf(b), "the caret stays on the line it was on");
        assertEquals(List.of(), diagnosticsAfterRelint(b, 0));
    }

    @Test
    void theRuleOfAnIssueIsSwitchedOffAndItsDocumentationOpened() throws Exception {
        EditorBuffer b = linted("lint.md", "# T\n\nsee https://bare.example now\n");
        caretAt(b, 2, 6);
        List<String> opened = new java.util.ArrayList<>();
        FxTestSupport.runOnFx(() -> previews.lint().openUrl = opened::add); // never a real browser
        run("markdownLint.ruleHelp");
        assertEquals(List.of("https://github.com/DavidAnson/markdownlint/blob/main/doc/md034.md"), opened);

        run("markdownLint.disableRule");
        assertEquals(List.of("MD034"), fx.shared.getSettings().getMarkdownLintDisabledRules());
        assertEquals(tr("status.markdownLint.ruleDisabled", "MD034"), status());
    }

    @Test
    void theEditorMenuOffersTheActionsForTheIssueAtTheCaret() throws Exception {
        EditorBuffer b = linted("lint.md", "# T\n\nsee https://bare.example now   \n");
        caretAt(b, 2, 6);
        List<javafx.scene.control.MenuItem> menu =
                FxTestSupport.callOnFx(() -> previews.lint().editorMenu(b));
        assertEquals(1, menu.size());
        javafx.scene.control.Menu sub = (javafx.scene.control.Menu) menu.get(0);
        assertEquals(tr("markdownLint.menu.title", "MD034: " + tr("mdlint.msg.MD034")), sub.getText());
        assertEquals(
                List.of(
                        tr("markdownLint.menu.fix"),
                        tr("markdownLint.menu.disableLine"),
                        tr("markdownLint.menu.disableRule", "MD034"),
                        tr("markdownLint.menu.help")),
                sub.getItems().stream()
                        .map(javafx.scene.control.MenuItem::getText)
                        .toList());
        assertTrue(sub.getItems().get(0).isDisable(), "a bare URL has no automatic fix");

        caretAt(b, 2, 30); // in the trailing whitespace
        javafx.scene.control.Menu ws = (javafx.scene.control.Menu)
                FxTestSupport.callOnFx(() -> previews.lint().editorMenu(b)).get(0);
        FxTestSupport.runOnFx(() -> ws.getItems().get(0).fire());
        assertEquals("# T\n\nsee https://bare.example now\n", text(b));

        caretAt(b, 0, 0);
        assertEquals(List.of(), FxTestSupport.callOnFx(() -> previews.lint().editorMenu(b)));
    }

    @Test
    void theLintWindowFollowsTheActiveTab() throws Exception {
        EditorBuffer dirty = linted("dirty.md", "# T\n\na   \n\nb   \n");
        EditorBuffer clean = linted("clean.md", "# T\n");
        EditorBuffer notes = document("notes.txt", "plain text   \n");
        ToolWindowManager windows = FxTestSupport.field(fx.controller, "toolWindows");
        ToolWindow window = FxTestSupport.field(fx.controller, "markdownLintToolWindow");
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(clean.getPath(), 0));
        run("markdownLint.refresh");
        lintSettled();
        MarkdownLintPanel panel = FxTestSupport.field(fx.controller, "markdownLintPanel");
        javafx.scene.control.ListView<?> list = FxTestSupport.field(panel, "list");
        javafx.scene.control.Label summary = FxTestSupport.field(panel, "summary");
        javafx.scene.control.Label fileName = FxTestSupport.field(panel, "fileName");
        assertEquals(0, list.getItems().size());
        assertEquals("clean.md", fileName.getText());

        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(dirty.getPath(), 0));
        lintSettled();
        assertEquals(2, list.getItems().size(), "the rows are the active tab's, without an edit or a refresh");
        assertEquals("dirty.md", fileName.getText());
        assertEquals(tr("markdownLint.summary", 2), summary.getText());

        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(notes.getPath(), 0));
        lintSettled();
        assertTrue(FxTestSupport.callOnFx(() -> windows.isOpen(window)), "the window is not closed behind your back");
        assertEquals(0, list.getItems().size());
        assertEquals(tr("status.markdownLint.notMarkdown"), summary.getText());
        assertEquals("notes.txt", fileName.getText());

        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(dirty.getPath(), 0));
        lintSettled();
        assertEquals(2, list.getItems().size());
        FxTestSupport.runOnFx(() -> windows.close(window));
    }

    @Test
    void projectConfigFilesAreReadAsMarkdownlintReadsThem() throws Exception {
        EditorBuffer b = document("lint.md", "# Title\n");
        Path folder = b.getPath().getParent();
        Path file = b.getPath();

        // JSONC with an alias and an options object.
        Path json = Files.writeString(
                folder.resolve(".markdownlint.json"),
                "{\n  // house style\n  \"default\": false,\n  \"no-hard-tabs\": true,\n  \"MD009\": { \"br_spaces\": 2 },\n}");
        Set<String> off = FxTestSupport.callOnFx(() -> previews.markdownLintConfigDisabled(file));
        assertFalse(off.contains("MD010"));
        assertFalse(off.contains("MD009"));
        assertTrue(off.contains("MD034"));
        assertEquals(
                tr("markdownLint.configNote", 14, ".markdownlint.json"),
                FxTestSupport.callOnFx(() -> previews.lint().configNote(b)));
        Files.delete(json);

        // YAML, extending a base file.
        Files.writeString(folder.resolve("base.json"), "{\"MD034\": false, \"MD040\": false}");
        Path yaml = Files.writeString(
                folder.resolve(".markdownlint.yaml"), "extends: base.json\nMD040: true\nwhitespace: false\n");
        assertEquals(
                Set.of("MD034", "MD009", "MD010", "MD012"),
                FxTestSupport.callOnFx(() -> previews.markdownLintConfigDisabled(file)));
        Files.delete(yaml);

        // .jsonc wins over .json; a file that cannot be parsed is reported once and disables nothing.
        Files.writeString(folder.resolve(".markdownlint.json"), "{\"MD009\": false}");
        Path jsonc = Files.writeString(folder.resolve(".markdownlint.jsonc"), "{ nope");
        mark("before");
        assertEquals(Set.of(), FxTestSupport.callOnFx(() -> previews.markdownLintConfigDisabled(file)));
        assertEquals(tr("status.markdownLint.configError", ".markdownlint.jsonc"), status());
        mark("again");
        assertEquals(Set.of(), FxTestSupport.callOnFx(() -> previews.markdownLintConfigDisabled(file)));
        assertEquals("again", status(), "not repeated on every lint");
        assertEquals("", FxTestSupport.callOnFx(() -> previews.lint().configNote(b)));
        Files.delete(jsonc);
        assertEquals(Set.of("MD009"), FxTestSupport.callOnFx(() -> previews.markdownLintConfigDisabled(file)));
    }

    @Test
    void theConfigLookupStopsAtTheRepositoryRoot() throws Exception {
        EditorBuffer b = document("lint.md", "# Title\n");
        Path outer = b.getPath().getParent();
        Files.writeString(outer.resolve(".markdownlint.json"), "{\"MD009\": false}");
        Path repo = Files.createDirectories(outer.resolve("repo"));
        Files.createDirectory(repo.resolve(".git"));
        Path inside =
                Files.writeString(Files.createDirectories(repo.resolve("docs")).resolve("a.md"), "# A\n");
        assertEquals(
                Set.of(),
                FxTestSupport.callOnFx(() -> previews.markdownLintConfigDisabled(inside)),
                "a config above the repository does not govern it");
        Files.writeString(repo.resolve(".markdownlint.json"), "{\"MD010\": false}");
        assertEquals(Set.of("MD010"), FxTestSupport.callOnFx(() -> previews.markdownLintConfigDisabled(inside)));
    }

    @Test
    void aNarrowedBufferIsNotJudgedAsAWholeFile() throws Exception {
        EditorBuffer b = linted("lint.md", "# Title\n\nfirst paragraph\n\nsecond paragraph\n");
        assertEquals(Set.of(), FxTestSupport.callOnFx(() -> previews.effectiveMarkdownLintDisabled(b)));
        FxTestSupport.runOnFx(() -> {
            b.getArea().selectRange(2, 0, 2, 15);
            registry.run("edit.narrowToRegion");
        });
        assertTrue(FxTestSupport.callOnFx(b::isNarrowed));
        assertEquals(Set.of("MD041", "MD047"), FxTestSupport.callOnFx(() -> previews.effectiveMarkdownLintDisabled(b)));
        assertEquals(List.of(), diagnosticsAfterRelint(b, 0), "a fragment has no first line");
        FxTestSupport.runOnFx(() -> registry.run("edit.widen"));
    }
}
