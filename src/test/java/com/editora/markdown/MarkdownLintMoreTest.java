package com.editora.markdown;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.markdown.MarkdownLint.Diagnostic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that read the document's parse ({@link MarkdownLintMore}): for each, what it reports, what it must
 * not report in ordinary documents, and what its fix does.
 */
class MarkdownLintMoreTest {

    private static final MarkdownLintOptions DEFAULTS = MarkdownLintOptions.DEFAULT;

    /** The diagnostics of rule {@code code} only, as {@code line:column+length}. */
    private static List<String> of(String code, String md) {
        return of(code, md, DEFAULTS);
    }

    private static List<String> of(String code, String md, MarkdownLintOptions options) {
        return MarkdownLint.lint(md, Set.of(), options).stream()
                .filter(d -> d.code().equals(code))
                .map(d -> d.line() + ":" + d.column() + "+" + d.length())
                .toList();
    }

    private static MarkdownLintOptions with(String code, String option, Object value) {
        return DEFAULTS.with(code, option, value);
    }

    /** Fixes rule {@code code} only. */
    private static String fix(String code, String md) {
        Set<String> off = new java.util.HashSet<>(MarkdownLint.CODES);
        off.remove(code);
        return MarkdownLintFix.fix(md, off, 4);
    }

    // --- defaults ---------------------------------------------------------------------------------------

    @Test
    void lineLengthAndInlineHtmlAreOffUntilEnabled() {
        assertEquals(Set.of("MD013", "MD033"), MarkdownLint.DEFAULT_OFF);
        String md = "# T\n\n" + "word ".repeat(40) + "<b>x</b>\n";
        assertTrue(MarkdownLint.lint(md).stream().noneMatch(d -> MarkdownLint.DEFAULT_OFF.contains(d.code())));
        assertFalse(of("MD013", md).isEmpty());
        assertFalse(of("MD033", md).isEmpty());
        for (MarkdownLint.Rule r : MarkdownLint.RULES) {
            assertEquals(!MarkdownLint.DEFAULT_OFF.contains(r.code()), r.defaultEnabled(), r.code());
        }
    }

    @Test
    void anOrdinaryDocumentIsClean() {
        String md =
                "# Project\n\nIntro with *emphasis*, **strong**, `code`, a [link](https://x.io) and ![logo](a.png).\n\n"
                        + "## Install\n\n1. Download\n2. Unpack\n   - on Linux: `tar xf`\n   - on macOS: double-click\n3. Run\n\n"
                        + "- one\n- two\n  - nested\n\n| Key | Value |\n| --- | ----- |\n| a | `x\\|y` |\n\n---\n\n"
                        + "```sh\nmake * all\n```\n\nSee [the docs][docs] and 2 * 3 * 4 = 24, *.md and *.txt, snake_case_name.\n\n"
                        + "## Usage\n\n**Note:** read this.\n\n[docs]: https://x.io/docs\n";
        assertEquals(List.of(), MarkdownLint.lint(md), "no rule fires on a tidy README");
    }

    // --- MD003 --------------------------------------------------------------------------------------------

    @Test
    void md003HeadingStyle() {
        assertEquals(List.of("5:1+1"), of("MD003", "# A\n\n## B\n\nC\n===\n\n"));
        assertEquals(List.of("3:1+7"), of("MD003", "# A\n\n## B ##\n"));
        assertEquals(
                List.of(),
                of("MD003", "A\n===\n\nB\n---\n\n### C\n"),
                "setext has no level 3: ATX is how it is written");
        assertEquals(List.of("1:1+3"), of("MD003", "# A\n", with("MD003", "style", "setext")));
        assertEquals(List.of(), of("MD003", "- item\n\n  ## In a list\n\n# Top\n"));
    }

    // --- MD004 / MD005 / MD007 / MD029 / MD030 / MD032 ---------------------------------------------------------

    @Test
    void md004ListMarkerStyle() {
        assertEquals(List.of("4:1+1"), of("MD004", "- a\n- b\n\n* c\n"));
        assertEquals(List.of("2:3+1"), of("MD004", "- a\n  + nested\n"));
        assertEquals(List.of("1:1+1"), of("MD004", "- a\n", with("MD004", "style", "asterisk")));
        assertEquals(List.of(), of("MD004", "* a\n* b\n\ntext * not a list * here\n"));
        assertEquals("- a\n- b\n\n- c\n  - d\n", fix("MD004", "- a\n- b\n\n* c\n  + d\n"));
    }

    @Test
    void md005ListIndent() {
        assertEquals(List.of("2:1+1"), of("MD005", "- a\n - b\n- c\n"));
        assertEquals(List.of(), of("MD005", " 8. a\n 9. b\n10. c\n"), "right-aligned numbers are aligned");
        assertEquals(List.of(), of("MD005", "- a\n  - b\n  - c\n- d\n"));
    }

    @Test
    void md007NestedListIndent() {
        assertEquals(List.of("2:1+4"), of("MD007", "- a\n    - four in\n"));
        assertEquals(List.of(), of("MD007", "- a\n  - two in\n    - four in\n"));
        assertEquals(
                List.of(), of("MD007", "1. a\n   - under an ordered item\n"), "only bullet lists inside bullet lists");
        assertEquals(List.of(), of("MD007", "- a\n    - b\n", with("MD007", "indent", 4)));
        assertEquals(List.of(), of("MD007", "-   wide\n    - child\n"), "the child cannot sit two columns in here");
    }

    @Test
    void md029OrderedListNumbers() {
        assertEquals(List.of(), of("MD029", "1. a\n2. b\n3. c\n"));
        assertEquals(List.of(), of("MD029", "1. a\n1. b\n1. c\n"));
        assertEquals(List.of(), of("MD029", "0. a\n1. b\n"));
        assertEquals(List.of(), of("MD029", "3. continuing\n4. a list\n"), "a list may start anywhere");
        assertEquals(List.of("3:1+2"), of("MD029", "1. a\n2. b\n4. d\n"));
        assertEquals(List.of("3:1+2"), of("MD029", "1. a\n1. b\n3. c\n"));
        assertEquals(List.of("2:1+2"), of("MD029", "1. a\n2. b\n", with("MD029", "style", "one")));
    }

    @Test
    void md030SpacesAfterListMarker() {
        assertEquals(List.of("1:2+2", "3:3+3"), of("MD030", "-  two\n\n1.   three\n"));
        assertEquals(List.of(), of("MD030", "- one\n1. one\n-\n- [ ] task\n"));
        assertEquals(List.of(), of("MD030", "-  two\n", with("MD030", "ul_single", 2)));
        assertEquals(List.of(), of("MD030", "-     code in an item\n"), "five spaces start an indented code block");
    }

    @Test
    void md032BlanksAroundLists() {
        assertEquals(List.of("2:1+3"), of("MD032", "text\n- a\n- b\n\nmore\n"));
        assertEquals(List.of("2:1+3"), of("MD032", "- a\n- b\n# H\n"));
        assertEquals(List.of(), of("MD032", "text\n\n- a\n  - nested\n- b\n\nmore\n"));
        assertEquals(List.of(), of("MD032", "<!-- markdownlint-disable-next-line MD004 -->\n* a\n"));
        assertEquals("text\n\n- a\n- b\n\n# H\n", fix("MD032", "text\n- a\n- b\n# H\n"));
        String quoted = "> text\n> - a\n";
        assertEquals(List.of("2:3+3"), of("MD032", quoted));
        assertEquals(quoted, fix("MD032", quoted), "inside a quote a blank line would need the marker: not offered");
    }

    // --- MD011 / MD037 / MD038 / MD039 --------------------------------------------------------------------------

    @Test
    void md011ReversedLink() {
        assertEquals(List.of("1:5+24"), of("MD011", "See (the site)[https://x.io] now\n"));
        assertEquals(
                List.of(),
                of("MD011", "call f(x)[0] in `(a)[b]` and (see)[note]\n\n[note]: /n\n"),
                "indexing, code, a real reference");
        assertEquals(List.of(), of("MD011", "a footnote (text)[^1]\n\n[^1]: note\n"));
        assertEquals("See [the site](https://x.io) now\n", fix("MD011", "See (the site)[https://x.io] now\n"));
    }

    @Test
    void md037SpacesInsideEmphasis() {
        assertEquals(List.of("1:1+9"), of("MD037", "**bold ** text\n"));
        assertEquals(List.of("1:6+9"), of("MD037", "some ** bold** text\n"));
        assertEquals(List.of("1:1+10"), of("MD037", "** bold ** text\n"));
        assertEquals(List.of("1:4+7"), of("MD037", "an *item * here\n"));
        assertEquals(List.of("1:4+7"), of("MD037", "an _item _ here\n"));
        assertEquals("**bold** text\n\nan *item* here\n", fix("MD037", "**bold ** text\n\nan * item* here\n"));
    }

    @Test
    void md037LeavesArithmeticGlobsAndIdentifiersAlone() {
        assertEquals(List.of(), of("MD037", "2 * 3 * 4 and a * b * c\n"));
        assertEquals(List.of(), of("MD037", "match *.md and *.txt, then rm *.o *\n"));
        assertEquals(List.of(), of("MD037", "snake_case names_ and __init__ and _private _ident\n"));
        assertEquals(List.of(), of("MD037", "**bold** and **more**, *one* and *two*\n"), "real emphasis is not text");
        assertEquals(List.of(), of("MD037", "- * a bullet in a bullet *\n* * *\n"));
        assertEquals(List.of(), of("MD037", "`** code **` and \\** escaped **\n"));
        assertEquals(List.of(), of("MD037", "$a_i * b_j$ and 5 *times* 3\n"));
    }

    @Test
    void md038SpacesInsideCodeSpan() {
        assertEquals(List.of("1:5+7"), of("MD038", "run ` make` now\n"));
        assertEquals(List.of("1:5+9"), of("MD038", "run `  make ` now\n"));
        assertEquals("run `make` now\n", fix("MD038", "run ` make` now\n"));
        assertEquals(
                List.of(),
                of("MD038", "`a` ` b ` `` ` `` `- ` and `rem `\n"),
                "padding, and a literal that ends in a space");
        assertEquals(
                List.of(), of("MD038", "a `x` stray ` then `y` end\n"), "mis-paired spans around a stray backtick");
    }

    @Test
    void md039SpacesInsideLinkText() {
        assertEquals(List.of("1:5+12"), of("MD039", "see [ text ](/u) ok\n"));
        assertEquals(List.of(), of("MD039", "see [text](/u) and ![ alt ](a.png) and [ ] task\n"));
        assertEquals("see [text](/u) ok\n", fix("MD039", "see [ text ](/u) ok\n"));
    }

    // --- MD042 / MD045 / MD051 / MD053 ------------------------------------------------------------------------------

    @Test
    void md042EmptyLinks() {
        assertEquals(List.of("1:1+8", "1:10+9"), of("MD042", "[text]() [text](#)\n"));
        assertEquals(List.of(), of("MD042", "[text](#frag) [text](/u) [text]\n"));
    }

    @Test
    void md045ImagesWithoutAlternateText() {
        assertEquals(List.of("1:1+10"), of("MD045", "![](a.png) ![alt](b.png)\n"));
        assertEquals(List.of("1:6+17"), of("MD045", "text <img src=\"a.png\"> and <img alt=\"x\" src=\"b.png\">\n"));
        assertEquals(List.of(), of("MD045", "`<img src=x>` in code\n"));
    }

    @Test
    void md051LinkFragments() {
        String md = "# Top Title\n\n## Getting started!\n\n## Getting started!\n\n<a id=\"custom\"></a>\n\n"
                + "[a](#top-title) [b](#getting-started) [c](#getting-started-1) [d](#custom) [e](#top) [f](#L12)\n\n"
                + "[bad](#missing) [case](#Top-Title)\n";
        assertEquals(List.of("11:1+15", "11:17+18"), of("MD051", md));
        assertEquals(List.of("11:1+15"), of("MD051", md, with("MD051", "ignore_case", true)));
        assertEquals(List.of(), of("MD051", "# T\n\n[other](other.md#whatever) [ext](https://x.io/#frag)\n"));
    }

    @Test
    void md053ReferenceDefinitions() {
        String md =
                "# T\n\n[used][a] and [b] and [c][] and ![img][d]\n\n[a]: /a\n[b]: /b\n[c]: /c\n[d]: /d.png\n[unused]: /u\n[a]: /again\n";
        assertEquals(List.of("9:1+12", "10:1+11"), of("MD053", md));
        assertEquals(md.replace("[unused]: /u\n[a]: /again\n", ""), fix("MD053", md));
        assertEquals(List.of(), of("MD053", "# T\n\n[//]: # (a comment)\n\ntext[^1]\n\n[^1]: footnote\n"));
        assertEquals(List.of(), of("MD053", "| a |\n|---|\n| [x][in-table] |\n\n[in-table]: /t\n"));
    }

    // --- MD024 / MD035 / MD036 / MD046 / MD048 / MD049 / MD050
    // ---------------------------------------------------------

    @Test
    void md024DuplicateHeadings() {
        String changelog = "# Changelog\n\n## 2.0\n\n### Added\n\n### Fixed\n\n## 1.0\n\n### Added\n\n### Added\n";
        assertEquals(List.of("13:1+9"), of("MD024", changelog), "the same title under different releases is fine");
        assertEquals(List.of("11:1+9", "13:1+9"), of("MD024", changelog, with("MD024", "siblings_only", false)));
        assertEquals(List.of("3:1+3"), of("MD024", "# A\n\n# A\n"));
    }

    @Test
    void md035HorizontalRuleStyle() {
        assertEquals(List.of("7:1+3"), of("MD035", "# T\n\n---\n\ntext\n\n***\n\n---\n"));
        assertEquals("# T\n\n---\n\ntext\n\n---\n", fix("MD035", "# T\n\n---\n\ntext\n\n* * *\n"));
        assertEquals(List.of("3:1+3"), of("MD035", "# T\n\n---\n", with("MD035", "style", "***")));
    }

    @Test
    void md036EmphasisAsHeading() {
        assertEquals(List.of("3:1+15"), of("MD036", "# T\n\n**Section one**\n\ntext\n"));
        assertEquals(List.of("3:1+9"), of("MD036", "# T\n\n_Section_\n\ntext\n"));
        assertEquals(
                List.of(), of("MD036", "# T\n\n**Note:**\n\n**Warning!**\n\n**Bold** then text\n\n**[link](/u)**\n"));
        assertEquals(List.of(), of("MD036", "- **Label**\n\n> **Quoted**\n\n**two\nlines**\n"));
    }

    @Test
    void md046AndMd048CodeBlockStyles() {
        assertEquals(List.of("7:1+8"), of("MD046", "```sh\nx\n```\n\ntext\n\n    code\n"));
        assertEquals(List.of("5:1+3"), of("MD048", "```sh\nx\n```\n\n~~~sh\ny\n~~~\n"));
        assertEquals(List.of(), of("MD048", "````md\n```sh\n```\n````\n"));
        assertEquals(List.of("1:1+3"), of("MD048", "```sh\nx\n```\n", with("MD048", "style", "tilde")));
    }

    @Test
    void md049AndMd050EmphasisStyles() {
        assertEquals(List.of("1:11+5"), of("MD049", "*one* and _two_ and *three*\n"));
        assertEquals(List.of("1:13+7"), of("MD050", "**one** and __two__\n"));
        assertEquals("*one* and *two*\n", fix("MD049", "*one* and _two_\n"));
        assertEquals("__one__ and __two__\n", fix("MD050", "__one__ and **two**\n"));
        // An asterisk inside a word cannot become an underscore: reported, not rewritten.
        assertEquals(List.of("1:16+5"), of("MD049", "_one_ and snake*cas*ed\n"));
        assertEquals("_one_ and snake*cas*ed\n", fix("MD049", "_one_ and snake*cas*ed\n"));
        assertEquals(List.of(), of("MD049", "snake_case_name and file_name_here, *one*\n"));
    }

    // --- MD013 / MD033 ----------------------------------------------------------------------------------------

    @Test
    void md013LineLength() {
        String longLine = "word ".repeat(20).strip();
        assertEquals(List.of("3:81+19"), of("MD013", "# T\n\n" + longLine + "\n"));
        assertEquals(List.of(), of("MD013", "# T\n\n" + longLine + "\n", with("MD013", "line_length", 120)));
        String url = "see https://example.com/" + "a".repeat(100);
        assertEquals(List.of(), of("MD013", url + "\n"), "nothing to break after the limit");
        assertEquals(List.of("1:81+" + (url.length() - 80)), of("MD013", url + "\n", with("MD013", "strict", true)));
        assertEquals(List.of(), of("MD013", "[a very long link text " + "x ".repeat(40) + "](https://x.io)\n"));
        String code = "```\n" + longLine + "\n```\n";
        assertEquals(List.of("2:81+19"), of("MD013", code));
        assertEquals(List.of(), of("MD013", code, with("MD013", "code_blocks", false)));
        String table = "| " + longLine + " |\n|---|\n";
        assertEquals(List.of(), of("MD013", table, with("MD013", "tables", false)));
    }

    @Test
    void md033InlineHtml() {
        assertEquals(List.of("1:6+5", "3:1+4"), of("MD033", "text <span>x</span>\n\n<div>\n</div>\n"));
        assertEquals(
                List.of("3:1+4"),
                of(
                        "MD033",
                        "text <span>x</span>\n\n<div>\n</div>\n",
                        with("MD033", "allowed_elements", List.of("SPAN"))));
        assertEquals(List.of(), of("MD033", "<!-- a comment -->\n\n`<b>` and <https://x.io> and a < b > c\n"));
    }

    // --- tables: MD055 / MD056 / MD058
    // --------------------------------------------------------------------------------

    @Test
    void md055PipeStyle() {
        assertEquals(List.of("3:1+5"), of("MD055", "| a | b |\n|---|---|\n1 | 2\n"));
        assertEquals(List.of(), of("MD055", "a | b\n--|--\n1 | 2\n"));
        assertEquals(
                List.of("1:1+5"),
                of("MD055", "a | b\n--|--\n", with("MD055", "style", "leading_and_trailing"))
                        .subList(0, 1));
    }

    @Test
    void md056ColumnCount() {
        assertEquals(
                List.of("3:1+5", "4:1+13"), of("MD056", "| a | b |\n|---|---|\n| 1 |\n| 1 | 2 | 3 |\n| ok | ok |\n"));
        assertEquals(List.of(), of("MD056", "| a | b |\n|---|---|\n| `x|y` | a\\|b |\n"));
    }

    @Test
    void md058BlanksAroundTables() {
        String md = "text\n| a |\n|---|\n| 1 |\n# H\n";
        assertEquals(List.of("2:1+5", "4:1+5"), of("MD058", md));
        assertEquals("text\n\n| a |\n|---|\n| 1 |\n\n# H\n", fix("MD058", md));
        assertEquals(List.of(), of("MD058", "text\n\n| a |\n|---|\n| 1 |\n\nmore\n"));
    }

    // --- link targets
    // --------------------------------------------------------------------------------------------------

    private static MarkdownLint.LinkTargets files(Map<String, Set<String>> files) {
        return new MarkdownLint.LinkTargets() {
            @Override
            public boolean exists(String path) {
                return files.containsKey(path);
            }

            @Override
            public Set<String> anchors(String path) {
                Set<String> anchors = files.get(path);
                return anchors == null || anchors.contains("?") ? null : anchors;
            }
        };
    }

    private static List<String> links(String md, MarkdownLint.LinkTargets targets) {
        return MarkdownLint.lint(md, Set.of(), DEFAULTS, targets).stream()
                .filter(d -> d.code().startsWith("LINK"))
                .map(d -> d.code() + "@" + d.line() + ":" + d.column() + "+" + d.length())
                .toList();
    }

    @Test
    void relativeLinksAndImagesMustLeadSomewhere() {
        MarkdownLint.LinkTargets targets = files(
                Map.of("docs/guide.md", Set.of("install"), "img/a b.png", Set.of(), "unreadable.md", Set.of("?")));
        String md = "[ok](docs/guide.md) [gone](docs/missing.md) ![ok](img/a%20b.png) ![gone](img/none.png)\n\n"
                + "[ok](docs/guide.md#install) [no heading](docs/guide.md#usage) [unknown](unreadable.md#x)\n\n"
                + "[ref]\n\n[ref]: nowhere.md\n";
        assertEquals(
                List.of("LINK001@1:21+23", "LINK001@1:66+21", "LINK002@3:29+33", "LINK001@5:1+5"), links(md, targets));
    }

    @Test
    void whatIsNotARelativePathIsNotChecked() {
        String md =
                "[a](https://x.io/a.md) [b](mailto:a@b.co) [c](#frag) [d](/site/root.md) [e](<{{ url }}>) [f]({{base}}/x.md)\n"
                        + "<https://x.io> `[g](gone.md)` [h](?query) [i](//host/path)\n\n```\n[j](gone.md)\n```\n";
        assertEquals(List.of(), links(md, files(Map.of())));
        assertEquals(List.of(), links("[gone](gone.md)\n", null), "without a resolver the rule says nothing");
    }

    // --- severities, messages, speed
    // -----------------------------------------------------------------------------------

    @Test
    void severityComesFromTheOptions() {
        MarkdownLintOptions options = DEFAULTS.withSeverity("MD042", "error")
                .withSeverity("MD045", "hint")
                .withSeverity("MD004", "loud");
        List<Diagnostic> all = MarkdownLint.lint("[x]() ![](a.png)\n\n- a\n* b\n", Set.of(), options);
        Diagnostic link =
                all.stream().filter(d -> d.code().equals("MD042")).findFirst().orElseThrow();
        Diagnostic image =
                all.stream().filter(d -> d.code().equals("MD045")).findFirst().orElseThrow();
        Diagnostic bullet =
                all.stream().filter(d -> d.code().equals("MD004")).findFirst().orElseThrow();
        assertTrue(link.isError());
        assertEquals(0, link.severityRank());
        assertTrue(image.isHint());
        assertEquals(2, image.severityRank());
        assertEquals("warning", bullet.severity());
        MarkdownLintOptions fromConfig = MarkdownLintConfig.options(
                MarkdownLintConfig.parse("{\"MD042\": \"error\", \"no-alt-text\": {\"severity\": \"hint\"}}"),
                DEFAULTS);
        assertEquals("error", fromConfig.severity("MD042"));
        assertEquals("hint", fromConfig.severity("MD045"));
    }

    @Test
    void everyRuleHasItsMessagesInTheCatalogSet() {
        for (MarkdownLint.Rule r : MarkdownLint.RULES) {
            assertTrue(
                    MarkdownLint.messageKeys().stream().anyMatch(k -> k.startsWith("mdlint.msg." + r.code())),
                    r.code());
        }
    }

    @Test
    void fixingEverythingIsIdempotent() {
        String md =
                "Title\n=====\ntext\n* a\n+ b\n# H #\n**bold ** and ` code` and [ link ](/u) and (rev)[/u] and _e_ and *f*\n"
                        + "| a |\n|---|\n***\n\n---\n\n[unused]: /u\n";
        String once = MarkdownLintFix.fix(md, MarkdownLint.DEFAULT_OFF, 4);
        assertEquals(once, MarkdownLintFix.fix(once, MarkdownLint.DEFAULT_OFF, 4));
        assertTrue(once.contains("**bold** and `code` and [link](/u) and [rev](/u) and _e_ and _f_"), once);
    }

    /**
     * Linear, not merely fast here: ten times the input must not take a hundred times as long. (A bound in milliseconds
     * that this machine meets says nothing about a slower one.)
     */
    @Test
    void theRulesScaleLinearlyOnPathologicalLines() {
        for (String unit :
                List.of("**a ** ", "` x` ", "[ a ](u) ", "(a)[b] ", "*a* _b_ ", "| a ", "<b>x</b> ", "[x][y] ")) {
            MarkdownLint.lint("# T\n\n" + unit.repeat(2_000) + "\n", Set.of()); // warm up
            long small = time("# T\n\n" + unit.repeat(4_000) + "\n");
            long large = time("# T\n\n" + unit.repeat(40_000) + "\n");
            assertTrue(
                    large < Math.max(small, 2_000_000L) * 40,
                    unit + ": 10x the input took " + large / Math.max(1, small) + "x the time");
        }
    }

    private static long time(String md) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            MarkdownLint.lint(md, Set.of());
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }

    // --- LINK003: wiki links -----------------------------------------------------------------------------------------

    private static MarkdownLint.LinkTargets notes(Map<String, Set<String>> notes) {
        return new MarkdownLint.LinkTargets() {
            @Override
            public boolean exists(String path) {
                return true;
            }

            @Override
            public Set<String> anchors(String path) {
                return notes.get(path.replace(".md", ""));
            }

            @Override
            public String wikiFile(String target) {
                return target.equals("somewhere") ? "" : notes.containsKey(target) ? target + ".md" : null;
            }
        };
    }

    private static List<String> wiki(String md, MarkdownLint.LinkTargets targets) {
        return MarkdownLint.lint(md, Set.of(), DEFAULTS, targets).stream()
                .filter(d -> d.code().equals("LINK003"))
                .map(d -> d.line() + ":" + d.column() + "+" + d.length()
                        + (d.key().endsWith(".heading") ? " heading" : ""))
                .toList();
    }

    @Test
    void aWikiLinkMustLeadToANote() {
        MarkdownLint.LinkTargets targets =
                notes(Map.of("guide", Set.of("install", "first-steps"), "logo.png", Set.of()));
        String md =
                "# Notes\n\n[[guide]] [[guide|the guide]] [[guide#Install]] [[guide#First steps|start]] ![[logo.png]]\n\n"
                        + "[[missing]] and [[guide#Nowhere]] and ![[gone.png]] and [[#Notes]] and [[#Elsewhere|x]]\n";
        assertEquals(List.of("5:1+11", "5:17+17 heading", "5:40+12", "5:72+16 heading"), wiki(md, targets));
    }

    @Test
    void whatIsNotAWikiLinkIsNotJudged() {
        MarkdownLint.LinkTargets targets = notes(Map.of());
        assertEquals(
                List.of(), wiki("`[[code]]` and \\[[escaped]] and [[]] and [[ ]] and [not [[nested] ]]\n", targets));
        assertEquals(
                List.of(),
                wiki("```\n[[in a fence]]\n```\n\n    [[indented code]]\n\n<!-- [[comment]] -->\n", targets));
        assertEquals(List.of(), wiki("[[somewhere]] cannot be told, so it is left alone\n", targets));
        assertEquals(List.of(), wiki("[[missing]]\n", null), "no resolver: silent");
        assertEquals(List.of("1:5+11"), wiki("a [[[[missing]] b\n", targets), "the innermost pair is the link");
    }

    /** The forms the preview's parser ({@link MarkdownWikiLinks}) draws the line at: lint draws it there too. */
    @Test
    void lintCallsAWikiLinkExactlyWhatThePreviewDoes() {
        MarkdownLint.LinkTargets targets = notes(Map.of());
        assertEquals(
                List.of(),
                wiki("see x[[1]] and matrix[[i]] for the index\n", targets),
                "after a letter or digit: text");
        assertEquals(List.of("1:5+8"), wiki("see [[gone]] here\n", targets));
        assertEquals(
                List.of(),
                wiki("see [[note]] here\n\n[note]: https://x.io\n", targets),
                "with a definition of [note] it is a reference link in brackets");
        assertEquals(
                List.of(),
                wiki("see [[note]](https://x.io) and [[note]][ref]\n\n[ref]: /r\n", targets),
                "ordinary links");
        assertEquals(
                List.of("1:6+8"),
                wiki("see [[[gone]]] here\n", targets),
                "a wiki link inside one more pair of brackets");
        assertEquals(
                List.of("3:3+15"),
                wiki("| a |\n|---|\n| [[gone\\|label]] |\n", targets),
                "in a table cell the pipe is written \\|");
        assertEquals(List.of(), wiki("`[[gone]]` and ``[[gone]]``\n\n    [[gone]]\n\n```\n[[gone]]\n```\n", targets));
        assertEquals(List.of("1:2+12"), wiki("![[gone.png]]\n", targets), "an embed is underlined from its brackets");
        // Each form, parsed by the preview's own extension: a WikiLink there exactly when lint reports one.
        for (String md : List.of(
                "see x[[1]] y",
                "see [[gone]] here",
                "see [[[gone]]] here",
                "see [[note]](https://x.io)",
                "\\[[gone]]",
                "![[gone.png]]")) {
            org.commonmark.node.Node doc = org.commonmark.parser.Parser.builder()
                    .extensions(List.of(MarkdownWikiLinks.extension()))
                    .build()
                    .parse(md);
            boolean[] found = {false};
            doc.accept(new org.commonmark.node.AbstractVisitor() {
                @Override
                public void visit(org.commonmark.node.CustomNode node) {
                    found[0] |= node instanceof MarkdownWikiLinks.WikiLink;
                    visitChildren(node);
                }
            });
            assertEquals(found[0], !wiki(md + "\n", targets).isEmpty(), md);
        }
    }

    @Test
    void aWallOfWikiLinksIsLintedWithoutThemRatherThanSlowly() {
        MarkdownLint.LinkTargets targets = notes(Map.of());
        assertEquals(2_000, wiki("[[a]] ".repeat(2_000) + "\n", targets).size());
        assertEquals(List.of(), wiki("[[a]] ".repeat(2_001) + "\n", targets), "past the bound LINK003 stands down");
        assertEquals(
                4_000,
                wiki("[[a]] ".repeat(2_000) + "\n\n" + "[[b]] ".repeat(2_000) + "\n", targets)
                        .size(),
                "per paragraph");
        long start = System.nanoTime();
        MarkdownLint.lint("[[".repeat(100_000) + "\n", Set.of(), DEFAULTS, targets);
        MarkdownLint.lint("[[a]] ".repeat(4_000) + "\n", Set.of(), DEFAULTS, targets);
        long small = System.nanoTime() - start;
        start = System.nanoTime();
        MarkdownLint.lint("[[".repeat(1_000_000) + "\n", Set.of(), DEFAULTS, targets);
        MarkdownLint.lint("[[a]] ".repeat(40_000) + "\n", Set.of(), DEFAULTS, targets);
        long large = System.nanoTime() - start;
        assertTrue(
                large < Math.max(small, 5_000_000L) * 40,
                "10x the input took " + large / Math.max(1, small) + "x the time");
    }
}
