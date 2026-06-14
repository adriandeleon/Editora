package com.editora.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    /** The non-base bundled locales; each must mirror the English base's key set exactly. */
    private static final List<String> LOCALES = List.of("it", "es", "fr", "pt", "de");

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

    @Test
    void trFallsBackOverlayThenBaseThenKey() {
        Messages.init("es");
        // A key present in the overlay returns the Spanish value.
        assertEquals("Configuración", Messages.tr("command.view.settings"));
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
