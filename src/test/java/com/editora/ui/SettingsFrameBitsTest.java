package com.editora.ui;

import com.editora.AppInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
