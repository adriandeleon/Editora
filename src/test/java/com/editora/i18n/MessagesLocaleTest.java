package com.editora.i18n;

import java.text.NumberFormat;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The locale that writes dates and numbers keeps the system's region ({@link Messages#locale}). */
class MessagesLocaleTest {

    private static final Locale AUSTRIA = Locale.of("de", "AT");
    private static final Locale JAPAN = Locale.of("ja", "JP");
    private static final Locale CANADA_FR = Locale.of("fr", "CA");
    private static final Locale US = Locale.of("en", "US");

    @Test
    void withNoLanguageChosenAndACatalogForTheSystemsLanguageItIsTheSystemsLocaleWhole() {
        assertEquals("de", Messages.resolve("", Messages.available().keySet(), AUSTRIA.getLanguage()));
        assertEquals(AUSTRIA, Messages.locale("", AUSTRIA, AUSTRIA));
        assertEquals(AUSTRIA, Messages.locale(null, AUSTRIA, AUSTRIA));
    }

    @Test
    void withNoLanguageChosenAndNoCatalogTheMenusAreEnglishAndTheSystemsLocaleIsKeptWhole() {
        assertEquals("en", Messages.resolve("", Messages.available().keySet(), JAPAN.getLanguage()));
        assertEquals(JAPAN, Messages.locale("", JAPAN, JAPAN));
    }

    @Test
    void withTheSystemsOwnLanguageChosenItsRegionIsKept() {
        assertEquals(AUSTRIA, Messages.locale("de", AUSTRIA, AUSTRIA));
        assertEquals(CANADA_FR, Messages.locale("fr", CANADA_FR, CANADA_FR));
    }

    @Test
    void withAnotherLanguageChosenItIsThatBareLanguage() {
        assertEquals(Locale.of("fr"), Messages.locale("fr", AUSTRIA, AUSTRIA));
        assertEquals(Locale.of("en"), Messages.locale("en", JAPAN, JAPAN));
    }

    @Test
    void aSystemThatSetsItsFormatsApartKeepsThemUnderItsOwnLanguage() {
        // An English system with Austrian dates and numbers: English chosen, or none, keeps the Austrian formats.
        assertEquals(AUSTRIA, Messages.locale("en", US, AUSTRIA));
        assertEquals(AUSTRIA, Messages.locale("", US, AUSTRIA));
        // German chosen is the language of its formats, so they stay Austrian rather than plain German.
        assertEquals(AUSTRIA, Messages.locale("de", US, AUSTRIA));
        assertEquals(Locale.of("it"), Messages.locale("it", US, AUSTRIA));
    }

    @Test
    void aCodeThatIsNotBundledIsNoChoice() {
        assertEquals(JAPAN, Messages.locale("ja", JAPAN, JAPAN));
        assertEquals(AUSTRIA, Messages.locale("system", AUSTRIA, AUSTRIA));
    }

    @Test
    void aMessageIsFormattedWithTheDefaultLocaleNotOneBuiltFromTheLanguage() {
        Locale before = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Messages.init("de");
            Locale.setDefault(AUSTRIA);
            // A count in a message is grouped the Austrian way, as every other number in the window is.
            String austrian = NumberFormat.getIntegerInstance(AUSTRIA).format(1234567);
            assertTrue(Messages.tr("find.replaced", 1234567).contains(austrian), Messages.tr("find.replaced", 1234567));
        } finally {
            Messages.init("en");
            Locale.setDefault(before);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }
}
