package com.editora.editops;

import java.nio.file.Path;

import com.editora.editops.Commenter.Edit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Toggle Comment on template lines writes the engine's own comment, which stops the code, where {@code <!-- -->}
 * only hid its output; and a {@code <?php} line is PHP (HTML review 2 C109).
 */
class CommenterTemplateTest {

    private static String apply(String text, int selStart, int selEnd, String lang, String file) {
        Edit e = Commenter.toggle(text, selStart, selEnd, lang, Path.of(file));
        return e == null ? null : text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    /** Toggles twice and checks the second toggle restores the text; returns the commented form. */
    private static String roundTrip(String text, int selStart, int selEnd, String lang, String file) {
        Path path = Path.of(file);
        Edit e = Commenter.toggle(text, selStart, selEnd, lang, path);
        if (e == null) {
            return null;
        }
        String once = text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
        Edit back = Commenter.toggle(once, e.selStart(), e.selEnd(), lang, path);
        assertTrue(back != null, once);
        assertEquals(text, once.substring(0, back.from()) + back.replacement() + once.substring(back.to()), once);
        return once;
    }

    @Test
    void jinjaTwigAndNunjucksLinesGetABraceHashComment() {
        assertEquals(
                "{# {% for x in items %}<li>{{ x }}</li>{% endfor %} #}\n",
                roundTrip("{% for x in items %}<li>{{ x }}</li>{% endfor %}\n", 0, 0, "html-template", "a.jinja"));
        assertEquals(
                "{# {% extends \"base.html\" %} #}\n<p>x</p>\n",
                roundTrip("{% extends \"base.html\" %}\n<p>x</p>\n", 0, 0, "html-template", "a.twig"));
        String two = "{% if a %}\n  <b>x</b>\n{% endif %}\n";
        assertEquals(
                "{# {% if a %}\n  <b>x</b>\n{% endif %} #}\n",
                roundTrip(two, 0, two.length() - 1, "html-template", "page.njk"), "Jinja's comment runs over lines");
        assertEquals("<!-- <p>x</p> -->\n", roundTrip("<p>x</p>\n", 0, 0, "html-template", "a.j2"), "plain markup");
        assertNull(apply("{# a #} {{ x }}\n", 0, 0, "html-template", "a.jinja"), "comments do not nest");
        assertEquals(
                "{{ x }}\n",
                apply("<!-- {{ x }} -->\n", 0, 0, "html-template", "a.jinja"),
                "an HTML comment is still uncommented");
    }

    @Test
    void aDjangoOrJinjaHtmlPageCommentsEachLineOnItsOwn() {
        String text = "{% load static %}\n{% if a %}\n  <div>\n{% endif %}\n";
        int from = text.indexOf("{% if");
        int to = text.indexOf("{% endif %}") + 4;
        assertEquals(
                "{% load static %}\n{# {% if a %} #}\n  {# <div> #}\n{# {% endif %} #}\n",
                roundTrip(text, from, to, "html", "/site/templates/base.html"), "Django's {# #} is one line only");
        assertEquals(
                "{# {% if a %}<b>{% endif %} #}\n",
                roundTrip("{% if a %}<b>{% endif %}\n", 3, 3, "html", "/site/templates/base.html"));
        assertNull(apply("{% if a %}\n{# x #} {{ y }}\n", 0, 20, "html", "/s/base.html"), "would nest");
    }

    @Test
    void erbAndEjsTagsBecomeCommentTags() {
        String ejs = "<% if (user) { %>\n<li><%= user.name %></li>\n<% } %>\n";
        assertEquals(
                "<%# if (user) { %>\n<li><%= user.name %></li>\n<% } %>\n",
                roundTrip(ejs, 0, 0, "html-template", "view.ejs"));
        assertEquals(
                "<%# if (user) { %>\n<li><%#= user.name %></li>\n<%# } %>\n",
                roundTrip(ejs, 0, ejs.length() - 1, "html-template", "show.html.erb"),
                "wrapping in <%# %> would end at the first %>");
        Edit caret = Commenter.toggle("<li><%= x %></li>\n", 6, 6, "html-template", Path.of("v.erb"));
        assertEquals(7, caret.selStart(), "the caret stays on its character");
        assertEquals(
                "<%# a %> <%%= literal %>\n",
                roundTrip("<% a %> <%%= literal %>\n", 0, 0, "html-template", "v.erb"), "<%% is a literal, not a tag");
        assertEquals(
                "<%#= x %>\n",
                roundTrip("<%= x %>\n", 0, 0, "html", "/app/views/x.html"), "an .html page that uses ERB");
        assertEquals(
                "<!-- <p>{{ x }}</p> -->\n",
                roundTrip("<p>{{ x }}</p>\n", 0, 0, "html-template", "v.erb"),
                "braces mean nothing to ERB");
        assertNull(apply("<p><%%= shown %></p>\n", 0, 0, "html-template", "v.erb"), "only a literal <%%");
    }

    @Test
    void handlebarsLiquidAndGoTemplatesUseTheirOwnComments() {
        assertEquals(
                "{{!-- {{#if a}}<b>x</b>{{/if}} --}}\n",
                roundTrip("{{#if a}}<b>x</b>{{/if}}\n", 0, 0, "handlebars", "a.hbs"));
        assertEquals(
                "{% comment %} {% include 'x.html' %} {% endcomment %}\n",
                roundTrip("{% include 'x.html' %}\n", 0, 0, "html-template", "a.liquid"));
        String jekyll = "---\ntitle: x\n---\n{% include header.html %}\n";
        assertEquals(
                "---\ntitle: x\n---\n{% comment %} {% include header.html %} {% endcomment %}\n",
                roundTrip(jekyll, jekyll.indexOf("{%"), jekyll.indexOf("{%"), "html", "/site/index.html"),
                "a page with front matter is Jekyll's Liquid");
        assertEquals(
                "{% comment %} {% include nav.html %} {% endcomment %}\n",
                roundTrip("{% include nav.html %}\n", 0, 0, "html", "/site/_includes/head.html"));
        assertEquals(
                "{{/* {{ if .IsHome }}<div class=\"hero\">{{ end }} */}}\n",
                roundTrip("{{ if .IsHome }}<div class=\"hero\">{{ end }}\n", 0, 0, "html", "/s/layouts/head.html"),
                "Hugo");
        assertEquals(
                "{{!-- {{#each items}}<li>{{this}}</li>{{/each}} --}}\n",
                roundTrip("{{#each items}}<li>{{this}}</li>{{/each}}\n", 0, 0, "html", "/s/list.html"));
        assertEquals(
                "<!-- <p>{{ x }}</p> -->\n",
                roundTrip("<p>{{ x }}</p>\n", 0, 0, "html", "/s/app.component.html"),
                "Angular's {{ value }} is HTML: its comment is <!-- -->");
    }

    @Test
    void aPhpOpenerLineIsPhp() {
        assertEquals(
                "<?php /* echo 1; */ ?>\n<p>x</p>\n",
                roundTrip("<?php echo 1; ?>\n<p>x</p>\n", 0, 0, "php", "a.php"),
                "the code after the opener is commented, not the opener");
        String block = "<?php\n$x = 1;\n$y = 2;\n?>\n";
        assertEquals(
                "<?php\n/* $x = 1;\n$y = 2; */\n?>\n",
                roundTrip(block, 0, block.indexOf("2;") + 2, "php", "a.php"),
                "from the opener line: the code lines below it");
        assertNull(apply(block, 0, 0, "php", "a.php"), "the opener alone");
        assertTrue(Commenter.onPhpOpener(block, 0, 0, "php"));
        assertNull(apply("<?= $x ?>\n", 0, 0, "php", "a.php"), "an echo has no statement to comment");
        assertTrue(Commenter.onPhpOpener("<?= $x ?>\n", 0, 0, "php"));
        assertFalse(Commenter.onPhpOpener(block, 0, block.indexOf("2;"), "php"), "a selection into the code is fine");
        assertFalse(Commenter.onPhpOpener("<?php echo 1; ?>", 0, 0, "php"));
        assertFalse(Commenter.onPhpOpener(block, 0, 0, "html"));
        assertEquals(
                "<!-- <p>x</p> -->\n<?php\n",
                roundTrip("<p>x</p>\n<?php\n", 0, 0, "php", "a.php"),
                "markup above the opener is still markup");
    }
}
