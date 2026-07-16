package com.editora.ui;

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
 * Regression for snippet prefixes that aren't identifiers. Tab-expansion scanned back only over
 * {@code [A-Za-z0-9_]}, so at {@code #inc} it stopped on the {@code #} and looked up "inc" — which no snippet
 * is registered under. That left 42 bundled snippets (c/cpp {@code #include}/{@code #ifndef}, the emmet
 * {@code !} html skeleton, {@code ?xml}, yaml {@code ---}, ruby {@code ->}, …) unreachable from the keyboard.
 * Fires a real TAB through the buffer's filters so the expansion path runs as it does in the editor.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnippetPrefixTabFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static void tab(EditorBuffer b) {
        b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
    }

    /** A buffer whose only snippet is registered under {@code prefix}, holding {@code typed} with the
     *  caret at the end — then Tab. Returns the resulting text. */
    private static String expand(String prefix, String body, String typed) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            Snippet s = new Snippet("snip", prefix, body, "desc", "c");
            b.setSnippetProvider((lang, p) -> prefix.equals(p) ? s : null);
            b.setContent(typed);
            b.getArea().moveTo(typed.length());
            tab(b);
            return b.getArea().getText();
        });
    }

    @Test
    void tabExpandsASnippetWhosePrefixStartsWithANonIdentifierChar() throws Exception {
        assertEquals("#include <>", expand("#inc", "#include <>", "#inc"));
    }

    @Test
    void tabExpandsASingleCharPrefix() throws Exception {
        assertEquals("<!DOCTYPE html>", expand("!", "<!DOCTYPE html>", "!"));
    }

    @Test
    void theTokenIsBoundedByWhitespaceSoAnIndentedPrefixStillExpands() throws Exception {
        assertEquals("    #include <>", expand("#inc", "#include <>", "    #inc"));
    }

    @Test
    void plainIdentifierPrefixesStillExpand() throws Exception {
        assertEquals("for (;;) {}", expand("fori", "for (;;) {}", "fori"));
    }

    @Test
    void anUnmatchedTokenFallsBackToTheIdentifierRun() throws Exception {
        // "#inc" is not registered; the identifier run "inc" is — the wider token must not shadow it.
        assertEquals("included", expand("inc", "included", "#inc").substring(1));
    }

    @Test
    void tabStillIndentsWhenNothingMatches() throws Exception {
        String out = expand("#inc", "#include <>", "#xyz");
        assertEquals("#xyz", out.strip(), "no snippet matched → the text is only indented, not expanded");
    }
}
