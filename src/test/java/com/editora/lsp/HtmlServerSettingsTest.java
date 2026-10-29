package com.editora.lsp;

import java.nio.file.Path;
import java.util.Map;

import com.editora.config.Settings;
import com.editora.editorconfig.EditorConfigProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HTML server's {@code html} section: VS Code's defaults, the user's settings, the project's, the document's. */
class HtmlServerSettingsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> format(Map<String, Object> html) {
        return (Map<String, Object>) html.get("format");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> html, String key) {
        return (Map<String, Object>) html.get(key);
    }

    /**
     * B2: with {@code {}} the server protects {@code script} alone and Format Document collapses {@code <pre>} and
     * {@code <textarea>}; VS Code's defaults keep {@code pre,code,textarea} as written.
     */
    @Test
    void theSectionIsVsCodesDefaults() {
        Map<String, Object> html = HtmlServerSettings.defaults().section(null);
        Map<String, Object> format = format(html);
        assertEquals("pre,code,textarea", format.get("contentUnformatted"));
        assertEquals("wbr", format.get("unformatted"));
        assertEquals("head, body, /html", format.get("extraLiners"));
        assertEquals(120, format.get("wrapLineLength"));
        assertEquals("auto", format.get("wrapAttributes"));
        assertEquals(Boolean.TRUE, format.get("enable"));
        assertEquals(Boolean.FALSE, format.get("indentInnerHtml"));
        assertEquals(Boolean.TRUE, format.get("preserveNewLines"));
        assertEquals(Boolean.FALSE, format.get("templating"));
        assertFalse(format.containsKey("endWithNewline"), "no document: the formatter's own default");
        assertEquals(Map.of("html5", true), html.get("suggest"));
        assertEquals(Map.of("scripts", true, "styles", true), html.get("validate"));
        assertEquals(Map.of("documentation", true, "references", true), html.get("hover"));
        assertEquals(Map.of("attributeDefaultValue", "doublequotes"), html.get("completion"));
        assertEquals(Boolean.TRUE, html.get("autoClosingTags"));
    }

    /** The user's Settings → LSP → HTML values, and the editor's auto-close switch (D3). */
    @Test
    void theUsersSettingsReachTheSection() {
        Settings s = new Settings();
        s.setHtmlFormatWrapLineLength(100);
        s.setHtmlFormatWrapAttributes("force-aligned");
        s.setHtmlFormatContentUnformatted("pre; code  textarea, ,svg");
        s.setHtmlAttributeQuotes("singlequotes");
        s.setHtmlValidateScripts(false);
        s.setAutoCloseTags(false);
        Map<String, Object> html = HtmlServerSettings.of(s).section(null);
        assertEquals(100, format(html).get("wrapLineLength"));
        assertEquals("force-aligned", format(html).get("wrapAttributes"));
        assertEquals("pre,code,textarea,svg", format(html).get("contentUnformatted"));
        assertEquals("singlequotes", child(html, "completion").get("attributeDefaultValue"));
        assertEquals(Map.of("scripts", false, "styles", true), html.get("validate"));
        assertEquals(Boolean.FALSE, html.get("autoClosingTags"));
        assertEquals(HtmlServerSettings.defaults(), HtmlServerSettings.of(new Settings()));
    }

    /** A3, B10: the file's EditorConfig max_line_length wins over the setting; off = never wrap. */
    @Test
    void theWrapColumnIsTheFilesMaxLineLength() {
        var user = HtmlServerSettings.defaults();
        var indent = new FormattingIndent(2, true);
        assertEquals(120, format(user.section(indent)).get("wrapLineLength"));
        assertEquals(60, format(user.section(indent.withDocument(60, null))).get("wrapLineLength"));
        assertEquals(
                0,
                format(user.section(indent.withDocument(EditorConfigProperties.OFF, null)))
                        .get("wrapLineLength"));
    }

    /**
     * A3, D6: the formatter's default dropped the final newline on every format. The file's
     * {@code insert_final_newline} decides; without one, the text as it is now.
     */
    @Test
    void theFinalNewlineFollowsEditorConfigElseTheText() {
        var user = HtmlServerSettings.defaults();
        var indent = new FormattingIndent(2, true);
        assertEquals(
                Boolean.TRUE,
                format(user.section(indent.withDocument(null, true))).get("endWithNewline"));
        assertEquals(
                Boolean.FALSE,
                format(user.section(indent.withDocument(null, false))).get("endWithNewline"));
        assertEquals(
                Boolean.TRUE,
                format(user.section(indent.withDocument(null, false).withSaveRules(null, true)))
                        .get("endWithNewline"));
        assertEquals(
                Boolean.FALSE,
                format(user.section(indent.withDocument(null, true).withSaveRules(null, false)))
                        .get("endWithNewline"));
        assertFalse(format(user.section(indent)).containsKey("endWithNewline"), "unknown text: not said");
    }

    /**
     * The server caches a document's answer, so a formatting request carries the two per-document values as
     * options, which it lays over its settings.
     */
    @Test
    void aFormattingRequestCarriesTheDocumentsValues() {
        var options =
                HtmlServerSettings.defaults().formattingOptions(new FormattingIndent(4, false).withDocument(80, true));
        assertEquals(4, options.getTabSize());
        assertFalse(options.isInsertSpaces());
        assertEquals(80, options.getNumber("wrapLineLength").intValue());
        assertEquals(Boolean.TRUE, options.getBoolean("endWithNewline"));
        var plain = HtmlServerSettings.defaults().formattingOptions(new FormattingIndent(2, true));
        assertEquals(120, plain.getNumber("wrapLineLength").intValue());
        assertNull(plain.get("endWithNewline"));
    }

    /** A4, D2: a project's {@code html} object, in VS Code's key names, is laid over the user's settings. */
    @Test
    void aProjectsHtmlObjectIsLaidOver() {
        Map<String, Object> project = Map.of(
                "format",
                Map.of(
                        "wrapAttributes",
                        "force",
                        "wrapLineLength",
                        90,
                        "indentInnerHtml",
                        true,
                        "endWithNewline",
                        true,
                        "templating",
                        "yes", // wrong type: kept as it was
                        "nonsense",
                        1), // unknown: dropped
                "validate",
                Map.of("styles", false),
                "autoClosingTags",
                false,
                "customData",
                java.util.List.of("x.json")); // paths: not taken
        var settings = HtmlServerSettings.defaults().withProject(project);
        Map<String, Object> html = settings.section(null);
        assertEquals("force", format(html).get("wrapAttributes"));
        assertEquals(90, format(html).get("wrapLineLength"));
        assertEquals(Boolean.TRUE, format(html).get("indentInnerHtml"));
        assertEquals(Boolean.TRUE, format(html).get("endWithNewline"));
        assertEquals(Boolean.FALSE, format(html).get("templating"));
        assertFalse(format(html).containsKey("nonsense"));
        assertEquals(Map.of("scripts", true, "styles", false), html.get("validate"));
        assertEquals(Boolean.FALSE, html.get("autoClosingTags"));
        assertFalse(html.containsKey("customData"));
        // The project's explicit choice beats the text's last character; EditorConfig beats both.
        var indent = new FormattingIndent(2, true).withDocument(70, false);
        assertEquals(Boolean.TRUE, format(settings.section(indent)).get("endWithNewline"));
        assertEquals(70, format(settings.section(indent)).get("wrapLineLength"));
        assertEquals(
                Boolean.FALSE,
                format(settings.section(indent.withSaveRules(null, false))).get("endWithNewline"));
    }

    /** The project's settings serve only the servers on its root; a loose file's server gets the user's. */
    @Test
    void theProjectsSettingsAreForItsOwnRoot() {
        var user = HtmlServerSettings.defaults();
        var project = user.withProject(Map.of("format", Map.of("wrapLineLength", 80)));
        var forRoot = HtmlServerSettings.perRoot(project, user, Path.of("/work/site"));
        assertSame(project, forRoot.apply(Path.of("/work/site")));
        assertSame(project, forRoot.apply(Path.of("/work/site/docs")));
        assertSame(user, forRoot.apply(Path.of("/tmp/loose")));
        assertSame(user, HtmlServerSettings.perRoot(project, user, null).apply(Path.of("/work/site")));
    }

    /** The four sections the server asks for, and a dotted path into the html object. */
    @Test
    void theServersFourSectionsAreAnswered() {
        var settings = HtmlServerSettings.defaults();
        assertEquals(Map.of(), settings.answer("css", null));
        assertEquals(Map.of(), settings.answer("javascript", null));
        assertNull(settings.answer("js/ts", null), "{} makes the server's JavaScript mode throw");
        assertTrue(settings.answer("html", null) instanceof Map);
        assertEquals("pre,code,textarea", settings.answer("html.format.contentUnformatted", null));
        assertNull(settings.answer("html.format.nope", null));
        assertNull(settings.answer("editor", null));
        assertNull(settings.answer(null, null));
    }

    /** The push: the section with no document facts, and objects for the embedded CSS and JavaScript. */
    @Test
    void thePushCarriesTheSectionAndEmptyEmbeddedSections() {
        Map<String, Object> push = HtmlServerSettings.defaults().push();
        assertEquals(java.util.Set.of("html", "css", "javascript"), push.keySet());
        assertEquals(Map.of(), push.get("css"));
        assertEquals(HtmlServerSettings.defaults().section(null), push.get("html"));
    }

    /** Hand-edited values are kept within what the server takes. */
    @Test
    void settingsValuesAreKeptInRange() {
        Settings s = new Settings();
        s.setHtmlFormatWrapLineLength(-5);
        assertEquals(0, s.getHtmlFormatWrapLineLength());
        s.setHtmlFormatWrapAttributes("sideways");
        assertEquals("auto", s.getHtmlFormatWrapAttributes());
        s.setHtmlAttributeQuotes(null);
        assertEquals("doublequotes", s.getHtmlAttributeQuotes());
        s.setHtmlFormatContentUnformatted("");
        assertEquals("", s.getHtmlFormatContentUnformatted());
    }
}
