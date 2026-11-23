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

    /** Paths of an adapter that names files {@code remote:<path>}. */
    private static final DapPaths PREFIXED = new DapPaths() {
        @Override
        public boolean owns(Path file) {
            return true;
        }

        @Override
        public String toAdapter(Path file) {
            return "remote:" + file;
        }

        @Override
        public Path fromAdapter(String path) {
            return path.startsWith("remote:") ? Path.of(path.substring("remote:".length())) : null;
        }
    };

    @Test
    void aFileNamedByARemoteAdapterIsComparedByItsLocalPath(@TempDir Path dir) {
        Path file = dir.resolve("A.java");
        assertTrue(JavaLaunchSupport.sameFile("remote:" + file, file, PREFIXED));
        assertFalse(JavaLaunchSupport.sameFile("jdt://x", file, PREFIXED), "a pseudo-source names no file");
        assertFalse(JavaLaunchSupport.sameFile(null, file, PREFIXED));
        assertFalse(JavaLaunchSupport.sameFile("remote:" + file, null, PREFIXED));
        assertTrue(JavaLaunchSupport.sameFile(file.toString(), file, DapPaths.LOCAL));
        assertFalse(JavaLaunchSupport.sameFile(null, file));
        assertFalse(JavaLaunchSupport.sameFile("x", null));
    }

    @Test
    void theFirstTypeNameSkipsCommentsImportsAndThePackage() {
        assertArrayEquals(
                new int[] {6, 13},
                JavaLaunchSupport.typeNamePosition(List.of(
                        "// class Fake",
                        "/* class Fake",
                        "   class Fake */",
                        "/** one line */",
                        "package p;",
                        "import java.util.List; * class Not",
                        "public class Real {}")));
        assertNull(JavaLaunchSupport.typeNamePosition(List.of("void main() {}")));
        assertNull(JavaLaunchSupport.typeNamePosition((Path) null));
    }

    @Test
    void theDeclaredPackageSkipsCommentsAndAnnotationsAndIsEmptyAfterCode(@TempDir Path dir) throws Exception {
        assertEquals(
                "a.b",
                JavaLaunchSupport.declaredPackage(
                        List.of("", "/* x", " */", "/* y */", "// z", " * w", "@Generated", "package a . b ;")));
        assertEquals("", JavaLaunchSupport.declaredPackage(List.of("import x;", "package late;")));
        assertEquals("", JavaLaunchSupport.declaredPackage(List.of()));
        assertEquals("", JavaLaunchSupport.declaredPackage(dir.resolve("missing.java")));
        Path f = dir.resolve("A.java");
        Files.writeString(f, "package q;\nclass A {}\n");
        assertEquals("q", JavaLaunchSupport.declaredPackage(f));
    }

    @Test
    void aSourceRootIsTheFolderAboveThePackagesFoldersWhenTheySpellIt() {
        Path root = Path.of("/w/src").toAbsolutePath();
        assertEquals(root, JavaLaunchSupport.sourceRoot(root.resolve("a/b/A.java"), "a.b"));
        assertEquals(root, JavaLaunchSupport.sourceRoot(root.resolve("A.java"), null));
        assertEquals(root, JavaLaunchSupport.sourceRoot(root.resolve("A.java"), " "));
        assertNull(JavaLaunchSupport.sourceRoot(root.resolve("x/b/A.java"), "a.b"), "misplaced");
        assertNull(JavaLaunchSupport.sourceRoot(Path.of("/A.java"), "a.b.c"), "the folders run out");
        assertNull(JavaLaunchSupport.sourceRoot(null, "a"));
        assertNull(JavaLaunchSupport.sourceRoot(Path.of("/"), "a"));
    }

    @Test
    void wrongSourceRootsAreTheFoldersBelowTheRootThatHoldTheFile(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("src");
        Path file = Files.createDirectories(root.resolve("a/b")).resolve("A.java");
        Files.writeString(file, "package a.b;");
        List<Path> current = List.of(root, root.resolve("a"), dir, dir.resolve("other"));
        assertEquals(List.of(root.resolve("a")), JavaLaunchSupport.wrongSourceRoots(current, file, root));
        assertEquals(List.of(), JavaLaunchSupport.wrongSourceRoots(null, file, root));
        assertEquals(List.of(), JavaLaunchSupport.wrongSourceRoots(current, null, root));
        assertEquals(List.of(), JavaLaunchSupport.wrongSourceRoots(current, file, null));
    }

    @Test
    void aQualifiedNameAndThePreviewQueryUseTheClassPartOfAModuleMainClass() {
        assertNull(JavaLaunchSupport.qualified(null, "a"));
        assertEquals("Main", JavaLaunchSupport.qualified("mod/x.y.Main", ""));
        assertEquals("Main", JavaLaunchSupport.qualified("x.Main", null));
        assertEquals("p.Main", JavaLaunchSupport.qualified("Main", "p"));
        String q = JavaLaunchSupport.previewSettingsQuery("mod/p.Main", null);
        assertTrue(q.contains("\"className\":\"p.Main\""), q);
        assertTrue(q.contains("\"projectName\":\"\""), q);
    }

    @Test
    void aProjectNameIsReadFromEveryReplyShape() {
        assertEquals("app", JavaLaunchSupport.projectName(List.of(Map.of("projectName", "app"))));
        assertNull(JavaLaunchSupport.projectName(List.of()));
        assertNull(JavaLaunchSupport.projectName(Map.of("projectName", 3)));
        assertNull(JavaLaunchSupport.projectName(Map.of("projectName", " ")));
        assertEquals("app", JavaLaunchSupport.projectName(JsonParser.parseString("[{\"projectName\":\"app\"}]")));
        assertNull(JavaLaunchSupport.projectName(JsonParser.parseString("[]")));
        assertNull(JavaLaunchSupport.projectName(JsonParser.parseString("{\"projectName\":{}}")));
        assertNull(JavaLaunchSupport.projectName(JsonParser.parseString("{}")));
        assertNull(JavaLaunchSupport.projectName("app"));
    }

    @Test
    void booleanRepliesAndStatusesInEveryShape() {
        assertTrue(JavaLaunchSupport.isTrue(true));
        assertTrue(JavaLaunchSupport.isTrue(JsonParser.parseString("true")));
        assertFalse(JavaLaunchSupport.isTrue(JsonParser.parseString("\"true\"")), "a JSON string is no boolean");
        assertFalse(JavaLaunchSupport.isTrue(JsonParser.parseString("{}")));
        assertTrue(JavaLaunchSupport.isTrue("true"));
        assertFalse(JavaLaunchSupport.isTrue(null));
        assertFalse(JavaLaunchSupport.isTrue(1));

        assertTrue(JavaLaunchSupport.statusOk(Map.of("status", true)));
        assertTrue(JavaLaunchSupport.statusOk(JsonParser.parseString("{\"status\":\"true\"}")));
        assertFalse(JavaLaunchSupport.statusOk(JsonParser.parseString("{\"status\":false}")));
        assertFalse(JavaLaunchSupport.statusOk(JsonParser.parseString("{}")));
        assertFalse(JavaLaunchSupport.statusOk(JsonParser.parseString("[]")));
        assertFalse(JavaLaunchSupport.statusOk(null));
    }

    @Test
    void sourcePathsKeepOnlyEntriesWithAPath() {
        assertEquals(
                List.of(Path.of("/a")),
                JavaLaunchSupport.sourcePaths(JsonParser.parseString(
                        "{\"data\":[{\"path\":\"/a\"},{\"name\":\"x\"},3,{\"path\":\"\\u0000\"}]}")));
        assertEquals(List.of(), JavaLaunchSupport.sourcePaths(JsonParser.parseString("{\"data\":{}}")));
        assertEquals(List.of(), JavaLaunchSupport.sourcePaths(JsonParser.parseString("{\"status\":true}")));
        assertEquals(List.of(), JavaLaunchSupport.sourcePaths(JsonParser.parseString("[]")));
        assertEquals(List.of(), JavaLaunchSupport.sourcePaths(null));
        assertEquals(
                List.of(Path.of("/b")), JavaLaunchSupport.sourcePaths(Map.of("data", List.of(Map.of("path", "/b")))));
    }

    @Test
    void aClassFileIsLookedForInFoldersOnly(@TempDir Path dir) throws Exception {
        Path out = Files.createDirectories(dir.resolve("out/p"));
        Files.writeString(out.resolve("Main.class"), "");
        List<String> paths = new ArrayList<>();
        paths.add(null);
        paths.add("\u0000bad");
        paths.add(dir.resolve("out").toString());
        assertTrue(JavaLaunchSupport.classFilePresent(paths, "mod/p.Main"));
        assertFalse(JavaLaunchSupport.classFilePresent(paths, "p.Other"));
        assertFalse(JavaLaunchSupport.classFilePresent(paths, null));
        assertFalse(JavaLaunchSupport.classFilePresent(paths, " "));
        assertFalse(JavaLaunchSupport.classFilePresent(null, "p.Main"));
    }

    @Test
    void aClassStateComparesTheClassFileWithItsSource(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("out/p"));
        Path classFile = classes.resolve("Main.class");
        Files.writeString(classFile, "");
        Path source = dir.resolve("Main.java");
        Files.writeString(source, "class Main {}");
        String out = dir.resolve("out").toString();
        Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(classFile, java.nio.file.attribute.FileTime.fromMillis(2_000_000));
        assertEquals(
                JavaLaunchSupport.ClassState.CURRENT, JavaLaunchSupport.classState(List.of(out), "p.Main", source));
        Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(3_000_000));
        assertEquals(JavaLaunchSupport.ClassState.STALE, JavaLaunchSupport.classState(List.of(out), "p.Main", source));
        assertEquals(
                JavaLaunchSupport.ClassState.CURRENT,
                JavaLaunchSupport.classState(List.of(out), "p.Main", dir.resolve("gone.java")),
                "no source to compare with");
        assertEquals(JavaLaunchSupport.ClassState.CURRENT, JavaLaunchSupport.classState(List.of(out), "p.Main", null));
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(dir.resolve("none").toString());
        withNull.add(out);
        assertEquals(JavaLaunchSupport.ClassState.MISSING, JavaLaunchSupport.classState(withNull, "p.Other", source));
        assertEquals(
                JavaLaunchSupport.ClassState.MISSING,
                JavaLaunchSupport.classState(List.of(out), "p.Other", null),
                "only folders: the class cannot be in a jar");
        assertEquals(
                JavaLaunchSupport.ClassState.UNKNOWN,
                JavaLaunchSupport.classState(List.of(out, "/lib/x.jar"), "p.Other", null),
                "a jar may hold it");
        assertEquals(
                JavaLaunchSupport.ClassState.UNKNOWN,
                JavaLaunchSupport.classState(List.of("/lib/x.jar"), "p.Other", source));
        assertEquals(JavaLaunchSupport.ClassState.UNKNOWN, JavaLaunchSupport.classState(null, "p.Main", source));
        assertEquals(JavaLaunchSupport.ClassState.UNKNOWN, JavaLaunchSupport.classState(List.of(out), " ", source));
        assertEquals(JavaLaunchSupport.ClassState.UNKNOWN, JavaLaunchSupport.classState(List.of(out), null, source));
    }

    @Test
    void theCompilerReportAndTheJvmWarningInTheirEdgeCases() {
        Path a = Path.of("/tmp/x/A.java");
        assertEquals("err", JavaLaunchSupport.compilerReport(null, "err", a, a));
        assertEquals("out", JavaLaunchSupport.compilerReport("out", null, a, null));
        assertEquals("/tmp/x/A.java:1", JavaLaunchSupport.compilerReport("/tmp/x/A.java:1", "", null, a));
        assertNull(JavaLaunchSupport.withoutJvmVersionWarning(null));
        assertEquals("x\n", JavaLaunchSupport.withoutJvmVersionWarning("Debugger JVM version: 21\nx\n"));
        assertEquals("x\n", JavaLaunchSupport.withoutJvmVersionWarning("x\nDebuggee JVM version: 8\n"));
        assertEquals("--enable-preview", JavaLaunchSupport.withPreview(null, true));
        assertEquals("", JavaLaunchSupport.withPreview(null, false));
    }

    @Test
    void argFilesAreSupportedUnlessTheReleaseFileSaysJava8(@TempDir Path dir) throws Exception {
        Path jdk = Files.createDirectories(dir.resolve("jdk/bin"));
        Path java = jdk.resolve("java");
        assertTrue(JavaLaunchSupport.supportsArgFiles(java.toString()), "no release file");
        Files.writeString(dir.resolve("jdk/release"), "IMPLEMENTOR=x\nJAVA_VERSION=\"1.8.0_392\"\n");
        assertFalse(JavaLaunchSupport.supportsArgFiles(java.toString()));
        Files.writeString(dir.resolve("jdk/release"), "IMPLEMENTOR=x\n");
        assertTrue(JavaLaunchSupport.supportsArgFiles(java.toString()), "no version in it");
        Files.writeString(dir.resolve("jdk/release"), "JAVA_VERSION=\"21\"\n");
        assertTrue(JavaLaunchSupport.supportsArgFiles(java.toString()));
        assertTrue(JavaLaunchSupport.supportsArgFiles(null));
        assertTrue(JavaLaunchSupport.supportsArgFiles("java"), "no release file beside a bare name");
        assertTrue(JavaLaunchSupport.supportsArgFiles("/java"));
        assertTrue(JavaLaunchSupport.supportsArgFiles("\u0000"));
    }
}
