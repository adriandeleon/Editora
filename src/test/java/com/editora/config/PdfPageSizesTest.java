package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PDF page size of a new installation follows the region; a stored value is never changed. Also the
 * page-footer setting added by the same schema step: on unless the user turned it off.
 */
class PdfPageSizesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theLetterCountriesGetLetterAndEveryoneElseA4() {
        for (String region : new String[] {"US", "CA", "MX", "PH", "CL", "CO", "VE", "CR", "PA", "GT", "DO", "PR"}) {
            assertEquals("letter", PdfPageSizes.forRegion(region), region);
        }
        for (String region : new String[] {"DE", "FR", "ES", "IT", "PT", "BR", "AR", "GB", "JP", "IN", "AU"}) {
            assertEquals("a4", PdfPageSizes.forRegion(region), region);
        }
        assertEquals("letter", PdfPageSizes.forRegion("mx"), "case does not matter");
    }

    @Test
    void anUnknownRegionKeepsTheSizeEveryEarlierBuildStartedWith() {
        assertEquals("letter", PdfPageSizes.forRegion(null));
        assertEquals("letter", PdfPageSizes.forRegion(""));
        assertEquals("letter", PdfPageSizes.forRegion("  "));
    }

    /** Runs {@code body} with the JVM's region property set to {@code region}, then puts it back. */
    private static void inRegion(String region, ThrowingRunnable body) throws Exception {
        String before = System.getProperty("user.country");
        System.setProperty("user.country", region);
        try {
            body.run();
        } finally {
            if (before == null) {
                System.clearProperty("user.country");
            } else {
                System.setProperty("user.country", before);
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void aNewInstallationStartsWithThePaperOfItsRegionAndWritesItDown(@TempDir Path dir) throws Exception {
        inRegion("DE", () -> {
            ConfigManager config = new ConfigManager(dir);
            assertEquals("a4", config.load().getPdfPageSize());
            config.save();
        });
        JsonNode saved = JSON.readTree(dir.resolve("settings.json").toFile());
        assertEquals("a4", saved.get("pdfPageSize").asText());
        // Once written it is a stored value: moving the machine to another region does not change it.
        inRegion("US", () -> assertEquals("a4", new ConfigManager(dir).load().getPdfPageSize()));
        inRegion("MX", () -> assertEquals("letter", new Settings().getPdfPageSize()));
    }

    @Test
    void aStoredValueSurvivesTheUpgradeWhateverTheRegion(@TempDir Path dir) throws Exception {
        Path letter = Files.createDirectories(dir.resolve("letter"));
        Files.writeString(letter.resolve("settings.json"), "{\"schemaVersion\":115,\"pdfPageSize\":\"letter\"}");
        inRegion(
                "DE",
                () -> assertEquals("letter", new ConfigManager(letter).load().getPdfPageSize()));

        Path a4 = Files.createDirectories(dir.resolve("a4"));
        Files.writeString(a4.resolve("settings.json"), "{\"schemaVersion\":115,\"pdfPageSize\":\"a4\"}");
        inRegion("US", () -> assertEquals("a4", new ConfigManager(a4).load().getPdfPageSize()));
    }

    /** No key at all: every earlier build exported Letter then, so an existing installation keeps Letter. */
    @Test
    void anOlderFileWithoutTheKeyKeepsLetter(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"schemaVersion\":115,\"tabSize\":4}");
        inRegion(
                "DE", () -> assertEquals("letter", new ConfigManager(dir).load().getPdfPageSize()));
    }

    @Test
    void thePageFooterIsOnForNewAndExistingInstallationsAndStaysOffOnceTurnedOff(@TempDir Path dir) throws Exception {
        assertTrue(new Settings().isPdfPageFooter(), "on by default");
        Files.writeString(dir.resolve("settings.json"), "{\"schemaVersion\":115,\"tabSize\":4}");
        ConfigManager config = new ConfigManager(dir);
        assertTrue(config.load().isPdfPageFooter(), "an upgraded file keeps the footer its pages had");
        config.getSettings().setPdfPageFooter(false);
        config.save();
        assertEquals(false, new ConfigManager(dir).load().isPdfPageFooter());
        assertEquals(
                false,
                JSON.readTree(dir.resolve("settings.json").toFile())
                        .get("pdfPageFooter")
                        .asBoolean());
    }
}
