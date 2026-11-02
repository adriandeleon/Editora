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
    }

    @Test
    void angularComponentTemplatesCloseComponentsWithASlashAndLinkFromTheApp() throws IOException {
        // HTML review 3 C201, reviewer C's Angular 17 app (rw/angular), trimmed.
        write("angular.json", "{}");
        write(
                "src/index.html",
                "<!doctype html>\n<html><head><base href=\"/\"></head><body><app-root></app-root>"
                        + "</body></html>\n");
        write("src/assets/logo.svg", "<svg/>");
        write("public/favicon.ico", "");
        Path app = write("src/app/app.component.html", """
                <app-header />
                <main class="content">
                  @if (user(); as u) {
                    <app-profile [user]="u" (logout)="onLogout()" />
                    <p>Welcome back, {{ u.name }}!</p>
                  } @else {
                    <app-login-form (submitted)="login($event)"></app-login-form>
                  }
                  <ul>
                    @for (item of items(); track item.id) {
                      <li [class.done]="item.done" (click)="toggle(item)">{{ item.title }}</li>
                    } @empty {
                      <li>No items</li>
                    }
                  </ul>
                  <mat-icon svgIcon="home" />
                  <router-outlet />
                  <section>
                </main>
                <app-footer />
                """);
        assertEquals(List.of("UNCLOSED section"), problems(app, root), "a real unclosed element is still found");
        Path header = write("src/app/header/header.component.html", """
                <header class="toolbar">
                  <img width="40" alt="Angular Logo" src="assets/logo.svg" />
                  <img src="favicon.ico"><img src="assets/gone.svg">
                  <button mat-icon-button *ngIf="loggedIn" (click)="menu.toggle()">
                    <mat-icon>menu</mat-icon>
                  </button>
                  <input type="text" [(ngModel)]="query" #box placeholder="Search" />
                </header>
                """);
        assertEquals(List.of("MISSING_FILE assets/gone.svg"), problems(header, root), "src/assets, public/");
        Path plain = write("src/app/widget.html", "<div><app-chip /></div>\n");
        assertEquals(List.of(), problems(plain, root), "below an angular.json");
        Path page = write("plain/page.html", "<div><my-el /></div>\n");
        assertEquals(
                List.of("UNCLOSED my-el"),
                problems(page, root.resolve("plain")),
                "a plain page keeps flagging <my-el />");
        assertTrue(HtmlMarkupCheck.isAngular("<b (click)=\"go()\">x</b>", null, null, null), "by its syntax");
        assertFalse(
                HtmlMarkupCheck.isAngular("<pre>&lt;b (click)=\"go()\"&gt;</pre><!-- @if (a) { -->", null, null, null),
                "syntax a page only shows");
    }

    @Test
    void aPlainStaticSiteDoesNotFindPageRelativeLinksInAssetsOrPublic() throws IOException {
        // HTML review 3 C208, reviewer C's rw/static-site.
        write("assets/css/style.css", "");
        write("assets/img/logo.png", "");
        write("public/img/hero.png", "");
        Path index = write("index.html", """
                <!DOCTYPE html>
                <html><head><link rel="stylesheet" href="css/style.css"></head>
                <body><img src="img/logo.png"><img src="assets/img/logo.png"><img src="/img/hero.png"></body></html>
                """);
        assertEquals(
                List.of("MISSING_FILE css/style.css", "MISSING_FILE img/logo.png"),
                problems(index, root),
                "assets/ is not served at the root, and public/ only for a root-relative link");
        Path post = write("blog/post.html", "<img src=\"img/hero.png\"><img src=\"../assets/img/logo.png\">\n");
        assertEquals(List.of("MISSING_FILE img/hero.png"), problems(post, root));
        write("hugo.toml", "");
        write("static/img/a.png", "");
        Path page = write("page.html", "<img src=\"img/a.png\"><img src=\"../../outside.png\">");
        assertEquals(List.of("MISSING_FILE ../../outside.png"), problems(page, root), "a build marker at the root");
        assertEquals(
                List.of("MISSING_FILE img/hero.png"),
                problems(post, root),
                "even then a subfolder page's link is below its own folder");
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
