package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.http.HttpFixtures;
import com.editora.http.HttpResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A full History keeps each request's newest response first, so one Run All cannot evict the rest (U12). */
class HttpHistoryRetentionTest {

    private static HttpHistoryEntry entry(String name, String url, long at) {
        HttpResult r = HttpFixtures.text(200, List.of(), "", "text/plain", 1, 1, null);
        return new HttpHistoryEntry(
                HttpFixtures.exchange("GET " + url, "GET", url, List.of(), "", r), name, -1, at, false, false, false);
    }

    private static List<String> names(List<HttpHistoryEntry> entries) {
        return entries.stream().map(e -> e.name() + "@" + e.at()).toList();
    }

    @Test
    void aRunAllDoesNotPushOutTheOnlyResponseOfAnotherRequest() {
        List<HttpHistoryEntry> newestFirst = new ArrayList<>();
        for (int round = 2; round >= 1; round--) {
            for (int i = 0; i < 6; i++) {
                newestFirst.add(entry("R" + i, "http://h/r" + i, round * 100 + i));
            }
        }
        newestFirst.add(entry("Lonely", "http://h/lonely", 1));
        List<HttpHistoryEntry> kept = HttpHistoryRetention.keep(newestFirst, 10);
        assertEquals(10, kept.size());
        assertEquals(
                List.of(
                        "R0@200",
                        "R1@201",
                        "R2@202",
                        "R3@203",
                        "R4@204",
                        "R5@205",
                        "R0@100",
                        "R1@101",
                        "R2@102",
                        "Lonely@1"),
                names(kept),
                "newest of each request first, then the newest of the rest, in newest-first order");
    }

    @Test
    void underTheCapNothingChangesAndUnnamedEntriesAreKeyedByMethodAndUrl() {
        List<HttpHistoryEntry> few = List.of(entry(null, "http://h/a", 3), entry(null, "http://h/a", 2));
        assertEquals(few, HttpHistoryRetention.keep(few, 10));
        List<HttpHistoryEntry> many =
                List.of(entry(null, "http://h/a", 5), entry(null, "http://h/a", 4), entry(null, "http://h/b", 3));
        assertEquals(List.of(many.get(0), many.get(2)), HttpHistoryRetention.keep(many, 2));
        assertEquals(List.of(), HttpHistoryRetention.keep(many, 0));
    }
}
