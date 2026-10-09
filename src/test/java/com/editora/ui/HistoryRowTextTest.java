package com.editora.ui;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

import com.editora.config.HistoryRevision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryRowTextTest {

    private static final ZoneId ZONE = ZoneId.of("America/Monterrey");

    private static long at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 36, 0, ZONE)
                .toInstant()
                .toEpochMilli();
    }

    private static HistoryRevision revision(long when, String sha, String reason, String label) {
        return new HistoryRevision("/p/Orders.java", when, 10, sha, reason, label);
    }

    @Test
    void aRowShowsTheTimeOfDayTheWayTheReadersLocaleWritesIt() {
        long when = at(8, 16, 40);
        assertEquals("16:40", HistoryRowText.timeText(when, ZONE, Locale.GERMANY));
        assertTrue(HistoryRowText.timeText(when, ZONE, Locale.US).startsWith("4:40"));
        // The same instant is another hour somewhere else: the zone is the reader's, not UTC.
        assertEquals("22:40", HistoryRowText.timeText(when, ZoneId.of("UTC"), Locale.GERMANY));
    }

    @Test
    void theFullMomentCarriesTheDateAndTheSeconds() {
        String full = HistoryRowText.fullText(at(8, 16, 40), ZONE, Locale.GERMANY);
        assertTrue(full.contains("08.10.2026"), full);
        assertTrue(full.contains("16:40:36"), full);
        String shortForm = HistoryRowText.dateTimeText(at(8, 16, 40), ZONE, Locale.GERMANY);
        assertTrue(shortForm.contains("16:40") && !shortForm.contains(":36"), shortForm);
    }

    @Test
    void daysAreNamedRelativeOnlyForTodayAndYesterday() {
        LocalDate today = LocalDate.of(2026, 10, 8);
        assertEquals(HistoryRowText.DayKind.TODAY, HistoryRowText.dayKind(today, today));
        assertEquals(HistoryRowText.DayKind.YESTERDAY, HistoryRowText.dayKind(today.minusDays(1), today));
        assertEquals(HistoryRowText.DayKind.DATED, HistoryRowText.dayKind(today.minusDays(2), today));
        assertEquals(HistoryRowText.DayKind.DATED, HistoryRowText.dayKind(today.plusDays(1), today));
        assertEquals("29. September 2026", HistoryRowText.dayText(LocalDate.of(2026, 9, 29), Locale.GERMANY));
        assertEquals(LocalDate.of(2026, 10, 8), HistoryRowText.dayOf(at(8, 23, 59), ZONE));
        assertEquals(LocalDate.of(2026, 10, 9), HistoryRowText.dayOf(at(8, 23, 59), ZoneId.of("UTC")));
    }

    @Test
    void theFirstRowOfEachDayCarriesTheCaption() {
        List<HistoryRevision> shown = List.of(
                revision(at(8, 16, 40), "a", "SAVE", ""),
                revision(at(8, 9, 0), "b", "SAVE", ""),
                revision(at(7, 23, 0), "c", "SAVE", ""),
                revision(at(1, 12, 0), "d", "SAVE", ""));
        assertTrue(HistoryRowText.startsDay(shown, 0, ZONE));
        assertFalse(HistoryRowText.startsDay(shown, 1, ZONE));
        assertTrue(HistoryRowText.startsDay(shown, 2, ZONE));
        assertTrue(HistoryRowText.startsDay(shown, 3, ZONE));
        assertFalse(HistoryRowText.startsDay(shown, -1, ZONE));
        assertFalse(HistoryRowText.startsDay(shown, 4, ZONE));
    }

    @Test
    void currentIsTheNewestRevisionTheEditorTextEquals() {
        List<HistoryRevision> revisions =
                List.of(revision(3, "new", "SAVE", ""), revision(2, "old", "SAVE", ""), revision(1, "old", "SAVE", ""));
        assertEquals(0, HistoryRowText.currentIndex(revisions, "new"));
        assertEquals(1, HistoryRowText.currentIndex(revisions, "old"), "the newer of two equal revisions");
        assertEquals(-1, HistoryRowText.currentIndex(revisions, "typed since"), "unsaved edits: no row is current");
        assertEquals(-1, HistoryRowText.currentIndex(revisions, ""));
        assertEquals(-1, HistoryRowText.currentIndex(revisions, null));
    }

    @Test
    void theFilterMatchesWhatTheRowShows() {
        HistoryRevision saved = revision(at(8, 16, 40), "a", HistoryRevision.REASON_SAVE, "");
        HistoryRevision external = revision(at(8, 16, 41), "b", HistoryRevision.REASON_EXTERNAL, "");
        HistoryRevision labelled = revision(at(8, 16, 42), "c", HistoryRevision.REASON_LABEL, "before overflow fix");

        assertTrue(HistoryRowText.matches(saved, "Saved", "Saved"), "the reason as displayed");
        assertTrue(HistoryRowText.matches(external, "external change", "External change"));
        assertTrue(HistoryRowText.matches(saved, "gespeich", "Gespeichert"), "in the interface language");
        assertFalse(HistoryRowText.matches(external, "saved", "External change"));
        assertTrue(HistoryRowText.matches(saved, "save", "Gespeichert"), "the stored code still matches");
        assertTrue(HistoryRowText.matches(labelled, "OVERFLOW", "Label"));
        assertTrue(HistoryRowText.matches(saved, "16:40", "Saved", "16:40", "08.10.2026, 16:40:36"));
        assertTrue(HistoryRowText.matches(saved, "08.10", "Saved", "16:40", "08.10.2026, 16:40:36"));
        assertFalse(HistoryRowText.matches(saved, "17:00", "Saved", "16:40", null));
        assertTrue(HistoryRowText.matches(saved, "  ", "Saved"), "a blank filter shows everything");
        assertTrue(HistoryRowText.matches(saved, null, "Saved"));
        assertFalse(HistoryRowText.matches(null, "x", "Saved"));
    }

    @Test
    void sizesFollowTheLocale() {
        assertEquals("0 B", HistoryRowText.sizeText(0, Locale.ROOT));
        assertEquals("1.0 KB", HistoryRowText.sizeText(1024, Locale.US));
        assertEquals("54,5 KB", HistoryRowText.sizeText(55_808, Locale.GERMANY));
        assertEquals("2.5 MB", HistoryRowText.sizeText((long) (2.5 * 1024 * 1024), Locale.US));
    }
}
