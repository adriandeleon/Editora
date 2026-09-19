package com.editora.completion;

import com.editora.snippet.Snippet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaCompletionInsertionTest {
    private Completion method(String body) {
        return Completion.lsp(
                        "foo(String text)", "foo(text)", "", null, CompletionIconKind.METHOD, null, false, false, null)
                .withSnippet(new Snippet("foo", "foo", body, "", ""));
    }

    @Test
    void reuseExistingEmptyAndNonemptyArgumentLists() {
        for (String doc : new String[] {"fo()", "fo(existingText)", "fo(unfinished"}) {
            var plan = JavaCompletionInsertion.plan(method("foo(${1:text})"), doc, 0, 2);
            assertEquals("foo", plan.text());
            assertEquals(4, plan.caretOffset());
            assertFalse(plan.snippet());
        }
    }

    @Test
    void preserveServerSnippetWithoutExistingCall() {
        assertEquals(
                "foo(${1:text})",
                JavaCompletionInsertion.plan(method("foo(${1:text})"), "fo", 0, 2)
                        .text());
    }

    @Test
    void referencesNeverGainParentheses() {
        var plan = JavaCompletionInsertion.plan(method("foo(${1:text})"), "SomeClass::fo", 11, 13);
        assertEquals("foo", plan.text());
        assertFalse(plan.snippet());
    }

    @Test
    void plainMethodGetsAnEditableCallButEmptyServerEditRemainsEmpty() {
        var item = Completion.lsp(
                "foo(String value)", "foo", "", null, CompletionIconKind.METHOD, null, false, false, null);
        assertEquals("foo($0)", JavaCompletionInsertion.plan(item, "fo", 0, 2).text());
        var empty =
                Completion.lsp("foo(String value)", "", "", null, CompletionIconKind.METHOD, null, false, false, null);
        assertEquals("", JavaCompletionInsertion.plan(empty, "foo(", 4, 4).text());
    }

    @Test
    void constructorDiamondReusesAnExistingArgumentList() {
        var item = Completion.lsp(
                "ArrayList()", "ArrayList<>()", "", null, CompletionIconKind.CONSTRUCTOR, null, false, false, null);
        var plan = JavaCompletionInsertion.plan(item, "new Arr(existing)", 4, 7);
        assertEquals("ArrayList<>", plan.text());
        assertEquals(12, plan.caretOffset());
    }
}
