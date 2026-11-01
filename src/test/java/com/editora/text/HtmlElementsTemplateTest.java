package com.editora.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared "this page uses template syntax" test (HTML review 2 C101, C108) and the HTML-only void elements. */
class HtmlElementsTemplateTest {

    @Test
    void templateTagsCommentsAndBlockFormsCount() {
        assertTrue(HtmlElements.hasTemplateSyntax("{% if user %}<div>{% endif %}"), "Django, Jinja, Liquid");
        assertTrue(HtmlElements.hasTemplateSyntax("<body>\n{# <div> a wrapper #}\n</body>"), "a template comment");
        assertTrue(HtmlElements.hasTemplateSyntax("<% if (a) { %><div><% } %>"), "ERB, EJS");
        assertTrue(HtmlElements.hasTemplateSyntax("{{#if a}}<ul>{{else}}<ol>{{/if}}"), "Handlebars");
        assertTrue(HtmlElements.hasTemplateSyntax("{{^empty}}<p>{{/empty}}"), "a Mustache inverted section");
        assertTrue(HtmlElements.hasTemplateSyntax("{{!-- <div> --}}"), "a Handlebars comment");
        assertTrue(HtmlElements.hasTemplateSyntax("{{ if .IsHome }}<div>{{ end }}"), "Go, Hugo");
        assertTrue(HtmlElements.hasTemplateSyntax("{{- range .Pages }}<li>{{ end -}}"), "Go with trim markers");
    }

    @Test
    void valuesAndLookalikesDoNot() {
        assertFalse(HtmlElements.hasTemplateSyntax("<p>{{ item.name }}</p>"), "Angular, Vue: a value only");
        assertFalse(
                HtmlElements.hasTemplateSyntax("<script>class A {#x = 1;\n get x() { return 1 }}</script>"),
                "a JavaScript private field");
        assertFalse(HtmlElements.hasTemplateSyntax("<style>a{width:100%}</style>"), "a CSS percentage");
        assertFalse(HtmlElements.hasTemplateSyntax("{% never closed"), "an opener alone");
        assertFalse(HtmlElements.hasTemplateSyntax("5 <% of them"), "a less-than percent alone");
        assertFalse(HtmlElements.hasTemplateSyntax("<p>plain</p>"));
    }

    @Test
    void imageIsVoidInHtml() {
        assertTrue(HtmlElements.isVoidInHtml("IMAGE"));
        assertTrue(HtmlElements.isVoidInHtml("br"));
        assertFalse(HtmlElements.isVoidInHtml("div"));
        assertFalse(HtmlElements.isVoid("image"), "the shared set is unchanged: SVG's <image> has an end tag");
        assertTrue(HtmlElements.isInline("price-tag"), "custom elements are inline");
        assertFalse(HtmlElements.isInline("DIV"));
    }
}
