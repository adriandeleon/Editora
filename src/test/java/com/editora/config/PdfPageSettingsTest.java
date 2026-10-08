package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The page settings of a text PDF added by schema 117 — orientation, margin preset, code font size:
 * today's page by default and after an upgrade, stored as chosen, and a value nobody offers reads as the
 * default.
 */
class PdfPageSettingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static void assertTodaysPage(Settings s) {
        assertEquals("portrait", s.getPdfOrientation());
        assertEquals("normal", s.getPdfMargins());
        assertEquals(9, s.getPdfCodeFontSize());
    }

    @Test
    void aNewInstallationHasThePageEveryPdfHadBefore() {
        assertTodaysPage(new Settings());
        assertEquals("portrait", Settings.PDF_ORIENTATIONS.get(0), "the default is listed first");
        assertEquals("normal", Settings.PDF_MARGINS.get(0));
        assertEquals(Settings.DEFAULT_PDF_CODE_FONT_SIZE, 9);
    }

    @Test
    void anUpgradedFileKeepsTodaysPage(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"schemaVersion\":116,\"pdfPageSize\":\"a4\"}");
        ConfigManager config = new ConfigManager(dir);
        Settings s = config.load();
        assertTodaysPage(s);
        assertEquals("a4", s.getPdfPageSize());
        assertEquals(117, s.getSchemaVersion());
        assertEquals(117, Settings.SCHEMA_VERSION);
    }

    @Test
    void theChoicesAreSavedAndReadBack(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        Settings s = config.load();
        s.setPdfOrientation("landscape");
        s.setPdfMargins("narrow");
        s.setPdfCodeFontSize(11);
        config.save();

        JsonNode saved = JSON.readTree(dir.resolve("settings.json").toFile());
        assertEquals("landscape", saved.get("pdfOrientation").asText());
        assertEquals("narrow", saved.get("pdfMargins").asText());
        assertEquals(11, saved.get("pdfCodeFontSize").asInt());

        Settings again = new ConfigManager(dir).load();
        assertEquals("landscape", again.getPdfOrientation());
        assertEquals("narrow", again.getPdfMargins());
        assertEquals(11, again.getPdfCodeFontSize());
    }

    @Test
    void aValueNobodyOffersReadsAsTheDefault(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("settings.json"),
                "{\"schemaVersion\":117,\"pdfOrientation\":\"sideways\",\"pdfMargins\":\"none\","
                        + "\"pdfCodeFontSize\":400}");
        assertTodaysPage(new ConfigManager(dir).load());

        Files.writeString(
                dir.resolve("settings.json"),
                "{\"schemaVersion\":117,\"pdfOrientation\":null,\"pdfMargins\":\"\",\"pdfCodeFontSize\":0}");
        assertTodaysPage(new ConfigManager(dir).load());

        Settings s = new Settings();
        s.setPdfOrientation(" Landscape ");
        s.setPdfMargins("WIDE");
        assertEquals("landscape", s.getPdfOrientation(), "case and stray spaces do not matter");
        assertEquals("wide", s.getPdfMargins());
        s.setPdfOrientation("upside-down");
        s.setPdfMargins(null);
        s.setPdfCodeFontSize(6);
        assertTodaysPage(s);
        s.setPdfCodeFontSize(13);
        assertEquals(9, s.getPdfCodeFontSize());
        for (int size : Settings.PDF_CODE_FONT_SIZES) {
            s.setPdfCodeFontSize(size);
            assertEquals(size, s.getPdfCodeFontSize());
        }
    }
}
