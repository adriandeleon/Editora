package com.editora.toolchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectToolchainTest {

    @TempDir
    Path tmp;

    private Path jkitInstall(Path jkitHome, String tool, String dir, String properties) throws Exception {
        Path folder = jkitHome.resolve("tools").resolve(tool).resolve(dir);
        Files.createDirectories(folder.resolve("bin"));
        Files.writeString(folder.resolve(JkitInstalls.MARKER), properties);
        return folder;
    }

    private Path sdkmanInstall(Path sdkman, String candidate, String id) throws Exception {
        Path folder = sdkman.resolve("candidates").resolve(candidate).resolve(id);
        Files.createDirectories(folder.resolve("bin"));
        return folder;
    }

    // --- jkit and SDKMAN folders ------------------------------------------------------------------------------

    @Test
    void readsJkitInstallsFromTheirMarkersNewestFirst() throws Exception {
        Path home = tmp.resolve("jkit");
        jkitInstall(home, "jdk", "21.0.5+11-temurin", "tool=jdk\nversion=21.0.5+11\nvendor=temurin\nfx=false\n");
        jkitInstall(home, "jdk", "21.0.9+10-temurin", "tool=jdk\nversion=21.0.9+10\nvendor=temurin\n");
        jkitInstall(home, "jdk", "17.0.13+11-sap_machine-fx", "version=17.0.13+11\nvendor=sap_machine\nfx=true\n");
        Files.createDirectories(home.resolve("tools/jdk/half-done")); // no marker: an unfinished install

        List<ToolInstall> jdks = JkitInstalls.installs(home, "jdk");

        assertEquals(
                List.of("21.0.9+10", "21.0.5+11", "17.0.13+11"),
                jdks.stream().map(ToolInstall::version).toList());
        ToolInstall sap = jdks.get(2);
        assertEquals("sapmachine", sap.vendor()); // foojay's sap_machine, by jkit's name
        assertTrue(sap.fx());
        assertEquals(ToolInstall.JKIT, sap.source());
        assertEquals("17.0.13+11-sap_machine-fx", sap.id());
        assertEquals(17, sap.major());
    }

    @Test
    void jkitHomeIsJkitHomeElseDotJkit() {
        Path user = Path.of("/home/u");
        assertEquals(Path.of("/opt/jk"), JkitInstalls.home(Map.of("JKIT_HOME", "/opt/jk"), user));
        assertEquals(user.resolve(".jkit"), JkitInstalls.home(Map.of(), user));
        assertEquals(user.resolve(".sdkman"), SdkmanInstalls.dir(Map.of(), user));
    }

    @Test
    void readsJkitGlobalLinks() throws Exception {
        Path home = tmp.resolve("jkit");
        Path maven = jkitInstall(home, "maven", "3.9.11", "version=3.9.11\nvendor=\n");
        Files.createDirectories(home.resolve("global"));
        Files.createSymbolicLink(home.resolve("global/maven"), Path.of("../tools/maven/3.9.11"));

        assertEquals(maven.toAbsolutePath().normalize(), JkitInstalls.global(home, "maven"));
        assertNull(JkitInstalls.global(home, "jdk"));
    }

    @Test
    void readsSdkmanCandidatesSkippingCurrent() throws Exception {
        Path sdkman = tmp.resolve("sdkman");
        Path tem = sdkmanInstall(sdkman, "java", "21.0.9-tem");
        sdkmanInstall(sdkman, "java", "17.0.13.fx-zulu");
        Files.createSymbolicLink(sdkman.resolve("candidates/java/current"), tem);

        List<ToolInstall> jdks = SdkmanInstalls.installs(sdkman, "jdk");

        assertEquals(2, jdks.size());
        assertEquals("21.0.9", jdks.get(0).version());
        assertEquals("temurin", jdks.get(0).vendor());
        assertEquals("zulu", jdks.get(1).vendor());
        assertTrue(jdks.get(1).fx());
    }

    // --- ids, versions, requests ------------------------------------------------------------------------------

    @Test
    void sdkmanIdentifiersRoundTrip() {
        SdkmanIds.Parsed p = SdkmanIds.parse("21.0.9-tem");
        assertNotNull(p);
        assertEquals("21.0.9", p.version());
        assertEquals("temurin", p.vendor());
        assertFalse(p.fx());
        SdkmanIds.Parsed fx = SdkmanIds.parse("21.0.9.fx-librca");
        assertNotNull(fx);
        assertEquals("liberica", fx.vendor());
        assertTrue(fx.fx());
        assertNull(SdkmanIds.parse("26-ea")); // a version, not a vendor
        assertEquals("21.0.9-tem", SdkmanIds.format("21.0.9+10", "temurin", false));
        assertEquals("21.0.9.fx-zulu", SdkmanIds.format("21.0.9", "zulu", true));
        assertEquals("25.0.1-amzn", SdkmanIds.format("25.0.1+8", "corretto", false));
        assertNull(SdkmanIds.format("21.0.9", "somebody-new", false));
    }

    @Test
    void versionsMatchByPrefixAtABoundary() {
        assertTrue(Versions.matches("21.0.9+10", "21"));
        assertTrue(Versions.matches("21.0.9+10", "21.0.9"));
        assertTrue(Versions.matches("21.0.9", "21.0.9+10")); // SDKMAN writes no build
        assertFalse(Versions.matches("21.0.10", "21.0.1"));
        assertFalse(Versions.matches("210.0.1", "21"));
        assertTrue(Versions.key("21.0.10").compareTo(Versions.key("21.0.9+10")) > 0);
        assertTrue(Versions.key("26-ea+3").compareTo(Versions.key("26")) < 0);
    }

    @Test
    void requestsReadEveryFormItAccepts() {
        ToolRequest sdkman = ToolRequest.parse("jdk", "21.0.9-tem");
        assertNotNull(sdkman);
        assertEquals("21.0.9", sdkman.version());
        assertEquals("temurin", sdkman.vendor());

        ToolRequest jkit = ToolRequest.parse("jdk", "25.0.1+8-graalvm-ce");
        assertNotNull(jkit);
        assertEquals("25.0.1+8", jkit.version());
        assertEquals("graalvm-ce", jkit.vendor());

        ToolRequest jkitFx = ToolRequest.parse("jdk", "21.0.9+10-zulu-fx");
        assertNotNull(jkitFx);
        assertEquals(Boolean.TRUE, jkitFx.fx());

        ToolRequest major = ToolRequest.parse("jdk", "21");
        assertNotNull(major);
        assertNull(major.vendor());
        assertNull(major.fx());

        ToolRequest folder = ToolRequest.parse("jdk", "/opt/jdk-21");
        assertNotNull(folder);
        assertEquals(Path.of("/opt/jdk-21"), folder.path());

        assertNull(ToolRequest.parse("jdk", "  "));
        assertEquals(List.of("install", "jdk@21.0.9", "temurin"), sdkman.jkitInstallArgs());
        assertEquals(List.of("install", "jdk@21.0.9+10", "zulu", "--fx"), jkitFx.jkitInstallArgs());
    }

    @Test
    void theBestInstallPrefersTheExactIdThenExactVersionThenPlainThenNewest() {
        ToolInstall sdkman =
                new ToolInstall("jdk", "21.0.9", "temurin", false, Path.of("/s/21.0.9-tem"), "sdkman", "21.0.9-tem");
        ToolInstall jkit =
                new ToolInstall("jdk", "21.0.9+10", "temurin", false, Path.of("/j/21"), "jkit", "21.0.9+10-temurin");
        ToolInstall newer =
                new ToolInstall("jdk", "21.0.10+7", "temurin", false, Path.of("/j/21n"), "jkit", "21.0.10+7-temurin");
        ToolInstall fx = new ToolInstall("jdk", "21.0.11", "zulu", true, Path.of("/j/fx"), "jkit", "21.0.11-zulu-fx");
        List<ToolInstall> all = List.of(jkit, newer, sdkman, fx);

        assertEquals(sdkman, ToolRequest.parse("jdk", "21.0.9-tem").best(all)); // SDKMAN's own id
        assertEquals(newer, ToolRequest.parse("jdk", "21").best(all)); // newest plain 21, not the JavaFX one
        assertEquals(jkit, ToolRequest.parse("jdk", "21.0.9+10-temurin").best(all));
        assertNull(ToolRequest.parse("jdk", "21.0.9-zulu").best(all)); // that vendor is not installed
    }

    // --- .sdkmanrc ------------------------------------------------------------------------------------------

    @Test
    void sdkmanrcReadsEntriesAndIgnoresComments() {
        Map<String, String> rc =
                SdkmanRc.parse("# made by sdk env init\njava=21.0.9-tem # LTS\n\nmaven = 3.9.11\nbad\n");
        assertEquals(Map.of("java", "21.0.9-tem", "maven", "3.9.11"), rc);
    }

    @Test
    void sdkmanrcEditsKeepEverythingElse() {
        String text = "# comment\njava=17.0.13-tem # old\nmaven=3.9.11\n";
        assertEquals("# comment\njava=21.0.9-tem # old\nmaven=3.9.11\n", SdkmanRc.with(text, "java", "21.0.9-tem"));
        assertEquals(
                "# comment\njava=17.0.13-tem # old\nmaven=3.9.11\ngradle=8.14.3\n",
                SdkmanRc.with(text, "gradle", "8.14.3"));
        assertTrue(SdkmanRc.with("", "java", "21.0.9-tem").endsWith("\njava=21.0.9-tem\n"));
        assertEquals("# comment\nmaven=3.9.11\n", SdkmanRc.without(text, "java"));
    }

    @Test
    void sdkmanrcIsWrittenAndRemovedOnDisk() throws Exception {
        SdkmanRc.write(tmp, "java", "21.0.9-tem");
        assertEquals(Map.of("java", "21.0.9-tem"), SdkmanRc.read(tmp));
        SdkmanRc.write(tmp, "maven", "3.9.11");
        SdkmanRc.remove(tmp, "java");
        assertEquals(Map.of("maven", "3.9.11"), SdkmanRc.read(tmp));
    }

    // --- resolution -----------------------------------------------------------------------------------------

    @Test
    void theProjectFileWinsOverSdkmanrcAndMissingIsSaid() {
        ToolInstall jdk21 =
                new ToolInstall("jdk", "21.0.9+10", "temurin", false, Path.of("/j/21"), "jkit", "21.0.9+10-temurin");
        ToolInstall jdk25 =
                new ToolInstall("jdk", "25.0.1+8", "temurin", false, Path.of("/j/25"), "jkit", "25.0.1+8-temurin");
        ToolInstall maven = new ToolInstall("maven", "3.9.11", null, false, Path.of("/j/mvn"), "jkit", "3.9.11");
        ProjectToolchain.Installs installs =
                new ProjectToolchain.Installs(List.of(jdk21, jdk25), List.of(maven), List.of());

        ProjectToolchain.Resolution fromRc = ProjectToolchain.resolve(
                Map.of(), Map.of("java", "21.0.9-tem", "maven", "3.9", "gradle", "8.14.3"), installs);
        assertEquals("/j/21", fromRc.home("jdk"));
        assertEquals("/j/mvn", fromRc.home("maven"));
        assertNull(fromRc.home("gradle"));
        assertEquals(ProjectToolchain.Origin.SDKMANRC, fromRc.jdk().origin());
        assertEquals(
                List.of("gradle"),
                fromRc.missing().stream().map(ProjectToolchain.Choice::tool).toList());

        ProjectToolchain.Resolution own =
                ProjectToolchain.resolve(Map.of("jdk", "25"), Map.of("java", "21.0.9-tem"), installs);
        assertEquals("/j/25", own.home("jdk"));
        assertEquals(ProjectToolchain.Origin.PROJECT_SETTINGS, own.jdk().origin());
        assertNull(own.maven()); // the project says nothing about Maven: the user's own applies

        assertEquals(ProjectToolchain.Resolution.NONE, ProjectToolchain.resolve(Map.of(), Map.of(), installs));
        assertTrue(!fromRc.signature().equals(own.signature()));
    }

    @Test
    void releaseFilesNameTheirVendor() {
        Map<String, String> values = ProjectToolchain.releaseValues(
                "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"21.0.9\"\nMODULES=\"java.base javafx.base\"\n");
        assertEquals("21.0.9", values.get("JAVA_VERSION"));
        assertEquals("temurin", Vendors.fromRelease(values.get("IMPLEMENTOR"), null));
        assertEquals("corretto", Vendors.fromRelease("Amazon.com Inc.", "Corretto-21.0.9.10.1"));
        assertEquals("zulu", Vendors.fromRelease("Azul Systems, Inc.", "Zulu21.44+17-CA"));
        assertEquals("graalvm-ce", Vendors.fromRelease("GraalVM Community", "GraalVM CE 21.0.2+13.1"));
        assertNull(Vendors.fromRelease("Somebody", null));
    }

    @Test
    void scanFindsJkitSdkmanAndOtherJdksOnce(@TempDir Path other) throws Exception {
        Path jkit = tmp.resolve("jkit");
        Path j21 = jkitInstall(jkit, "jdk", "21.0.9+10-temurin", "version=21.0.9+10\nvendor=temurin\n");
        Path sdkman = tmp.resolve("sdkman");
        sdkmanInstall(sdkman, "maven", "3.9.11");
        Files.writeString(other.resolve("release"), "IMPLEMENTOR=\"Azul Systems, Inc.\"\nJAVA_VERSION=\"17.0.13\"\n");

        ProjectToolchain.Installs found =
                ProjectToolchain.scan(jkit, sdkman, List.of(j21.toString(), other.toString()));

        assertEquals(2, found.jdks().size()); // jkit's 21 is not counted again from the other list
        assertEquals("zulu", found.jdks().get(1).vendor());
        assertEquals(ToolInstall.OTHER, found.jdks().get(1).source());
        assertEquals(
                List.of("3.9.11"),
                found.mavens().stream().map(ToolInstall::version).toList());
    }
}
