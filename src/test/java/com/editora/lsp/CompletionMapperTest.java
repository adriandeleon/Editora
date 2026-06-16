package com.editora.lsp;

import java.util.List;

import com.editora.completion.Completion;
import com.editora.completion.CompletionIconKind;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.CompletionItemTag;
import org.eclipse.lsp4j.InsertTextFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
