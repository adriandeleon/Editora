package com.editora.completion;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The Mermaid keyword source behind completion in {@code .mmd} buffers. */
class MermaidKeywordsTest {

    @Test
    void matchesByPrefixIgnoringCaseInListOrder() {
        assertEquals(List.of("stateDiagram", "stateDiagram-v2"), MermaidKeywords.startingWith("stated", 10));
        assertEquals(List.of("class", "classDef", "classDiagram", "click"), MermaidKeywords.startingWith("CL", 10));
        assertEquals(List.of("timeline", "title", "TB", "TD"), MermaidKeywords.startingWith("t", 10));
    }

    @Test
    void theWordAlreadyTypedIsNotOfferedBack() {
        assertEquals(List.of("stateDiagram", "stateDiagram-v2"), MermaidKeywords.startingWith("state", 10));
        assertEquals(List.of(), MermaidKeywords.startingWith("LR", 10));
        assertFalse(MermaidKeywords.startingWith("lr", 10).contains("LR"), "also when the case differs");
    }

    @Test
    void theResultIsCappedAndAnEmptyPrefixOffersNothing() {
        assertEquals(List.of("section"), MermaidKeywords.startingWith("s", 1));
        assertEquals(List.of("section", "sequenceDiagram", "state"), MermaidKeywords.startingWith("s", 3));
        assertEquals(List.of(), MermaidKeywords.startingWith("", 10));
        assertEquals(List.of(), MermaidKeywords.startingWith(null, 10));
        assertEquals(List.of(), MermaidKeywords.startingWith("zz", 10));
    }
}
