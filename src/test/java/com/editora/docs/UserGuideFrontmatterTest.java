package com.editora.docs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The website builds the guide in user-docs/guide/ with a YAML front matter parser, so a page whose front matter it
 * cannot read stops the whole site (and the guide copy the app ships) from building. A new Doctor page once did: its
 * unquoted description held ": ", which YAML reads as a second mapping. Every page needs the four keys the sidebar
 * reads, and a plain value may not contain ": " or start with a character YAML treats as syntax.
 */
@Tag("guard")
class UserGuideFrontmatterTest {

    private static final Path GUIDE = Path.of("user-docs", "guide");
    private static final Set<String> REQUIRED = Set.of("title", "description", "category", "order");
    private static final Pattern KEY_VALUE = Pattern.compile("^([a-zA-Z]+):\\s*(.*)$");

    @Test
    void everyGuidePageHasFrontmatterTheSiteCanRead() throws IOException {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> pages = Files.list(GUIDE)) {
            for (Path page :
                    pages.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                problems.addAll(check(page));
            }
        }
        assertEquals(List.of(), problems);
    }

    private static List<String> check(Path page) throws IOException {
        List<String> lines = Files.readAllLines(page, StandardCharsets.UTF_8);
        String name = page.getFileName().toString();
        List<String> problems = new ArrayList<>();
        if (lines.isEmpty() || !lines.get(0).equals("---")) {
            return List.of(name + ": no front matter");
        }
        int end = lines.subList(1, lines.size()).indexOf("---");
        if (end < 0) {
            return List.of(name + ": front matter is not closed");
        }
        Set<String> seen = new java.util.HashSet<>();
        for (String line : lines.subList(1, end + 1)) {
            var m = KEY_VALUE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            seen.add(m.group(1));
            String value = m.group(2);
            boolean quoted = value.startsWith("\"") || value.startsWith("'");
            if (!quoted && (value.contains(": ") || value.matches("^[\\[{&*!|>%@`#].*"))) {
                problems.add(name + ": quote the " + m.group(1) + " value (" + value + ")");
            }
        }
        for (String key : REQUIRED) {
            if (!seen.contains(key)) {
                problems.add(name + ": no " + key);
            }
        }
        return problems;
    }
}
