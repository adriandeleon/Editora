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
    void textEditRangeStartIsCarriedAsTheReplaceStart() {
        // A server (e.g. bash) that sends a textEdit whose range starts before the caret must have that
        // start honored on accept, not the identifier-before-caret walk.
        CompletionItem te = new CompletionItem("$user");
        te.setTextEdit(Either.forLeft(new TextEdit(new Range(new Position(2, 0), new Position(2, 1)), "$user")));
        Completion.ReplaceStart rs = CompletionMapper.map(List.of(te)).get(0).replaceStart();
        assertEquals(2, rs.line());
        assertEquals(0, rs.character());

        // The InsertReplaceEdit shape uses the insert range's start.
        CompletionItem ire = new CompletionItem("$name");
        ire.setTextEdit(Either.forRight(new InsertReplaceEdit(
                "$name",
                new Range(new Position(5, 3), new Position(5, 4)),
                new Range(new Position(5, 3), new Position(5, 6)))));
        Completion.ReplaceStart rs2 = CompletionMapper.map(List.of(ire)).get(0).replaceStart();
        assertEquals(5, rs2.line());
        assertEquals(3, rs2.character());

        // An insertText-only item carries no replace-start (the editor does the trigger-overlap walk itself).
        CompletionItem plain = new CompletionItem("length");
        plain.setInsertText("length");
        assertNull(CompletionMapper.map(List.of(plain)).get(0).replaceStart());
    }
}
