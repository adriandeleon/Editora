package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JavaLaunchSupportTest {

    @Test
    void classNameDropsTheModuleJdtlsPrefixes() {
        assertEquals("app.core.ModMain", JavaLaunchSupport.className("app.core/app.core.ModMain"));
        assertEquals("demo.Args", JavaLaunchSupport.className("demo.Args"));
        assertEquals("Tiny", JavaLaunchSupport.className("Tiny"));
        assertNull(JavaLaunchSupport.className(null));
    }

    @Test
    void sameFileSeesThroughASymlinkedDirectory(@TempDir Path dir) throws Exception {
        Path real = Files.createDirectories(dir.resolve("real/src"));
        Path file = Files.writeString(real.resolve("Loop.java"), "class Loop {}\n");
        Path link = dir.resolve("link");
        try {
            Files.createSymbolicLink(link, dir.resolve("real"));
        } catch (Exception e) {
            assumeTrue(false, "symbolic links are not available here: " + e);
        }
        Path viaLink = link.resolve("src/Loop.java");

        assertTrue(JavaLaunchSupport.sameFile(file.toRealPath().toString(), viaLink), "jdtls reports the real path");
        assertTrue(JavaLaunchSupport.sameFile(viaLink.toString(), file));
        assertFalse(JavaLaunchSupport.sameFile(real.resolve("Other.java").toString(), viaLink));
    }

    @Test
    void sameFileStillComparesPathsThatDoNotExist(@TempDir Path dir) {
        Path gone = dir.resolve("a/../Gone.java");
        assertTrue(JavaLaunchSupport.sameFile(dir.resolve("Gone.java").toString(), gone));
        assertFalse(JavaLaunchSupport.sameFile(null, gone));
        assertFalse(JavaLaunchSupport.sameFile(gone.toString(), null));
    }

    @Test
    void typeNamePositionFindsTheFirstDeclaredTypeAndSkipsCommentsAndImports() {
        assertArrayEquals(
                new int[] {5, 19},
                JavaLaunchSupport.typeNamePosition(List.of(
                        "/*",
                        " * class Header {}",
                        " */",
                        "package demo.record;",
                        "import demo.interface_.Thing; // class Nope",
                        "public final class LoopTest {",
                        "    class Inner {}",
                        "}")));
        assertArrayEquals(new int[] {0, 7}, JavaLaunchSupport.typeNamePosition(List.of("record Point(int x) {}")));
        assertArrayEquals(
                new int[] {1, 5}, JavaLaunchSupport.typeNamePosition(List.of("// enum No", "enum Café { A }")));
    }

    @Test
    void typeNamePositionIsNullForACompactSourceOrAnUnreadableFile(@TempDir Path dir) {
        assertNull(JavaLaunchSupport.typeNamePosition(List.of("void main() {", "    IO.println(1);", "}")));
        assertNull(JavaLaunchSupport.typeNamePosition(dir.resolve("Missing.java")));
    }

    @Test
    void projectNameReadsBothReplyShapesAsPlainCollectionsOrGson() {
        assertEquals(
                "myproj",
                JavaLaunchSupport.projectName(
                        Map.of("declaringType", "demo.T", "projectName", "myproj", "hasMainMethod", false)));
        assertEquals(
                "tiny_9f8e",
                JavaLaunchSupport.projectName(List.of(Map.of("mainClass", "Tiny", "projectName", "tiny_9f8e"))));
        assertEquals("myproj", JavaLaunchSupport.projectName(JsonParser.parseString("{\"projectName\":\"myproj\"}")));
        assertEquals("myproj", JavaLaunchSupport.projectName(JsonParser.parseString("[{\"projectName\":\"myproj\"}]")));
        assertNull(JavaLaunchSupport.projectName(List.of()));
        assertNull(JavaLaunchSupport.projectName(JsonParser.parseString("[]")));
        assertNull(JavaLaunchSupport.projectName(Map.of("projectName", " ")));
        assertNull(JavaLaunchSupport.projectName(null));
        assertNull(JavaLaunchSupport.projectName("myproj"));
    }

    @Test
    void classFilePresentLooksInDirectoriesForTheClassPartOfTheName(@TempDir Path dir) throws Exception {
        Path empty = Files.createDirectories(dir.resolve("bin"));
        Path classes = Files.createDirectories(dir.resolve("classes/app/core"));
        Files.writeString(classes.resolve("ModMain.class"), "x");
        List<String> both = List.of(
                empty.toString(),
                dir.resolve("lib.jar").toString(),
                dir.resolve("classes").toString());

        assertTrue(JavaLaunchSupport.classFilePresent(both, "app.core.ModMain"));
        assertTrue(JavaLaunchSupport.classFilePresent(both, "app.core/app.core.ModMain"));
        assertFalse(JavaLaunchSupport.classFilePresent(List.of(empty.toString()), "app.core.ModMain"));
        assertFalse(JavaLaunchSupport.classFilePresent(both, "app.core.Other"));
        assertFalse(JavaLaunchSupport.classFilePresent(List.of(), "app.core.ModMain"));
        assertFalse(JavaLaunchSupport.classFilePresent(both, null));
    }

    @Test
    void thePreviewQueryIsOneJsonStringNamingTheClassAndProject() {
        var q = JsonParser.parseString(JavaLaunchSupport.previewSettingsQuery("app.core/app.core.ModMain", null))
                .getAsJsonObject();
        assertEquals("app.core.ModMain", q.get("className").getAsString());
        assertEquals("", q.get("projectName").getAsString());
        assertTrue(q.get("inheritedOptions").getAsBoolean());
        assertEquals(
                "enabled",
                q.getAsJsonObject("expectedOptions")
                        .get(JavaLaunchSupport.PREVIEW_OPTION)
                        .getAsString());
    }

    @Test
    void isTrueAcceptsOnlyATrueReply() {
        assertTrue(JavaLaunchSupport.isTrue(Boolean.TRUE));
        assertTrue(JavaLaunchSupport.isTrue(JsonParser.parseString("true")));
        assertTrue(JavaLaunchSupport.isTrue("true"));
        assertFalse(JavaLaunchSupport.isTrue(Boolean.FALSE));
        assertFalse(JavaLaunchSupport.isTrue(JsonParser.parseString("\"true\"")));
        assertFalse(JavaLaunchSupport.isTrue(null));
    }

    @Test
    void withPreviewAddsTheFlagOnceAheadOfTheUsersArguments() {
        assertEquals("--enable-preview", JavaLaunchSupport.withPreview("", true));
        assertEquals("--enable-preview", JavaLaunchSupport.withPreview(null, true));
        assertEquals("--enable-preview -Xmx1g -Dk=\"a b\"", JavaLaunchSupport.withPreview("-Xmx1g -Dk=\"a b\"", true));
        assertEquals("-Xmx1g --enable-preview", JavaLaunchSupport.withPreview("-Xmx1g --enable-preview", true));
        assertEquals("-Xmx1g", JavaLaunchSupport.withPreview("-Xmx1g", false));
        assertEquals("", JavaLaunchSupport.withPreview(null, false));
    }

    private static Map<String, Object> launchWithClassPathOf(int chars) {
        List<String> cp = new ArrayList<>();
        String entry = "/repo/" + "x".repeat(93) + ".jar"; // 103 chars + the separator
        for (int n = 0; n < chars; n += entry.length() + 1) {
            cp.add(entry);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mainClass", "com.app.Main");
        m.put("classPaths", cp);
        return m;
    }

    @Test
    void shortenCommandLineOnlyWhenTheCommandCannotBeStarted() {
        // Windows: the whole command line is limited to 32,767 characters.
        assertNull(JavaLaunchSupport.shortenCommandLine(launchWithClassPathOf(20_000), true, exec -> true));
        assertEquals(
                "argfile", JavaLaunchSupport.shortenCommandLine(launchWithClassPathOf(40_000), true, exec -> true));
        // Elsewhere one argument is limited to 128 KiB; 40,000 characters is fine there.
        assertNull(JavaLaunchSupport.shortenCommandLine(launchWithClassPathOf(40_000), false, exec -> true));
        assertEquals(
                "argfile", JavaLaunchSupport.shortenCommandLine(launchWithClassPathOf(140_000), false, exec -> true));
        // A launcher older than JDK 9 has no @argfiles.
        assertEquals(
                "jarmanifest",
                JavaLaunchSupport.shortenCommandLine(launchWithClassPathOf(140_000), false, exec -> false));
        assertNull(JavaLaunchSupport.shortenCommandLine(new LinkedHashMap<>(), true, exec -> false));
    }

    @Test
    void windowsCountsTheRestOfTheCommandLineToo() {
        Map<String, Object> m = launchWithClassPathOf(29_000);
        assertNull(JavaLaunchSupport.shortenCommandLine(m, true, exec -> true));
        m.put("args", "y".repeat(3_000));
        assertEquals("argfile", JavaLaunchSupport.shortenCommandLine(m, true, exec -> true));
    }

    @Test
    void argFilesNeedAJdk9Launcher(@TempDir Path dir) throws Exception {
        Path jdk8 = Files.createDirectories(dir.resolve("jdk8/bin"));
        Files.writeString(dir.resolve("jdk8/release"), "JAVA_VERSION=\"1.8.0_402\"\nOS_NAME=\"Linux\"\n");
        Path jdk21 = Files.createDirectories(dir.resolve("jdk21/bin"));
        Files.writeString(dir.resolve("jdk21/release"), "IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"21.0.4\"\n");
        Path unknown = Files.createDirectories(dir.resolve("custom/bin"));

        assertFalse(JavaLaunchSupport.supportsArgFiles(jdk8.resolve("java").toString()));
        assertTrue(JavaLaunchSupport.supportsArgFiles(jdk21.resolve("java").toString()));
        assertTrue(JavaLaunchSupport.supportsArgFiles(unknown.resolve("java").toString()), "unknown counts as modern");
        assertTrue(JavaLaunchSupport.supportsArgFiles(""), "java-debug then uses the project's own runtime");
        assertTrue(JavaLaunchSupport.supportsArgFiles(null));
    }
}
