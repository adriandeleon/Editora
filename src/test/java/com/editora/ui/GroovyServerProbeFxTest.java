package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (true) {
                FxTestSupport.drainFx();
                Map<Path, List<LspDiagnostic>> problems =
                        FxTestSupport.callOnFx(() -> FxTestSupport.field(lsp, "problems"));
                List<LspDiagnostic> here =
                        problems.values().stream().flatMap(List::stream).toList();
                if (!here.isEmpty()) {
                    System.out.println("gls problems: " + here);
                    return;
                }
                assertTrue(System.nanoTime() < deadline, "no diagnostics from gls");
                Thread.sleep(200);
            }
        }
    }
}
