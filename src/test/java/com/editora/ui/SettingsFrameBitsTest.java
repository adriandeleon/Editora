package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.AppInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsFrameBitsTest {

    @Test
    void aSettingCoveredByPartOfALongerPageLinksItsSection() {
        String docs = AppInfo.docsUrl();
        assertEquals(docs + "/languages#autocomplete", SettingsFrameBits.helpUrl("COMPLETION"));
        assertEquals(docs + "/languages#spell-checking", SettingsFrameBits.helpUrl("SPELL_CHECK"));
        assertEquals(docs + "/markdown#mermaid-diagrams", SettingsFrameBits.helpUrl("MERMAID"));
    }

    @Test
    void aPageWithItsOwnDocsLinksThatPageAndAnUnknownOneTheDocsHome() {
        assertEquals(AppInfo.docsUrl() + "/settings-sync", SettingsFrameBits.helpUrl("SYNC"));
        assertEquals(AppInfo.docsUrl(), SettingsFrameBits.helpUrl("NO_SUCH_PAGE"));
    }

    @Test
    void debuggingSettingsLinkTheDebuggingPage() {
        assertEquals(AppInfo.docsUrl() + "/debugging", SettingsFrameBits.helpUrl("DEBUG"));
    }

    @Test
    void everyLinkedPageIsInTheUserGuide() throws IOException {
        Path guide = Path.of("user-docs", "guide");
        for (String link : SettingsFrameBits.docsPages()) {
            String page = link.contains("#") ? link.substring(0, link.indexOf('#')) : link;
            Path file = guide.resolve(page + ".md");
            assertTrue(Files.isRegularFile(file), "Settings links docs page " + page + ", which user-docs/guide lacks");
        }
    }
}
