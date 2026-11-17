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
 * MANUAL PROBE (not part of the suite): the real Groovy server (gvy's {@code gls} jar) in a real window, started
 * from its installed {@code java -jar} command on the Settings JDK, reports a syntax error in the Problems list.
 * Run with {@code -Dtest=GroovyServerProbeFxTest -Dgroups=probe -Dlsp.probe.gls=/path/to/gls-0.4.8.jar}.
 */
@Tag("probe")
@Tag("fx")
class GroovyServerProbeFxTest {

    @TempDir
    Path dir;

    @Test
    void aSyntaxErrorReachesTheProblemsList() throws Exception {
        String jar = System.getProperty("lsp.probe.gls", "");
        Assumptions.assumeTrue(!jar.isBlank() && Files.isRegularFile(Path.of(jar)), "opt-in: -Dlsp.probe.gls=<jar>");
        FxTestSupport.bootToolkit();
        Path script = dir.resolve("Broken.groovy");
        Files.writeString(script, "def x = [1, 2\nprintln x\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> {
                var s = fx.shared.getSettings();
                s.setLspSupport(true);
                s.setGroovyLspEnabled(true);
                s.setGroovyLspCommand(com.editora.install.InstallCatalog.jvmJarCommand(Path.of(jar)));
                s.setMavenJdkHome(System.getProperty("java.home"));
            });
            FxTestSupport.runOnFx(() -> c.openAndNavigate(script, 1));
            LspCoordinator lsp = FxTestSupport.field(c, "lspCoordinator");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(lsp, "applySupport", new Class<?>[] {}));
            List<LspDiagnostic> here = FxTestSupport.awaitFxValue(
                    () -> {
                        Map<Path, List<LspDiagnostic>> problems = FxTestSupport.field(lsp, "problems");
                        List<LspDiagnostic> all =
                                problems.values().stream().flatMap(List::stream).toList();
                        return all.isEmpty() ? null : all;
                    },
                    "diagnostics from gls",
                    java.time.Duration.ofSeconds(90));
            System.out.println("gls problems: " + here);
        }
    }
}
