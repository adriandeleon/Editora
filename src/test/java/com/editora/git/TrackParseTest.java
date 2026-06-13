package com.editora.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Unit tests for parsing git's {@code %(upstream:track)} field into ahead/behind counts. */
class TrackParseTest {

    @Test
    void aheadOnly() {
        assertArrayEquals(new int[] {2, 0}, GitService.parseTrack("[ahead 2]"));
    }

    @Test
    void behindOnly() {
        assertArrayEquals(new int[] {0, 153}, GitService.parseTrack("[behind 153]"));
    }

    @Test
    void aheadAndBehind() {
        assertArrayEquals(new int[] {2, 153}, GitService.parseTrack("[ahead 2, behind 153]"));
    }

    @Test
    void upToDateOrEmptyOrNull() {
        assertArrayEquals(new int[] {0, 0}, GitService.parseTrack(""));
        assertArrayEquals(new int[] {0, 0}, GitService.parseTrack(null));
    }

    @Test
    void goneHasNoCounts() {
        assertArrayEquals(new int[] {0, 0}, GitService.parseTrack("[gone]"));
    }
}
