package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The POM schemas Editora ships for the XML servers (XML/pom review 2, D12): with schema downloads off (an untrusted
 * folder, a loose pom) every pom showed "Downloading external resources is disabled" and "Cannot find the declaration
 * of element 'project'" on line 2, and had no element completion.
 */
class XmlBundledSchemasTest {

    @TempDir
    Path config;

    @SuppressWarnings("unchecked")
    private static List<String> catalogs(java.util.Map<String, Object> xml) {
        return (List<String>) xml.get("catalogs");
    }

    @Test
    void ensureWritesTheSchemasAndACatalogBesideLemminxsCacheNotInIt() throws Exception {
        Path catalog = XmlBundledSchemas.ensure(config);
        assertNotNull(catalog);
        Path dir = XmlBundledSchemas.dir(config);
        assertEquals(dir.resolve("catalog.xml"), catalog);
        for (String xsd : List.of("maven-4.0.0.xsd", "maven-4.1.0.xsd")) {
            String text = Files.readString(dir.resolve(xsd));
            assertTrue(text.contains("targetNamespace=\"http://maven.apache.org/POM/"), xsd);
            assertTrue(text.contains("Apache Software Foundation"), xsd + ": its licence header ships with it");
        }
        assertTrue(
                !dir.startsWith(XmlServerSettings.cacheDir(config)),
                "Doctor's Clear empties lemminx's cache, not this");
        assertTrue(dir.startsWith(config.resolve("cache")), "settings export skips cache/");
    }

    @Test
    void theCatalogMapsEveryPomLocationAndTheNamespaceToTheShippedCopy() {
        String xml = XmlBundledSchemas.catalogXml();
        for (String name : List.of(
                "http://maven.apache.org/xsd/maven-4.0.0.xsd",
                "https://maven.apache.org/xsd/maven-4.0.0.xsd",
                "http://maven.apache.org/maven-v4_0_0.xsd")) {
            assertTrue(xml.contains("<system systemId=\"" + name + "\" uri=\"maven-4.0.0.xsd\"/>"), name);
        }
        assertTrue(xml.contains("<uri name=\"http://maven.apache.org/POM/4.0.0\" uri=\"maven-4.0.0.xsd\"/>"));
        assertTrue(xml.contains("<system systemId=\"https://maven.apache.org/xsd/maven-4.1.0.xsd\""));
        assertTrue(xml.contains("<uri name=\"http://maven.apache.org/POM/4.1.0\" uri=\"maven-4.1.0.xsd\"/>"));
    }

    /**
     * XML/pom review 3, C21: lemminx's grammar-aware formatting of a generated XSD loads XMLSchema.xsd, which imports
     * {@code http://www.w3.org/2001/xml.xsd}; lemminx ships only the https copy, so with downloads off both XSD fixes
     * of XML: Generate Grammar… resolved to null. The catalog now maps that location to a shipped copy.
     */
    @Test
    void theXmlNamespaceSchemaIsShippedForGeneratingAnXsdWithDownloadsOff() throws Exception {
        String xml = XmlBundledSchemas.catalogXml();
        assertTrue(xml.contains("<system systemId=\"http://www.w3.org/2001/xml.xsd\" uri=\"xml.xsd\"/>"), xml);
        assertTrue(xml.contains("<uri name=\"http://www.w3.org/2001/xml.xsd\" uri=\"xml.xsd\"/>"), xml);
        XmlBundledSchemas.ensure(config);
        String text = Files.readString(XmlBundledSchemas.dir(config).resolve("xml.xsd"));
        assertTrue(text.contains("targetNamespace=\"http://www.w3.org/XML/1998/namespace\""));
        assertTrue(!text.contains("<!DOCTYPE"), "it must load without fetching a DTD");
    }

    @Test
    void aChangedOrMissingFileIsWrittenAgain() throws Exception {
        XmlBundledSchemas.ensure(config);
        Path xsd = XmlBundledSchemas.dir(config).resolve("maven-4.0.0.xsd");
        long size = Files.size(xsd);
        Files.writeString(xsd, "truncated");
        Files.delete(XmlBundledSchemas.dir(config).resolve("catalog.xml"));
        XmlBundledSchemas.ensure(config);
        assertEquals(size, Files.size(xsd));
        assertTrue(Files.isRegularFile(XmlBundledSchemas.dir(config).resolve("catalog.xml")));
    }

    @Test
    void bothLemminxServersGetTheCatalogFirstWhenTheyRunHere() {
        // absolute on every OS (a POSIX "/etc/xml/catalog.xml" is relative on Windows and is left out)
        String own =
                config.toAbsolutePath().resolveSibling("etc-xml-catalog.xml").toString();
        Settings s = new Settings();
        s.setXmlCatalogs(List.of(own));
        XmlServerSettings settings = XmlServerSettings.of(s, config);
        assertEquals(List.of(own), catalogs(settings.section(config, false, true)), "not written");

        Path catalog = XmlBundledSchemas.ensure(config);
        assertEquals(List.of(String.valueOf(catalog), own), catalogs(settings.section(config, false, true)));
        assertEquals(
                List.of(own),
                catalogs(settings.section(config, false, false)),
                "a remote server cannot read this machine's files");
    }
}
