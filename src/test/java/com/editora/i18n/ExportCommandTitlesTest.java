package com.editora.i18n;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commands that export a file's preview read as one family in the palette, in every language: the same
 * group prefix as the other export and print commands, and an ellipsis, because each opens a Save dialog.
 * (They were "Preview: Export to HTML", "File: Export Preview to Word (.docx)" and "File: Export Rendered
 * Preview to PDF…" — three shapes, one with the ellipsis.)
 */
class ExportCommandTitlesTest {

    private static final List<String> EXPORTS =
            List.of("preview.exportPdf", "preview.exportDocx", "preview.exportOdt", "preview.exportHtml");

    private static Properties catalog(String suffix) throws Exception {
        Properties p = new Properties();
        try (InputStream in = ExportCommandTitlesTest.class.getResourceAsStream(
                "/com/editora/i18n/messages" + suffix + ".properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static String group(String title) {
        return title.substring(0, title.indexOf(':')).strip();
    }

    @Test
    void thePreviewExportsAreOneFamilyInEveryLanguage() throws Exception {
        for (String suffix : List.of("", "_de", "_es", "_fr", "_it", "_pt")) {
            Properties p = catalog(suffix);
            String file = group(p.getProperty("command.editor.exportPdf"));
            for (String id : EXPORTS) {
                String title = p.getProperty("command." + id);
                assertEquals(file, group(title), suffix + " " + id + ": " + title);
                assertTrue(title.endsWith("…"), suffix + " " + id + " opens a Save dialog: " + title);
            }
            assertEquals(file, group(p.getProperty("command.preview.print")), suffix);
        }
    }

    @Test
    void theEnglishTitlesNameTheFormatTheSameWay() throws Exception {
        Properties p = catalog("");
        assertEquals("File: Export Preview to PDF…", p.getProperty("command.preview.exportPdf"));
        assertEquals("File: Export Preview to Word (.docx)…", p.getProperty("command.preview.exportDocx"));
        assertEquals("File: Export Preview to OpenDocument (.odt)…", p.getProperty("command.preview.exportOdt"));
        assertEquals("File: Export Preview to HTML…", p.getProperty("command.preview.exportHtml"));
    }
}
