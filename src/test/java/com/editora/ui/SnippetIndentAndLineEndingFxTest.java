package com.editora.ui;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import com.editora.snippet.Snippet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Snippet text used to go into the buffer verbatim. A tab-indented body (274 of the bundled snippets) put a
 * literal tab into a space-indented file — unparseable YAML, a TabError in Python — and a CRLF anywhere in the
 * text (a {@code $CLIPBOARD} value, a user snippet string, a server's text) left every later tab stop one
 * character late per line break, because the area stores a CRLF as a single character: the wrong text was
 * selected and the next Tab threw out of the key handler.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnippetIndentAndLineEndingFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static void tab(EditorBuffer b) {
        b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
    }

    private static EditorBuffer buffer(String prefix, String body, String content) {
        EditorBuffer b = new EditorBuffer();
        Snippet s = new Snippet("snip", prefix, body, "desc", "yaml");
        b.setSnippetProvider((lang, p) -> prefix.equals(p) ? s : null);
        b.setContent(content);
        b.getArea().moveTo(content.length());
        return b;
    }

    @Test
    void aTabIndentedBodyUsesSpacesInASpaceIndentedBuffer() throws Exception {
        String body = "${1:key}:\n\t${2:subkey}: ${3:value}"; // the bundled yaml `map`
        assertEquals("key:\n    subkey: value", FxTestSupport.callOnFx(() -> {
            EditorBuffer b = buffer("map", body, "map"); // empty file: the unit defaults to spaces
            tab(b);
            return b.getArea().getText();
        }));
        assertEquals("top:\n  a: 1\n  key:\n    subkey: value", FxTestSupport.callOnFx(() -> {
            EditorBuffer b = buffer("map", body, "top:\n  a: 1\n  map"); // an existing 2-space file
            b.setTabSize(2);
            tab(b);
            return b.getArea().getText();
        }));
    }

    @Test
    void aTabIndentedBufferKeepsTheTabs() throws Exception {
        assertEquals("a:\n\tb: 1\nkey:\n\tsubkey: value", FxTestSupport.callOnFx(() -> {
            EditorBuffer b = buffer("map", "${1:key}:\n\t${2:subkey}: ${3:value}", "a:\n\tb: 1\nmap");
            tab(b);
            return b.getArea().getText();
        }));
    }

    @Test
    void crLfInTheBodyDoesNotShiftTheTabStops() throws Exception {
        String out = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = buffer("iff", "if (${1:cond}) {\r\n\t${2:body}\r\n}${3:tail}$0", "x = 1\n\tiff");
            StringBuilder sb = new StringBuilder();
            tab(b); // expand → $1
            sb.append(b.getArea().getSelectedText()).append('|');
            tab(b); // → $2
            sb.append(b.getArea().getSelectedText()).append('|');
            tab(b); // → $3 (this one threw IndexOutOfBounds)
            sb.append(b.getArea().getSelectedText()).append('|');
            tab(b); // → $0
            sb.append(b.getArea().getCaretPosition() == b.getArea().getLength());
            return sb + "|" + b.getArea().getText();
        });
        assertEquals("cond|body|tail|true|x = 1\n\tif (cond) {\n\t\tbody\n\t}tail", out);
    }

    @Test
    void aCrLfClipboardValueDoesNotShiftTheTabStops() throws Exception {
        String out = FxTestSupport.callOnFx(() -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString("x = 1;\r\ny = 2;");
            Clipboard.getSystemClipboard().setContent(cc);
            EditorBuffer b = buffer("clip", "$CLIPBOARD ${1:after} ${2:end}", "clip");
            tab(b);
            String first = b.getArea().getSelectedText();
            tab(b);
            return first + "|" + b.getArea().getSelectedText() + "|"
                    + b.getArea().getText();
        });
        assertEquals("after|end|x = 1;\ny = 2; after end", out);
    }
}
