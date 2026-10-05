package com.editora.ui;

import com.editora.search.LineMatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A12-10: a popup row lays out a window of a long line, not the megabyte it came from. */
class SearchPopupPreviewTest {

    @Test
    void aShortLineIsShownWhole() {
        assertEquals("int a = 1;", SearchInFilesPopup.previewOf(new LineMatch(1, 5, 3, "    int a = 1;  ")));
        assertEquals("", SearchInFilesPopup.previewOf(new LineMatch(1, 1, 0, null)));
    }

    @Test
    void aLongLineIsCutToAWindowAroundTheMatch() {
        String line = "x".repeat(500_000) + "function" + "y".repeat(500_000);
        String preview = SearchInFilesPopup.previewOf(new LineMatch(1, 500_001, 8, line));
        assertTrue(preview.length() <= SearchInFilesPopup.PREVIEW_CHARS + 2, "bounded: " + preview.length());
        assertTrue(preview.contains("function"), "the match is in view");
        assertTrue(preview.startsWith("…") && preview.endsWith("…"), "both cuts are marked");
    }

    @Test
    void aMatchAtEitherEndOfALongLineStaysInView() {
        String line = "function" + "y".repeat(10_000) + "tail";
        String head = SearchInFilesPopup.previewOf(new LineMatch(1, 1, 8, line));
        assertTrue(head.startsWith("function") && head.endsWith("…"));
        String tail = SearchInFilesPopup.previewOf(new LineMatch(1, line.length() - 3, 4, line));
        assertTrue(tail.startsWith("…") && tail.endsWith("tail"));
        assertTrue(tail.length() <= SearchInFilesPopup.PREVIEW_CHARS + 1);
    }
}
