package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** jdtls's library sources: its settings, its commands' shapes and its SourceInvalidated event. */
class JavaLibrarySourcesTest {

    private static final String URI = "jdt://contents/guava-33.3.1-jre.jar/com.google.common.base/Preconditions.class"
            + "?=demo/%5C/home%5C/u%5C/.m2%5C/guava-33.3.1-jre.jar";

    @Test
    void theDownloadSwitchesGoIntoTheJavaSettingsAndTheInitializeOptions() {
        Map<String, Object> java = new java.util.HashMap<>(Map.of("maven", Map.of("updateSnapshots", false)));
        JavaLibrarySources.into(java, true);
        assertEquals(Map.of("updateSnapshots", false, "downloadSources", true), java.get("maven"), "kept beside");
        assertEquals(Map.of("downloadSources", true), java.get("eclipse"), "Gradle's");
        Object options = JavaLibrarySources.withDownloadSources(
                Map.of("settings", Map.of("java", Map.of("autobuild", Map.of("enabled", false)))), false);
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) ((Map<String, Object>) options).get("settings");
        @SuppressWarnings("unchecked")
        Map<String, Object> j = (Map<String, Object>) settings.get("java");
        assertEquals(Map.of("downloadSources", false), j.get("maven"));
        assertEquals(Map.of("enabled", false), j.get("autobuild"));
        assertEquals("plain", JavaLibrarySources.withDownloadSources("plain", true), "another shape as it is");
    }

    @Test
    void sourceInvalidatedNamesTheLibrariesThatGotSources() {
        Map<String, Object> data = Map.of("affectedRootPaths", Map.of("/home/u/.m2/guava-33.3.1-jre.jar", true));
        assertEquals(Set.of("/home/u/.m2/guava-33.3.1-jre.jar"), JavaLibrarySources.invalidatedRoots(500.0, data));
        assertEquals(
                1,
                JavaLibrarySources.invalidatedRoots("SourceInvalidated", data).size());
        assertTrue(JavaLibrarySources.invalidatedRoots(100.0, data).isEmpty(), "ClasspathUpdated");
        assertTrue(JavaLibrarySources.invalidatedRoots(500.0, null).isEmpty());
    }

    @Test
    void aClassFilesArchiveIsReadFromItsUri() {
        assertEquals("guava-33.3.1-jre.jar", JavaLibrarySources.archiveName(URI));
        assertNull(JavaLibrarySources.archiveName("file:///x/A.java"));
        assertTrue(JavaLibrarySources.inAny(URI, Set.of("/home/u/.m2/guava-33.3.1-jre.jar")));
        assertTrue(JavaLibrarySources.inAny(URI, Set.of("C:\\m2\\guava-33.3.1-jre.jar")));
        assertFalse(JavaLibrarySources.inAny(URI, Set.of("/home/u/.m2/guava-32.0.jar")));
    }

    /** A JDK module's class is in jrt-fs.jar, named by its URI's query; only a Maven artifact is downloadable (LS2, LS4). */
    @Test
    void aJdkModulesRootAndAMavenArtifactAreTold() {
        String jdk = "jdt://contents/java.base/java.util/ArrayList.class?=demo/%5C/opt%5C/jdk17%5C/lib%5C/jrt-fs.jar"
                + "%60java.base=/javadoc_location=/x";
        assertTrue(JavaLibrarySources.inAny(jdk, Set.of("/opt/jdk17/lib/jrt-fs.jar")));
        assertFalse(JavaLibrarySources.inAny(jdk, Set.of("/opt/jdk21/lib/jrt-fs.jar")));
        assertFalse(JavaLibrarySources.downloadable(jdk));
        String maven = "jdt://contents/c-1.0.jar/p/C.class?=demo/%5C/m2%5C/c-1.0.jar=/maven.pomderived=/true=/"
                + "=/maven.groupId=/com.example=/";
        assertTrue(JavaLibrarySources.downloadable(maven));
        assertFalse(
                JavaLibrarySources.downloadable("jdt://contents/libbase.jar/p/C.class?=demo/%5C/lib%5C/libbase.jar"));
    }

    @Test
    void theCommandsAreAskedAndAnsweredInJdtlssShapes(@TempDir Path dir) throws Exception {
        List<Object> resolve = JavaLibrarySources.request(URI, null, null);
        assertEquals("{\"classFileUri\":\"" + URI + "\"}", resolve.get(0).toString());
        String attach = JavaLibrarySources.request(URI, "/x/g.jar", "/x/g-sources.jar")
                .get(0)
                .toString();
        assertTrue(attach.contains("\"jarPath\":\"/x/g.jar\"") && attach.contains("\"sourceAttachmentPath\""), attach);
        Path sources = Files.writeString(dir.resolve("g-sources.jar"), "");
        var attached = JavaLibrarySources.attachment(
                Map.of("attributes", Map.of("jarPath", "/x/g.jar", "sourceAttachmentPath", sources.toString())));
        assertTrue(attached.attached());
        assertEquals("/x/g.jar", attached.jarPath());
        var none = JavaLibrarySources.attachment(Map.of("attributes", Map.of("jarPath", "/x/g.jar")));
        assertFalse(none.attached());
        assertEquals(
                "No source",
                JavaLibrarySources.attachment(Map.of("errorMessage", "No source"))
                        .error());
        assertNull(JavaLibrarySources.attachment(null));
    }
}
