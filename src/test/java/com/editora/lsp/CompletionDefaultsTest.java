package com.editora.lsp;

import java.util.List;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompletionDefaultsTest {
    @Test
    void defaultsSupplySnippetRangesResolveDataAndCommitCharacters() {
        var item = new CompletionItem("println(String s)");
        item.setTextEditText("println(${1:s})");
        item.setFilterText("println");
        var defaults = new CompletionItemDefaults();
        defaults.setData("resolve-token");
        defaults.setCommitCharacters(List.of("("));
        defaults.setInsertTextFormat(InsertTextFormat.Snippet);
        defaults.setEditRange(Either.forRight(new InsertReplaceRange(
                new Range(new Position(1, 2), new Position(1, 4)), new Range(new Position(1, 2), new Position(1, 7)))));
        var list = new CompletionList(true, List.of(item), defaults);
        var items = CompletionMapper.itemsOf(Either.forRight(list));
        var mapped = CompletionMapper.map(items).getFirst();
        assertEquals("println(s)", mapped.insert());
        assertNotNull(mapped.snippet());
        assertEquals("println", mapped.filterText());
        assertEquals(4, mapped.replaceRange().endCharacter());
        assertEquals(7, mapped.protocol().replaceRange().endCharacter());
        assertEquals(List.of("("), mapped.protocol().commitCharacters());
        assertEquals("resolve-token", ((CompletionItem) mapped.resolveToken()).getData());
    }

    @Test
    void itemFieldsWinOverDefaults() {
        var item = new CompletionItem("String");
        item.setData("own");
        item.setCommitCharacters(List.of());
        item.setInsertTextFormat(InsertTextFormat.PlainText);
        var defaults = new CompletionItemDefaults();
        defaults.setData("default");
        defaults.setCommitCharacters(List.of("."));
        defaults.setInsertTextFormat(InsertTextFormat.Snippet);
        CompletionMapper.itemsOf(Either.forRight(new CompletionList(false, List.of(item), defaults)));
        assertEquals("own", item.getData());
        assertTrue(item.getCommitCharacters().isEmpty());
        assertEquals(InsertTextFormat.PlainText, item.getInsertTextFormat());
    }

    @Test
    void labelDetailsKeepOverloadParametersVisible() {
        var item = new CompletionItem("println");
        var details = new CompletionItemLabelDetails();
        details.setDetail("(String value) : void");
        details.setDescription("java.io.PrintStream");
        item.setLabelDetails(details);
        var mapped = CompletionMapper.map(List.of(item)).getFirst();
        assertEquals("println(String value) : void", mapped.label());
        assertEquals("println", mapped.insert());
        assertEquals("java.io.PrintStream", mapped.detail());
    }

    @Test
    void nestedPlaceholdersAndChoicesHaveCorrectFlattenedText() {
        assertEquals("foo(one)", CompletionMapper.stripSnippet("foo(${1|one,two|})"));
        assertEquals("foo(inner)", CompletionMapper.stripSnippet("foo(${1:${2:inner}})"));
    }
}
