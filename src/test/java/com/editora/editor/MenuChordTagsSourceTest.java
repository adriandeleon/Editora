package com.editora.editor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A menu row shows the chord of the command it is {@linkplain MenuChords#tag tagged} with, so a tag that names
 * no command is a row that silently never shows one. Every id written in a tag is a command the catalog titles.
 */
class MenuChordTagsSourceTest {

    private static final Pattern TAG = Pattern.compile("MenuChords\\s*\\.\\s*tag\\s*\\(");

    /** The call's last argument, when it is a string literal: the command id. */
    private static final Pattern LITERAL_ID = Pattern.compile(",\\s*\"([^\"]+)\"\\s*$");

    @Test
    void everyTaggedIdIsACommandTheCatalogTitles() throws IOException {
        Properties catalog = new Properties();
        try (Reader in = Files.newBufferedReader(
                Path.of("src/main/resources/com/editora/i18n/messages.properties"), StandardCharsets.UTF_8)) {
            catalog.load(in);
        }
        List<String> unknown = new ArrayList<>();
        int tags = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/editora"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher tag = TAG.matcher(source);
                while (tag.find()) {
                    Matcher id = LITERAL_ID.matcher(arguments(source, tag.end()));
                    if (!id.find()) {
                        continue; // the id is a variable: a helper that its callers hand a literal
                    }
                    tags++;
                    if (!catalog.containsKey("command." + id.group(1))) {
                        long line = source.substring(0, tag.start()).lines().count() + 1;
                        unknown.add(file + ":" + line + " " + id.group(1));
                    }
                }
            }
        }
        assertTrue(tags > 100, "the scan found the tags (found " + tags + ")");
        assertTrue(
                unknown.isEmpty(),
                "these rows are tagged with an id that is no command:\n" + String.join("\n", unknown));
    }

    /** The text between the parenthesis that ends at {@code open} and the one that closes it. */
    private static String arguments(String source, int open) {
        int depth = 1;
        boolean inString = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return source.substring(open, i);
            }
        }
        return "";
    }
}
