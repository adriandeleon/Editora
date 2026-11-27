package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When the one-time library sources offer shows (r/JavaFX dry run, 2026-11-27). */
class LibrarySourcesOfferTest {

    @Test
    void offeredOnceForAJavaFileWhoseBuildLoadedWhileDownloadsAreOff() {
        assertTrue(LibrarySourcesOffer.shouldOffer(false, false, true, true));
        assertFalse(LibrarySourcesOffer.shouldOffer(true, false, true, true), "already downloading");
        assertFalse(LibrarySourcesOffer.shouldOffer(false, true, true, true), "offered before");
        assertFalse(LibrarySourcesOffer.shouldOffer(false, false, false, true), "not a Java file");
        assertFalse(LibrarySourcesOffer.shouldOffer(false, false, true, false), "the build waits for trust");
    }
}
