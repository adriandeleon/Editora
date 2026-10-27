package com.editora.ui;

import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A file with no telling extension that opens with an XML declaration becomes an XML buffer — XML grammar, XML spell
 * mode, the XML server — the way a shebang promotes a script (XML review A8). A {@code .txt} file stays text.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class XmlDeclarationSniffFxTest {

    private static final String MANIFEST =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest package=\"com.example\"/>\n";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private String languageOf(String path, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setPath(Path.of(path));
            buf.setContent(text);
            String language = buf.getLanguage();
            buf.dispose();
            return language;
        });
    }

    @Test
    void anExtensionlessXmlFileIsPromotedToXmlOnLoad() throws Exception {
        assertEquals("xml", languageOf("/tmp/app/AndroidManifest", MANIFEST));
        assertEquals("xml", languageOf("/tmp/app/layout.jrxml-old", MANIFEST), "an unknown extension is sniffed too");
    }

    @Test
    void aTextFileOrAKnownLanguageKeepsItsOwn() throws Exception {
        assertEquals("plaintext", languageOf("/tmp/app/output.txt", MANIFEST));
        assertEquals("json", languageOf("/tmp/app/data.json", MANIFEST));
        assertEquals("plaintext", languageOf("/tmp/app/README", "Not markup.\n"));
    }
}
