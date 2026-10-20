package com.editora.test;

import java.nio.file.Path;
import java.util.List;

import com.editora.build.BuildTool;
import com.editora.maven.PomBuildConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The report folders a run polls: the standard ones, plus a pom's own (review D17). */
class JvmReportDirsTest {

    @Test
    void aPomsOwnFoldersComeFirstAndTheDefaultsStay() {
        Path root = Path.of("/m");
        PomBuildConfig pom = PomBuildConfig.parse(
                "<project><build><directory>build</directory><plugins><plugin>"
                        + "<artifactId>maven-surefire-plugin</artifactId>"
                        + "<configuration><reportsDirectory>${basedir}/test-reports</reportsDirectory></configuration>"
                        + "</plugin></plugins></build></project>",
                "/m");
        assertEquals(
                List.of(
                        Path.of("/m/test-reports"),
                        Path.of("/m/build/failsafe-reports"),
                        Path.of("/m/target/surefire-reports"),
                        Path.of("/m/target/failsafe-reports")),
                JvmReportDirs.reportDirs(BuildTool.MAVEN, root, pom));
        assertEquals(
                List.of(Path.of("/m/target/surefire-reports"), Path.of("/m/target/failsafe-reports")),
                JvmReportDirs.reportDirs(BuildTool.MAVEN, root, null),
                "nothing configured: the defaults, once each");
        assertEquals(
                JvmReportDirs.reportDirs(BuildTool.MAVEN, root), JvmReportDirs.reportDirs(BuildTool.MAVEN, root, null));
        assertEquals(List.of(), JvmReportDirs.reportDirs(BuildTool.GO, root, pom));
    }
}
