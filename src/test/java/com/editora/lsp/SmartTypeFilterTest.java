package com.editora.lsp;

import java.util.List;

import com.editora.completion.Completion;
import com.editora.completion.CompletionIconKind;
import com.editora.lsp.SmartTypeFilter.Fit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmartTypeFilterTest {

    /** A jdtls proposal: {@code relevance} becomes its sortText as jdtls computes it. */
    private static Completion item(String label, CompletionIconKind kind, String type, int relevance) {
        String sort = String.valueOf(999_999_999L - relevance * 16L - 5);
        return Completion.lsp(label, label, type, null, kind, sort, false, false, null);
    }

    private static List<String> labels(List<Completion> items) {
        return items.stream().map(Completion::label).toList();
    }

    @Test
    void namesDecideWhatFits() {
        assertEquals(Fit.YES, SmartTypeFilter.fit("String", "CharSequence"));
        assertEquals(Fit.YES, SmartTypeFilter.fit("int", "long"));
        assertEquals(Fit.NO, SmartTypeFilter.fit("long", "int"));
        assertEquals(Fit.YES, SmartTypeFilter.fit("int", "Integer"));
        assertEquals(Fit.YES, SmartTypeFilter.fit("Integer", "long"), "unboxing, then widening");
        assertEquals(Fit.YES, SmartTypeFilter.fit("char", "Object"));
        assertEquals(Fit.YES, SmartTypeFilter.fit("ArrayList", "Collection"));
        assertEquals(Fit.NO, SmartTypeFilter.fit("List", "String"));
        assertEquals(Fit.YES, SmartTypeFilter.fit("String[]", "Object[]"));
        assertEquals(Fit.NO, SmartTypeFilter.fit("int[]", "long[]"));
        assertEquals(Fit.YES, SmartTypeFilter.fit("int[]", "Object"));
        assertEquals(Fit.NO, SmartTypeFilter.fit("String", "Shape"), "a JDK type extends nothing of the project's");
        assertEquals(Fit.UNKNOWN, SmartTypeFilter.fit("Circle", "Shape"));
        assertEquals(Fit.UNKNOWN, SmartTypeFilter.fit("Names", "List"), "a project type may implement List");
        assertEquals(Fit.NO, SmartTypeFilter.fit("Names", "String"), "nothing extends String");
        assertEquals("List[]", SmartTypeFilter.erasure("java.util.List<java.lang.String>[]"));
        assertEquals("Map", SmartTypeFilter.erasure("Map<String, List<Integer>>"));
    }

    @Test
    void onlyWhatFitsTheExpectedTypeStays() {
        List<Completion> items = List.of(
                item("name", CompletionIconKind.FIELD, "String", 82),
                item("names", CompletionIconKind.FIELD, "List<java.lang.String>", 52),
                item("label() : String", CompletionIconKind.METHOD, "String", 80),
                item("run() : void", CompletionIconKind.METHOD, "void", 47),
                item("null", CompletionIconKind.KEYWORD, "", 50),
                item("new", CompletionIconKind.KEYWORD, "", 50),
                item("NullPointerException", CompletionIconKind.CLASS, "java.lang", 70),
                item("String", CompletionIconKind.CLASS, "java.lang", 74),
                item("fori", CompletionIconKind.SNIPPET, "", 90));
        assertEquals(
                List.of("name", "label() : String", "null", "String"),
                labels(SmartTypeFilter.filter(items, "String", false)));
        assertEquals(
                List.of("names", "null"), labels(SmartTypeFilter.filter(items, "java.util.Collection<String>", false)));
    }

    @Test
    void primitivesTakeNoNullAndBooleansTakeTheLiterals() {
        List<Completion> items = List.of(
                item("count", CompletionIconKind.FIELD, "int", 82),
                item("ok", CompletionIconKind.VARIABLE, "boolean", 82),
                item("null", CompletionIconKind.KEYWORD, "", 50),
                item("true", CompletionIconKind.KEYWORD, "", 50),
                item("false", CompletionIconKind.KEYWORD, "", 50));
        assertEquals(List.of("count"), labels(SmartTypeFilter.filter(items, "long", false)));
        assertEquals(List.of("ok", "true", "false"), labels(SmartTypeFilter.filter(items, "boolean", false)));
        assertEquals(List.of(), SmartTypeFilter.filter(items, "void", false));
    }

    @Test
    void afterNewTheImplementationsOfTheExpectedTypeStay() {
        List<Completion> items = List.of(
                item("ArrayList()", CompletionIconKind.CONSTRUCTOR, "java.util", 60),
                item("HashMap()", CompletionIconKind.CONSTRUCTOR, "java.util", 60),
                item("List", CompletionIconKind.INTERFACE, "java.util", 60),
                item("names", CompletionIconKind.FIELD, "List<String>", 82));
        assertEquals(List.of("ArrayList()", "List"), labels(SmartTypeFilter.filter(items, "List<String>", true)));
    }

    @Test
    void forAProjectTypeJdtlssBoostDecides() {
        List<Completion> items = List.of(
                item("circle", CompletionIconKind.FIELD, "Circle", 72), // jdtls: compatible, +20
                item("label", CompletionIconKind.FIELD, "Label", 52), // not related
                item("text", CompletionIconKind.FIELD, "String", 52),
                item("shape", CompletionIconKind.VARIABLE, "Shape", 88));
        assertEquals(List.of("circle", "shape"), labels(SmartTypeFilter.filter(items, "Shape", false)));
    }

    @Test
    void anItemWithoutJdtlssRankingIsNotGuessedAt() {
        Completion bare = Completion.lsp("x", "x", "Circle", null, CompletionIconKind.FIELD, null, false, false, null);
        assertEquals(-1, SmartTypeFilter.relevance(bare));
        assertEquals(List.of(), SmartTypeFilter.filter(List.of(bare), "Shape", false));
    }

    @Test
    void aVarargsArgumentTakesAnElementOrTheArray() {
        List<Completion> items = List.of(
                item("count", CompletionIconKind.FIELD, "int", 82),
                item("args", CompletionIconKind.VARIABLE, "Object[]", 70),
                item("run() : void", CompletionIconKind.METHOD, "void", 47));
        assertEquals(
                List.of("count", "args"), labels(SmartTypeFilter.filter(items, List.of("Object", "Object[]"), false)));
    }

    @Test
    void lambdaProposalsStayForAFunctionalTarget() {
        List<Completion> items = List.of(
                item("() ->", CompletionIconKind.SNIPPET, "", 60),
                item("String::valueOf", CompletionIconKind.METHOD, "String", 60),
                item("task", CompletionIconKind.FIELD, "Runnable", 82));
        assertEquals(
                List.of("() ->", "String::valueOf", "task"), labels(SmartTypeFilter.filter(items, "Runnable", false)));
        assertEquals(List.of(), labels(SmartTypeFilter.filter(items.subList(0, 1), "String", false)));
        assertEquals(true, SmartTypeFilter.functional("Shape"), "a project type may be a functional interface");
        assertEquals(false, SmartTypeFilter.functional("List"));
    }
}
