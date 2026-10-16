package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The response view's content-dependent sizes: the top section's share, the warnings rows, the History width. */
class HttpPanelLayoutTest {

    @Test
    void theTopSectionGetsWhatItAsksForWithinBounds() {
        assertEquals(0.5, HttpPanelLayout.headShare(300, 600), 1e-9);
        assertEquals(HttpPanelLayout.HEAD_MIN_SHARE, HttpPanelLayout.headShare(10, 600), 1e-9);
        assertEquals(HttpPanelLayout.HEAD_MAX_SHARE, HttpPanelLayout.headShare(900, 600), 1e-9);
        assertEquals(0.3, HttpPanelLayout.headShare(300, 0), 1e-9, "not laid out yet");
    }

    @Test
    void theWarningsBoxCountsWrappedRowsNotLines() {
        String warning = "x".repeat(95);
        assertEquals(4, HttpPanelLayout.notesRows(List.of(warning, warning), 50), "two warnings of two rows each");
        assertEquals(1, HttpPanelLayout.notesRows(List.of(""), 50));
        assertEquals(HttpPanelLayout.NOTES_MAX_ROWS, HttpPanelLayout.notesRows(List.of("x".repeat(1000)), 50));
        assertEquals(10, HttpPanelLayout.columns(85, 8));
    }

    @Test
    void theHistoryPickerGrowsWhenTheEnvironmentPickerStillFits() {
        assertEquals(HttpPanelLayout.HISTORY_WIDTH, HttpPanelLayout.historyWidth(581), 1e-9, "a Split at 1400 px");
        assertEquals(HttpPanelLayout.HISTORY_MAX_WIDTH, HttpPanelLayout.historyWidth(1290), 1e-9, "Preview");
        assertEquals(HttpPanelLayout.HISTORY_MIN_WIDTH, HttpPanelLayout.historyWidth(100), 1e-9, "a tight Split");
        assertEquals(200, HttpPanelLayout.historyWidth(200), 1e-9);
    }
}
