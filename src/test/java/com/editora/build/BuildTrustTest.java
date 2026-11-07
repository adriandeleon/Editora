package com.editora.build;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The trust prompt's content (review B10): it names what a build in the folder would run. */
class BuildTrustTest {

    @Test
    void everyToolButGoGatesRuns() {
        assertTrue(BuildTrust.gates(BuildTool.MAVEN));
        assertTrue(BuildTrust.gates(BuildTool.GRADLE));
        assertTrue(BuildTrust.gates(BuildTool.SBT), "build.sbt and project/*.scala are compiled and run");
        assertTrue(BuildTrust.gates(BuildTool.MILL), "build.mill is Scala that Mill compiles and runs");
        assertTrue(BuildTrust.gates(BuildTool.LEIN), "project.clj is Clojure that lein evaluates");
        assertTrue(BuildTrust.gates(BuildTool.BABASHKA), "bb.edn tasks are Clojure, with :init before each");
        assertTrue(BuildTrust.gates(BuildTool.CLOJURE), "a deps.edn alias can add a -javaagent");
        assertTrue(BuildTrust.gates(BuildTool.CARGO), "build.rs and procedural macros run on cargo build");
        assertTrue(BuildTrust.gates(BuildTool.SCALA_CLI), "macros and compiler plugins run as it compiles");
        assertTrue(BuildTrust.gates(BuildTool.NPM), "pre/post scripts, install hooks, a repository's yarnPath");
        assertFalse(BuildTrust.gates(BuildTool.GO), "go build never runs the module's code");
    }

    @Test
    void aLeiningenFolderListsItsProjectAndLeinClasspath(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("project.clj"), "(defproject demo \"1.0\")\n");
        Files.writeString(root.resolve("profiles.clj"), "{}\n");
        Files.writeString(root.resolve(".lein-classpath"), "evil.jar\n");
        assertEquals(
                java.util.List.of(
                        tr("trust.item.leinScripts", "project.clj, profiles.clj"),
                        tr("trust.item.launcherOptions", ".lein-classpath")),
                BuildTrust.describe(BuildTool.LEIN, root, null, "").items());
    }

    @Test
    void aCargoFolderNamesBuildScriptsAndTheSettingsThatSwapTheCompiler(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Cargo.toml"), "[package]\nname = \"demo\"\n");
        assertEquals(
                java.util.List.of(tr("trust.item.cargoBuild")),
                BuildTrust.describe(BuildTool.CARGO, root, null, "").items(),
                "a plain crate still runs its dependencies' build scripts");
        Files.createDirectories(root.resolve(".cargo"));
        Files.writeString(root.resolve(".cargo").resolve("config.toml"), "[build]\nrustc-wrapper = \"./x\"\n");
        Files.writeString(root.resolve("rust-toolchain.toml"), "[toolchain]\npath = \"./tc\"\n");
        assertEquals(
                java.util.List.of(
                        tr("trust.item.cargoBuild"),
                        tr("trust.item.cargoSettings", ".cargo/config.toml, rust-toolchain.toml")),
                BuildTrust.describe(BuildTool.CARGO, root, null, "").items());
    }

    @Test
    void anNpmFolderNamesItsScriptsAndThePackageManagerFiles(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("package.json"), "{}");
        assertEquals(
                java.util.List.of(tr("trust.item.npmScripts")),
                BuildTrust.describe(BuildTool.NPM, root, null, "").items());
        Files.writeString(root.resolve(".npmrc"), "script-shell=./sh\n");
        Files.writeString(root.resolve(".yarnrc.yml"), "yarnPath: .yarn/releases/yarn.cjs\n");
        Files.writeString(root.resolve(".pnpmfile.cjs"), "");
        assertEquals(
                java.util.List.of(
                        tr("trust.item.npmScripts"),
                        tr("trust.item.packageManagerFiles", ".npmrc, .yarnrc.yml, .pnpmfile.cjs")),
                BuildTrust.describe(BuildTool.NPM, root, null, "").items());
    }

    @Test
    void theClojureAndScalaCliToolsSayWhatTheirFilesRun(@TempDir Path root) throws Exception {
        assertEquals(
                java.util.List.of(tr("trust.item.bbTasks")),
                BuildTrust.describe(BuildTool.BABASHKA, root, null, "").items());
        assertEquals(
                java.util.List.of(tr("trust.item.depsAliases")),
                BuildTrust.describe(BuildTool.CLOJURE, root, null, "").items());
        assertEquals(
                java.util.List.of(tr("trust.item.scalaCli")),
                BuildTrust.describe(BuildTool.SCALA_CLI, root, null, "").items());
    }

    @Test
    void aMavenFolderListsTheWrapperThePluginsTheMvnFilesAndTheOverride(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><artifactId>demo</artifactId><build><plugins>
                  <plugin><groupId>org.openjfx</groupId><artifactId>javafx-maven-plugin</artifactId><version>0.0.8</version></plugin>
                  <plugin><artifactId>maven-surefire-plugin</artifactId></plugin>
                </plugins></build>
                <profiles><profile><id>p</id><build><plugins>
                  <plugin><groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId></plugin>
                </plugins></build></profile></profiles></project>
                """);
        Files.createDirectories(root.resolve(".mvn"));
        Files.writeString(root.resolve(".mvn").resolve("extensions.xml"), "<extensions/>");
        Files.writeString(root.resolve(".mvn").resolve("maven.config"), "-s evil.xml");
        Path wrapper = root.resolve("mvnw");

        BuildTrust.Summary s = BuildTrust.describe(BuildTool.MAVEN, root, wrapper, "/opt/mvn/bin/mvn");

        assertEquals(root, s.root());
        assertEquals(wrapper, s.wrapper());
        assertEquals(
                java.util.List.of(
                        tr("trust.item.wrapper", "mvnw"),
                        tr(
                                "trust.item.plugins",
                                "org.openjfx:javafx-maven-plugin:0.0.8, org.apache.maven.plugins:maven-surefire-plugin, "
                                        + "org.codehaus.mojo:exec-maven-plugin"),
                        tr("trust.item.mvnFiles", ".mvn/extensions.xml, .mvn/maven.config"),
                        tr("trust.item.projectOverride", "/opt/mvn/bin/mvn", "mvn")),
                s.items());
    }

    /**
     * The override item names the project file's own command, the one trusting would apply — read even though the
     * folder is not trusted yet, when the effective command is still the user's own Settings one (which is never
     * listed as the project's).
     */
    @Test
    void theOverrideItemIsTheProjectFilesCommandNotTheUsers(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");
        Path file = com.editora.config.ProjectSettings.fileFor(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"buildCommands\": {\"maven\": \"  /opt/team/mvn  \"}}");
        com.editora.config.ProjectSettings project = com.editora.config.ProjectSettings.load(root);
        assertEquals("/usr/bin/mvn", project.buildCommand("maven", "/usr/bin/mvn", false), "untrusted: not applied");

        BuildTrust.Summary s = BuildTrust.describe(BuildTool.MAVEN, root, null, project.ownBuildCommand("maven"));

        assertEquals("/opt/team/mvn", s.projectOverride());
        assertEquals(java.util.List.of(tr("trust.item.projectOverride", "/opt/team/mvn", "mvn")), s.items());
        assertEquals("", project.ownBuildCommand("gradle"), "a tool the file leaves alone has none");
        assertEquals("", new com.editora.config.ProjectSettings().ownBuildCommand("maven"), "nor does a missing file");
    }

    @Test
    void manyPluginsAreCappedAndABrokenPomIsSaidSo(@TempDir Path root) throws Exception {
        StringBuilder plugins = new StringBuilder();
        for (int i = 0; i < BuildTrust.MAX_LISTED_PLUGINS + 3; i++) {
            plugins.append("<plugin><artifactId>p").append(i).append("</artifactId></plugin>");
        }
        Files.writeString(
                root.resolve("pom.xml"),
                "<project><artifactId>demo</artifactId><build><plugins>" + plugins + "</plugins></build></project>");
        BuildTrust.Summary capped = BuildTrust.describe(BuildTool.MAVEN, root, null, "");
        java.util.List<String> first = new java.util.ArrayList<>();
        for (int i = 0; i < BuildTrust.MAX_LISTED_PLUGINS; i++) {
            first.add("org.apache.maven.plugins:p" + i);
        }
        assertEquals(
                java.util.List.of(tr("trust.item.pluginsMore", String.join(", ", first), 3)),
                capped.items(),
                "the first few, then how many more");

        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>x");
        assertEquals(
                java.util.List.of(tr("trust.item.pomUnreadable")),
                BuildTrust.describe(BuildTool.MAVEN, root, null, null).items());
    }

    @Test
    void aPomWithNoPluginsStillNamesTheBuildFile(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");
        assertEquals(
                java.util.List.of(tr("trust.item.buildFile", "pom.xml")),
                BuildTrust.describe(BuildTool.MAVEN, root, null, "").items());
    }

    @Test
    void aGradleFolderListsItsScripts(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle.kts"), "");
        Files.writeString(root.resolve("settings.gradle.kts"), "");
        Files.createDirectories(root.resolve("buildSrc"));
        BuildTrust.Summary s = BuildTrust.describe(BuildTool.GRADLE, root, root.resolve("gradlew"), "");
        assertEquals(
                java.util.List.of(
                        tr("trust.item.wrapper", "gradlew"),
                        tr("trust.item.gradleScripts", "build.gradle.kts, settings.gradle.kts, buildSrc/")),
                s.items());
    }

    @Test
    void aMillFolderListsTheLauncherTheBuildScriptsAndTheLauncherOptions(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.mill"), "package build\n");
        Files.writeString(root.resolve("build.sc"), "");
        Files.createDirectories(root.resolve("mill-build"));
        Files.writeString(root.resolve(".mill-jvm-opts"), "-javaagent:evil.jar\n");
        BuildTrust.Summary s = BuildTrust.describe(BuildTool.MILL, root, root.resolve("mill"), "");
        assertEquals(
                java.util.List.of(
                        tr("trust.item.wrapper", "mill"),
                        tr("trust.item.millScripts", "build.mill, build.sc, mill-build/"),
                        tr("trust.item.launcherOptions", ".mill-jvm-opts")),
                s.items());
    }

    @Test
    void aMillFolderOnThePathMillStillNamesItsBuildScript(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.mill.yaml"), "");
        assertEquals(
                java.util.List.of(tr("trust.item.millScripts", "build.mill.yaml")),
                BuildTrust.describe(BuildTool.MILL, root, null, "").items());
    }

    @Test
    void anSbtFolderListsTheBuildDefinitionAndTheLauncherOptions(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.sbt"), "");
        Files.createDirectories(root.resolve("project"));
        Files.writeString(root.resolve("project").resolve("plugins.sbt"), "");
        Files.writeString(root.resolve(".jvmopts"), "");
        Files.writeString(root.resolve(".sbtopts"), "");
        assertEquals(
                java.util.List.of(
                        tr("trust.item.sbtScripts", "build.sbt, project/"),
                        tr("trust.item.launcherOptions", ".jvmopts, .sbtopts"),
                        tr("trust.item.projectOverride", "/opt/sbt/bin/sbt", "sbt")),
                BuildTrust.describe(BuildTool.SBT, root, null, "/opt/sbt/bin/sbt")
                        .items());
    }
}
