package com.editora.format;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Format Document for HTML without a language server (HTML review C17, E21). */
class HtmlFormatterTest {

    private static final String MESSY = """
            <html><head><title>messy</title>
            <style>body{margin:0;padding:0}.a{color:red}</style></head>
            <body><div><p>one</p><p>two <b>three</b></p>
            <ul><li>a</li><li>b</li></ul></div>
            <pre>
              keep   this
                 spacing
            </pre>
            <script>function f(){if(true){return 1}}</script>
            <textarea>  raw   text  </textarea>
            </body></html>
            """;

    @Test
    void aMessyPageGetsOneBlockPerLineAndKeepsItsPreformattedContent() {
        assertEquals("""
                <html>
                  <head>
                    <title>messy</title>
                    <style>body{margin:0;padding:0}.a{color:red}</style>
                  </head>
                  <body>
                    <div>
                      <p>one</p>
                      <p>two <b>three</b></p>
                      <ul>
                        <li>a</li>
                        <li>b</li>
                      </ul>
                    </div>
                    <pre>
                  keep   this
                     spacing
                </pre>
                    <script>function f(){if(true){return 1}}</script>
                    <textarea>  raw   text  </textarea>
                  </body>
                </html>
                """, HtmlFormatter.format(MESSY, "  "));
    }

    @Test
    void theAuthorsLineBreaksAndBlankLinesAreKept() {
        String hand = "<body>\n\n<p>\n  A paragraph written\n     on its own lines.\n</p>\n\n\n<p>Short</p>\n</body>\n";
        assertEquals(
                "<body>\n\n    <p>\n        A paragraph written\n        on its own lines.\n    </p>\n\n"
                        + "    <p>Short</p>\n</body>\n",
                HtmlFormatter.format(hand, "    "));
    }

    @Test
    void formattingTwiceChangesNothing() {
        String once = HtmlFormatter.format(MESSY, "  ");
        assertEquals(once, HtmlFormatter.format(once, "  "));
    }

    @Test
    void aConstructLeftOpenIsNotFormatted() {
        assertNull(HtmlFormatter.format("<div><script>var a = 1;", "  "));
        assertNull(HtmlFormatter.format("<div><!-- never closed", "  "));
        assertNull(HtmlFormatter.format("<p class=\"a", "  "));
    }

    @Test
    void theResponseViewStillLeavesAnIndentedBodyAlone() {
        String indented = "<html>\n  <body>x</body>\n</html>";
        assertTrue(indented == HtmlFormatter.indentResponse(indented, 1000));
        assertEquals("<div>\n  <p>x</p>\n</div>", HtmlFormatter.indentResponse("<div><p>x</p></div>", 1000));
    }
    /**
     * Elements that are not blocks stay on the line of the text around them, custom elements included, and a comment
     * inside a word stays in it: a line break there renders as a space (HTML review 2 E106, reviewer E's probe).
     */
    @Test
    void inlineElementsAndCommentsInsideAWordStayOnTheirLine() {
        for (String inline : new String[] {
            "<p><button><svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"/></svg>Save</button></p>\n",
            "<p>Note:<textarea>x</textarea>(optional)</p>\n",
            "<p>Total:<script>document.write(1)</script>items</p>\n",
            "<p>Price:<price-tag value=3></price-tag>EUR</p>\n",
            "<p>See<video src=a.mp4></video>here</p>\n",
            "<p>foo<!-- x -->bar</p>\n",
            "<p>x<math><mi>y</mi></math>z</p>\n",
            "<p>a<picture><source srcset=a.webp><img src=a.png></picture>b</p>\n",
            "<p>Go <a href=/about/>there</a>, or <image src=old.png> here.</p>\n"
        }) {
            assertEquals(inline, HtmlFormatter.format(inline, "  "), inline);
        }
        assertEquals(
                "<p>Sort by <select>\n    <option>name</option>\n  </select>, then go.\n</p>\n",
                HtmlFormatter.format("<p>Sort by <select><option>name</option></select>, then go.</p>\n", "  "),
                "a block inside an inline element: the end tag lines up, and no space is added before the comma");
        assertEquals(
                "<label>Size<select>\n    <option>S\n  </select></label>\n",
                HtmlFormatter.format("<label>Size<select><option>S</select></label>\n", "  "));
        assertEquals(
                "<div>\n  <p>foo</p>\n  <!-- x -->\n  <p>bar</p>\n</div>\n",
                HtmlFormatter.format("<div><p>foo</p><!-- x --><p>bar</p></div>\n", "  "),
                "a comment between blocks keeps its own line");
        assertEquals(
                "<head>\n  <script src=a.js></script>\n  <title>t</title>\n</head>\n",
                HtmlFormatter.format("<head><script src=a.js></script><title>t</title></head>\n", "  "),
                "a script that follows no text keeps its own line");
    }

    /**
     * A {@code <style>}'s or {@code <script>}'s code moves with its start tag, one level in, and the end tag lines up
     * with the start tag (HTML review 2 C107, reviewer C's fixture page); a template literal over lines keeps them.
     */
    @Test
    void scriptAndStyleContentMovesWithItsStartTag() {
        String page = """
                <html>
                <head>
                    <style>
                    body { font-family: system-ui; }
                      a { color: red; }
                  </style>
                </head>
                <body>
                <div>
                        <script>
                        function greet(name) {
                          const msg = `Hello ${name}`;
                          return msg;
                        }
                    </script>
                </div>
                <pre>
                   keep
                </pre>
                </body>
                </html>
                """;
        String out = HtmlFormatter.format(page, "  ");
        assertEquals("""
                <html>
                  <head>
                    <style>
                      body { font-family: system-ui; }
                        a { color: red; }
                    </style>
                  </head>
                  <body>
                    <div>
                      <script>
                        function greet(name) {
                          const msg = `Hello ${name}`;
                          return msg;
                        }
                      </script>
                    </div>
                    <pre>
                   keep
                </pre>
                  </body>
                </html>
                """, out);
        assertEquals(out, HtmlFormatter.format(out, "  "), "idempotent");

        String literal = "<div>\n<script>\n  const t = `\n    x\n  `;\n      </script>\n</div>\n";
        assertEquals(
                "<div>\n  <script>\n  const t = `\n    x\n  `;\n  </script>\n</div>\n",
                HtmlFormatter.format(literal, "  "),
                "a template literal over lines: only the end tag moves");
        String continued = "<div>\n<script>\n  var s = 'a\\\n  b';\n</script>\n</div>\n";
        assertEquals(
                "<div>\n  <script>\n  var s = 'a\\\n  b';\n  </script>\n</div>\n",
                HtmlFormatter.format(continued, "  "));
        String commented = "<div>\n<script>\n  // it's `fine`\n  /* don't */ var a = '`';\n</script>\n</div>\n";
        assertEquals(
                "<div>\n  <script>\n    // it's `fine`\n    /* don't */ var a = '`';\n  </script>\n</div>\n",
                HtmlFormatter.format(commented, "  "),
                "quotes in comments and a backtick in a string do not stop the move");
        assertEquals(
                "<div>\n  <style>a{}\n    b{}</style>\n</div>\n",
                HtmlFormatter.format("<div><style>a{}\n  b{}</style></div>\n", "  "),
                "code on the start tag's line stays there; an end tag after code stays after it");
    }

    /**
     * HTML review 3 C210: an HTML template in a {@code <script type="text/x-template">} is copied as written (its
     * {@code <pre>} kept), where only JavaScript, JSON and CSS move with their start tag; and an Angular template's
     * {@code @if}/{@code @for} blocks keep their level (reviewer C's rw/angular app.component.html).
     */
    @Test
    void htmlTemplateScriptsStayAsWrittenAndAngularBlocksIndent() {
        String template = "<div>\n<script type=\"text/x-template\" id=\"t\">\n      <pre>\n  keep\n    this\n</pre>\n"
                + "</script>\n</div>\n";
        assertEquals(
                "<div>\n  <script type=\"text/x-template\" id=\"t\">\n      <pre>\n  keep\n    this\n</pre>\n</script>\n"
                        + "</div>\n",
                HtmlFormatter.format(template, "  "));
        assertEquals(
                "<div>\n  <script type=\"module\">\n    go();\n  </script>\n  <script type=\"application/ld+json\">\n"
                        + "    {}\n  </script>\n</div>\n",
                HtmlFormatter.format(
                        "<div>\n<script type=\"module\">\ngo();\n</script>\n<script type=\"application/ld+json\">\n{}\n"
                                + "</script>\n</div>\n",
                        "  "),
                "JavaScript and JSON still move");

        String angular = """
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
                  @defer {
                    <big-chart />
                  }
                  <router-outlet />
                </main>
                <app-footer />
                """;
        assertEquals(angular, HtmlFormatter.format(angular, "  "), "an indented Angular template is left as it is");
        String flat = angular.lines().map(String::strip).collect(java.util.stream.Collectors.joining("\n")) + "\n";
        assertEquals(angular, HtmlFormatter.format(flat, "  "), "and a flat one indented by its blocks");
        String inline = "<ul>\n<li>@if (a) {<b>x</b>}</li>\n<li>y</li>\n</ul>\n";
        assertEquals(
                "<ul>\n  <li>@if (a) {<b>x</b>}</li>\n  <li>y</li>\n</ul>\n",
                HtmlFormatter.format(inline, "  "),
                "a block on one line opens no level");
    }

    /** A <code>}</code> line that closes an i18n ICU expression or an interpolation closes no Angular block. */
    @Test
    void anIcuExpressionsClosingBraceInsideABlockClosesNoBlock() {
        String icu = """
                <app-x />
                @if (show) {
                  <span i18n>
                    {count, plural, =0 {none} other {many}
                    }
                  </span>
                  <p>{{ total(
                    a) }}</p>
                  <p>after</p>
                }
                <p>out</p>
                """;
        assertEquals(icu, HtmlFormatter.format(icu, "  "), "an indented page is left as it is (idempotent)");
    }
}
