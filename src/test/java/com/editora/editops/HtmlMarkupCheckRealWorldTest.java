package com.editora.editops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The markup check on the stock pages of real set-ups (HTML review 2 C101–C103, reviewer C's real-world fixtures,
 * trimmed): Django, Jekyll and Hugo templates, the Vite, Create React App and Angular entry pages with their
 * {@code public/} folders, and a minified page. Each is written to a temporary project, so links are looked up on
 * disk as in the editor.
 */
class HtmlMarkupCheckRealWorldTest {

    @TempDir
    Path root;

    private Path write(String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private List<String> problems(Path page, Path projectRoot) throws IOException {
        return HtmlMarkupCheck.check(Files.readString(page), page, projectRoot, Files::exists).stream()
                .map(p -> p.kind() + " " + p.subject())
                .toList();
    }

    @Test
    void aDjangoBaseTemplateWithAnIfElseWrapperAndATemplateCommentIsClean() throws IOException {
        write("css/site.css", "");
        Path base = write("site/pages/base.html", """
                {% load static %}
                <!DOCTYPE html>
                <html>
                <head>
                  <link rel="stylesheet" href="{% static 'css/site.css' %}">
                  <title>{% block title %}Site{% endblock %}</title>
                </head>
                <body>
                  {# <div> a commented-out wrapper #}
                  {% if user.is_authenticated %}
                    <div class="nav logged-in">
                  {% else %}
                    <div class="nav">
                  {% endif %}
                      <a href="{% url 'home' %}">Home</a>
                    </div>
                  {% if items|length < 3 %}<p>few</p>{% endif %}
                  <img src="missing.png">
                </body>
                </html>
                """);
        assertEquals(List.of("MISSING_FILE missing.png"), problems(base, root), "the links are still checked");
    }

    @Test
    void headerAndFooterPartialsInATemplatesFolderAreNotCheckedForBalance() throws IOException {
        Path header =
                write("app/templates/header.html", "<header class=\"site\">\n  <div class=\"wrap\">\n    <nav>\n");
        Path footer = write("app/templates/footer.html", "    </nav>\n  </div>\n</header>\n");
        assertEquals(List.of(), problems(header, root));
        assertEquals(List.of(), problems(footer, root));
        Path page = write("app/pages/broken.html", "<header class=\"site\">\n  <div class=\"wrap\">\n</header>\n");
        assertEquals(List.of("UNCLOSED div"), problems(page, root), "outside a template folder it is checked");
        assertTrue(HtmlMarkupCheck.inTemplateFolder(root.resolve("_layouts/default.html"), null));
        assertFalse(
                HtmlMarkupCheck.inTemplateFolder(
                        root.resolve("templates/site/index.html"), root.resolve("templates/site")),
                "a project that is itself named templates is checked");
    }

    @Test
    void hugoAndHandlebarsBranchesAreNotCheckedForBalance() throws IOException {
        Path hugo = write("hugo/head.html", """
                <head>
                  {{ with .Params.css }}<link rel="stylesheet" href="{{ . }}">{{ end }}
                  {{ if .IsHome }}<div class="hero">{{ else }}<div class="page">{{ end }}
                </head>
                """);
        assertEquals(List.of(), problems(hugo, root));
        Path hbs = write("ember/list.html", "{{#if open}}<ul class=\"a\">{{else}}<ul class=\"b\">{{/if}}<li>x</ul>\n");
        assertEquals(List.of(), problems(hbs, root));
        Path angular = write("ng/item.html", "<p>{{ item.name }}</p><div>\n");
        assertEquals(List.of("UNCLOSED div"), problems(angular, root), "a {{ value }} alone is not a template");
    }

    @Test
    void aJekyllLayoutFindsItsSassStylesheetAndSkipsTemplatedLinks() throws IOException {
        write("assets/css/style.scss", "");
        Path layout = write("_layouts/default.html", """
                ---
                layout: none
                ---
                <!DOCTYPE html>
                <html>
                <head>
                  <link rel="stylesheet" href="/assets/css/style.css">
                  <link rel="stylesheet" href="{{ '/assets/main.css' | relative_url }}">
                </head>
                <body>
                  {% include header.html %}
                  {{ content }}
                </body>
                </html>
                """);
        assertEquals(List.of(), problems(layout, root));
    }

    @Test
    void viteServesPublicAtTheRoot() throws IOException {
        write("public/vite.svg", "<svg/>");
        write("src/main.jsx", "");
        Path index = write("index.html", """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="UTF-8" />
                    <link rel="icon" type="image/svg+xml" href="/vite.svg" />
                    <title>Vite + React</title>
                  </head>
                  <body>
                    <div id="root"></div>
                    <script type="module" src="/src/main.jsx"></script>
                    <img src="/gone.svg">
                  </body>
                </html>
                """);
        assertEquals(List.of("MISSING_FILE /gone.svg"), problems(index, root));
        assertEquals(List.of("MISSING_FILE /gone.svg"), problems(index, null), "no project: the page's folder");
    }

    @Test
    void createReactAppPlaceholdersAreTemplated() throws IOException {
        write("public/favicon.ico", "");
        Path index = write("public/index.html", """
                <!DOCTYPE html>
                <html lang="en">
                  <head>
                    <link rel="icon" href="%PUBLIC_URL%/favicon.ico" />
                    <link rel="apple-touch-icon" href="%PUBLIC_URL%/logo192.png" />
                    <link rel="manifest" href="%PUBLIC_URL%/manifest.json" />
                    <title>React App</title>
                  </head>
                  <body><noscript>You need to enable JavaScript to run this app.</noscript><div id="root"></div></body>
                </html>
                """);
        assertEquals(List.of(), problems(index, root));
        assertTrue(HtmlLinks.templated("%PUBLIC_URL%/x.png"));
        assertFalse(HtmlLinks.templated("caf%C3%A9%20menu.html"), "a percent escape is not a placeholder");
    }

    @Test
    void angularFindsItsFaviconInPublicThroughTheBaseHref() throws IOException {
        write("public/favicon.ico", "");
        Path index = write("src/index.html", """
                <!doctype html>
                <html lang="en">
                <head>
                  <base href="/">
                  <link rel="icon" type="image/x-icon" href="favicon.ico">
                </head>
                <body><app-root></app-root></body>
                </html>
                """);
        assertEquals(List.of(), problems(index, root));
        Path relative = write("static-site/page.html", "<img src=\"img/a.png\"><img src=\"../../outside.png\">");
        write("static/img/a.png", "");
        assertEquals(List.of("MISSING_FILE ../../outside.png"), problems(relative, root));
    }

    @Test
    void minifiedUnquotedValuesEndingInASlashDoNotCloseTheirElement() throws IOException {
        Path page = write("min.html", """
                <p><a href=https://example.com/>minified link</a>
                <a href=/about/>root link</a>
                <image src="old.png">
                <svg><image href="a.png"></image><path d="M0 0"/><g/></svg>
                <math><mspace/></math>
                <div/>
                </p>
                """);
        write("old.png", "");
        write("a.png", "");
        assertEquals(List.of("UNCLOSED div"), problems(page, root), "<div/> stays open in HTML");
    }
}
