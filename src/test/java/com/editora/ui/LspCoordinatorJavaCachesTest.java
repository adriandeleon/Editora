package com.editora.ui;

import com.editora.i18n.Messages;
import com.editora.lsp.LspManager.JdtlsCacheClearResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The status line {@code lsp.clearJavaIndexCaches} reports when it finishes. */
class LspCoordinatorJavaCachesTest {

    @BeforeAll
    static void initMessages() {
        Messages.init("en");
    }

    @Test
    void reportsWhatWasClearedAndWhatWasKept() {
        assertEquals(
                "No Java index caches to clear",
                LspCoordinator.javaCachesClearedStatus(new JdtlsCacheClearResult(0, 0, 0)));
        assertEquals(
                "Cleared 1 Java index cache (2.0 MB freed).",
                LspCoordinator.javaCachesClearedStatus(new JdtlsCacheClearResult(1, 2 * 1024 * 1024, 0)));
        assertEquals(
                "Cleared 12 Java index caches (512 B freed). 1 cache is in use and was kept.",
                LspCoordinator.javaCachesClearedStatus(new JdtlsCacheClearResult(12, 512, 1)));
        assertEquals(
                "2 caches are in use and were kept.",
                LspCoordinator.javaCachesClearedStatus(new JdtlsCacheClearResult(0, 0, 2)),
                "nothing deleted: no \"Cleared 0\" line");
    }
}
