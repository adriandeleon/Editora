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
    void syntaxAPageOnlyShowsOrHoldsAsDataDoesNot() {
        // HTML review 3 C207: a page documenting Jinja, and a Backbone page's Underscore template.
        assertFalse(HtmlElements.hasTemplateSyntax(
                "<pre><code>{% for x in y %}{% endfor %}</code></pre>\n<div>\n<p>unclosed div\n"));
        assertFalse(HtmlElements.hasTemplateSyntax(
                "<script type=\"text/template\" id=\"t\"><% _.each(items, function(i) { %><li><%= i %></li>"
                        + "<% }); %></script>\n<span>\n"));
        assertFalse(HtmlElements.hasTemplateSyntax("<!-- {% if a %} -->\n<textarea>{{#each}}{{/each}}</textarea>"));
        assertFalse(HtmlElements.hasTemplateSyntax("<p>Write <code>{# a note #}</code> for a comment.</p>"));
        assertTrue(
                HtmlElements.hasTemplateSyntax("<pre>{% raw %}</pre>\n{% if a %}<div>{% endif %}"),
                "a template that also shows some");
        assertTrue(HtmlElements.hasTemplateSyntax("<a href=\"{% url 'x' %}\">x</a>"), "in an attribute it runs");
        assertTrue(HtmlElements.hasTemplateSyntax("<pre>{% if a %}"), "an unclosed <pre> hides nothing");
    }

    @Test
    void angularSyntaxIsFoundInMarkupOnly() {
        assertTrue(HtmlElements.hasAngularSyntax("@if (user) {\n<p>x</p>\n}"));
        assertTrue(HtmlElements.hasAngularSyntax("@defer {\n<big-chart />\n}"));
        assertTrue(HtmlElements.hasAngularSyntax("<li *ngFor=\"let i of items\">"));
        assertTrue(HtmlElements.hasAngularSyntax("<input [value]=\"v\">"));
        assertTrue(HtmlElements.hasAngularSyntax("<input [(ngModel)]='q'>"));
        assertTrue(HtmlElements.hasAngularSyntax("<button (click)=\"go()\">"));
        assertFalse(HtmlElements.hasAngularSyntax("<script>if (a) x[(i)]= 1; f (b)= 2</script>"), "code in a script");
        assertFalse(HtmlElements.hasAngularSyntax("<p>Mail me @if (you like).</p><pre>[x]=\"y\"</pre>"));
        assertFalse(HtmlElements.hasAngularSyntax("<button @click=\"go\" :class=\"c\">"), "Vue");
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
