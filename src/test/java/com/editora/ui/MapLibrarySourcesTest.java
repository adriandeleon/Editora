package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the Map writes a library class's source, and what it remembers of it. */
class MapLibrarySourcesTest {

    static final String JDK = "jdt://contents/java.base/java.util/ArrayList.class?=demo/%5C/jrt-fs.jar%60java.base";
    static final String JAR = "jdt://contents/guava-33.3.1-jre.jar/com.google.common.collect/ImmutableList.class"
            + "?=demo/%5C/home%5C/.m2%5C/guava-33.3.1-jre.jar=/maven.groupId=/com.google.guava=/";

    @TempDir
    Path temp;

    @Test
    void aClassFileUriNamesItsArchivePackageAndClass() {
        assertArrayEquals(new String[] {"java.base", "java.util", "ArrayList.class"}, MapLibrarySources.parts(JDK));
        assertArrayEquals(
                new String[] {"guava-33.3.1-jre.jar", "com.google.common.collect", "ImmutableList.class"},
                MapLibrarySources.parts(JAR));
        assertArrayEquals(
                new String[] {"a b.jar", "p", "C.class"},
                MapLibrarySources.parts("jdt://contents/a%20b.jar/p/C.class"));
        assertNull(MapLibrarySources.parts(null));
        assertNull(MapLibrarySources.parts("file:///p/A.java"));
        assertNull(MapLibrarySources.parts("jdt://contents/java.base/ArrayList.class"), "no package segment");
        assertNull(MapLibrarySources.parts("jdt://contents/java.base/java.util/ArrayList.java"));
        assertNull(MapLibrarySources.parts("jdt://contents/java.base/java.util/.class"));
        assertNull(MapLibrarySources.parts("jdt://contents/%20/java.util/A.class"), "a blank part");
        assertTrue(MapLibrarySources.showable(JDK));
        assertFalse(MapLibrarySources.showable("jdt://contents/x"));
    }

    @Test
    void itsSourceGoesUnderItsArchiveAndPackageFolders() {
        assertEquals(temp.resolve("java.base/java/util/ArrayList.java"), MapLibrarySources.fileFor(temp, JDK));
        assertEquals(
                temp.resolve("guava-33.3.1-jre.jar/com/google/common/collect/ImmutableList.java"),
                MapLibrarySources.fileFor(temp, JAR));
        assertEquals(
                temp.resolve("a_b.jar/p/Map$Entry.java"),
                MapLibrarySources.fileFor(temp, "jdt://contents/a%20b.jar/p/Map$Entry.class"));
        assertNull(MapLibrarySources.fileFor(temp, "file:///p/A.java"));
        assertEquals("_..", MapLibrarySources.safe(".."));
        assertEquals("_.", MapLibrarySources.safe("."));
        assertEquals("_", MapLibrarySources.safe(""));
        assertEquals("a_b_c", MapLibrarySources.safe("a/b\\c"));
    }

    @Test
    void writtenReadOnlyRememberedAndWrittenAgainWhenItsSourceChanges() throws Exception {
        Path anchor = temp.resolve("Use.java");
        Path file = MapLibrarySources.write(JDK, anchor, "class ArrayList {}\n");
        assertNotNull(file);
        assertEquals("ArrayList.java", file.getFileName().toString());
        assertEquals("class ArrayList {}\n", Files.readString(file));
        assertFalse(Files.isWritable(file), "a copy: read-only");
        MapLibrarySources.Library library = MapLibrarySources.of(file);
        assertEquals(new MapLibrarySources.Library(JDK, anchor, "java.base"), library);
        assertNull(MapLibrarySources.of(anchor));
        assertNull(MapLibrarySources.of(null));

        assertEquals(file, MapLibrarySources.write(JDK, anchor, "/** attached */\nclass ArrayList {}\n"));
        assertEquals("/** attached */\nclass ArrayList {}\n", Files.readString(file), "its sources came since");
        assertEquals(file, MapLibrarySources.write(JDK, anchor, "/** attached */\nclass ArrayList {}\n"));
        assertNull(MapLibrarySources.write("file:///p/A.java", anchor, "x"));
    }

    @Test
    void itsFolderIsRemovedReadOnlyFilesAndAll() throws Exception {
        Path base = Files.createDirectories(temp.resolve("lib/p"));
        Path file = Files.writeString(base.resolve("A.java"), "class A {}");
        file.toFile().setWritable(false);
        MapLibrarySources.remove(temp.resolve("lib"));
        assertFalse(Files.exists(temp.resolve("lib")));
        MapLibrarySources.remove(temp.resolve("gone")); // nothing there: nothing to do
    }
}
