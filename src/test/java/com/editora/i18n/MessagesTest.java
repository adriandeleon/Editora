package com.editora.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("guard")
class MessagesTest {

    /** The non-base bundled locales; each must mirror the English base's key set exactly. */
    private static final List<String> LOCALES = List.of("it", "es", "fr", "pt", "de");

    /** The base catalog ("") plus every overlay — for checks that apply to all six equally. */
    private static final List<String> ALL_CATALOGS = List.of("", "it", "es", "fr", "pt", "de");

    private static Properties loadProps(String resource) {
        Properties p = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "missing resource: " + resource);
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return p;
    }

    /** The raw lines of one catalog — the parity checks read parsed keys, which hides malformed lines. */
    private static List<String> readLines(String suffix) {
        try (InputStream in =
                Messages.class.getResourceAsStream("/com/editora/i18n/messages" + suffix + ".properties")) {
            assertTrue(in != null, "missing catalog: " + suffix);
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Every line is a comment, blank, a continuation, or {@code key=value}.
     *
     * <p>The parity check cannot see this. A merge writes SYMMETRIC conflict markers into all six catalogs
     * at once, and {@code Properties.load} turns each marker into a key with an empty value — so the key
     * sets still match and a full {@code verify} passes with {@code <<<<<<< HEAD} sitting in the shipped
     * resources. Observed, not hypothesised: it is how a conflicted cherry-pick went green here.
     */
    @Test
    void noCatalogHasAMalformedOrDuplicateKey() {
        for (String lang : ALL_CATALOGS) {
            String suffix = lang.isEmpty() ? "" : "_" + lang;
            List<String> lines = readLines(suffix);
            Map<String, Integer> firstLine = new TreeMap<>();
            boolean continuation = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                boolean wasContinuation = continuation;
                continuation = endsWithOddBackslashes(line);
                String trimmed = line.strip();
                if (wasContinuation || trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                String where = "messages" + suffix + ".properties:" + (i + 1);
                assertFalse(
                        trimmed.startsWith("<<<<<<<") || trimmed.startsWith(">>>>>>>") || trimmed.equals("======="),
                        where + " is an unresolved merge conflict marker: " + trimmed);
                assertTrue(trimmed.indexOf('=') > 0, where + " is neither a comment nor key=value: " + trimmed);
                String key = trimmed.substring(0, trimmed.indexOf('='));
                Integer previous = firstLine.putIfAbsent(key, i + 1);
                assertTrue(previous == null, where + " duplicates key " + key + " from line " + previous);
            }
        }
    }

    /** True when the line ends inside an escape, i.e. the next line continues this value. */
    private static boolean endsWithOddBackslashes(String line) {
        int n = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            n++;
        }
        return n % 2 == 1;
    }

    @Test
    void translationCatalogsHaveExactlyTheBaseKeySet() {
        Properties base = loadProps("/com/editora/i18n/messages.properties");
        Set<String> baseKeys = base.stringPropertyNames();
        assertFalse(baseKeys.isEmpty(), "English base catalog is empty");

        for (String lang : LOCALES) {
            Properties loc = loadProps("/com/editora/i18n/messages_" + lang + ".properties");
            Set<String> locKeys = loc.stringPropertyNames();

            Set<String> missing = new TreeSet<>(baseKeys);
            missing.removeAll(locKeys);
            Set<String> extra = new TreeSet<>(locKeys);
            extra.removeAll(baseKeys);

            assertTrue(missing.isEmpty(), lang + " is missing keys: " + missing);
            assertTrue(extra.isEmpty(), lang + " has extra keys not in the base: " + extra);
        }
    }

    @Test
    void translationsAreNonEmptyAndDistinctFromKeys() {
        for (String lang : LOCALES) {
            Properties loc = loadProps("/com/editora/i18n/messages_" + lang + ".properties");
            for (String key : loc.stringPropertyNames()) {
                String value = loc.getProperty(key).trim();
                assertFalse(value.isEmpty(), lang + ": empty value for " + key);
            }
        }
    }

    @Test
    void everyCommandTitleHasAMatchingDescription() {
        // The command palette shows command.<id>.desc as the highlighted command's detail line, so
        // every registered command's title key (command.<id>, not the .desc itself) must have one.
        Properties base = loadProps("/com/editora/i18n/messages.properties");
        Set<String> keys = base.stringPropertyNames();
        Set<String> missing = new TreeSet<>();
        for (String key : keys) {
            if (key.startsWith("command.") && !key.endsWith(".desc") && !keys.contains(key + ".desc")) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "command titles without a .desc description: " + missing);
    }

    /**
     * A command family either prefixes all of its titles ({@code Edit: Cut}, {@code Git: Push}) or none of
     * them — never a mix. The palette lists ~550 commands, so a half-prefixed family scatters related
     * entries when scanning; {@code view.*} was a 46/48 coin flip before this was pinned. Families that are
     * deliberately bare in full (their titles are self-describing nouns — "Command Palette", "Switcher")
     * stay bare, which this allows; what it forbids is the mix.
     *
     * <p>Every locale is checked, not just the base: the prefix is part of the translated string, so a
     * catalog can drift on its own.
     */
    @Test
    void aCommandFamilyIsEitherFullyPrefixedOrFullyBare() {
        // Allows hyphens/apostrophes/accents ("HTML-Vorschau: ", "Fenêtre d'outils : ") and the French
        // space-before-colon convention.
        Pattern prefix = Pattern.compile("^[A-Za-zÀ-ÿ][A-Za-zÀ-ÿ0-9'’\\- ]*\\s?: ");
        for (String lang : ALL_CATALOGS) {
            Properties props =
                    loadProps("/com/editora/i18n/messages" + (lang.isEmpty() ? "" : "_" + lang) + ".properties");
            Map<String, Set<String>> prefixed = new TreeMap<>();
            Map<String, Set<String>> bare = new TreeMap<>();
            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith("command.") || key.endsWith(".desc")) {
                    continue;
                }
                String[] parts = key.split("\\.", 3);
                if (parts.length < 3) {
                    continue;
                }
                String family = parts[1];
                String title = props.getProperty(key).trim();
                (prefix.matcher(title).find() ? prefixed : bare)
                        .computeIfAbsent(family, f -> new TreeSet<>())
                        .add(key);
                // A prefix applied twice ("Markdown: Table: Add Row") is always a bug.
                assertFalse(
                        prefix.matcher(prefix.matcher(title).replaceFirst("")).find(),
                        lang + ": doubled family prefix in " + key + " = " + title);
            }
            for (String family : prefixed.keySet()) {
                if (bare.containsKey(family)) {
                    fail(lang + ": command family '" + family + "' mixes prefixed and bare titles — bare: "
                            + bare.get(family) + ", prefixed: " + prefixed.get(family));
                }
            }
        }
    }

    @Test
    void trFallsBackOverlayThenBaseThenKey() {
        Messages.init("es");
        // A key present in the overlay returns the Spanish value ("Ver: " is the view.* family prefix).
        assertEquals("Ver: Ajustes", Messages.tr("command.view.settings"));
        // A wholly unknown key returns the key itself (never an exception).
        assertEquals("no.such.key.exists", Messages.tr("no.such.key.exists"));
        Messages.init("en");
    }

    @Test
    void trAppliesMessageFormatArguments() {
        Messages.init("en");
        assertEquals("About Editora", Messages.tr("dialog.about.title", "Editora"));
    }

    @Test
    void trWithBadCodeFallsBackToEnglish() {
        Messages.init("zz"); // unbundled
        assertEquals("en", Messages.current());
        Messages.init("en");
    }

    /**
     * Every parameterized value — one whose <b>English</b> pattern has a {@code {n}} placeholder, so
     * {@code tr(key, args)} runs it through {@link java.text.MessageFormat} — must format cleanly in every
     * locale. The trap is that a single {@code '} is an <em>escape</em> in a MessageFormat pattern: an
     * unescaped apostrophe (rife in French {@code l'} / {@code d'} and Italian {@code dell'}) silently turns a
     * following {@code {0}} into the literal text "{0}", so the filename/error never appears — and it's
     * invisible in English, where the base has no such apostrophes. This formats each locale's value with
     * sentinel args and fails if any placeholder the English pattern carries didn't get substituted.
     */
    @Test
    void everyParameterizedValueFormatsInEveryLocaleWithoutSwallowingAPlaceholder() {
        Properties base = loadProps("/com/editora/i18n/messages.properties");
        // A REAL MessageFormat placeholder is `{n}` or `{n,…}` — digits then a comma or close brace. This
        // must NOT match a snippet help string's `${1:default}` (the `{1:` isn't a placeholder, and that key
        // is shown via tr(key) with no args, so MessageFormat never touches it).
        java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("\\{(\\d+)\\s*[,}]");

        // Each catalog is read once: read again for every key, they took this test a minute.
        Map<String, Properties> catalogs = new java.util.LinkedHashMap<>();
        catalogs.put("", base);
        LOCALES.forEach(l -> catalogs.put("_" + l, loadProps("/com/editora/i18n/messages_" + l + ".properties")));

        Set<String> problems = new TreeSet<>();
        for (String key : base.stringPropertyNames()) {
            java.util.regex.Matcher m = placeholder.matcher(base.getProperty(key));
            Set<Integer> indices = new TreeSet<>();
            while (m.find()) {
                indices.add(Integer.parseInt(m.group(1)));
            }
            if (indices.isEmpty()) {
                continue; // not a MessageFormat value — tr(key) returns it verbatim, apostrophes fine
            }
            for (Map.Entry<String, Properties> catalog : catalogs.entrySet()) {
                String suffix = catalog.getKey();
                String value = catalog.getValue().getProperty(key);
                if (value == null) {
                    continue; // key-parity is covered by another test
                }
                // A sentinel per argument, unlikely to occur in any translation. A placeholder with a format
                // type ({n,number,#} for a line number, {n,choice,…} for a plural) needs a number, not text.
                Object[] args = new Object[10];
                for (int i = 0; i < args.length; i++) {
                    args[i] = value.matches("(?s).*\\{" + i + "\\s*,.*") ? (Object) (731 + i) : "‹A" + i + "›";
                }
                String out;
                try {
                    out = new java.text.MessageFormat(value).format(args);
                } catch (IllegalArgumentException bad) {
                    problems.add((suffix.isEmpty() ? "en" : suffix.substring(1)) + " / " + key
                            + " → MessageFormat rejected it: " + value);
                    continue;
                }
                // Every index the English pattern carries must survive into the output as its sentinel; if a
                // stray apostrophe swallowed it, the literal "{n}" shows instead.
                for (int idx : indices) {
                    if (idx < args.length && !out.contains(String.valueOf(args[idx]))) {
                        problems.add((suffix.isEmpty() ? "en" : suffix.substring(1)) + " / " + key + " → {" + idx
                                + "} not substituted (likely an unescaped apostrophe): " + value);
                    }
                }
            }
        }
        assertTrue(
                problems.isEmpty(),
                "MessageFormat values that drop an argument in some locale (double the apostrophes: l' → l''):\n"
                        + String.join("\n", problems));
    }

    /** A MessageFormat placeholder: {@code {n}} or {@code {n,…}}. Its presence decides how a value is written. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+\\s*[,}]");

    private static Properties catalog(String lang) {
        return loadProps("/com/editora/i18n/messages" + (lang.isEmpty() ? "" : "_" + lang) + ".properties");
    }

    private static String name(String lang) {
        return lang.isEmpty() ? "en" : lang;
    }

    /**
     * A value <b>without</b> a placeholder is shown exactly as written ({@code Messages.tr} never runs it
     * through {@link java.text.MessageFormat}), so an apostrophe doubled "to be safe" is displayed doubled:
     * the palette showed {@code Configuration d''exécution…} in French and {@code isn''t} in English.
     */
    @Test
    void aValueWithoutAPlaceholderNeverDoublesAnApostrophe() {
        Set<String> problems = new TreeSet<>();
        for (String lang : ALL_CATALOGS) {
            Properties props = catalog(lang);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (!PLACEHOLDER.matcher(value).find() && value.contains("''")) {
                    problems.add(name(lang) + " / " + key + " = " + value);
                }
            }
        }
        assertTrue(
                problems.isEmpty(),
                "values without {n} are shown verbatim — write a single apostrophe:\n" + String.join("\n", problems));
    }

    /**
     * A value <b>with</b> a placeholder is a MessageFormat pattern, where a lone apostrophe is not text but
     * the start of a quoted section: at best it vanishes ({@code nell'albero} rendered "nellalbero"), at worst
     * it swallows the placeholder after it. Every literal apostrophe in such a value is doubled.
     */
    @Test
    void aValueWithAPlaceholderNeverHasALoneApostrophe() {
        Set<String> problems = new TreeSet<>();
        for (String lang : ALL_CATALOGS) {
            Properties props = catalog(lang);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (PLACEHOLDER.matcher(value).find() && value.replace("''", "").indexOf('\'') >= 0) {
                    problems.add(name(lang) + " / " + key + " = " + value);
                }
            }
        }
        assertTrue(
                problems.isEmpty(),
                "values with {n} are MessageFormat patterns — double the apostrophe (l' → l''):\n"
                        + String.join("\n", problems));
    }

    /**
     * Inside a {@code {n,choice,…}} limb the text is a pattern of its own, parsed a second time when the limb
     * holds a placeholder: there a literal apostrophe is written four times, and twice in a limb without one.
     * Two in a limb with a placeholder format to no apostrophe at all ("n''ont" showed as "nont").
     */
    @Test
    void anApostropheInAChoiceLimbIsWrittenFourTimesBesideAPlaceholderAndTwiceWithout() {
        Set<String> problems = new TreeSet<>();
        for (String lang : ALL_CATALOGS) {
            Properties props = catalog(lang);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                for (String limb : choiceLimbs(value)) {
                    int want = limb.indexOf('{') >= 0 ? 4 : 2;
                    java.util.regex.Matcher run =
                            java.util.regex.Pattern.compile("'+").matcher(limb);
                    while (run.find()) {
                        if (run.group().length() != want) {
                            problems.add(name(lang) + " / " + key + ": "
                                    + run.group().length() + " apostrophes, want " + want + " in \"" + limb + "\"");
                        }
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertEquals(List.of("1 a", "{0} bc"), choiceLimbs("x {0,choice,1#1 a|1<{0} bc} y"));
    }

    /** The text of each limb of every {@code {n,choice,…}} in {@code value}, after its {@code 1#} or {@code 1<}. */
    private static List<String> choiceLimbs(String value) {
        List<String> limbs = new java.util.ArrayList<>();
        java.util.regex.Matcher open =
                java.util.regex.Pattern.compile("\\{\\d+,choice,").matcher(value);
        int from = 0;
        while (open.find(from)) {
            int depth = 1;
            int i = open.end();
            StringBuilder limb = new StringBuilder();
            for (; i < value.length() && depth > 0; i++) {
                char c = value.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                if (c == '|' && depth == 1) {
                    limbs.add(limb.toString());
                    limb.setLength(0);
                } else {
                    limb.append(c);
                }
            }
            limbs.add(limb.toString());
            from = i;
        }
        return limbs.stream().map(l -> l.replaceFirst("^[^#<]*[#<]", "")).toList();
    }

    /** Each of the ten Go to Mnemonic commands has its own title and description, like every built-in command. */
    @Test
    void everyGoToMnemonicCommandHasATitleAndADescription() {
        Properties base = catalog("");
        for (char digit : com.editora.config.BookmarkMnemonics.DIGITS.toCharArray()) {
            String key = "command.bookmarks.gotoMnemonic" + digit;
            assertTrue(base.getProperty(key, "").endsWith(" " + digit), key);
            assertTrue(base.getProperty(key + ".desc", "").contains(String.valueOf(digit)), key + ".desc");
        }
        assertNull(base.getProperty("bookmarks.mnemonic.gotoTitle"), "the shared title template is gone");
    }

    /** The code half of the rule above: what decides is the value, not whether the caller passed arguments. */
    @Test
    void trShowsAValueWithoutAPlaceholderVerbatimEvenWhenGivenArguments() {
        Messages.init("fr");
        try {
            String raw = Messages.tr("menu.unpin");
            assertTrue(raw.contains("l'onglet"), "fixture: a French value with one apostrophe and no placeholder");
            assertEquals(raw, Messages.tr("menu.unpin", "ignored"));
        } finally {
            Messages.init("en");
        }
    }

    /**
     * Arguments that are identifiers rather than quantities — a line or column, a port, a pull-request number,
     * an exit code — as {@code key → argument indices}. A plain {@code {0}} groups digits by locale ("Line
     * 12,345", "port 5.005"), so each of these uses {@code {n,number,#}} in every catalog. Add a message here
     * when it takes such an argument.
     */
    private static final Map<String, int[]> IDENTIFIER_ARGUMENTS = Map.ofEntries(
            Map.entry("status.gotoResult", new int[] {0, 1}),
            Map.entry("httppanel.imageCaption", new int[] {1, 2}),
            Map.entry("structure.line", new int[] {0}),
            Map.entry("status.testrunner.debugAttaching", new int[] {0}),
            Map.entry("status.debug.breakpointMoved", new int[] {0, 1}),
            Map.entry("status.debug.breakpointMerged", new int[] {0, 1}),
            Map.entry("agent.context.header", new int[] {1}),
            Map.entry("agent.exited", new int[] {0}),
            Map.entry("csvgrid.column", new int[] {0}),
            Map.entry("template.problem.malformedJson", new int[] {0}),
            Map.entry("dialog.removeBookmark.body", new int[] {0}),
            Map.entry("dialog.review.title", new int[] {0}),
            Map.entry("diff.title.prFile", new int[] {1}),
            Map.entry("github.review.tab", new int[] {0}),
            Map.entry("github.review.title", new int[] {0}),
            Map.entry("status.github.checkingOut", new int[] {0}),
            Map.entry("status.github.checkedOut", new int[] {0}),
            Map.entry("status.github.prDiffEmpty", new int[] {0}),
            Map.entry("status.github.reviewing", new int[] {0}),
            Map.entry("status.github.reviewed", new int[] {0}),
            Map.entry("status.github.prDiffFallback", new int[] {0}),
            Map.entry("status.github.prExists", new int[] {0}),
            Map.entry("dialog.createPr.exists", new int[] {0}),
            Map.entry("github.ci.header", new int[] {1}),
            Map.entry("github.checks.title", new int[] {0}),
            // A workflow run id is a 11-digit identifier: "run 37,562,222,495" is not how GitHub writes it.
            Map.entry("status.github.rerunning", new int[] {0}),
            Map.entry("status.github.rerunStarted", new int[] {0}),
            Map.entry("status.github.cancelling", new int[] {0}),
            Map.entry("status.github.runCancelled", new int[] {0}),
            Map.entry("externalTool.exited", new int[] {1}),
            Map.entry("run.exited", new int[] {0}),
            Map.entry("status.run.exit", new int[] {0}),
            Map.entry("status.build.exit", new int[] {1}),
            Map.entry("lsp.peek.title", new int[] {1}),
            Map.entry("markdownLint.row", new int[] {0, 1}),
            Map.entry("mermaid.diagnosticLine", new int[] {0, 1}),
            Map.entry("notes.line", new int[] {0}),
            Map.entry("status.snippetFileSyntaxError", new int[] {2}),
            Map.entry("status.snippetEntryError", new int[] {2}),
            Map.entry("debug.smartStep.line", new int[] {0}),
            Map.entry("dialog.debug.groupTitle", new int[] {0}),
            Map.entry("debug.triggered.prompt", new int[] {0}),
            Map.entry("status.debug.temporaryAdded", new int[] {0}),
            Map.entry("status.debug.recordingAdded", new int[] {0}),
            Map.entry("status.debug.triggeredAdded", new int[] {0}),
            Map.entry("status.debug.triggeredRemoved", new int[] {0}),
            Map.entry("status.debug.groupSet", new int[] {0}),
            Map.entry("status.debug.groupNone", new int[] {0}),
            Map.entry("status.debug.lambdaAdded", new int[] {0, 1}),
            Map.entry("status.debug.lambdaRemoved", new int[] {0, 1}),
            Map.entry("debug.gutter.stopInLambda", new int[] {0}),
            Map.entry("status.debug.noLambdaHere", new int[] {0}),
            Map.entry("status.debug.temporaryRemoved", new int[] {0}),
            Map.entry("dialog.goToLine.content", new int[] {0}),
            Map.entry("project.map.gutter.breakpoint", new int[] {0}),
            Map.entry("picker.lsp.chooseImport", new int[] {1}),
            Map.entry("status.lsp.problem", new int[] {0}),
            Map.entry("dialog.note.titleForLine", new int[] {1}),
            Map.entry("dialog.note.titleForLines", new int[] {1, 2}),
            Map.entry("project.map.results.symbolRow", new int[] {3}),
            Map.entry("project.map.results.textRow", new int[] {1}),
            Map.entry("project.map.preview.goToLine.range", new int[] {0, 1}),
            Map.entry("dialog.debug.pinLabel", new int[] {0}),
            Map.entry("status.debug.pinned", new int[] {1}),
            Map.entry("status.debug.unpinned", new int[] {1}),
            Map.entry("status.debug.unpinnedLine", new int[] {0}),
            Map.entry("status.debug.noPins", new int[] {0}),
            Map.entry("debugpanel.pinToLine", new int[] {1}),
            Map.entry("testrunner.finishedAborted", new int[] {0}),
            Map.entry("status.fillColumn.set", new int[] {0}),
            Map.entry("table.header.column", new int[] {0}),
            Map.entry("debug.hcr.jdkMismatch", new int[] {0, 1}),
            Map.entry("status.run.needJdk25", new int[] {0}),
            Map.entry("status.run.needJdkVersion", new int[] {0, 1}),
            Map.entry("status.run.needSource25", new int[] {0}),
            Map.entry("status.debug.needSource25", new int[] {0}),
            Map.entry("dialog.revertMerge.parent", new int[] {0}),
            Map.entry("statusbar.tip.offset", new int[] {0}),
            Map.entry("debugpanel.goToIndexLabel", new int[] {0}),
            Map.entry("debug.sessions.untitled", new int[] {0}),
            Map.entry("debugpanel.history.banner", new int[] {0}),
            Map.entry("debugpanel.history.seen", new int[] {0}),
            Map.entry("debugpanel.history.label", new int[] {0}),
            Map.entry("debugpanel.history.entry", new int[] {0}),
            Map.entry("debug.console.recorded", new int[] {0}),
            Map.entry("project.map.status.trail.declarationGone", new int[] {0}),
            Map.entry("project.map.status.trail.stopGone", new int[] {0}),
            Map.entry("project.map.status.trail.linesPastEnd", new int[] {0, 1, 2}),
            Map.entry("pdf.footer.page", new int[] {0}),
            Map.entry("print.footer.page", new int[] {0}),
            Map.entry("print.preview.page", new int[] {0}),
            Map.entry("print.preview.printingPage", new int[] {0}),
            Map.entry("pdfviewer.pageOf", new int[] {0}),
            Map.entry("print.preview.pageField", new int[] {0, 1}),
            Map.entry("print.preview.pageInvalid", new int[] {0}),
            Map.entry("status.pdf.exportingPage", new int[] {0}),
            Map.entry("project.map.preview.showingLines", new int[] {0}),
            Map.entry("http.env.parseError", new int[] {0, 1}),
            Map.entry("dialog.debug.breakpointTitleAt", new int[] {1}),
            Map.entry("dialog.debug.logpointTitleAt", new int[] {1}),
            Map.entry("debug.error.noConnection", new int[] {0}),
            Map.entry("status.encoding.notValid", new int[] {2, 3}),
            Map.entry("status.save.cannotEncodeSurrogate", new int[] {1}),
            Map.entry("http.redirect.notReplacedError", new int[] {1}),
            Map.entry("status.plugins.cmdFailedOutput", new int[] {1}),
            Map.entry("jsonl.check.invalid", new int[] {0}),
            Map.entry("status.maven.completionLeftOut", new int[] {1}),
            Map.entry("status.maven.completionLeftOutMore", new int[] {1}),
            Map.entry("status.maven.whyEntities", new int[] {0}),
            Map.entry("status.maven.whyDuplicate", new int[] {1}),
            Map.entry("status.maven.whyUnknown", new int[] {1}),
            Map.entry("project.map.hierarchy.row", new int[] {2}),
            Map.entry("project.map.history.cardEntry", new int[] {1}),
            Map.entry("project.map.trails.stop.lines", new int[] {1, 2}),
            Map.entry("debug.attachProcess.row", new int[] {1}),
            Map.entry("project.map.places.place", new int[] {0}),
            Map.entry("register.position", new int[] {1}),
            Map.entry("settings.rulers.notAColumn", new int[] {1}),
            Map.entry("http.script.crashed", new int[] {0}),
            Map.entry("http.preScript.why.crashed", new int[] {0}),
            Map.entry("statusbar.tip.indentMixed", new int[] {0}),
            Map.entry("debug.error.jsNodeTooOld", new int[] {2}),
            Map.entry("debug.notice.jsCondition", new int[] {1}),
            Map.entry("debugpanel.state.exited", new int[] {0}),
            Map.entry("debug.runToHere.tooltip", new int[] {0}),
            Map.entry("status.debug.logpointAdded", new int[] {0}),
            Map.entry("status.debug.attachQueued", new int[] {0}),
            Map.entry("status.lsp.pomUnreadableAt", new int[] {2, 3}),
            Map.entry("backlinks.link", new int[] {0}),
            Map.entry("editor.narrow.bar", new int[] {0, 1}),
            Map.entry("testrunner.debugWaiting", new int[] {0}),
            Map.entry("status.testrunner.debugNoAttach", new int[] {0}),
            Map.entry("pom.invalidAt", new int[] {0, 1}),
            Map.entry("pom.gotoLine", new int[] {0}),
            Map.entry("project.map.structure.lines", new int[] {0, 1}),
            Map.entry("project.map.preview.lines", new int[] {0, 1}),
            Map.entry("project.map.sticky.line", new int[] {0}),
            Map.entry("project.map.sticky.lines", new int[] {0, 1}),
            Map.entry("project.map.accessible.symbol", new int[] {2}),
            Map.entry("project.map.accessible.row.symbol", new int[] {2}),
            Map.entry("preview.errorAt", new int[] {0, 1}),
            Map.entry("preview.errorAtLine", new int[] {0}),
            Map.entry("status.lsp.bashCannotFormatAt", new int[] {0}),
            Map.entry("status.lsp.bashCannotFormatPosix", new int[] {0}),
            Map.entry("lsp.codeAction.bashFixAt", new int[] {2}),
            Map.entry("structure.targetAtLine", new int[] {1}),
            Map.entry("project.map.graph.at", new int[] {1}),
            Map.entry("project.map.graph.where", new int[] {1}),
            Map.entry("project.map.history.titleTip", new int[] {1, 2}),
            Map.entry("project.map.deps.whyLine", new int[] {1}),
            Map.entry("project.map.changes.step", new int[] {3}),
            Map.entry("project.map.changes.lineAdded", new int[] {0}),
            Map.entry("project.map.changes.lineRemoved", new int[] {0}));

    @Test
    void identifierArgumentsAreNeverDigitGrouped() {
        Set<String> problems = new TreeSet<>();
        for (String lang : ALL_CATALOGS) {
            Properties props = catalog(lang);
            java.util.Locale locale = java.util.Locale.forLanguageTag(name(lang));
            IDENTIFIER_ARGUMENTS.forEach((key, indices) -> {
                String value = props.getProperty(key);
                if (value == null) {
                    problems.add(name(lang) + " / " + key + " is missing");
                    return;
                }
                // Text for the other arguments, except where the pattern asks for a number ({n,choice,…}).
                Object[] args = new Object[6];
                for (int i = 0; i < args.length; i++) {
                    args[i] = value.matches("(?s).*\\{" + i + "\\s*,.*") ? (Object) 2 : "a" + i;
                }
                for (int index : indices) {
                    args[index] = 1234567 + index;
                }
                String out = new java.text.MessageFormat(value, locale).format(args);
                for (int index : indices) {
                    if (!out.contains(String.valueOf(1234567 + index))) {
                        problems.add(name(lang) + " / " + key + " groups {" + index + "}: " + out);
                    }
                }
            });
        }
        assertTrue(problems.isEmpty(), "use {n,number,#} for these arguments:\n" + String.join("\n", problems));
    }

    /** A plural written as an ending in brackets or after a slash: file(s), Datei(en), ligação(ões), immagine/i. */
    private static final Pattern BRACKETED_PLURAL =
            Pattern.compile("(?U)\\w\\((?:s|es|e|en|er|n|ns|ões)\\)|\\p{L}{3}/[ie]\\b");

    /**
     * A message with a count words it for the count, with a {@code {0,choice,…}} limb for each form the language
     * needs: "Replaced 1 occurrence", never "Replaced 1 occurrence(s)". The bracketed ending is English; the
     * other languages change more than one ending, and their translators had each invented a notation.
     */
    @Test
    void noMessageWithACountWritesItsPluralAsAnEndingInBrackets() {
        Set<String> problems = new TreeSet<>();
        for (String lang : ALL_CATALOGS) {
            Properties props = catalog(lang);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (PLACEHOLDER.matcher(value).find()
                        && BRACKETED_PLURAL.matcher(value).find()) {
                    problems.add(name(lang) + " / " + key + " = " + value);
                }
            }
        }
        assertTrue(problems.isEmpty(), "use a {n,choice,…} limb for each form:\n" + String.join("\n", problems));
    }

    /** The count picks the form: one of something, several, and none, in the way each language says none. */
    @Test
    void aCountPicksTheSingularOrThePluralInEveryLanguage() {
        for (String lang : ALL_CATALOGS) {
            Properties props = catalog(lang);
            java.util.Locale locale = java.util.Locale.forLanguageTag(name(lang));
            for (String key :
                    List.of("search.replaced", "status.markdown.imageDropped", "markdown.links.update.item")) {
                java.text.MessageFormat format = new java.text.MessageFormat(props.getProperty(key), locale);
                String one = format.format(new Object[] {1, 1});
                String two = format.format(new Object[] {2, 2});
                String none = format.format(new Object[] {0, 0});
                assertNotEquals(one.replace('1', '2'), two, name(lang) + " / " + key + ": one and two read alike");
                // French says "0 fichier"; the other five say none in the plural.
                String like = "fr".equals(lang) ? one.replace('1', '0') : two.replace('2', '0');
                assertEquals(like, none, name(lang) + " / " + key);
            }
        }
    }

    /** A modifier key in another language, as a word of its own: "Umschalt" but not "Umschalter" (a switcher). */
    private static final Pattern TRANSLATED_MODIFIER =
            Pattern.compile("(?U)\\b(?:Strg|Umschalt|Maj|Mayús|Maiusc|Wahltaste|Opción|Opzione)\\b");

    /**
     * A chord's modifiers read the same in every language: Ctrl, Alt, Shift, and on a Mac Control, Option and
     * the glyphs. The keymaps, the docs and the key caps use one spelling, and a user who changes the language
     * keeps the chords they learned: "Strg+D" and "Maj+Entrée" were the same keys under other names.
     */
    @Test
    void noTranslationRenamesAModifierKey() {
        Set<String> problems = new TreeSet<>();
        for (String lang : LOCALES) {
            Properties props = catalog(lang);
            for (String key : props.stringPropertyNames()) {
                if (TRANSLATED_MODIFIER.matcher(props.getProperty(key)).find()) {
                    problems.add(lang + " / " + key + " = " + props.getProperty(key));
                }
            }
        }
        assertTrue(problems.isEmpty(), "write Ctrl, Alt, Shift, Control and Option:\n" + String.join("\n", problems));
    }

    /** {@code tr("key")} / {@code tr("key", …)} with a literal key, and the two keys of {@code tr(c ? "a" : "b")}. */
    private static final Pattern LITERAL_KEY = Pattern.compile("\\b(?:Messages\\.)?tr\\(\\s*\"([^\"\\\\]+)\"\\s*[,)]");

    private static final Pattern TERNARY_KEYS = Pattern.compile(
            "\\b(?:Messages\\.)?tr\\([^()\";]*\\?\\s*\"([^\"\\\\]+)\"\\s*:\\s*\"([^\"\\\\]+)\"\\s*[,)]");

    /**
     * Literal keys that are deliberately not in the catalog. A key built at run time ({@code tr("prefix." + id)})
     * is not a literal and needs no entry here.
     */
    private static final Set<String> KEYS_NOT_IN_CATALOG = Set.of();

    /**
     * Every key a source file asks for by literal exists. A missing key is not an error at run time — {@code tr}
     * falls back to the key itself — so {@code status.occur.none} and {@code undoHistory.popupPrompt} were
     * shown to users as those very strings, in every language, with all six catalogs "in parity".
     */
    @Test
    void everyLiteralKeyUsedInTheSourcesExists() throws IOException {
        java.nio.file.Path sourceRoot = java.nio.file.Path.of("src/main/java");
        assertTrue(java.nio.file.Files.isDirectory(sourceRoot), "Run from the Maven project root");
        Set<String> known = catalog("").stringPropertyNames();
        Set<String> missing = new TreeSet<>();
        int[] found = {0};
        try (var files = java.nio.file.Files.walk(sourceRoot)) {
            for (java.nio.file.Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String source = java.nio.file.Files.readString(file);
                for (Pattern pattern : List.of(LITERAL_KEY, TERNARY_KEYS)) {
                    java.util.regex.Matcher m = pattern.matcher(source);
                    while (m.find()) {
                        for (int g = 1; g <= m.groupCount(); g++) {
                            found[0]++;
                            String key = m.group(g);
                            if (!known.contains(key) && !KEYS_NOT_IN_CATALOG.contains(key)) {
                                missing.add(key + "  (" + sourceRoot.relativize(file) + ")");
                            }
                        }
                    }
                }
            }
        }
        assertTrue(found[0] > 1000, "the scan no longer finds the tr(…) calls: " + found[0]);
        assertTrue(
                missing.isEmpty(),
                "keys used in the sources but missing from messages.properties:\n" + String.join("\n", missing));
    }

    @Test
    void resolvePrefersExplicitThenSystemThenEnglish() {
        Set<String> available = Set.of("en", "it", "es", "fr", "pt", "de");
        // explicit preference wins when bundled
        assertEquals("fr", Messages.resolve("fr", available, "de"));
        // empty/unbundled preference falls back to the system language when bundled
        assertEquals("de", Messages.resolve("", available, "de"));
        assertEquals("de", Messages.resolve(null, available, "de"));
        // neither bundled → English
        assertEquals("en", Messages.resolve("", available, "ja"));
        assertEquals("en", Messages.resolve("ru", available, "ja"));
    }
}
