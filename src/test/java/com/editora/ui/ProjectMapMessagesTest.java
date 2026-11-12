package com.editora.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every {@code project.map.*} string is used somewhere in the code, so a control that goes away takes its
 * strings (in all six catalogs) with it. The help popover's rows, the type selector and the flow selector build
 * their keys from a prefix, and a filter chip's tooltip is its key plus {@code .help}, so those are exempt.
 */
@Tag("guard")
class ProjectMapMessagesTest {

    /**
     * Keys the code builds rather than spells out. The Map code navigation's are built from each {@code
     * MapCardLinks.Kind}'s stem; {@code ProjectMapNavigationTest} checks every kind's texts are in the catalog.
     */
    private static final List<String> BUILT_FROM_A_PREFIX = java.util.stream.Stream.concat(
                    Stream.of("project.map.help.", "project.map.type.", "project.map.flow."),
                    java.util.Arrays.stream(MapCardLinks.Kind.values())
                            .filter(kind -> kind != MapCardLinks.Kind.REFERENCES && kind != MapCardLinks.Kind.CALLERS)
                            .map(kind -> "project.map.links." + kind.stem + "."))
            .toList();

    @Test
    void everyProjectMapStringIsUsed() throws IOException {
        Properties catalog = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/com/editora/i18n/messages.properties")) {
            catalog.load(in);
        }
        StringBuilder code = new StringBuilder();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                code.append(Files.readString(file));
            }
        }
        List<String> unused = catalog.stringPropertyNames().stream()
                .filter(key -> key.startsWith("project.map."))
                .filter(key -> BUILT_FROM_A_PREFIX.stream().noneMatch(key::startsWith))
                .filter(key -> code.indexOf('"' + key + '"') < 0)
                .filter(key -> !key.endsWith(".help")
                        || code.indexOf('"' + key.substring(0, key.length() - ".help".length()) + '"') < 0)
                .sorted()
                .toList();
        assertEquals(List.of(), unused, "Project Map strings no code uses: remove them from all six catalogs");
    }
}
