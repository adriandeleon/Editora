package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorStatus;
import com.editora.lsp.XmlSchemaCache;
import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What lemminx says that only VS Code could act on, said the Editora way (XML review A13, B2), and the Doctor row
 * for its schema cache (D4).
 */
class XmlServerNoticesTest {

    @org.junit.jupiter.api.BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    private static LspDiagnostic diagnostic(String code) {
        return new LspDiagnostic(
                1,
                0,
                1,
                5,
                LspDiagnostic.Severity.WARNING,
                "Downloading external resources is disabled.",
                code,
                "xml",
                null,
                false,
                false);
    }

    @Test
    void theSymbolLimitNoticeNamesEditorasSetting() {
        String lemminx = "mid.xml: For performance reasons, document symbols have been limited to 5000 items.\n"
                + "If a new limit is set, please close and reopen this file to recompute document symbols.";
        String said = XmlServerNotices.rewrite("xml", lemminx);
        assertEquals("mid.xml: " + tr("status.lsp.xmlSymbolLimit", 5000), said);
        assertTrue(said.contains("5,000") || said.contains("5000"), said);
        assertFalse(said.contains("close and reopen"), said);
        assertEquals(
                tr("status.lsp.xmlSymbolLimit", 7),
                XmlServerNotices.rewrite(
                        "maven-pom", "For performance reasons, document symbols have been limited to 7 items."));
        assertEquals(lemminx, XmlServerNotices.rewrite("java", lemminx), "another server's message is its own");
        assertEquals("Loaded and ready", XmlServerNotices.rewrite("xml", "Loaded and ready"));
    }

    @Test
    void aBlockedDownloadIsSaidOnceUntilTheSettingsAreAppliedAgain() {
        XmlServerNotices notices = new XmlServerNotices();
        assertNull(notices.onDiagnostics(List.of(diagnostic("cvc-elt.1.a"))));
        assertEquals(
                tr("status.lsp.xmlDownloadsOff"),
                notices.onDiagnostics(List.of(diagnostic(XmlServerNotices.DOWNLOAD_DISABLED))));
        assertNull(notices.onDiagnostics(List.of(diagnostic(XmlServerNotices.DOWNLOAD_DISABLED))), "once");
        notices.reset();
        assertNotNull(notices.onDiagnostics(List.of(diagnostic(XmlServerNotices.DOWNLOAD_DISABLED))));
    }

    @Test
    void doctorShowsTheCachesSizeAndWhereAndClearEmptiesIt(@TempDir Path dir) throws Exception {
        var empty = DoctorCoordinator.xmlCacheRow(dir).probe().get();
        assertEquals(DoctorStatus.OK, empty.status());
        assertEquals(tr("doctor.xmlCache.empty"), empty.detail());
        assertTrue(empty.clearable());
        assertNull(DoctorPane.pathIn(empty.detail()), "nothing to clear: no Clear button");

        Path cached = dir.resolve("cache/http/example.test/a.xsd");
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "x".repeat(2048));
        DoctorCheck full = DoctorCoordinator.xmlCacheRow(dir).probe().get();
        assertTrue(full.detail().endsWith(" — " + dir.toAbsolutePath()), full.detail());
        assertEquals(dir.toAbsolutePath(), DoctorPane.pathIn(full.detail()), "Clear and Reveal find the folder");

        assertEquals(2048, XmlSchemaCache.clear(dir));
        assertTrue(Files.isDirectory(dir), "the folder itself stays");
        assertEquals(0, XmlSchemaCache.size(dir));
        assertFalse(new DoctorCheck("lsp.xml", "lsp", "XML", "lemminx", DoctorStatus.OK).clearable());
    }
}
