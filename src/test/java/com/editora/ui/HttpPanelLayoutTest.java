package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The response view's width-dependent layout: what the header row keeps on one line, and the tab strip's fit. */
class HttpPanelLayoutTest {

    private static final double RUN = 140;

    @Test
    void theHistoryPickerGrowsInAWidePanelAndKeepsItsUsualWidthInASplit() {
        assertEquals(HttpPanelLayout.HISTORY_WIDTH, HttpPanelLayout.preferredHistory(640), 1e-9);
        assertEquals(HttpPanelLayout.HISTORY_MAX_WIDTH, HttpPanelLayout.preferredHistory(1600), 1e-9);
        assertEquals(1280 * HttpPanelLayout.HISTORY_SHARE, HttpPanelLayout.preferredHistory(1280), 1e-9, "Preview");
    }

    @Test
    void aWideHeaderKeepsEverythingOnOneRow() {
        HttpPanelLayout.HeaderFit f = HttpPanelLayout.header(1280, RUN, 60, 60, 95);
        assertEquals(1, f.rows());
        assertTrue(f.meta());
        assertEquals(60, f.status(), 1e-9);
        assertEquals(HttpPanelLayout.preferredHistory(1280), f.history(), 1e-9);
    }

    @Test
    void historyNarrowsFirstThenTheTimingHidesThenHistoryWraps() {
        // Room for everything with History at 200 px: History narrows, nothing else changes.
        double tight = RUN + 60 + 95 + 200 + 3 * HttpPanelLayout.GAP;
        HttpPanelLayout.HeaderFit narrowed = HttpPanelLayout.header(tight, RUN, 60, 60, 95);
        assertEquals(1, narrowed.rows());
        assertTrue(narrowed.meta());
        assertEquals(200, narrowed.history(), 1e-9);
        // History at its least no longer fits beside the timing: the timing hides first.
        double noMeta = RUN + 60 + HttpPanelLayout.HISTORY_MIN_WIDTH + 2 * HttpPanelLayout.GAP + 50;
        HttpPanelLayout.HeaderFit hidden = HttpPanelLayout.header(noMeta, RUN, 60, 60, 95);
        assertEquals(1, hidden.rows());
        assertFalse(hidden.meta());
        assertEquals(60, hidden.status(), 1e-9, "the status is whole");
        assertTrue(hidden.history() >= HttpPanelLayout.HISTORY_MIN_WIDTH);
        // Narrower still: History goes to a second row; the status stays whole.
        HttpPanelLayout.HeaderFit wrapped = HttpPanelLayout.header(400, RUN, 100, 100, 95);
        assertEquals(2, wrapped.rows());
        assertEquals(100, wrapped.status(), 1e-9);
        assertTrue(wrapped.meta(), "on its own row the status has room for the timing again");
        assertEquals(HttpPanelLayout.HISTORY_WIDTH, wrapped.history(), 1e-9, "History has its row to itself");
    }

    @Test
    void aRunningStatusIsCutBeforeHistoryWraps() {
        double room = 640;
        HttpPanelLayout.HeaderFit f = HttpPanelLayout.header(room, RUN, 420, 120, 0);
        assertEquals(1, f.rows());
        assertEquals(HttpPanelLayout.HISTORY_MIN_WIDTH, f.history(), 1e-9);
        assertEquals(room - RUN - 2 * HttpPanelLayout.GAP - HttpPanelLayout.HISTORY_MIN_WIDTH, f.status(), 1e-9);
    }

    @Test
    void theNarrowestPanelGivesHistoryTheSecondRow() {
        HttpPanelLayout.HeaderFit f = HttpPanelLayout.header(248, RUN, 60, 60, 95);
        assertEquals(2, f.rows());
        assertEquals(60, f.status(), 1e-9);
        assertEquals(248, f.history(), 1e-9, "History takes the second row");
    }

    @Test
    void theStripFoldsItsIconsBeforeATabLosesItsName() {
        assertEquals(HttpPanelLayout.StripFit.FULL, HttpPanelLayout.strip(600, 400, 260, 140, 36));
        assertEquals(HttpPanelLayout.StripFit.ACTIONS_IN_MENU, HttpPanelLayout.strip(460, 400, 260, 140, 36));
        assertEquals(HttpPanelLayout.StripFit.COUNTS, HttpPanelLayout.strip(400, 400, 260, 140, 36));
        assertEquals(
                HttpPanelLayout.StripFit.ACTIONS_IN_MENU,
                HttpPanelLayout.strip(300, 300, 300, 140, 36),
                "tabs without counts cannot get shorter");
    }
}
