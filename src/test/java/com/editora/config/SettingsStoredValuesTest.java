package com.editora.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@link Settings} makes of a value a hand-edited or synced file stored: odd entries read as the default. */
class SettingsStoredValuesTest {

    @Test
    void unknownChoicesReadAsTheirDefault() {
        Settings s = new Settings();
        s.setMarkdownUpdateLinksOnRename("sometimes");
        assertEquals("ask", s.getMarkdownUpdateLinksOnRename());
        s.setMarkdownUpdateLinksOnRename(null);
        assertEquals("ask", s.getMarkdownUpdateLinksOnRename());
        s.setMarkdownUpdateLinksOnRename("never");
        assertEquals("never", s.getMarkdownUpdateLinksOnRename());
        s.setMarkdownPreviewTheme("neon");
        assertEquals("", s.getMarkdownPreviewTheme());
        s.setMarkdownPreviewTheme("dark");
        assertEquals("dark", s.getMarkdownPreviewTheme());
        s.setDebugHotCodeReplace(" ");
        assertEquals("auto", s.getDebugHotCodeReplace());
        s.setDebugHotCodeReplace(null);
        assertEquals("auto", s.getDebugHotCodeReplace());
        s.setBashShfmtDialect("fish");
        assertEquals("auto", s.getBashShfmtDialect());
        s.setSpellLanguage(" ");
        assertEquals("en_US", s.getSpellLanguage());
        s.setXmlSymbolLimit(0);
        assertEquals(Settings.XML_SYMBOL_LIMIT_DEFAULT, s.getXmlSymbolLimit());
        s.setXmlSymbolLimit(Integer.MAX_VALUE);
        assertEquals(Settings.XML_SYMBOL_LIMIT_MAX, s.getXmlSymbolLimit());
        s.setPdfCodeFontSize(30);
        assertEquals(Settings.DEFAULT_PDF_CODE_FONT_SIZE, s.getPdfCodeFontSize());
        s.setXmlSchemaDownloads("sometimes");
        assertEquals("trusted", s.getXmlSchemaDownloads());
    }

    @Test
    void aBlankAuthorFollowsTheOsUser() {
        Settings s = new Settings();
        s.setAuthorName(" ");
        assertEquals(System.getProperty("user.name", ""), s.getAuthorName());
        assertEquals(" ", s.getAuthorNameRaw());
        s.setAuthorName("Ada");
        assertEquals("Ada", s.getAuthorName());
    }

    @Test
    void imageNamesThatCannotBeFileNamesFallBack() {
        String fallback = Settings.DEFAULT_MARKDOWN_IMAGE_NAME;
        assertEquals(fallback, Settings.normalizeImageName(null));
        assertEquals(fallback, Settings.normalizeImageName("  "));
        assertEquals(fallback, Settings.normalizeImageName("x".repeat(81)));
        assertEquals(fallback, Settings.normalizeImageName(".hidden"));
        assertEquals(fallback, Settings.normalizeImageName("trailing."));
        assertEquals(fallback, Settings.normalizeImageName("a/b"));
        assertEquals(fallback, Settings.normalizeImageName("tab\there"));
        assertEquals(fallback, Settings.normalizeImageName("what?"));
        assertEquals("shot-{n}", Settings.normalizeImageName(" shot-{n} "));
    }

    @Test
    void lintRulesTurnOnAndOffInTheListThatGovernsThem() {
        Settings s = new Settings();
        assertTrue(s.isMarkdownLintRuleOn("md001"));
        assertFalse(s.isMarkdownLintRuleOn("MD013"), "off until enabled");
        assertTrue(s.isMarkdownLintRuleOn(null), "no code: an unknown rule is on");
        s.setMarkdownLintRuleOn(" md013 ", true);
        assertTrue(s.isMarkdownLintRuleOn("MD013"));
        assertEquals(List.of("MD013"), s.getMarkdownLintEnabledRules());
        s.setMarkdownLintRuleOn("MD013", false);
        assertFalse(s.isMarkdownLintRuleOn("MD013"));
        s.setMarkdownLintRuleOn("MD001", false);
        assertFalse(s.isMarkdownLintRuleOn("md001"));
        s.setMarkdownLintRuleOn("md001", true);
        assertTrue(s.isMarkdownLintRuleOn("MD001"));
        s.setMarkdownLintEnabledRules(new ArrayList<>(Arrays.asList(null, " md033 ")));
        assertTrue(s.isMarkdownLintRuleOn("MD033"), "a stored null and spaces are tolerated");
        s.setMarkdownLintRuleOn("MD033", false);
        assertEquals(List.of(), s.getMarkdownLintEnabledRules(), "the null goes with it");
        s.setMarkdownLintEnabledRules(null);
        assertEquals(List.of(), s.getMarkdownLintEnabledRules());
    }

    @Test
    void lintSeveritiesStoreOnlyWhatIsNotTheDefault() {
        Settings s = new Settings();
        assertEquals("warning", s.markdownLintSeverity("MD001"));
        assertEquals("warning", s.markdownLintSeverity(null));
        s.setMarkdownLintSeverity("md001", " ERROR ");
        assertEquals(Map.of("MD001", "error"), s.getMarkdownLintSeverities());
        assertEquals("error", s.markdownLintSeverity(" Md001 "));
        s.setMarkdownLintSeverity("MD001", "warning");
        assertEquals(Map.of(), s.getMarkdownLintSeverities(), "the default is no entry");
        s.setMarkdownLintSeverity("MD002", null);
        assertEquals(Map.of(), s.getMarkdownLintSeverities());
        Map<String, String> stored = new HashMap<>();
        stored.put(null, "error");
        stored.put("MD003", null);
        stored.put("MD004", "loud");
        stored.put("md005", "hint");
        s.setMarkdownLintSeverities(stored);
        assertEquals("warning", s.markdownLintSeverity("MD003"));
        assertEquals("warning", s.markdownLintSeverity("MD004"), "an unknown severity reads as a warning");
        assertEquals("hint", s.markdownLintSeverity("MD005"));
        s.setMarkdownLintSeverity("MD006", "hint");
        assertFalse(s.getMarkdownLintSeverities().containsKey(null), "a null key is dropped on the next write");
        s.setMarkdownLintSeverities(null);
        assertEquals(Map.of(), s.getMarkdownLintSeverities());
    }

    @Test
    void containerBindingsAndXmlListsDropBlanks() {
        Settings s = new Settings();
        Map<String, String> bindings = new LinkedHashMap<>();
        bindings.put(" /work ", " box ");
        bindings.put("", "x");
        bindings.put("/a", " ");
        bindings.put(null, "y");
        bindings.put("/b", null);
        s.setContainerBindings(bindings);
        assertEquals(Map.of("/work", "box"), s.getContainerBindings());
        s.setContainerBindings(null);
        assertEquals(Map.of(), s.getContainerBindings());

        s.setXmlCatalogs(Arrays.asList(" cat.xml ", null, " "));
        assertEquals(List.of("cat.xml"), s.getXmlCatalogs());
        Map<String, String> assoc = new LinkedHashMap<>();
        assoc.put(" *.xml ", " s.xsd ");
        assoc.put(" ", "s.xsd");
        assoc.put("*.a", " ");
        assoc.put(null, "s");
        assoc.put("*.b", null);
        s.setXmlFileAssociations(assoc);
        assertEquals(Map.of("*.xml", "s.xsd"), s.getXmlFileAssociations());
        s.setXmlFormatPreserveSpace(Arrays.asList(" pre ", "pre", null, ""));
        assertEquals(List.of("pre"), s.getXmlFormatPreserveSpace());
    }

    @Test
    void recentRecipientsKeepTheNewestTwenty() {
        Settings s = new Settings();
        s.rememberEncryptionRecipients(List.of("a", "b"));
        s.rememberEncryptionRecipients(List.of("c", "a"));
        assertEquals(List.of("c", "a", "b"), s.getEncryptionRecentRecipients());
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add("k" + i);
        }
        s.rememberEncryptionRecipients(many);
        assertEquals(20, s.getEncryptionRecentRecipients().size());
        assertEquals("k0", s.getEncryptionRecentRecipients().get(0));
    }

    @Test
    void registryUrlsMustBeHttpsWithAHost() {
        assertTrue(Settings.isAcceptableRegistryUrl(null));
        assertTrue(Settings.isAcceptableRegistryUrl(" "));
        assertTrue(Settings.isAcceptableRegistryUrl(" HTTPS://h.example/index.json "));
        assertFalse(Settings.isAcceptableRegistryUrl("http://h.example/index.json"));
        assertFalse(Settings.isAcceptableRegistryUrl("https:///index.json"));
        assertFalse(Settings.isAcceptableRegistryUrl("https://bad host/"));
    }

    @Test
    void theMavenCatalogFollowsTheDefaultWhenBlank() {
        Settings s = new Settings();
        s.setMavenArchetypeCatalogUrl(" ");
        assertEquals(Settings.DEFAULT_MAVEN_ARCHETYPE_CATALOG, s.getMavenArchetypeCatalogUrl());
        s.setMavenArchetypeCatalogUrl("https://mirror/catalog.xml");
        assertEquals("https://mirror/catalog.xml", s.getMavenArchetypeCatalogUrl());
        s.setMavenArchetypeCatalogUrl(Settings.DEFAULT_MAVEN_ARCHETYPE_CATALOG);
        assertEquals("", s.getMavenArchetypeCatalogUrlRaw());
    }
}
