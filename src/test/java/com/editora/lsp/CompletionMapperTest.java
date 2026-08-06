package com.editora.lsp;

import java.util.List;

import com.editora.completion.Completion;
import com.editora.completion.CompletionIconKind;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.CompletionItemTag;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionMapperTest {

    @Test
    void prefersInsertTextThenLabel() {
        CompletionItem withInsert = new CompletionItem("toString");
        withInsert.setInsertText("toString()");
        withInsert.setDetail("String");
        CompletionItem labelOnly = new CompletionItem("length");

        List<Completion> out = CompletionMapper.map(List.of(withInsert, labelOnly));
        assertEquals(2, out.size());
        assertEquals("toString", out.get(0).label());
        assertEquals("toString()", out.get(0).insert());
        assertEquals("String", out.get(0).detail());
        assertEquals(Completion.Kind.WORD, out.get(0).kind());
        assertEquals("length", out.get(1).insert()); // falls back to label
    }

    @Test
    void snippetPlaceholdersAreStrippedNotInsertedRaw() {
        CompletionItem method = new CompletionItem("greeting(String name) : String");
        method.setInsertTextFormat(InsertTextFormat.Snippet);
        method.setInsertText("greeting(${1:name})");
        // A plain field still marked snippet-format (JDT LS does this when snippetSupport is advertised).
        CompletionItem field = new CompletionItem("names : List<String>");
        field.setInsertTextFormat(InsertTextFormat.Snippet);
        field.setInsertText("names");

        List<Completion> out = CompletionMapper.map(List.of(method, field));
        assertEquals("greeting(name)", out.get(0).insert()); // placeholders stripped, not raw $-text
        assertEquals("names", out.get(1).insert()); // never the decorated label
    }

    /**
     * The two shapes jdtls actually sends (captured from a live server, see {@code JdtlsSnippetProbeTest}):
     * an import package proposal and a method proposal. Both must arrive as expandable snippets, or the
     * placeholder text lands in the document as literal characters to delete by hand.
     */
    @Test
    void aSnippetFormatItemKeepsItsBodyToExpand() {
        CompletionItem pkg = new CompletionItem("java.util");
        pkg.setInsertTextFormat(InsertTextFormat.Snippet);
        pkg.setTextEdit(
                Either.forLeft(new TextEdit(new Range(new Position(3, 7), new Position(3, 15)), "java.util.${0:*};")));
        CompletionItem method = new CompletionItem("add(String e) : boolean");
        method.setInsertTextFormat(InsertTextFormat.Snippet);
        method.setTextEdit(
                Either.forLeft(new TextEdit(new Range(new Position(7, 13), new Position(7, 15)), "add(${1:e})")));

        List<Completion> out = CompletionMapper.map(List.of(pkg, method));
        assertEquals("java.util.${0:*};", out.get(0).snippet().body());
        assertEquals("add(${1:e})", out.get(1).snippet().body());
        // insert stays the flattened text — de-duplication and the label fallback compare on it.
        assertEquals("java.util.*;", out.get(0).insert());
        assertEquals("add(e)", out.get(1).insert());
        // The kind is untouched: SNIPPET marks a *local* snippet, which ranking and replace-range key on.
        assertEquals(Completion.Kind.WORD, out.get(0).kind());
    }

    @Test
    void anItemWithNoPlaceholderIsInsertedLiterally() {
        // Plain items, and — the load-bearing case — a snippet-format item carrying an unescaped literal
        // dollar (phpactor's $user, shell variables). Expanding that would read "$user" as a variable and
        // insert nothing at all, so only a real tab stop earns a snippet session.
        CompletionItem plain = new CompletionItem("length");
        plain.setInsertText("length");
        CompletionItem field = new CompletionItem("names : List<String>");
        field.setInsertTextFormat(InsertTextFormat.Snippet);
        field.setInsertText("names");
        CompletionItem sigil = new CompletionItem("$user");
        sigil.setInsertTextFormat(InsertTextFormat.Snippet);
        sigil.setInsertText("$user");

        List<Completion> out = CompletionMapper.map(List.of(plain, field, sigil));
        assertNull(out.get(0).snippet());
        assertNull(out.get(1).snippet());
        assertNull(out.get(2).snippet(), "an unescaped literal dollar is not a tab stop");
        assertEquals("$user", out.get(2).insert());
    }

    @Test
    void hasTabStopDistinguishesPlaceholdersFromLiteralDollars() {
        assertTrue(CompletionMapper.hasTabStop("add(${1:e})"));
        assertTrue(CompletionMapper.hasTabStop("java.util.${0:*};"));
        assertTrue(CompletionMapper.hasTabStop("foo($0)"));
        assertFalse(CompletionMapper.hasTabStop("$user"));
        assertFalse(CompletionMapper.hasTabStop("${TM_FILENAME}"));
        assertFalse(CompletionMapper.hasTabStop("cost = 5\\$1"), "an escaped dollar is a literal");
        assertFalse(CompletionMapper.hasTabStop("plain"));
        assertFalse(CompletionMapper.hasTabStop("$"));
    }

    @Test
    void stripSnippetHandlesTabstopsAndEscapes() {
        assertEquals("foo()", CompletionMapper.stripSnippet("foo($0)"));
        assertEquals("x = value;", CompletionMapper.stripSnippet("x = ${1:value};"));
        assertEquals("a$b", CompletionMapper.stripSnippet("a\\$b"));
    }

    @Test
    void nullsAreSkipped() {
        CompletionItem noLabel = new CompletionItem();
        assertTrue(CompletionMapper.map(List.of(noLabel)).isEmpty());
        assertTrue(CompletionMapper.map(null).isEmpty());
    }

    @Test
    void mapsCompletionItemKindToDisplayKind() {
        assertEquals(CompletionIconKind.METHOD, CompletionMapper.iconKindOf(CompletionItemKind.Method));
        assertEquals(CompletionIconKind.CLASS, CompletionMapper.iconKindOf(CompletionItemKind.Class));
        assertEquals(CompletionIconKind.FIELD, CompletionMapper.iconKindOf(CompletionItemKind.Field));
        assertEquals(CompletionIconKind.KEYWORD, CompletionMapper.iconKindOf(CompletionItemKind.Keyword));
        assertEquals(CompletionIconKind.OTHER, CompletionMapper.iconKindOf(null));

        CompletionItem method = new CompletionItem("foo");
        method.setKind(CompletionItemKind.Method);
        assertEquals(
                CompletionIconKind.METHOD,
                CompletionMapper.map(List.of(method)).get(0).iconKind());
    }

    @Test
    void detailPrefersLabelDetailsDescriptionAndCollapsesNewlines() {
        CompletionItem item = new CompletionItem("of");
        item.setDetail("ignored when description present");
        CompletionItemLabelDetails ld = new CompletionItemLabelDetails();
        ld.setDescription("java.util.List");
        item.setLabelDetails(ld);
        assertEquals("java.util.List", CompletionMapper.detailText(item));

        CompletionItem multiline = new CompletionItem("x");
        multiline.setDetail("line1\n  line2");
        assertEquals("line1 line2", CompletionMapper.detailText(multiline));
    }

    @Test
    void carriesSortTextPreselectAndResolveToken() {
        CompletionItem item = new CompletionItem("foo");
        item.setSortText("0001");
        item.setPreselect(true);
        Completion c = CompletionMapper.map(List.of(item)).get(0);
        assertEquals("0001", c.sortText());
        assertTrue(c.preselect());
        assertEquals(item, c.resolveToken()); // the raw item is the opaque resolve token
    }

    @Test
    void detectsDeprecationViaTagOrFlag() {
        CompletionItem tagged = new CompletionItem("old");
        tagged.setTags(List.of(CompletionItemTag.Deprecated));
        assertTrue(CompletionMapper.isDeprecated(tagged));

        CompletionItem fresh = new CompletionItem("current");
        assertFalse(CompletionMapper.isDeprecated(fresh));

        assertTrue(CompletionMapper.map(List.of(tagged)).get(0).deprecated());
    }

    @Test
    void textEditRangeIsCarriedAsTheReplaceRange() {
        // A server (e.g. bash) that sends a textEdit whose range starts before the caret must have that
        // start honored on accept, not the identifier-before-caret walk.
        CompletionItem te = new CompletionItem("$user");
        te.setTextEdit(Either.forLeft(new TextEdit(new Range(new Position(2, 0), new Position(2, 1)), "$user")));
        Completion.ReplaceRange rs = CompletionMapper.map(List.of(te)).get(0).replaceRange();
        assertEquals(2, rs.line());
        assertEquals(0, rs.character());
        // The end is carried too: a server uses it to say it is rewriting text that follows the caret.
        assertTrue(rs.hasEnd());
        assertEquals(2, rs.endLine());
        assertEquals(1, rs.endCharacter());

        // The InsertReplaceEdit shape uses the *insert* range for both ends — VS Code's default mode. Its
        // replace range (here 5:3–5:6) deliberately reaches over the whole following token, which would
        // delete text the user never asked to lose.
        CompletionItem ire = new CompletionItem("$name");
        ire.setTextEdit(Either.forRight(new InsertReplaceEdit(
                "$name",
                new Range(new Position(5, 3), new Position(5, 4)),
                new Range(new Position(5, 3), new Position(5, 6)))));
        Completion.ReplaceRange rs2 = CompletionMapper.map(List.of(ire)).get(0).replaceRange();
        assertEquals(5, rs2.line());
        assertEquals(3, rs2.character());
        assertEquals(4, rs2.endCharacter());

        // An insertText-only item carries no replace range (the editor does the trigger-overlap walk itself).
        CompletionItem plain = new CompletionItem("length");
        plain.setInsertText("length");
        assertNull(CompletionMapper.map(List.of(plain)).get(0).replaceRange());
    }
}
