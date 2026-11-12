package com.editora;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Os} is the one class that works out which operating system Editora runs on: what it answers for a
 * name, how a test runs as another OS, and — scanning the sources — that no other class works it out for itself.
 */
class OsTest {

    private static final Path SOURCES = Path.of("src/main/java");

    @AfterEach
    void reset() {
        Os.override(null);
        System.clearProperty(Os.PROPERTY);
    }

    @Test
    void aNameStandsForOneOfThreeSystems() {
        assertEquals(Os.Kind.LINUX, Os.parse("Linux"));
        assertEquals(Os.Kind.MAC, Os.parse("Mac OS X"));
        assertEquals(Os.Kind.MAC, Os.parse("Darwin"));
        assertEquals(Os.Kind.WINDOWS, Os.parse("Windows 11"));
        assertEquals(Os.Kind.WINDOWS, Os.parse("Windows Server 2022"));
        assertEquals(Os.Kind.WINDOWS, Os.parse("windows"));
        assertEquals(Os.Kind.MAC, Os.parse(" mac "));
        // Any other Unix runs as Linux does; so does a name the JVM did not give.
        assertEquals(Os.Kind.LINUX, Os.parse("FreeBSD"));
        assertEquals(Os.Kind.LINUX, Os.parse(""));
        assertEquals(Os.Kind.LINUX, Os.parse(null));
    }

    @Test
    void exactlyOneOfTheThreeAnswersIsTrue() {
        for (Os.Kind kind : Os.Kind.values()) {
            Os.override(kind);
            assertEquals(kind, Os.current());
            assertEquals(kind == Os.Kind.WINDOWS, Os.isWindows());
            assertEquals(kind == Os.Kind.MAC, Os.isMac());
            assertEquals(kind == Os.Kind.LINUX, Os.isLinux());
        }
    }

    @Test
    void withoutAnOverrideItIsTheSystemItRunsOn() {
        assertEquals(Os.parse(System.getProperty("os.name")), Os.current());
        assertFalse(Os.description().isBlank());
        assertTrue(Os.description().contains(Os.name()));
    }

    @Test
    void thePropertyRunsAWholeProcessAsAnotherSystemAndTheOverrideWinsOverIt() {
        System.setProperty(Os.PROPERTY, "windows");
        assertTrue(Os.isWindows());
        System.setProperty(Os.PROPERTY, "mac");
        assertTrue(Os.isMac());
        Os.override(Os.Kind.LINUX);
        assertTrue(Os.isLinux());
    }

    /** OS-02: {@code grep -rl '"os.name"' src/main/java} lists one file. */
    @Test
    void noOtherClassReadsTheOsName() throws IOException {
        assertEquals(List.of("com/editora/Os.java"), filesMatching(Pattern.compile("\"os\\.name\"")));
    }

    /** The other way to ask "is this Windows": a path separator compared with a character. */
    @Test
    void noClassInfersTheOsFromAPathSeparator() throws IOException {
        Pattern separatorTest = Pattern.compile(
                "(separatorChar|pathSeparatorChar)\\s*[!=]=|[!=]=\\s*(java\\.io\\.)?File\\.(separatorChar|pathSeparatorChar)"
                        + "|File\\.(separator|pathSeparator)\\)?\\.equals\\(|\\.equals\\(\\s*(java\\.io\\.)?File\\.(separator|pathSeparator)\\s*\\)");
        assertEquals(List.of(), filesMatching(separatorTest));
    }

    private static List<String> filesMatching(Pattern pattern) throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return pattern.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .map(p -> SOURCES.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }
}
