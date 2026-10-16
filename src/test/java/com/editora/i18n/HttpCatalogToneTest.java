package com.editora.i18n;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The HTTP Client's strings address the user one way per language: German as "Sie", as most of its HTTP strings
 * do, and Spanish as "tú", as most of its strings do. Round 3 found "setze den Cursor" among "Sie" sentences and
 * an export dialog that said "su configuración … Déjelo" among "tú" ones.
 */
class HttpCatalogToneTest {

    private static final Pattern HTTP_KEY = Pattern.compile(
            "^(httppanel|status\\.http|dialog\\.httpTrust|http|command\\.http|dialog\\.exportConfig)\\..*");

    private static Properties catalog(String suffix) throws Exception {
        Properties p = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream("messages" + suffix + ".properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static List<String> matching(Properties p, Pattern wrongTone) {
        List<String> out = new ArrayList<>();
        for (String key : p.stringPropertyNames()) {
            if (HTTP_KEY.matcher(key).matches()
                    && wrongTone.matcher(p.getProperty(key)).find()) {
                out.add(key + "=" + p.getProperty(key));
            }
        }
        return out;
    }

    @Test
    void germanHttpStringsSaySie() throws Exception {
        Pattern du = Pattern.compile("\\b(du|dein|deine|deinen|setze|speichere|führe|wähle|drücke)\\b");
        assertEquals(List.of(), matching(catalog("_de"), du));
    }

    @Test
    void spanishHttpStringsSayTu() throws Exception {
        Pattern usted = Pattern.compile("(?i)\\b(vuelva a|déjelo|guarde|ejecute|pulse|su configuración|si va a)\\b");
        assertEquals(List.of(), matching(catalog("_es"), usted));
    }
}
