package com.editora.ui;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

import com.editora.config.HistoryRevision;
import com.editora.history.HistoryQueries;

/**
 * The pure text decisions behind a Local History row ({@link FileHistoryPanel}): how a revision's time is
 * written in the row, in a day caption and in the tooltip, which rows start a new day, which row is the one
 * the editor currently holds, and what the filter matches. Zone and locale are parameters so every answer is
 * the same on any machine, and none of it needs a JavaFX toolkit.
 */
final class HistoryRowText {

    /** How a day caption names a day: relative for the two days a reader thinks of by name, dated otherwise. */
    enum DayKind {
        TODAY,
        YESTERDAY,
        DATED
    }

    private HistoryRowText() {}

    static LocalDate dayOf(long epochMillis, ZoneId zone) {
        return at(epochMillis, zone).toLocalDate();
    }

    static DayKind dayKind(LocalDate day, LocalDate today) {
        if (day.equals(today)) {
            return DayKind.TODAY;
        }
        return day.equals(today.minusDays(1)) ? DayKind.YESTERDAY : DayKind.DATED;
    }

    /** The day as the locale writes a date in full ("8 October 2026"), for a caption above a day's rows. */
    static String dayText(LocalDate day, Locale locale) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                .withLocale(locale)
                .format(day);
    }

    /** The time of day as the locale writes it briefly ("16:40", "4:40 PM"): what a row under a day caption shows. */
    static String timeText(long epochMillis, ZoneId zone, Locale locale) {
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(locale)
                .format(at(epochMillis, zone));
    }

    /** Short date and time, for a row that has no day caption above it (the folder view). */
    static String dateTimeText(long epochMillis, ZoneId zone, Locale locale) {
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withLocale(locale)
                .format(at(epochMillis, zone));
    }

    /** The full moment, to the second, in the locale's own form: the tooltip, the diff header, a screen reader. */
    static String fullText(long epochMillis, ZoneId zone, Locale locale) {
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(at(epochMillis, zone));
    }

    private static ZonedDateTime at(long epochMillis, ZoneId zone) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone);
    }

    /**
     * Whether the row at {@code index} is the first of its day in {@code shown} (a newest-first list), and so
     * carries the day caption. The caption belongs to a row rather than being a row of its own, so the arrow
     * keys never stop on one.
     */
    static boolean startsDay(List<HistoryRevision> shown, int index, ZoneId zone) {
        if (index < 0 || index >= shown.size()) {
            return false;
        }
        return index == 0
                || !dayOf(shown.get(index).timestamp(), zone)
                        .equals(dayOf(shown.get(index - 1).timestamp(), zone));
    }

    /**
     * The index of the newest revision whose content is {@code editorSha} (the hash of the text the editor
     * holds now), or -1 when none is: the newest row is only "current" while nothing has been typed, restored
     * or undone since it was recorded.
     */
    static int currentIndex(List<HistoryRevision> revisions, String editorSha) {
        if (editorSha == null || editorSha.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < revisions.size(); i++) {
            if (editorSha.equals(revisions.get(i).sha256())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The filter: {@code query} matches a revision by its label, by the reason <em>as the row shows it</em>
     * ({@code reasonLabel}, in the interface language), or by any of {@code shownTexts} (the times the row and
     * its tooltip display). The stored reason code still matches too, so a habit like "save" keeps working.
     */
    static boolean matches(HistoryRevision revision, String query, String reasonLabel, String... shownTexts) {
        if (query == null || query.isBlank()) {
            return true;
        }
        if (revision == null) {
            return false;
        }
        if (HistoryQueries.matches(revision, query, reasonLabel)) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT).strip();
        for (String text : shownTexts) {
            if (contains(text, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    /** A byte count as the locale writes numbers ("54.5 KB", "54,5 KB"). */
    static String sizeText(long bytes, Locale locale) {
        if (bytes < 1024) {
            return bytes + " B"; // under four digits: nothing for a locale to group
        }
        NumberFormat oneDecimal = NumberFormat.getNumberInstance(locale);
        oneDecimal.setMinimumFractionDigits(1);
        oneDecimal.setMaximumFractionDigits(1);
        if (bytes < 1024 * 1024) {
            return oneDecimal.format(bytes / 1024.0) + " KB";
        }
        return oneDecimal.format(bytes / (1024.0 * 1024.0)) + " MB";
    }
}
