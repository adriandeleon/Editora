package com.editora.editor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.editora.editor.highlight.GrammarRegistry;
import com.editora.text.LanguageNames;
import com.editora.text.LanguageRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTML templates open as HTML for Editora's own features; the snippets and templates are fixed (C7, C12). */
class HtmlTemplateLanguagesTest {

    private static final GrammarRegistry GRAMMARS = GrammarRegistry.shared();

    @Test
    void templateFilesAreHtmlTemplatesColouredAsHtml() {
        for (String name : new String[] {
            "view.html.erb",
            "view.ejs",
            "page.njk",
            "base.jinja",
            "base.jinja2",
            "page.html.j2",
            "tpl.twig",
            "theme.liquid",
            "card.mustache"
        }) {
            assertEquals("html-template", LanguageRegistry.forFileName(name), name);
            assertEquals("text.html.basic", GRAMMARS.forFileName(name).getScopeName(), name);
        }
        assertEquals("handlebars", LanguageRegistry.forFileName("list.hbs"));
        assertEquals("handlebars", LanguageRegistry.forFileName("list.handlebars"));
        assertEquals("text.html.basic", GRAMMARS.forFileName("list.hbs").getScopeName());
        assertEquals("HTML Template", LanguageNames.display("html-template"));
        assertEquals("Handlebars", LanguageNames.display("handlebars"));
        assertNotNull(GRAMMARS.forLanguageName("html-template"), "pickable in the language menu");
        // Never the HTML server for template syntax, except Handlebars, which it serves (as VS Code does).
        assertEquals(null, com.editora.lsp.LspServerRegistry.serverIdFor("html-template"));
        assertEquals("html", com.editora.lsp.LspServerRegistry.serverIdFor("handlebars"));
    }

    @Test
    void aTemplateNamedAfterAnotherFileIsThatFile() {
        assertEquals("ini", LanguageRegistry.forFileName("nginx.conf.j2"));
        assertEquals("yaml", LanguageRegistry.forFileName("config.yml.erb"));
        assertEquals("css", LanguageRegistry.forFileName("theme.css.liquid"));
        assertEquals("shell", LanguageRegistry.forFileName("deploy.sh.j2"));
        assertEquals("source.yaml", GRAMMARS.forFileName("config.yml.erb").getScopeName());
        assertEquals("html-template", LanguageRegistry.forFileName("odd.unknownext.j2"));
        assertEquals("html-template", LanguageRegistry.forFileName(".j2"), "no inner name");
    }

    @Test
    void theHtmlSnippetsAndTemplatesAreFixed() throws Exception {
        String snippets = resource("/com/editora/snippets/html.json");
        assertTrue(snippets.contains("<html lang=\\\"${1:en}\\\">"), "! selects en");
        assertTrue(snippets.contains("\"<!DOCTYPE html>\", \"$0\""), "a valid doctype");
        assertTrue(snippets.contains("<link rel=\\\"${1:stylesheet}\\\" href=\\\"$2\\\">$0"));
        for (String obsolete : new String[] {"\"big\"", "\"command\"", "\"keygen\"", "\"menuitem\""}) {
            assertFalse(snippets.contains(obsolete + ": {"), obsolete);
        }
        for (String template :
                new String[] {"/com/editora/templates/html-page.json", "/com/editora/templates/html-bundle.json"}) {
            String body = resource(template);
            assertTrue(body.contains("<!DOCTYPE html>"), template);
            assertTrue(body.contains("<meta charset=\\\"UTF-8\\\">"), template);
            assertTrue(body.contains("name=\\\"viewport\\\""), template);
        }
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = HtmlTemplateLanguagesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
