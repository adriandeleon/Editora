package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MANUAL PROBE (not part of the suite): JetBrains' Kotlin LSP in a real window, started from its installed
 * launcher, imports a Gradle build (which needs Gradle's Kotlin plugin, from the network or Gradle's cache) and
 * reports a type error in the Problems list. Run with {@code -Dtest=KotlinServerProbeFxTest -Dgroups=probe
 * -Dlsp.probe.kotlin=/path/to/kotlin-server-<v>/bin/intellij-server}.
 *
 * <p>Measured with 263.7084.0: the import takes ~9 s, then the file's diagnostics come both pushed and pulled
 * ({@code textDocument/diagnostic}; a pull during the import answers an empty report). A {@code .kts} script, a
 * {@code build.gradle.kts} and a {@code .kt} outside the build's source folders get no diagnostics and no hover;
 * a folder with no build file is not imported ({@code window/showMessage}: "Project import skipped"). The server
 * asks nothing (no data-sharing prompt: {@code dataSharing=NONE} unless {@code --data-sharing} is given).
 */
@Tag("probe")
@Tag("fx")
class KotlinServerProbeFxTest {

    @TempDir
    Path dir;

    @Test
    void aTypeErrorInAGradleProjectReachesTheProblemsList() throws Exception {
        String launcher = System.getProperty("lsp.probe.kotlin", "");
        Assumptions.assumeTrue(
                !launcher.isBlank() && Files.isExecutable(Path.of(launcher)),
                "opt-in: -Dlsp.probe.kotlin=<intellij-server>");
        FxTestSupport.bootToolkit();
        Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"probe\"\n");
        Files.writeString(
                dir.resolve("build.gradle.kts"),
                "plugins {\n    kotlin(\"jvm\") version \"2.2.20\"\n}\nrepositories { mavenCentral() }\n");
        Path source =
                Files.createDirectories(dir.resolve("src/main/kotlin/demo")).resolve("App.kt");
        Files.writeString(source, "package demo\n\nfun answer(): Int {\n    val s: Int = \"no\"\n    return s\n}\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> {
                var s = fx.shared.getSettings();
                s.setLspSupport(true);
                s.setKotlinLspEnabled(true);
                s.setKotlinLspCommand(com.editora.install.InstallCatalog.binaryCommand(Path.of(launcher), " --stdio"));
            });
            FxTestSupport.runOnFx(() -> c.openAndNavigate(source, 1));
            LspCoordinator lsp = FxTestSupport.field(c, "lspCoordinator");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(lsp, "applySupport", new Class<?>[] {}));
            List<LspDiagnostic> here = FxTestSupport.awaitFxValue(
                    () -> {
                        Map<Path, List<LspDiagnostic>> problems = FxTestSupport.field(lsp, "problems");
                        List<LspDiagnostic> all =
                                problems.values().stream().flatMap(List::stream).toList();
                        return all.stream().anyMatch(d -> d.toString().contains("mismatch")) ? all : null;
                    },
                    "a type mismatch from kotlin-lsp",
                    java.time.Duration.ofSeconds(300));
            System.out.println("kotlin-lsp problems: " + here);
        }
    }
}
