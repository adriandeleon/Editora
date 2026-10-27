package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.editora.config.Settings;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The XML server gets its settings for its own root through {@link LspManager}: schema downloads follow that
 * folder's trust and change when it is trusted (XML review B2), the cache goes under the config folder (D4), and
 * lemminx's indentation question is answered from the document (B12).
 */
@Tag("fx")
class LspXmlSettingsFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    @TempDir
    Path config;

    private LspManager manager;
    private List<FakeLanguageServer> fakes;
    private final AtomicBoolean trusted = new AtomicBoolean();
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        fakes = LspTestHooks.useFakeSessions(manager);
        manager.setFolderTrust(folder -> trusted.get());
        manager.configure(true, Map.of("xml", "lemminx"));
        manager.setXmlSettings(XmlServerSettings.of(new Settings(), config));
        file = root.resolve("a.xml");
        Files.writeString(file, "<a/>\n");
        manager.setDocumentIndent(file, new FormattingIndent(2, false));
        manager.openDocument(file, root, "xml", "<a/>\n");
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pushedXml(FakeLanguageServer fake) {
        return (Map<String, Object>) ((Map<String, Object>)
                        FakeLanguageServer.last(fake.configurations).getSettings())
                .get("xml");
    }

    private Object ask(String section) throws Exception {
        ConfigurationItem item = new ConfigurationItem();
        item.setSection(section);
        item.setScopeUri(LspTestHooks.uri(file));
        return manager.sessionForTest(file)
                .configuration(new ConfigurationParams(List.of(item)))
                .get()
                .get(0);
    }

    @Test
    void anUntrustedFolderDownloadsNothingUntilItIsTrusted() throws Exception {
        assertEquals(1, fakes.size());
        assertEquals(false, ask("xml.downloadExternalResources.enabled"), "untrusted: no schema download");
        assertEquals(
                XmlServerSettings.cacheDir(config).toString(),
                ask("xml.server.workDir"),
                "the cache is under the config folder");

        trusted.set(true);
        manager.setXmlSettings(XmlServerSettings.of(new Settings(), config)); // what a trust decision's apply does
        assertEquals(true, ask("xml.downloadExternalResources.enabled"));
        assertEquals(
                Map.of("enabled", true),
                pushedXml(fakes.get(0)).get("downloadExternalResources"),
                "the running server is told");

        Settings never = new Settings();
        never.setXmlSchemaDownloads("never");
        manager.setXmlSettings(XmlServerSettings.of(never, config));
        assertEquals(Map.of("enabled", false), pushedXml(fakes.get(0)).get("downloadExternalResources"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void formattingIsSwitchedOnInEveryWayTheServerReadsIt() throws Exception {
        // lemminx registers formatting only when xml.format.enabled is true, and reads a missing key as false
        // (XML review 2 C1): Format Document fell back to the built-in formatter and Tab got no server indent.
        assertEquals(true, ask("xml.format.enabled"));
        assertEquals(2, ask("xml.format.preservedNewlines"));
        Settings wrap = new Settings();
        wrap.setXmlFormatWrap(true); // a changed setting is pushed with the whole object, enabled included
        manager.setXmlSettings(XmlServerSettings.of(wrap, config));
        Integer width = wrap.getFillColumn();
        long until = System.nanoTime() + 10_000_000_000L; // the push is a notification: it lands on another thread
        while (System.nanoTime() < until
                && (FakeLanguageServer.last(fakes.get(0).configurations) == null
                        || !width.equals(
                                ((Map<String, Object>) pushedXml(fakes.get(0)).get("format")).get("maxLineWidth")))) {
            Thread.sleep(20);
        }
        Map<String, Object> format =
                (Map<String, Object>) pushedXml(fakes.get(0)).get("format");
        assertEquals(width, format.get("maxLineWidth"));
        assertEquals(true, format.get("enabled"), "the didChangeConfiguration push");
        assertEquals(true, format.get("preserveAttributeLineBreaks"));
    }

    @Test
    void indentationIsAnsweredFromTheDocument() throws Exception {
        assertEquals(2, ask("xml.format.tabSize"));
        assertEquals(false, ask("xml.format.insertSpaces"));
        assertEquals("ignore", ask("xml.validation.noGrammar"));
        assertTrue(ask("xml.catalogs") instanceof List<?>);
    }
}
