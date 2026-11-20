package com.editora.toolchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The edges of the toolchain package: every vendor name, every request form, folders, odd jkit answers. */
class ToolchainEdgesTest {

    @TempDir
    Path tmp;

    @Test
    void everyReleaseImplementorHasItsVendor() {
        assertEquals("temurin", Vendors.fromRelease(null, "Temurin-21.0.9+10"));
        assertEquals("microsoft", Vendors.fromRelease("Microsoft", null));
        assertEquals("sapmachine", Vendors.fromRelease("SAP SE", null));
        assertEquals("liberica", Vendors.fromRelease("BellSoft", null));
        assertEquals("semeru", Vendors.fromRelease("International Business Machines Corporation", null));
        assertEquals("jetbrains", Vendors.fromRelease("JetBrains s.r.o.", null));
        assertEquals("dragonwell", Vendors.fromRelease("Alibaba", null));
        assertEquals("kona", Vendors.fromRelease("Tencent", null));
        assertEquals("redhat", Vendors.fromRelease("Red Hat, Inc.", null));
        assertEquals("graalvm", Vendors.fromRelease("Oracle Corporation", "Oracle GraalVM 21.0.2+13.1"));
        assertEquals("oracle", Vendors.fromRelease("Oracle Corporation", null));
        assertEquals("oracle", Vendors.fromRelease("Oracle Corporation", "Oracle Corporation"));
        assertEquals("openjdk", Vendors.fromRelease("Oracle Corporation", "18.9"));
        assertNull(Vendors.fromRelease(null, null));
    }

    @Test
    void vendorNamesNormaliseFromEitherConvention() {
        assertEquals("temurin", Vendors.normalize("adoptium"));
        assertEquals("temurin", Vendors.normalize("TEM"));
        assertEquals("sapmachine", Vendors.normalize("sap_machine"));
        assertEquals("zulu", Vendors.normalize("zulu"));
        assertEquals("graalvm-ce", Vendors.fromFoojay("graalvm_community"));
        assertEquals("zulu-prime", Vendors.fromFoojay("zulu_prime"));
        assertTrue(Vendors.known("tem"));
        assertTrue(Vendors.known("corretto"));
        assertTrue(Vendors.known("redhat"));
        assertFalse(Vendors.known("nobody"));
        assertEquals("tem", Vendors.toSdkman("temurin"));
        assertEquals("dragonwell", Vendors.fromSdkman("albba"));
        assertNull(Vendors.toSdkman(null));
        assertNull(Vendors.toSdkman("redhat"));
        assertNull(Vendors.fromSdkman("xyz"));
    }

    @Test
    void sdkmanIdsThatAreNotIds() {
        assertNull(SdkmanIds.parse("21"));
        assertNull(SdkmanIds.parse("-tem"));
        assertNull(SdkmanIds.parse("21-"));
        assertNull(SdkmanIds.parse("tem-21"));
        assertNull(SdkmanIds.parse("21-12"));
        assertNull(SdkmanIds.parse("21-a.b"));
        assertNull(SdkmanIds.parse("4.0.0-rc1"));
        SdkmanIds.Parsed crac = SdkmanIds.parse("21.0.9.crac-zulu");
        assertNotNull(crac);
        assertEquals("21.0.9", crac.version());
        SdkmanIds.Parsed oldFx = SdkmanIds.parse("11.0.21-zulufx");
        assertNotNull(oldFx);
        assertTrue(oldFx.fx());
        assertEquals("zulu", oldFx.vendor());
        SdkmanIds.Parsed unknown = SdkmanIds.parse("22.1.0-xyz");
        assertNotNull(unknown);
        assertNull(unknown.vendor()); // its version can still match
    }

    @Test
    void requestsForMavenGradleFoldersAndOddJdks() throws Exception {
        ToolRequest maven = ToolRequest.parse("maven", "3.9");
        assertNotNull(maven);
        assertEquals("3.9", maven.version());
        assertNull(maven.vendor());

        ToolRequest home = ToolRequest.parse("jdk", "~/jdks/21");
        assertNotNull(home);
        assertEquals(Path.of(System.getProperty("user.home"), "jdks", "21"), home.path());
        assertNull(home.jkitInstallArgs());
        ToolRequest windows = ToolRequest.parse("jdk", "C:\\Java\\jdk-21");
        assertNotNull(windows);
        assertNotNull(windows.path());

        ToolRequest redhat = ToolRequest.parse("jdk", "21.0.9-redhat");
        assertNotNull(redhat);
        assertEquals("redhat", redhat.vendor());
        ToolRequest notJkit = ToolRequest.parse("jdk", "temurin");
        assertNotNull(notJkit);
        assertEquals("temurin", notJkit.version()); // no number: matches nothing, said missing
        ToolRequest bare = ToolRequest.parse("jdk", "-temurin");
        assertNotNull(bare);

        ToolInstall j = new ToolInstall("jdk", "21.0.9", "temurin", false, tmp, "other", "x");
        assertFalse(maven.matches(j)); // another tool
        ToolRequest fxWanted = ToolRequest.parse("jdk", "21.0.9.fx-tem");
        assertNotNull(fxWanted);
        assertFalse(fxWanted.matches(j));

        Path jdk = tmp.resolve("jdk-21");
        Files.createDirectories(jdk.resolve("bin"));
        Files.writeString(jdk.resolve("bin/java"), "");
        ToolRequest atFolder = ToolRequest.parse("jdk", jdk.toString());
        assertNotNull(atFolder);
        assertTrue(atFolder.matches(new ToolInstall("jdk", "21", null, false, jdk, "other", "jdk-21")));
        assertFalse(atFolder.matches(new ToolInstall("jdk", "21", null, false, tmp.resolve("gone"), "other", "g")));
    }

    @Test
    void foldersNamedByTheProjectAreUsedWhenTheyHoldTheTool() throws Exception {
        Path jdk = tmp.resolve("jdk");
        Files.createDirectories(jdk.resolve("bin"));
        Files.writeString(jdk.resolve("bin/java"), "");
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"21.0.9\"\nIMPLEMENTOR=\"Azul Systems, Inc.\"\n");
        Path bareJdk = tmp.resolve("bare");
        Files.createDirectories(bareJdk.resolve("bin"));
        Files.writeString(bareJdk.resolve("bin/java.exe"), "");
        Path maven = tmp.resolve("mvn");
        Files.createDirectories(maven.resolve("bin"));
        Files.writeString(maven.resolve("bin/mvn.cmd"), "");
        Path gradle = tmp.resolve("gradle");
        Files.createDirectories(gradle.resolve("bin"));
        Files.writeString(gradle.resolve("bin/gradle.bat"), "");

        ProjectToolchain.Resolution r = ProjectToolchain.resolve(
                Map.of("jdk", jdk.toString(), "maven", maven.toString(), "gradle", gradle.toString()),
                Map.of(),
                ProjectToolchain.Installs.NONE);
        assertEquals("zulu", r.jdk().install().vendor());
        assertEquals(maven.toAbsolutePath().normalize().toString(), r.home("maven"));
        assertEquals(gradle.toAbsolutePath().normalize().toString(), r.home("gradle"));
        assertTrue(r.missing().isEmpty());
        assertNull(r.of("ant"));
        assertEquals("bare", ProjectToolchain.atPath("jdk", bareJdk).version());
        assertNull(ProjectToolchain.atPath("maven", tmp.resolve("empty")));
        assertNull(ProjectToolchain.fromRelease(tmp.resolve("empty")));
        assertEquals(List.of(), ProjectToolchain.Installs.NONE.of("ant"));

        assertTrue(ProjectToolchain.executable("maven", "/m", false).endsWith("mvn"));
        assertTrue(ProjectToolchain.executable("maven", "/m", true).endsWith("mvn.cmd"));
        assertTrue(ProjectToolchain.executable("gradle", "/g", true).endsWith("gradle.bat"));
        assertTrue(ProjectToolchain.executable("gradle", "/g", false).endsWith("gradle"));
        assertTrue(ProjectToolchain.releaseValues("no equals\n=x\nA=b\n").containsKey("A"));
    }

    @Test
    void installsDescribeThemselves() {
        assertEquals(
                "JDK 21.0.9+10 · temurin · JavaFX",
                new ToolInstall("jdk", "21.0.9+10", "temurin", true, tmp, "jkit", "x").label());
        assertEquals("Maven 3.9.11", new ToolInstall("maven", "3.9.11", null, false, tmp, "jkit", "x").label());
        assertEquals("Gradle 8.14.3", new ToolInstall("gradle", "8.14.3", null, false, tmp, "jkit", "x").label());
        assertEquals(8, ToolInstall.major("jdk", "1.8.0_472"));
        assertEquals(1, ToolInstall.major("maven", "1.0"));
        assertEquals(0, ToolInstall.major("jdk", "ea"));
        assertEquals(0, ToolInstall.major("jdk", "99999999999"));
    }

    @Test
    void foldersThatAreNotInstallsAreSkipped() throws Exception {
        Path jkit = tmp.resolve("jkit");
        assertEquals(List.of(), JkitInstalls.installs(jkit, "jdk")); // no folder
        Path dir = jkit.resolve("tools/jdk");
        Files.createDirectories(dir.resolve("no-version"));
        Files.writeString(dir.resolve("no-version/" + JkitInstalls.MARKER), "vendor=temurin\n");
        Files.writeString(dir.resolve("a-file"), "");
        Files.createSymbolicLink(dir.resolve("alias"), dir.resolve("no-version"));
        assertEquals(List.of(), JkitInstalls.installs(jkit, "jdk"));
        assertNull(JkitInstalls.global(jkit, "jdk"));
        assertEquals(List.of(), SdkmanInstalls.installs(tmp.resolve("sdkman"), "gradle"));
        assertEquals("gradle", SdkmanInstalls.candidate("gradle"));
        assertEquals(tmp, SdkmanInstalls.dir(Map.of("SDKMAN_DIR", tmp.toString()), Path.of("/home/u")));
        assertNotNull(JkitInstalls.home());
        assertNotNull(SdkmanInstalls.dir());
    }

    @Test
    void sdkmanrcThatIsMissingOrHuge() throws Exception {
        assertEquals(Map.of(), SdkmanRc.read(tmp));
        Files.writeString(SdkmanRc.fileFor(tmp), "x".repeat(70 * 1024));
        assertEquals(Map.of(), SdkmanRc.read(tmp));
        SdkmanRc.remove(tmp.resolve("nowhere"), "java"); // no file: nothing to do
        assertEquals("java=21", SdkmanRc.with("java=17", "java", "21")); // a last line without its newline keeps none
        assertEquals("maven=3\njava=21\n", SdkmanRc.with("maven=3", "java", "21"));
    }

    @Test
    void jkitOutcomesForEveryWayItCanEnd() {
        JkitCli.Outcome cancelled = JkitCli.outcome(new ProcessRunner.Result(-1, "", ProcessRunner.CANCELLED));
        assertEquals(ProcessRunner.CANCELLED, cancelled.error());
        JkitCli.Outcome notAnObject = JkitCli.outcome(new ProcessRunner.Result(0, "[1]", ""));
        assertFalse(notAnObject.ok());
        JkitCli.Outcome garbage = JkitCli.outcome(new ProcessRunner.Result(1, "{nope", "\n\n"));
        assertEquals("jkit failed (exit 1)", garbage.error());
        JkitCli.Outcome failedQuietly = JkitCli.outcome(new ProcessRunner.Result(1, "{\"schemaVersion\":1}", ""));
        assertEquals("jkit failed (exit 1)", failedQuietly.error());
        JkitCli.Outcome tooOld = JkitCli.outcome(new ProcessRunner.Result(2, "", "Unknown option: --json"));
        assertTrue(tooOld.error().contains("too old"));
        JkitCli.Outcome plainLast = JkitCli.outcome(new ProcessRunner.Result(1, "", "downloading\nit broke\n"));
        assertEquals("it broke", plainLast.error());

        assertEquals(List.of(), JkitCli.parseList(null));
        assertEquals(List.of(), JkitCli.parseSearch(null));
        assertEquals(List.of(), JkitCli.parseUpgrade(null));
        assertNull(JkitCli.parseInstall(null));
        JkitCli.Available fx = new JkitCli.Available("jdk", "21.0.9", 21, null, true, false, true, false, false);
        assertEquals(List.of("install", "jdk@21.0.9", "--fx"), fx.installArgs());
    }
}
