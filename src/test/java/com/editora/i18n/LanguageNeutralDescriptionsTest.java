package com.editora.i18n;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Commands that work for every language describe themselves without a list of languages, which goes stale. Debug:
 * Toggle Debug Adapter and Debug: Set Adapter Path said "a Python or JavaScript debug adapter" long after the pickers
 * had grown to Go, Rust, C/C++, C#, Ruby, PHP, Bash, Dart, Lua, Firefox, Elixir and PowerShell; Run and Debug Test at
 * Caret named JUnit, pytest and node --test after Jest, Vitest, Go, Rust, C# and more had joined.
 */
@Tag("guard")
class LanguageNeutralDescriptionsTest {

    private static final List<String> SUFFIXES = List.of("", "_de", "_es", "_fr", "_it", "_pt");
    private static final List<String> KEYS = List.of(
            "command.debug.toggleAdapter.desc",
            "command.debug.setAdapterPath.desc",
            "command.test.runAtCaret.desc",
            "command.test.debugAtCaret.desc");
    private static final Pattern LANGUAGE_NAME =
            Pattern.compile("\\b(Java|Python|JavaScript|JUnit|pytest|Node\\.js)\\b");

    private static Properties catalog(String suffix) throws Exception {
        Properties p = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream("messages" + suffix + ".properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void everyLanguageCommandsNameNoLanguage() throws Exception {
        List<String> named = new ArrayList<>();
        for (String suffix : SUFFIXES) {
            Properties p = catalog(suffix);
            for (String key : KEYS) {
                String value = p.getProperty(key);
                if (value == null || LANGUAGE_NAME.matcher(value).find()) {
                    named.add("messages" + suffix + ": " + key + "=" + value);
                }
            }
        }
        assertEquals(List.of(), named);
    }
}
