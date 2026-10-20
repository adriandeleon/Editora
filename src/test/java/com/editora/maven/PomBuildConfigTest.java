package com.editora.maven;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The build folders and the Spring Boot plugin a pom configures (reviews D11, D17). */
class PomBuildConfigTest {

    @Test
    void readsTheBuildDirectoryAndTheReportFoldersWithPropertiesResolved() {
        String pom = "<project><properties><out>build</out></properties>"
                + "<build><directory>${basedir}/${out}</directory><plugins>"
                + "<plugin><artifactId>maven-surefire-plugin</artifactId>"
                + "<configuration><reportsDirectory>${project.build.directory}/test-reports</reportsDirectory></configuration></plugin>"
                + "<plugin><artifactId>maven-failsafe-plugin</artifactId></plugin>"
                + "</plugins></build></project>";
        PomBuildConfig cfg = PomBuildConfig.parse(pom, "/p");
        assertEquals("/p/build", cfg.buildDirectory());
        assertEquals("/p/build/test-reports", cfg.surefireReportsDirectory());
        assertNull(cfg.failsafeReportsDirectory());
        Path root = Path.of("/p");
        assertEquals(Path.of("/p/build/test-reports"), cfg.surefireReports(root));
        assertEquals(Path.of("/p/build/failsafe-reports"), cfg.failsafeReports(root));
        assertFalse(cfg.springBoot());

        PomBuildConfig defaults = PomBuildConfig.parse("<project/>", "");
        assertEquals(root.resolve("target").resolve("surefire-reports"), defaults.surefireReports(root));
        assertEquals(PomBuildConfig.DEFAULTS, PomBuildConfig.parse("<not xml", ""), "a broken pom contributes nothing");
    }

    @Test
    void theSpringBootPluginIsFoundInManagementAndProfilesButNotInAComment() {
        assertFalse(PomBuildConfig.parse("<project><!-- spring-boot-maven-plugin --></project>", "")
                .springBoot());
        PomBuildConfig managed = PomBuildConfig.parse(
                "<project><build><pluginManagement><plugins><plugin><groupId>org.springframework.boot</groupId>"
                        + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></pluginManagement></build></project>",
                "");
        assertTrue(managed.springBoot());
        assertNull(managed.springBootJvmArguments());
        PomBuildConfig profile = PomBuildConfig.parse(
                "<project><properties><dev.args>-Dspring.profiles.active=dev</dev.args></properties>"
                        + "<profiles><profile><id>dev</id><build><plugins><plugin>"
                        + "<artifactId>spring-boot-maven-plugin</artifactId>"
                        + "<configuration><jvmArguments>${dev.args} -Xmx1g</jvmArguments></configuration>"
                        + "</plugin></plugins></build></profile></profiles></project>",
                "");
        assertTrue(profile.springBoot());
        assertEquals("-Dspring.profiles.active=dev -Xmx1g", profile.springBootJvmArguments());
    }

    @Test
    void theParentChainOnDiskFillsWhatTheModuleLeavesUnset(@TempDir Path repo) throws Exception {
        Files.writeString(
                repo.resolve("pom.xml"),
                "<project><artifactId>parent</artifactId><build><directory>out</directory><plugins>"
                        + "<plugin><artifactId>spring-boot-maven-plugin</artifactId>"
                        + "<configuration><jvmArguments>-Xmx2g</jvmArguments></configuration></plugin>"
                        + "<plugin><artifactId>maven-surefire-plugin</artifactId>"
                        + "<configuration><reportsDirectory>${project.build.directory}/reports</reportsDirectory></configuration></plugin>"
                        + "</plugins></build></project>");
        Path app = Files.createDirectories(repo.resolve("app"));
        Files.writeString(
                app.resolve("pom.xml"),
                "<project><parent><artifactId>parent</artifactId></parent><artifactId>app</artifactId>"
                        + "<build><plugins><plugin><artifactId>spring-boot-maven-plugin</artifactId>"
                        + "<configuration><jvmArguments>-Xmx512m</jvmArguments></configuration></plugin></plugins></build></project>");

        PomBuildConfig cfg = PomBuildConfig.read(app);
        assertTrue(cfg.springBoot());
        assertEquals("-Xmx512m", cfg.springBootJvmArguments(), "the module's own value wins");
        assertEquals("out", cfg.buildDirectory(), "inherited");
        assertEquals(
                app.resolve("out").resolve("reports"),
                cfg.surefireReports(app),
                "the parent's folder, resolved for the module");

        // An explicit <relativePath/> means "no parent on disk".
        Files.writeString(
                app.resolve("pom.xml"),
                "<project><parent><artifactId>parent</artifactId><relativePath/></parent><artifactId>app</artifactId></project>");
        assertEquals(PomBuildConfig.DEFAULTS, PomBuildConfig.read(app.resolve("pom.xml")));
        assertEquals(PomBuildConfig.DEFAULTS, PomBuildConfig.read(repo.resolve("nowhere")));
    }
}
