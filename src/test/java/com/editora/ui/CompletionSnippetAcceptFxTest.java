package com.editora.ui;

import java.util.List;

import javafx.scene.control.IndexRange;

import com.editora.completion.Completion;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.CompletionMapper;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accepting a language server's snippet-format completion expands it as a snippet, placeholders and all.
 *
 * <p>The reported symptom was Java imports: typing {@code import java.uti} and accepting jdtls's
 * {@code java.util} proposal produced {@code import java.util.*;} with the caret parked after the
 * semicolon — an on-demand import nobody asked for, and no way to carry on to {@code ArrayList}. The
 * server had in fact sent {@code java.util.$&#123;0:*&#125;;}: the {@code *} is a placeholder meant to be
 * <em>selected</em> so the next keystroke replaces it. Editora flattened every server snippet to literal
 * text, so the placeholder arrived as an ordinary character.
 *
 * <p>Driven through the real {@link CompletionMapper} from lsp4j items shaped exactly as jdtls sends them
 * (captured live — see {@code JdtlsSnippetProbeTest}), so the mapper, the accept path and the snippet
 * session are all exercised together; a test that hand-built the {@link Completion} would pass with the
 * mapper still flattening.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompletionSnippetAcceptFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A jdtls-shaped item: snippet format, an explicit replace range, a placeholder body. */
    private static Completion jdtls(String label, int line, int fromChar, int toChar, String newText) {
        CompletionItem item = new CompletionItem(label);
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setTextEdit(Either.forLeft(
                new TextEdit(new Range(new Position(line, fromChar), new Position(line, toChar)), newText)));
        return CompletionMapper.map(List.of(item)).get(0);
    }

    private record Accepted(String text, String selected, boolean sessionActive) {}

    private static Accepted accept(String content, int caret, Completion c) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent(content);
            b.getNode();
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(caret);
            FxTestSupport.call(b, "acceptCompletion", new Class<?>[] {CodeArea.class, Completion.class}, area, c);
            IndexRange sel = area.getSelection();
            return new Accepted(area.getText(), area.getText().substring(sel.getStart(), sel.getEnd()), (Boolean)
                    FxTestSupport.call(b, "hasActiveSnippet", new Class<?>[] {}));
        });
    }

    @Test
    void acceptingAnImportPackageSelectsTheStarToTypeOver() throws Exception {
        String content = "package demo;\n\nimport java.uti\n";
        Accepted r = accept(
                content,
                content.indexOf("import java.uti") + "import java.uti".length(),
                jdtls("java.util", 2, 7, 15, "java.util.${0:*};"));

        assertEquals("package demo;\n\nimport java.util.*;\n", r.text());
        // The point of the fix: the '*' is selected, so typing ArrayList replaces it instead of appending.
        assertEquals("*", r.selected());
        // $0 is the last stop, so there is nothing left to Tab through — the session ends immediately.
        assertFalse(r.sessionActive());
    }

    @Test
    void acceptingAMethodSelectsItsFirstArgumentAndKeepsTheSessionOpen() throws Exception {
        String content = "package demo;\n\nclass A { void go() { list.ad } }\n";
        Accepted r = accept(
                content,
                content.indexOf("list.ad") + "list.ad".length(),
                jdtls(
                        "add(String e) : boolean",
                        2,
                        "class A { void go() { list.".length(),
                        "class A { void go() { list.ad".length(),
                        "add(${1:e})"));

        assertEquals("package demo;\n\nclass A { void go() { list.add(e) } }\n", r.text());
        assertEquals("e", r.selected(), "the argument placeholder should be selected, not left as text");
        assertTrue(r.sessionActive(), "a $1 stop means Tab must still step through the snippet");
    }

    /**
     * The follow-on report: completing an import twice produced {@code import java.util.*;;}.
     *
     * <p>After the first accept the line already reads {@code import java.*;} with the {@code *} selected, so
     * typing {@code ut} leaves {@code import java.ut|;} — caret <em>before</em> a semicolon. jdtls then sends
     * a range that covers that semicolon (verified live: {@code [3:7–3:15]} for a 15-char line) precisely
     * because its insert ends with one. Replacing only up to the caret — what the accept always did — kept
     * the old semicolon and appended a second.
     */
    @Test
    void aServerRangeThatCoversATrailingSemicolonReplacesIt() throws Exception {
        String content = "package demo;\n\nimport java.ut;\n";
        int caret = content.indexOf("import java.ut") + "import java.ut".length(); // before the ';'
        // Range 7..15 on line 2 = "java.ut;" — the server's own end, one past the semicolon.
        Accepted r = accept(content, caret, jdtls("java.util", 2, 7, 15, "java.util.${0:*};"));

        assertEquals("package demo;\n\nimport java.util.*;\n", r.text());
        assertEquals("*", r.selected());
    }

    @Test
    void aStaleServerEndBeforeTheCaretNeverShrinksTheReplacement() throws Exception {
        // The user typed more since the request went out: the range end is behind the caret, and those
        // characters must be absorbed rather than left dangling after the insert.
        String content = "package demo;\n\nimport java.utix\n";
        int caret = content.indexOf("import java.utix") + "import java.utix".length();
        Accepted r = accept(content, caret, jdtls("java.util", 2, 7, 14, "java.util.${0:*};"));

        assertEquals("package demo;\n\nimport java.util.*;\n", r.text());
    }

    @Test
    void aPlainCompletionIsStillInsertedLiterally() throws Exception {
        // No placeholder in the body ⇒ no session, no selection: the ordinary path must not change.
        CompletionItem item = new CompletionItem("ArrayList - java.util");
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setTextEdit(
                Either.forLeft(new TextEdit(new Range(new Position(2, 17), new Position(2, 17)), "ArrayList;")));
        Completion c = CompletionMapper.map(List.of(item)).get(0);

        String content = "package demo;\n\nimport java.util.\n";
        Accepted r = accept(content, content.indexOf("import java.util.") + "import java.util.".length(), c);

        assertEquals("package demo;\n\nimport java.util.ArrayList;\n", r.text());
        assertEquals("", r.selected());
        assertFalse(r.sessionActive());
    }
}
