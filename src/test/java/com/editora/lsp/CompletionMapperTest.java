package com.editora.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.InsertTextFormat;
import org.junit.jupiter.api.Test;

import com.editora.completion.Completion;

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
        assertEquals("names", out.get(1).insert());           // never the decorated label
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
}
