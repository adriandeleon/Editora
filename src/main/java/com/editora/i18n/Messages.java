package com.editora.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Tiny UI-localization catalog. Strings live in {@code resources/com/editora/i18n/messages.properties}
 * (English, the base/fallback) plus {@code messages_<lang>.properties} overlays, loaded once at startup
 * via {@code getResourceAsStream} + a UTF-8 reader — the same resource-loading style as the keymap and
 * snippets, so no {@code module-info} {@code opens} is needed (we read our own module's resources).
 *
 * <p>{@link #tr(String)} returns the active language's value, falling back to English, then to the key
 * itself (so a missing key is visible, never an exception). {@link #tr(String, Object...)} applies
 * {@link MessageFormat} (e.g. {@code status.saved = Saved {0}}). The active language is chosen once by
 * {@code App.start} (after config load, before any UI is built); the UI is fully relabeled on restart.
 */
public final class Messages {

    /** Bundled UI languages: code → endonym (its own name). English is the base catalog. */
    private static final Map<String, String> LANGUAGES = new LinkedHashMap<>();

    static {
        LANGUAGES.put("en", "English");
        LANGUAGES.put("it", "Italiano");
        LANGUAGES.put("es", "Español");
        LANGUAGES.put("fr", "Français");
        LANGUAGES.put("pt", "Português");
        LANGUAGES.put("de", "Deutsch");
    }

    private static Map<String, String> base = Map.of();
    private static Map<String, String> active = Map.of();
    private static String currentLang = "en";

    private Messages() {}

    /** Loads the English base + the chosen language overlay (falling back to English for an unknown code). */
    public static synchronized void init(String lang) {
        base = load("en");
        currentLang = LANGUAGES.containsKey(lang) ? lang : "en";
        active = "en".equals(currentLang) ? base : load(currentLang);
    }

    private static Map<String, String> load(String lang) {
        String name = "en".equals(lang)
                ? "/com/editora/i18n/messages.properties"
                : "/com/editora/i18n/messages_" + lang + ".properties";
        Properties p = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream(name)) {
            if (in != null) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // missing/unreadable bundle → empty map; tr() falls back to English/key
        }
        Map<String, String> m = new HashMap<>();
        for (String key : p.stringPropertyNames()) {
            m.put(key, p.getProperty(key));
        }
        return m;
    }

    /** The localized string for {@code key} (active → English → the key itself). */
    public static String tr(String key) {
        String v = active.get(key);
        if (v == null) {
            v = base.get(key);
        }
        return v != null ? v : key;
    }

    /** The localized string for {@code key} with {@link MessageFormat} arguments. */
    public static String tr(String key, Object... args) {
        String pattern = tr(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return MessageFormat.format(pattern, args);
        } catch (RuntimeException e) {
            return pattern; // a malformed pattern shouldn't crash the UI
        }
    }

    /** The active language code (e.g. {@code "es"}). */
    public static String current() {
        return currentLang;
    }

    /** Bundled languages: code → endonym, in display order (English first). */
    public static Map<String, String> available() {
        return new LinkedHashMap<>(LANGUAGES);
    }

    /** The endonym for a language code (e.g. {@code "es"} → {@code "Español"}); the code itself if unknown. */
    public static String languageName(String code) {
        return LANGUAGES.getOrDefault(code, code);
    }

    /**
     * The effective UI language: the explicit {@code pref} if bundled, else the {@code systemLang} if
     * bundled, else English. Pure (no toolkit / no I/O) for unit testing.
     */
    public static String resolve(String pref, Set<String> available, String systemLang) {
        if (pref != null && available.contains(pref)) {
            return pref;
        }
        if (systemLang != null && available.contains(systemLang)) {
            return systemLang;
        }
        return "en";
    }
}
