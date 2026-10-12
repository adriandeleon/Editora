package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.editor.EditorBuffer.MarkdownViewMode;
import com.editora.editor.PreviewFind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A buffer that shows only its rendered preview: editing commands are refused (the text they would change is
 * not on screen), and the Find bar searches what is rendered. {@code CommandSweepFxTest}'s
 * {@code MARKDOWN_PREVIEW} state runs every command against the same rule; this names a few and checks what
 * the user is told.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreviewOnlyFxTest {

    private static final String DOC = "# Title\n\nFirst needle here, and a second Needle.\n\n"
            + "Some `needle code` too.\n\n```\nplain needle block\n```\n\nLast line without it.   \n\n\n\nTrailing spaces and blank lines for the lint fixer.\n";

    private FxWindowFixture fx;
    private CommandRegistry registry;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private EditorBuffer open(String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            b.setLanguageOverride("markdown");
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.getArea().moveTo(0);
            return b;
        });
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
        FxTestSupport.drainFx();
    }

    @Test
    void editingCommandsAreRefusedWhileOnlyThePreviewShowsAndSaySo() throws Exception {
        EditorBuffer b = open(DOC);
        // An edit made in the editor, so there is something to undo.
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "X"));
        String text = FxTestSupport.callOnFx(b::getContent);
        FxTestSupport.runOnFx(() -> {
            Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, "PASTED"));
            b.setMarkdownViewMode(MarkdownViewMode.PREVIEW);
        });
        assertTrue(FxTestSupport.callOnFx(b::isPreviewOnly));
        for (String id : List.of(
                "edit.killLine",
                "edit.deleteChar",
                "edit.paste",
                "edit.cut",
                "edit.undo",
                "edit.duplicateLine",
                "edit.toggleComment",
                "markdown.bold",
                "markdown.toc",
                "markdownLint.fix")) {
            run(id);
            assertEquals(text, FxTestSupport.callOnFx(b::getContent), id + " must not edit text nobody can see");
        }
        run("edit.killLine");
        assertEquals(tr("status.previewCannotEdit"), SaveDecisionsFxTest.lastMessage(fx), "and the user is told why");

        // With the editor on screen again the same command edits.
        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(MarkdownViewMode.SPLIT));
        assertFalse(FxTestSupport.callOnFx(b::isPreviewOnly));
        run("edit.killLine");
        assertNotEquals(text, FxTestSupport.callOnFx(b::getContent));
        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(MarkdownViewMode.EDITOR));
    }

    @Test
    void lineAndDocumentNavigationScrollThePreviewNotTheHiddenCaret() throws Exception {
        StringBuilder md = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            md.append("Paragraph ").append(i).append(".\n\n");
        }
        EditorBuffer b = open(md.toString());
        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        javafx.scene.control.ScrollPane[] pane = new javafx.scene.control.ScrollPane[1];
        for (int i = 0; i < 300; i++) {
            pane[0] = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "previewPane"));
            boolean ready = FxTestSupport.callOnFx(() -> {
                b.getNode().applyCss();
                b.getNode().layout();
                return pane[0] != null
                        && pane[0].getContent() != null
                        && pane[0].getContent().getLayoutBounds().getHeight()
                                > pane[0].getViewportBounds().getHeight() + 500;
            });
            if (ready) {
                break;
            }
            Thread.sleep(10);
        }
        int caret = FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition());
        FxTestSupport.runOnFx(() -> pane[0].setVvalue(0));
        run("nav.lineDown");
        double line = FxTestSupport.callOnFx(() -> pane[0].getVvalue());
        assertTrue(line > 0, "line-down scrolls the preview a little");
        run("nav.lineUp");
        assertEquals(0.0, FxTestSupport.callOnFx(() -> pane[0].getVvalue()), 1e-6);
        run("nav.docEnd");
        assertEquals(1.0, FxTestSupport.callOnFx(() -> pane[0].getVvalue()), 1e-6, "end of document: end of preview");
        run("nav.docStart");
        assertEquals(0.0, FxTestSupport.callOnFx(() -> pane[0].getVvalue()), 1e-6);
        assertEquals(caret, FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition()), "the hidden caret stayed");
        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(MarkdownViewMode.EDITOR));
        run("nav.docEnd");
        assertNotEquals(
                caret,
                FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition()),
                "in the editor it moves the caret");
    }

    /** A find bar on a buffer in Preview, in a shown stage so the preview is laid out. */
    @Test
    void findSearchesTheRenderedPreviewAndReplaceIsRefusedThere() throws Exception {
        List<String> statuses = new ArrayList<>();
        EditorBuffer[] ref = new EditorBuffer[1];
        FindReplaceBar[] barRef = new FindReplaceBar[1];
        Stage[] stage = new Stage[1];
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("markdown");
            b.setContent(DOC);
            FindReplaceBar bar = new FindReplaceBar(() -> b, statuses::add);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(new VBox(bar, b.getNode()), 700, 500));
            stage[0].show();
            b.setMarkdownViewMode(MarkdownViewMode.PREVIEW);
            ref[0] = b;
            barRef[0] = bar;
        });
        EditorBuffer b = ref[0];
        FindReplaceBar bar = barRef[0];
        // (the render lands from the preview pool)
        for (int i = 0; i < 200 && FxTestSupport.callOnFx(() -> b.previewFind() == null); i++) {
            Thread.sleep(10);
            FxTestSupport.drainFx();
        }
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            bar.show(false);
            FxTestSupport.<TextField>field(bar, "findField").setText("needle");
            FxTestSupport.invoke(bar, "recompute");
            PreviewFind find = b.previewFind();
            // "needle" + "Needle" in the paragraph, the inline code and the plain code block (one each).
            assertEquals(4, find.count(), "found in what is rendered, case-insensitively");
            assertEquals(0, find.current());
            assertEquals(
                    tr("find.count", 1, 4),
                    FxTestSupport.<Label>field(bar, "countLabel").getText());
            assertEquals(4, b.getNode().lookupAll(".md-find-match").size(), "every match is marked");
            assertEquals(1, b.getNode().lookupAll(".md-find-current").size());
            assertEquals(0, b.getArea().getSelection().getLength(), "the hidden editor is not searched");

            bar.findNext();
            assertEquals(1, find.current());
            assertEquals(
                    tr("find.count", 2, 4),
                    FxTestSupport.<Label>field(bar, "countLabel").getText());
            bar.findPrevious();
            bar.findPrevious();
            assertEquals(3, find.current(), "stepping wraps around");

            FxTestSupport.<javafx.scene.control.CheckBox>field(bar, "caseSensitive")
                    .setSelected(true);
            FxTestSupport.invoke(bar, "recompute");
            assertEquals(3, find.count(), "the options are the Find bar's: \"Needle\" no longer matches");

            String before = b.getContent();
            FxTestSupport.<TextField>field(bar, "replaceField").setText("pin");
            bar.replaceCurrentMatch();
            bar.replaceAllMatches();
            assertEquals(before, b.getContent(), "replace needs the text on screen");
            assertEquals(tr("status.previewCannotEdit"), statuses.get(statuses.size() - 1));

            FxTestSupport.<TextField>field(bar, "findField").setText("no such text");
            FxTestSupport.invoke(bar, "recompute");
            assertEquals(0, find.count());
            assertEquals("", FxTestSupport.<Label>field(bar, "countLabel").getText());
            assertEquals(tr("find.notFound", "no such text"), statuses.get(statuses.size() - 1));

            FxTestSupport.<TextField>field(bar, "findField").setText("needle");
            FxTestSupport.invoke(bar, "recompute");
            bar.hideBar();
            assertTrue(b.getNode().lookupAll(".md-find-match").isEmpty(), "closing the bar removes the marks");
            assertTrue(
                    com.editora.editor.EditorFxBridge.previewText(b)
                            .contains("First needle here, and a second Needle."),
                    "and the text is whole again");
            stage[0].close();
            b.dispose();
        });
    }
}
