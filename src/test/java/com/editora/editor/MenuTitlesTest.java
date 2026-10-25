package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Menu rows drop the palette's category prefixes where the rest still reads (third review ET3). */
class MenuTitlesTest {

    @Test
    void aPrefixGoesWhenWhatIsLeftStillSaysWhatTheRowDoes() {
        assertEquals("Complete Statement", MenuTitles.of("Edit: Complete Statement"));
        assertEquals("Go to Line…", MenuTitles.of("Go: Go to Line…"));
        assertEquals("Surround With…", MenuTitles.of("Snippet: Surround With…"));
        assertEquals("Generate…", MenuTitles.of("LSP: Generate…"));
        assertEquals("Extraire une variable", MenuTitles.of("LSP : Extraire une variable"));
        assertEquals("Extraer variable", MenuTitles.of("LSP: extraer variable"));
        assertEquals("Snippet: Insert…", MenuTitles.of("Snippet: Insert…"), "Insert… alone says too little");
        assertEquals("Find File…", MenuTitles.of("Find File…"));
    }
}
