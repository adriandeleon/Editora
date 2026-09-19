package com.editora.ui;

import com.editora.snippet.SnippetParser;
import com.editora.snippet.SnippetSessions;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class NestedSnippetFxTest {
    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static void expand(SnippetSessions sessions, CodeArea area, String body) {
        var selection = area.getSelection();
        sessions.start(area, SnippetParser.parse(body, name -> null), selection.getStart(), selection.getEnd(), "");
    }

    @Test
    void childCompletionUpdatesTheParentsMirrorsAndNextField() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = new CodeArea();
            SnippetSessions sessions = new SnippetSessions();
            expand(sessions, area, "${1:arg} = $1; ${2:tail}");
            expand(sessions, area, "get(${1:value})");
            area.replaceSelection("x");
            assertEquals("get(x) = arg; tail", area.getText());
            sessions.next();
            assertEquals("get(x) = get(x); tail", area.getText());
            assertEquals(6, area.getCaretPosition());
            sessions.next();
            assertEquals("tail", area.getSelectedText());
            sessions.cancel();
            area.dispose();
        });
    }

    @Test
    void finalPlaceholderOnlyChildPreservesSelectionWhenTheParentMirrors() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = new CodeArea();
            SnippetSessions sessions = new SnippetSessions();
            expand(sessions, area, "${1:arg} = $1; ${2:tail}");
            expand(sessions, area, "get(${0:value})");
            assertEquals("get(value) = get(value); tail", area.getText());
            assertEquals("value", area.getSelectedText());
            sessions.next();
            assertEquals("tail", area.getSelectedText());
            sessions.cancel();
            area.dispose();
        });
    }

    @Test
    void threeLevelsResumeInOrder() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = new CodeArea();
            SnippetSessions sessions = new SnippetSessions();
            expand(sessions, area, "a(${1:x}, ${2:outer})");
            expand(sessions, area, "b(${1:y}, ${2:middle})");
            expand(sessions, area, "c(${1:z})");
            area.replaceSelection("value");
            sessions.next();
            sessions.next();
            assertEquals("middle", area.getSelectedText());
            sessions.next();
            sessions.next();
            assertEquals("outer", area.getSelectedText());
            sessions.next();
            assertFalse(sessions.isActive());
            assertEquals("a(b(c(value), middle), outer)", area.getText());
            area.dispose();
        });
    }

    @Test
    void undoInsideChildCancelsEveryLevelWithoutMirroring() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = new CodeArea();
            SnippetSessions sessions = new SnippetSessions();
            expand(sessions, area, "${1:arg} = $1;");
            area.getUndoManager().preventMerge();
            expand(sessions, area, "get(${1:value})");
            area.getUndoManager().preventMerge();
            area.replaceSelection("x");
            area.undo();
            assertFalse(sessions.isActive());
            assertEquals("get(value) = arg;", area.getText());
            area.dispose();
        });
    }

    @Test
    void externalEditsOverlappingAPlaceholderCancelTheChain() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = new CodeArea();
            SnippetSessions sessions = new SnippetSessions();
            expand(sessions, area, "a(${1:arg}, ${2:tail})");
            expand(sessions, area, "get(${1:value})");
            sessions.withExternalEdits(() -> area.replaceText(0, area.getLength(), "formatted"));
            assertFalse(sessions.isActive());
            assertEquals("formatted", area.getText());
            area.dispose();
        });
    }
}
