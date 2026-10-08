package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import com.editora.editor.EditorBuffer.MarkdownViewMode;
import com.editora.editor.EditorBuffer.Split;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smaller buffer features end to end: TODO navigation, the injected CSV and HTTP previews, abbreviations,
 * occurrence carets, the split view, and the note marker.
 */
@Tag("fx")
class EditorBufferFeaturesFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static EditorBuffer buffer(String language, String text) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLanguageOverride(language);
        buffer.getArea().replaceText(text);
        buffer.getArea().moveTo(0);
        return buffer;
    }

    /** Marks every "TODO" in the text, the way the configured patterns would. */
    private static final TodoMatcher TODOS = text -> {
        List<TodoMark> marks = new ArrayList<>();
        Matcher m = Pattern.compile("TODO").matcher(text);
        while (m.find()) {
            marks.add(new TodoMark(m.start(), m.end(), 0, "TODO", "#ffaa00"));
        }
        return marks;
    };

    @Test
    void todoNavigationWrapsAroundAndIsOffWithTheHighlight() throws Exception {
        EditorFx.onFx(() -> {
            String text = "a\n// TODO one\nb\n// TODO two\nc";
            EditorBuffer buffer = buffer("java", text);
            CodeArea area = buffer.getArea();
            int first = text.indexOf("TODO");
            int second = text.lastIndexOf("TODO");
            assertFalse(buffer.jumpToNextTodo(), "no matcher is configured");
            buffer.setTodoMatcher(TODOS);
            assertFalse(buffer.jumpToNextTodo(), "the highlight is off");
            buffer.setTodoHighlightEnabled(true);

            assertTrue(buffer.jumpToNextTodo());
            assertEquals(first, area.getCaretPosition());
            assertTrue(buffer.jumpToNextTodo());
            assertEquals(second, area.getCaretPosition());
            assertTrue(buffer.jumpToNextTodo());
            assertEquals(first, area.getCaretPosition(), "past the last one it wraps to the first");

            assertTrue(buffer.jumpToPreviousTodo());
            assertEquals(second, area.getCaretPosition(), "and before the first it wraps to the last");
            assertTrue(buffer.jumpToPreviousTodo());
            assertEquals(first, area.getCaretPosition());

            area.replaceText("nothing to do");
            assertFalse(buffer.jumpToNextTodo(), "no match, no jump");
            assertFalse(buffer.jumpToPreviousTodo());

            area.replaceText(text);
            area.moveTo(0);
            buffer.setLargeFile(true);
            assertFalse(buffer.jumpToNextTodo(), "a large file is not scanned");
            assertEquals(0, area.getCaretPosition());
            buffer.dispose();
        });
    }

    @Test
    void theCsvGridIsThePreviewWhileItIsInjected() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("csv", "a,b\n1,2\n");
            AtomicInteger refreshes = new AtomicInteger();
            List<String> modes = new ArrayList<>();
            buffer.setOnViewModeChanged(
                    () -> modes.add(buffer.getMarkdownViewMode().name()));
            assertTrue(buffer.isCsv());
            assertFalse(buffer.hasPreview(), "the grid has not been injected: the feature is off");

            Label grid = new Label("the grid");
            grid.getStyleClass().add("test-grid");
            buffer.setCsvPreviewNode(grid);
            buffer.setCsvPreviewNode(grid); // the same node again changes nothing
            buffer.setCsvPreviewRefresh(refreshes::incrementAndGet);
            assertTrue(buffer.hasCsvPreview());
            assertTrue(buffer.hasPreview());
            assertTrue(buffer.hasExportablePreview(), "the grid goes out as a table");

            buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW);
            assertEquals(1, refreshes.get(), "showing the preview re-parses the text into the grid");
            assertSame(grid, EditorFx.styled(buffer.getNode(), "test-grid"), "the injected grid is what is shown");
            buffer.refreshPreview();
            assertEquals(2, refreshes.get());

            buffer.setCsvPreviewRefresh(null);
            buffer.refreshPreview();
            assertEquals(2, refreshes.get());

            buffer.setCsvPreviewNode(null);
            assertEquals(MarkdownViewMode.EDITOR, buffer.getMarkdownViewMode(), "the grid is gone: back to source");
            assertFalse(buffer.hasPreview());
            assertNull(EditorFx.styled(buffer.getNode(), "test-grid"));
            assertEquals(List.of("PREVIEW"), modes);
            buffer.dispose();
        });
    }

    @Test
    void theHttpResponsePanelIsRevealedBesideTheSourceAndIsNotExportable() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("http", "GET https://example.org\n");
            buffer.revealHttpPreview();
            assertEquals(MarkdownViewMode.EDITOR, buffer.getMarkdownViewMode(), "no panel yet: nothing to reveal");

            Label panel = new Label("200 OK");
            panel.getStyleClass().add("test-panel");
            buffer.setHttpPreviewNode(panel);
            buffer.setHttpPreviewNode(panel);
            assertTrue(buffer.hasHttpPreview());
            assertTrue(buffer.hasPreview());
            assertFalse(buffer.hasExportablePreview(), "a response panel is not a page to export");

            buffer.revealHttpPreview();
            assertEquals(MarkdownViewMode.SPLIT, buffer.getMarkdownViewMode());
            buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW);
            buffer.revealHttpPreview();
            assertEquals(MarkdownViewMode.PREVIEW, buffer.getMarkdownViewMode(), "the user's own mode wins");
            assertSame(panel, EditorFx.styled(buffer.getNode(), "test-panel"));
            buffer.refreshPreview(); // never re-runs a request: the panel stays as it is
            assertSame(panel, EditorFx.styled(buffer.getNode(), "test-panel"));

            buffer.setHttpPreviewNode(null);
            assertEquals(MarkdownViewMode.EDITOR, buffer.getMarkdownViewMode());
            assertFalse(buffer.hasPreview());
            buffer.dispose();
        });
    }

    @Test
    void abbreviationsExpandOnDemandAndAsYouType() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markdown", "see btw");
            CodeArea area = buffer.getArea();
            area.moveTo(area.getLength());
            assertFalse(buffer.expandAbbrevAtCaret(), "no abbreviations are defined");

            buffer.setAbbrevs(Map.of("btw", "by the way"), false);
            assertTrue(buffer.expandAbbrevAtCaret());
            assertEquals("see by the way", area.getText());
            assertFalse(buffer.expandAbbrevAtCaret(), "\"way\" is not an abbreviation");

            // Without auto-expand, typing a terminator leaves the word alone.
            area.replaceText("btw");
            area.insertText(3, " ");
            assertEquals("btw ", area.getText());

            buffer.setAbbrevs(Map.of("btw", "by the way"), true);
            area.replaceText("btw");
            area.insertText(3, ",");
            assertEquals("by the way,", area.getText(), "the terminator stays after the expansion");
            area.replaceText("btw");
            area.insertText(3, "x");
            assertEquals("btwx", area.getText(), "a letter does not end the word");

            buffer.setViewMode(true);
            assertFalse(buffer.expandAbbrevAtCaret(), "not in a read-only view");
            buffer.setViewMode(false);
            buffer.setAbbrevs(null, true);
            area.replaceText("btw");
            area.insertText(3, " ");
            assertEquals("btw ", area.getText());
            buffer.dispose();
        });
    }

    @Test
    void occurrenceCaretsArePlacedAtEveryRangeOrOnlyThePrimaryWithoutMultiCaret() throws Exception {
        EditorFx.onFx(() -> {
            String text = "foo bar foo baz foo";
            List<int[]> ranges = List.of(new int[] {0, 3}, new int[] {8, 11}, new int[] {16, 19});
            EditorBuffer buffer = buffer("java", text);
            assertEquals(0, buffer.placeOccurrenceCarets(null, 0));
            assertEquals(0, buffer.placeOccurrenceCarets(List.of(), 0));

            buffer.setMultiCaretEnabled(false);
            assertEquals(1, buffer.placeOccurrenceCarets(ranges, 8), "without multi-caret: the primary only");
            assertEquals("foo", buffer.getArea().getSelectedText());
            assertEquals(8, buffer.getArea().getSelection().getStart(), "the occurrence the caret was on");
            assertFalse(buffer.hasMultipleCarets());
            assertTrue(buffer.anyCaretSelection());

            buffer.setMultiCaretEnabled(true);
            assertEquals(3, buffer.placeOccurrenceCarets(ranges, 8));
            assertTrue(buffer.hasMultipleCarets());
            assertEquals(8, buffer.getArea().getSelection().getStart(), "the primary stays where the caret was");
            buffer.collapseCarets();
            assertFalse(buffer.hasMultipleCarets());
            buffer.dispose();
        });
    }

    @Test
    void theSplitViewSharesTheDocumentAndFollowsEditability() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("java", "class A {\n}\n");
            assertNull(buffer.getSplitView(), "no second view until the buffer is split");
            buffer.setMultiCaretEnabled(true);
            buffer.setSplit(Split.SIDE_BY_SIDE);
            assertEquals(Split.SIDE_BY_SIDE, buffer.getSplit());
            CodeArea second = buffer.getSplitView();
            assertNotNull(second);
            assertEquals("class A {\n}\n", second.getText(), "both views show the one document");
            buffer.getArea().insertText(0, "// top\n");
            assertTrue(second.getText().startsWith("// top\n"));
            assertTrue(buffer.ownsKeyTarget(second), "keys aimed at the second view belong to this buffer");

            buffer.setViewMode(true);
            assertFalse(second.isEditable());
            assertTrue(second.getStyleClass().contains("read-only"));
            buffer.setViewMode(false);
            assertTrue(second.isEditable());
            assertFalse(second.getStyleClass().contains("read-only"));

            buffer.setMultiCaretEnabled(false);
            buffer.setMultiCaretEnabled(true); // re-installs multi-caret on both views
            assertNotNull(EditorFx.field(buffer, "multiCaret2"));

            buffer.toggleSplit(Split.SIDE_BY_SIDE);
            assertEquals(Split.NONE, buffer.getSplit(), "toggling the same orientation closes the split");
            buffer.toggleSplit(Split.STACKED);
            assertEquals(Split.STACKED, buffer.getSplit());
            // A preview supersedes the code split.
            buffer.setLanguageOverride("markdown");
            buffer.setMarkdownViewMode(MarkdownViewMode.SPLIT);
            assertEquals(Split.NONE, buffer.getSplit());
            buffer.setMarkdownViewMode(null);
            assertEquals(MarkdownViewMode.EDITOR, buffer.getMarkdownViewMode());
            buffer.dispose();
        });
    }

    @Test
    void clickingANotesMarkerOpensThatNote() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("java", "first line\nhello target world");
            PersonalNote note = PersonalNote.create(
                    null,
                    NoteScope.WORD,
                    new TextAnchor(1, 6, 1, 12, "target", "first line\nhello ", " world"),
                    "Remember this",
                    List.of());
            buffer.applyNotes(List.of(note));
            List<String> opened = new ArrayList<>();
            buffer.setNoteMarkerClick(null); // ignored: the default no-op handler stays
            buffer.setNoteMarkerClick((b, n) -> opened.add(n.body()));
            Stage stage = EditorFx.show(buffer, 700, 360);
            CodeArea area = buffer.getArea();
            area.layout();

            Bounds marker = area.getCharacterBoundsOnScreen(17, 18).orElseThrow();
            Point2D local = area.screenToLocal(marker.getMinX() + 3, marker.getMinY() + 3);
            MouseEvent onMarker = EditorFx.mouse(
                    MouseEvent.MOUSE_CLICKED,
                    local.getX(),
                    local.getY(),
                    marker.getMinX() + 3,
                    marker.getMinY() + 3,
                    false,
                    1);
            area.fireEvent(onMarker);
            assertEquals(List.of("Remember this"), opened);

            // A double click, or a click on the text away from the marker, is an ordinary click.
            area.fireEvent(EditorFx.mouse(
                    MouseEvent.MOUSE_CLICKED,
                    local.getX(),
                    local.getY(),
                    marker.getMinX() + 3,
                    marker.getMinY() + 3,
                    false,
                    2));
            area.fireEvent(EditorFx.mouse(
                    MouseEvent.MOUSE_CLICKED,
                    local.getX() + 60,
                    local.getY(),
                    marker.getMinX() + 60,
                    marker.getMinY() + 3,
                    false,
                    1));
            assertEquals(1, opened.size());

            buffer.setNoteIndicatorsVisible(false);
            buffer.setNoteIndicatorsVisible(false);
            area.fireEvent(onMarker);
            assertEquals(1, opened.size(), "with the indicators hidden there is no marker to click");
            stage.close();
            buffer.dispose();
        });
    }

    @Test
    void smallQueriesAnswerFromTheDocument() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("java", "one\ntwo\nthree");
            assertEquals("two", buffer.lineText(1));
            assertEquals("", buffer.lineText(-1));
            assertEquals("", buffer.lineText(3));
            assertEquals(3, buffer.lineCount());
            assertEquals(5, buffer.lastLineLength());
            assertFalse(buffer.hasChangeBars());
            assertFalse(buffer.isSpellCheckEnabled());

            buffer.setIndentOverride(Boolean.FALSE, 8);
            assertFalse(buffer.detectInsertSpaces(4), "the EditorConfig rule wins over what the text looks like");
            buffer.setIndentOverride(Boolean.TRUE, 2);
            assertTrue(buffer.detectInsertSpaces(4));

            assertTrue(buffer.toggleBookmark(1));
            buffer.removeBookmark(1);
            assertFalse(buffer.getBookmarkManager().isBookmarked(1));

            List<Integer> clicks = new ArrayList<>();
            buffer.setGutterBreakpointClick(null);
            buffer.setGutterBreakpointClick((b, line) -> clicks.add(line));
            java.util.function.BiConsumer<EditorBuffer, Integer> click =
                    EditorFx.field(buffer, "gutterBreakpointClick");
            click.accept(buffer, 2);
            assertEquals(List.of(2), clicks);

            // Semantic tokens are dropped, not kept, once the feature goes off for this buffer.
            buffer.setSemanticActive(true);
            assertTrue(buffer.isSemanticActive());
            buffer.setSemanticTokens(List.of(new SemanticToken(0, 0, 3, "variable")));
            assertEquals(
                    1,
                    EditorFx.<List<SemanticToken>>field(buffer, "semanticTokens")
                            .size());
            buffer.setSemanticTokens(null);
            assertTrue(EditorFx.<List<SemanticToken>>field(buffer, "semanticTokens")
                    .isEmpty());
            buffer.setSemanticTokens(List.of(new SemanticToken(0, 0, 3, "variable")));
            buffer.setSemanticTokens(List.of(), buffer.semanticGen() - 1);
            assertEquals(
                    1,
                    EditorFx.<List<SemanticToken>>field(buffer, "semanticTokens")
                            .size(),
                    "an answer to an older request is ignored");
            buffer.setSemanticActive(false);
            assertTrue(EditorFx.<List<SemanticToken>>field(buffer, "semanticTokens")
                    .isEmpty());
            buffer.dispose();
        });
    }
}
