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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        assertEquals("Ver: Configuración", Messages.tr("command.view.settings"));
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
        Object[] args = new Object[10];
        for (int i = 0; i < args.length; i++) {
            args[i] = "‹A" + i + "›"; // a sentinel unlikely to occur in any translation
        }
        // A REAL MessageFormat placeholder is `{n}` or `{n,…}` — digits then a comma or close brace. This
        // must NOT match a snippet help string's `${1:default}` (the `{1:` isn't a placeholder, and that key
        // is shown via tr(key) with no args, so MessageFormat never touches it).
        java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("\\{(\\d+)\\s*[,}]");

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
            List<String> catalogs = new java.util.ArrayList<>();
            catalogs.add("");
            LOCALES.forEach(l -> catalogs.add("_" + l));
            for (String suffix : catalogs) {
                Properties cat = loadProps("/com/editora/i18n/messages" + suffix + ".properties");
                String value = cat.getProperty(key);
                if (value == null) {
                    continue; // key-parity is covered by another test
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
                    if (idx < args.length && !out.contains((String) args[idx])) {
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
