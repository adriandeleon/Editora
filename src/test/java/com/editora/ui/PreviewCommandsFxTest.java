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
        assertEquals(tr("status.markdownLint.fixed"), status());
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
}
