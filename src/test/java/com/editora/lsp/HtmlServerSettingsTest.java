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
        assertEquals(Boolean.FALSE, format.get("endWithNewline"), "VS Code's: Editora keeps the final newline (B104)");
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
     * {@code insert_final_newline} decides; without one, the text as it is now. B104: the server is always told
     * {@code endWithNewline: false} (it reached every {@code <style>}'s CSS beautifier) and Editora applies this.
     */
    @Test
    void theFinalNewlineFollowsEditorConfigElseTheText() {
        var user = HtmlServerSettings.defaults();
        var indent = new FormattingIndent(2, true);
        assertEquals(Boolean.TRUE, user.finalNewline(indent.withDocument(null, true)));
        assertEquals(Boolean.FALSE, user.finalNewline(indent.withDocument(null, false)));
        assertEquals(
                Boolean.TRUE, user.finalNewline(indent.withDocument(null, false).withSaveRules(null, true)));
        assertEquals(
                Boolean.FALSE, user.finalNewline(indent.withDocument(null, true).withSaveRules(null, false)));
        assertNull(user.finalNewline(indent), "unknown text (Tab re-indent): left as the server made it");
        assertNull(user.finalNewline(indent.withSaveRules(null, true)), "nor for a save rule alone");
        for (var doc : java.util.List.of(indent.withDocument(null, true), indent.withSaveRules(null, true))) {
            assertEquals(Boolean.FALSE, format(user.section(doc)).get("endWithNewline"));
        }
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
        assertEquals(Boolean.FALSE, options.getBoolean("endWithNewline"), "B104: never the server's to add");
        var plain = HtmlServerSettings.defaults().formattingOptions(new FormattingIndent(2, true));
        assertEquals(120, plain.getNumber("wrapLineLength").intValue());
        assertEquals(Boolean.FALSE, plain.getBoolean("endWithNewline"));
        // HTML review 3 A205: insert_final_newline is Editora's to keep, so the server is not told it either
        var rules = HtmlServerSettings.defaults()
                .formattingOptions(new FormattingIndent(2, true).withSaveRules(true, true));
        assertEquals(Boolean.TRUE, rules.getBoolean("trimTrailingWhitespace"));
        assertFalse(rules.containsKey("insertFinalNewline"), String.valueOf(rules));
        assertEquals(
                Boolean.TRUE,
                new FormattingIndent(2, true)
                        .withSaveRules(true, true)
                        .options()
                        .getBoolean("insertFinalNewline"),
                "other servers still are");
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
        assertEquals(Boolean.FALSE, format(html).get("endWithNewline"), "Editora's to apply (B104)");
        assertEquals(Boolean.FALSE, format(html).get("templating"));
        assertFalse(format(html).containsKey("nonsense"));
        assertEquals(Map.of("scripts", true, "styles", false), html.get("validate"));
        assertEquals(Boolean.TRUE, html.get("autoClosingTags"), "D102: Editora's own auto-close, not the project's");
        assertFalse(html.containsKey("customData"));
        // The project's explicit choice beats the text's last character; EditorConfig beats both.
        var indent = new FormattingIndent(2, true).withDocument(70, false);
        assertEquals(Boolean.TRUE, settings.finalNewline(indent));
        assertEquals(70, format(settings.section(indent)).get("wrapLineLength"));
        assertEquals(Boolean.FALSE, settings.finalNewline(indent.withSaveRules(null, false)));
        // A107: what was not taken is named, as dotted keys.
        assertEquals(
                java.util.Set.of("format.templating", "format.nonsense", "autoClosingTags", "customData"),
                java.util.Set.copyOf(settings.ignoredProjectKeys()));
        assertEquals(java.util.List.of(), HtmlServerSettings.defaults().ignoredProjectKeys());
    }

    /**
     * D102: a project's {@code autoClosingTags} (copied from a VS Code setup, where {@code false} means "VS Code,
     * don't close") must not bring back the server's closer while Editora closes tags itself, nor hide it from a
     * user who turned Editora's auto-close off.
     */
    @Test
    void autoClosingTagsAlwaysFollowsEditorasOwnSwitch() {
        Settings off = new Settings();
        off.setAutoCloseTags(false);
        assertEquals(
                Boolean.TRUE,
                HtmlServerSettings.defaults()
                        .withProject(Map.of("autoClosingTags", false))
                        .section(null)
                        .get("autoClosingTags"));
        assertEquals(
                Boolean.FALSE,
                HtmlServerSettings.of(off)
                        .withProject(Map.of("autoClosingTags", true))
                        .section(null)
                        .get("autoClosingTags"));
    }

    /**
     * A107: VS Code's dotted keys work as well as nested ones — {@code "format.wrapAttributes"} in the object (and
     * the top-level {@code "html.format.wrapAttributes"}, which {@code ProjectSettings} hands over the same way).
     */
    @Test
    void dottedKeysAreTakenLikeNestedOnes() {
        Map<String, Object> project = new java.util.LinkedHashMap<>();
        project.put("format", Map.of("wrapLineLength", 90));
        project.put("format.wrapAttributes", "force-expand-multiline");
        project.put("validate.styles", false);
        project.put("format.endWithNewline", false);
        project.put("format.wrapAtributes", "force"); // misspelt
        var settings = HtmlServerSettings.defaults().withProject(project);
        Map<String, Object> html = settings.section(null);
        assertEquals(90, format(html).get("wrapLineLength"));
        assertEquals("force-expand-multiline", format(html).get("wrapAttributes"));
        assertEquals(Map.of("scripts", true, "styles", false), html.get("validate"));
        assertEquals(Boolean.FALSE, settings.finalNewline(new FormattingIndent(2, true).withDocument(null, true)));
        assertEquals(java.util.List.of("format.wrapAtributes"), settings.ignoredProjectKeys());
        assertEquals(
                Map.of("a", Map.of("b", 1, "c", 2)),
                HtmlServerSettings.unflatten(Map.of("a", Map.of("b", 1), "a.c", 2)),
                "nested and dotted keys of one object are merged");
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> embeddedFormat(Object section) {
        return (Map<String, Object>) ((Map<String, Object>) section).get("format");
    }

    /**
     * The four sections the server asks for, and a dotted path into the html object. B105: {@code javascript} is
     * VS Code's format defaults — with {@code {}} the server's TypeScript formatter dropped the spaces inside
     * non-empty braces ({@code function f() {return 1} }) — and {@code css} too, for parity.
     */
    @Test
    void theServersFourSectionsAreAnswered() {
        var settings = HtmlServerSettings.defaults();
        Map<String, Object> js = embeddedFormat(settings.answer("javascript", null));
        assertEquals(Boolean.TRUE, js.get("insertSpaceAfterOpeningAndBeforeClosingNonemptyBraces"));
        assertEquals(Boolean.TRUE, js.get("insertSpaceAfterOpeningAndBeforeClosingEmptyBraces"));
        assertEquals(Boolean.TRUE, js.get("insertSpaceAfterKeywordsInControlFlowStatements"));
        assertEquals(Boolean.FALSE, js.get("insertSpaceBeforeFunctionParenthesis"));
        assertEquals(Boolean.FALSE, js.get("placeOpenBraceOnNewLineForFunctions"));
        assertEquals("ignore", js.get("semicolons"));
        Map<String, Object> css = embeddedFormat(settings.answer("css", null));
        assertEquals("collapse", css.get("braceStyle"));
        assertEquals(Boolean.TRUE, css.get("newlineBetweenRules"));
        js.put("semicolons", "insert");
        assertEquals(
                "ignore", embeddedFormat(settings.answer("javascript", null)).get("semicolons"), "a copy");
        assertNull(settings.answer("js/ts", null), "{} makes the server's JavaScript mode throw");
        assertTrue(settings.answer("html", null) instanceof Map);
        assertEquals("pre,code,textarea", settings.answer("html.format.contentUnformatted", null));
        assertNull(settings.answer("html.format.nope", null));
        assertNull(settings.answer("editor", null));
        assertNull(settings.answer(null, null));
    }

    /** The push: the section with no document facts, and the embedded CSS and JavaScript ones (B105). */
    @Test
    void thePushCarriesTheSectionAndTheEmbeddedSections() {
        Map<String, Object> push = HtmlServerSettings.defaults().push();
        assertEquals(java.util.Set.of("html", "css", "javascript"), push.keySet());
        assertEquals(HtmlServerSettings.defaults().answer("css", null), push.get("css"));
        assertEquals(HtmlServerSettings.defaults().answer("javascript", null), push.get("javascript"));
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
