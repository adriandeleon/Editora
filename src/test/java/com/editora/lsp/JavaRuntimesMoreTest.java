package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.editora.lsp.JavaRuntimes.Jdk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * More of {@link JavaRuntimes}: the build layouts {@link JavaRuntimes#statedRelease} reads past (parents it may not
 * read, properties up the tree, catalogs without the key), JDKs found only through the preferred home, and the
 * launcher, bundle and runtime-entry readers on odd input.
 */
class JavaRuntimesMoreTest {

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    /** A JDK folder: a compiler and a release file stating {@code major}. */
    private static Path jdk(Path dir, int major) throws Exception {
        write(dir.resolve("bin/javac"), "");
        write(dir.resolve("bin/java"), "");
        write(dir.resolve("release"), "JAVA_VERSION=\"" + major + ".0.1\"\n");
        return dir;
    }

    private static String pom(String inner) {
        return "<project><modelVersion>4.0.0</modelVersion>" + inner + "</project>";
    }

    @Test
    void springBootsParentGivesItsOwnDefault(@TempDir Path dir) throws Exception {
        Path boot2 = dir.resolve("boot2");
        write(
                boot2.resolve("pom.xml"),
                pom("<parent><groupId>org.springframework.boot</groupId>"
                        + "<artifactId>spring-boot-starter-parent</artifactId><version>2.7.18</version>"
                        + "<relativePath/></parent>"));
        assertEquals(8, JavaRuntimes.statedRelease(boot2), "Spring Boot 2 defaults to Java 8");

        Path unversioned = dir.resolve("unversioned");
        write(
                unversioned.resolve("pom.xml"),
                pom("<parent><groupId>org.springframework.boot</groupId>"
                        + "<artifactId>spring-boot-starter-parent</artifactId><version>${boot}</version>"
                        + "<relativePath/></parent>"));
        assertEquals(0, JavaRuntimes.statedRelease(unversioned), "a version it cannot read");

        Path other = dir.resolve("other");
        write(
                other.resolve("pom.xml"),
                pom("<parent><groupId>g</groupId><artifactId>corp-parent</artifactId>"
                        + "<version>1</version><relativePath/></parent>"));
        assertEquals(0, JavaRuntimes.statedRelease(other), "another parent, from a repository: nothing stated");
    }

    @Test
    void modulesAreReadOnlyTwoLevelsDown(@TempDir Path dir) throws Exception {
        write(dir.resolve("pom.xml"), pom("<modules><module>a</module></modules>"));
        write(dir.resolve("a/pom.xml"), pom("<modules><module>b</module></modules>"));
        write(dir.resolve("a/b/pom.xml"), pom("<modules><module>c</module></modules>"));
        write(
                dir.resolve("a/b/c/pom.xml"),
                pom("<properties><maven.compiler.release>17</maven.compiler.release>" + "</properties>"));
        assertEquals(17, JavaRuntimes.statedRelease(dir.resolve("a")), "two levels down");
        assertEquals(0, JavaRuntimes.statedRelease(dir), "three levels down is too deep");
    }

    @Test
    void aParentIsReadOnlyWhenItIsTheOneNamed(@TempDir Path dir) throws Exception {
        String parentPom = pom("<groupId>org.acme</groupId><artifactId>acme-parent</artifactId>"
                + "<properties><maven.compiler.release>21</maven.compiler.release></properties>");
        write(dir.resolve("parent/pom.xml"), parentPom);

        Path byFolder = dir.resolve("byFolder");
        write(
                byFolder.resolve("pom.xml"),
                pom("<parent><groupId>org.acme</groupId>"
                        + "<artifactId>acme-parent</artifactId><relativePath>../parent</relativePath></parent>"));
        assertEquals(21, JavaRuntimes.statedRelease(byFolder), "a relative path to the parent's folder");

        Path wrongArtifact = dir.resolve("wrongArtifact");
        write(
                wrongArtifact.resolve("pom.xml"),
                pom(
                        "<parent><groupId>org.acme</groupId>"
                                + "<artifactId>other-parent</artifactId><relativePath>../parent/pom.xml</relativePath></parent>"));
        assertEquals(0, JavaRuntimes.statedRelease(wrongArtifact), "not the parent it names");

        Path wrongGroup = dir.resolve("wrongGroup");
        write(
                wrongGroup.resolve("pom.xml"),
                pom(
                        "<parent><groupId>org.other</groupId>"
                                + "<artifactId>acme-parent</artifactId><relativePath>../parent/pom.xml</relativePath></parent>"));
        assertEquals(0, JavaRuntimes.statedRelease(wrongGroup), "another group's");

        write(
                dir.resolve("inherits/pom.xml"),
                pom("<parent><groupId>org.acme</groupId><artifactId>root</artifactId>"
                        + "<relativePath/></parent><artifactId>mid</artifactId>"
                        + "<properties><maven.compiler.release>11</maven.compiler.release></properties>"));
        Path child = dir.resolve("inherits/child");
        write(
                child.resolve("pom.xml"),
                pom("<parent><groupId>org.acme</groupId><artifactId>mid</artifactId>" + "</parent>"));
        assertEquals(11, JavaRuntimes.statedRelease(child), "the parent's group is its own parent's");

        Path missing = dir.resolve("missing");
        write(
                missing.resolve("pom.xml"),
                pom("<parent><artifactId>x</artifactId>" + "<relativePath>../nowhere/pom.xml</relativePath></parent>"));
        assertEquals(0, JavaRuntimes.statedRelease(missing), "a parent that is not on disk");
    }

    @Test
    void propertiesResolveAFewLevelsDeepAndNotForever(@TempDir Path dir) throws Exception {
        Path chain = dir.resolve("chain");
        write(
                chain.resolve("pom.xml"),
                pom("<properties><a>${b}</a><b>${c}</b><c>17</c>"
                        + "<maven.compiler.release>${a}</maven.compiler.release></properties>"));
        assertEquals(17, JavaRuntimes.statedRelease(chain));

        Path loop = dir.resolve("loop");
        write(
                loop.resolve("pom.xml"),
                pom("<properties><a>${a}</a>" + "<maven.compiler.release>${a}</maven.compiler.release></properties>"));
        assertEquals(0, JavaRuntimes.statedRelease(loop), "a property naming itself states nothing");

        Path words = dir.resolve("words");
        write(
                words.resolve("pom.xml"),
                pom("<properties><maven.compiler.release>latest</maven.compiler.release>"
                        + "<maven.compiler.source>1.8</maven.compiler.source></properties>"));
        assertEquals(8, JavaRuntimes.statedRelease(words), "not a release: the source next");
    }

    @Test
    void aGradlePropertyIsLookedForUpToTheBuildsRoot(@TempDir Path dir) throws Exception {
        write(dir.resolve("settings.gradle"), "include 'app'");
        write(dir.resolve("gradle.properties"), "javaVersion=21\n");
        Path app = dir.resolve("app");
        write(
                app.resolve("build.gradle"),
                "java { toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) } }");
        assertEquals(21, JavaRuntimes.statedRelease(app), "the root's gradle.properties");

        Path unset = dir.resolve("unset");
        write(
                unset.resolve("build.gradle"),
                "java { toolchain { languageVersion = JavaLanguageVersion.of(jdkLevel) } }");
        assertEquals(0, JavaRuntimes.statedRelease(unset), "no property of that name up to the root");

        Path kts = dir.resolve("kts");
        write(kts.resolve("settings.gradle.kts"), "rootProject.name = \"k\"");
        write(kts.resolve("build.gradle.kts"), "kotlin { jvmToolchain(jdkLevel) }");
        assertEquals(0, JavaRuntimes.statedRelease(kts), "a Kotlin settings file is a root too");
    }

    @Test
    void aVersionCatalogIsReadOnlyForItsOwnKey(@TempDir Path dir) throws Exception {
        write(dir.resolve("settings.gradle.kts"), "include(\"app\")");
        write(dir.resolve("gradle/libs.versions.toml"), "[versions]\nkotlin = \"2.0.0\"\njava-lts = \"21\"\n");
        Path app = dir.resolve("app");
        write(app.resolve("build.gradle.kts"), "kotlin { jvmToolchain(libs.versions.java.lts.get()) }");
        assertEquals(21, JavaRuntimes.statedRelease(app), "found up the tree, the separators ignored");

        Path other = dir.resolve("other");
        write(other.resolve("build.gradle.kts"), "kotlin { jvmToolchain(libs.versions.jdk.get()) }");
        assertEquals(0, JavaRuntimes.statedRelease(other), "a key the catalog lacks");

        Path noCatalog = dir.resolve("noCatalog");
        write(noCatalog.resolve("settings.gradle"), "");
        write(
                noCatalog.resolve("build.gradle"),
                "java { toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.jdk) } }");
        assertEquals(0, JavaRuntimes.statedRelease(noCatalog), "the build's root has no catalog");
    }

    @Test
    void aConventionPluginWithoutAReleaseLeavesTheIncludedProjects(@TempDir Path dir) throws Exception {
        write(dir.resolve("buildSrc/src/main/groovy/conventions.gradle"), "plugins { id 'java' }");
        write(
                dir.resolve("buildSrc/build.gradle"),
                "java { toolchain { languageVersion = JavaLanguageVersion.of(11) } }");
        write(dir.resolve("settings.gradle"), "include 'lib'");
        write(dir.resolve("lib/build.gradle"), "java { sourceCompatibility = JavaVersion.VERSION_17 }");
        assertEquals(17, JavaRuntimes.statedRelease(dir));
    }

    @Test
    void thePreferredHomeAddsAJdkNoSearchFound(@TempDir Path dir) throws Exception {
        Path found = jdk(dir.resolve("found"), 21);
        Path extra = jdk(dir.resolve("opt/jdk-17"), 17);
        List<Jdk> jdks = List.of(new Jdk(21, found.toRealPath().toString()));

        List<Jdk> with = JavaRuntimes.withPreferred(jdks, extra.toString());
        assertEquals(2, with.size());
        assertEquals(17, with.get(1).major());
        assertSame(jdks, JavaRuntimes.withPreferred(jdks, found.toString()), "already there");
        assertSame(
                jdks, JavaRuntimes.withPreferred(jdks, dir.resolve("found/bin").toString()), "no JDK");
        Path noRelease = dir.resolve("broken");
        write(noRelease.resolve("bin/javac"), "");
        assertSame(jdks, JavaRuntimes.withPreferred(jdks, noRelease.toString()), "a compiler without a release");
        Path badRelease = jdk(dir.resolve("bad"), 0);
        write(badRelease.resolve("release"), "JAVA_VERSION=\"x\"\n");
        assertSame(jdks, JavaRuntimes.withPreferred(jdks, badRelease.toString()), "a release with no version");
        assertEquals(List.of(), JavaRuntimes.withPreferred(null, null));

        var runtimes = JavaRuntimes.runtimes(jdks, extra.toString(), true);
        assertEquals(
                List.of("JavaSE-21", "JavaSE-17"),
                runtimes.stream().map(e -> e.get("name")).toList());
        assertEquals(17, JavaRuntimes.defaultMajor(runtimes), "the preferred JDK is the default");
        assertEquals(extra.toRealPath().toString(), JavaRuntimes.defaultPath(runtimes));
    }

    @Test
    void aRemoteHostsJdksAreTakenAsTheyAre() {
        List<Jdk> jdks = new ArrayList<>(Arrays.asList(
                new Jdk(17, "/remote/jdk-17"), null, new Jdk(0, "/remote/odd"), new Jdk(21, " "), new Jdk(21, null)));
        var runtimes = JavaRuntimes.runtimes(jdks, "/remote/jdk-17", false);
        assertEquals(1, runtimes.size());
        assertEquals("/remote/jdk-17", runtimes.get(0).get("path"));
        assertEquals(Boolean.TRUE, runtimes.get(0).get("default"));
        assertEquals(List.of(), JavaRuntimes.runtimes(List.of(new Jdk(0, "/x")), null, false), "nothing usable");
        assertEquals(List.of(), JavaRuntimes.runtimes(null, null, false));
        assertEquals(
                "/remote/jdk-21",
                JavaRuntimes.defaultPath(JavaRuntimes.runtimes(
                        List.of(new Jdk(17, "/remote/jdk-17"), new Jdk(21, "/remote/jdk-21")), "/elsewhere", false)),
                "a preferred home none of them is: the newest");
    }

    @Test
    void theDefaultEntryIsReadOnlyWhenItIsWellFormed() {
        Map<String, Object> notDefault = Map.of("name", "JavaSE-17", "path", "/jdk/17");
        Map<String, Object> noName = new HashMap<>(Map.of("default", true, "name", 25, "path", 7));
        Map<String, Object> odd = Map.of("default", true, "name", "Custom-X", "path", "/jdk/x");
        assertNull(JavaRuntimes.defaultMajor(List.of(notDefault)));
        assertNull(JavaRuntimes.defaultMajor(List.of(noName)), "a name that is no string");
        assertNull(JavaRuntimes.defaultMajor(List.of(odd)), "a name that is no version");
        assertEquals(11, JavaRuntimes.defaultMajor(List.of(Map.of("default", true, "name", "11"))));
        assertNull(JavaRuntimes.defaultPath(List.of(notDefault, noName)));
        assertEquals("/jdk/x", JavaRuntimes.defaultPath(List.of(noName, odd)));
    }

    @Test
    void theHomesAreTakenInOrderAndBlankOnesSkipped() {
        assertEquals("/path/jdk", JavaRuntimes.preferredHome(" ", " /path/jdk ", "/java/home"));
        assertEquals("/java/home", JavaRuntimes.preferredHome(null, " ", " /java/home "));
        assertNull(JavaRuntimes.preferredHome(null, null, " "));
        assertNull(JavaRuntimes.preferredHome(null, null, null));
        assertNull(JavaRuntimes.homeOf(null));
        assertNull(JavaRuntimes.homeOf("  "));
        assertNull(JavaRuntimes.homeOf("java"), "not a path");
        assertNull(JavaRuntimes.homeOf("/no/such/jdk/bin/java"), "no JDK there");
        assertFalse(JavaRuntimes.isJdkHome(" "));
    }

    @Test
    void aLauncherPathIsAJdksOnlyAsHomeBinJava(@TempDir Path dir) throws Exception {
        assertEquals(Path.of("/opt/jdk").toString(), JavaRuntimes.homeOfPath(Path.of("/opt/jdk/bin/java.exe")));
        assertNull(JavaRuntimes.homeOfPath(Path.of("/opt/jdk/bin/javac")));
        assertNull(JavaRuntimes.homeOfPath(Path.of("/opt/jdk/lib/java")));
        assertNull(JavaRuntimes.homeOfPath(Path.of("java")));
        assertNull(JavaRuntimes.homeOfPath(Path.of("/java")));
        Path home = jdk(dir.resolve("jdk-21"), 21);
        assertEquals(
                home.toRealPath().toString(),
                JavaRuntimes.homeOf(home.resolve("bin/java").toString()));
        assertEquals(21, JavaRuntimes.javaMajor(home.resolve("bin/java")));
        assertEquals(0, JavaRuntimes.javaMajor(dir.resolve("nothing/bin/java")));
    }

    private static void bundle(Path jar, String executionEnvironment) throws Exception {
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        if (executionEnvironment != null) {
            manifest.getMainAttributes().putValue("Bundle-RequiredExecutionEnvironment", executionEnvironment);
        }
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new java.util.jar.JarEntry("a.txt"));
            out.closeEntry();
        }
    }

    @Test
    void onlyTheCoreBundlesSayWhatJavaJdtlsNeeds(@TempDir Path dir) throws Exception {
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        bundle(plugins.resolve("org.eclipse.jdt.ls.core_1.50.jar"), "JavaSE-21");
        bundle(plugins.resolve("org.eclipse.core.resources_3.20.jar"), "JavaSE-1.8, JavaSE-25");
        bundle(plugins.resolve("org.eclipse.jdt.core_3.40.jar"), null);
        bundle(plugins.resolve("org.eclipse.other_1.0.jar"), "JavaSE-30");
        write(plugins.resolve("org.eclipse.jdt.core_notes.txt"), "JavaSE-31");
        write(plugins.resolve("org.eclipse.jdt.core_broken.jar"), "not a jar");
        assertEquals(25, JavaRuntimes.bundleFloor(plugins));
        assertEquals(0, JavaRuntimes.bundleFloor(dir.resolve("missing")), "no folder");

        Path launcher = write(dir.resolve("bin/jdtls"), "#!/bin/sh");
        assertEquals(25, JavaRuntimes.serverMinMajor(launcher.toString()));
        assertEquals(
                JavaRuntimes.SERVER_MIN_MAJOR,
                JavaRuntimes.serverMinMajor(dir.resolve("x/jdtls").toString()));
        assertEquals(JavaRuntimes.SERVER_MIN_MAJOR, JavaRuntimes.serverMinMajor(null));
        assertEquals(JavaRuntimes.SERVER_MIN_MAJOR, JavaRuntimes.serverMinMajor("no-such-launcher-on-path-xyz"));
    }

    @Test
    void jdtlsRunsOnThePreferredJdkWhenItIsNewEnough(@TempDir Path dir) throws Exception {
        Path jdk25 = jdk(dir.resolve("jdk-25"), 25);
        List<Jdk> jdks = new ArrayList<>(Arrays.asList(
                null,
                new Jdk(17, "/jdk/17"),
                new Jdk(25, null),
                new Jdk(25, jdk25.toRealPath().toString()),
                new Jdk(26, "/jdk/26")));
        var preferred = JavaRuntimes.serverJava(17, jdks, jdk25.toString(), 21);
        assertTrue(preferred.executable().startsWith(jdk25.toRealPath().toString()), preferred.executable());
        var newest = JavaRuntimes.serverJava(17, jdks, null, 21);
        assertTrue(newest.executable().startsWith(Path.of("/jdk/26").toString()));
        assertEquals(new JavaRuntimes.ServerJava(null, true), JavaRuntimes.serverJava(17, null, null, 21));
        assertEquals(new JavaRuntimes.ServerJava(null, false), JavaRuntimes.serverJava(0, jdks, null, 21));
        assertEquals(new JavaRuntimes.ServerJava(null, false), JavaRuntimes.serverJava(21, jdks, null));
    }
}
