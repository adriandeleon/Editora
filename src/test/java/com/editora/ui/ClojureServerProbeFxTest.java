package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.editora.editor.LspDiagnostic;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MANUAL PROBE (not part of the suite): the real clojure-lsp (its native binary) in a real window, in a
 * {@code deps.edn} project, reports clj-kondo's unresolved symbol in the Problems list. clojure-lsp asks the Clojure
 * CLI for the classpath ({@code clojure -Spath}) when it is on PATH, and carries on without it otherwise. Run with
 * {@code -Dtest=ClojureServerProbeFxTest -Dgroups=probe -Dlsp.probe.clojureLsp=/path/to/clojure-lsp}.
 */
@Tag("probe")
@Tag("fx")
class ClojureServerProbeFxTest {

    @TempDir
    Path dir;

    @Test
    void anUnresolvedSymbolReachesTheProblemsList() throws Exception {
        String server = System.getProperty("lsp.probe.clojureLsp", "");
        Assumptions.assumeTrue(
                !server.isBlank() && Files.isRegularFile(Path.of(server)), "opt-in: -Dlsp.probe.clojureLsp=<binary>");
        FxTestSupport.bootToolkit();
        Files.writeString(dir.resolve("deps.edn"), "{:paths [\"src\"]}\n");
        Path core = Files.createDirectories(dir.resolve("src/my_app")).resolve("core.clj");
        Files.writeString(core, "(ns my-app.core)\n\n(defn greet [who]\n  (str \"Hello, \" whom))\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> {
                var s = fx.shared.getSettings();
                s.setLspSupport(true);
                s.setClojureLspEnabled(true);
                s.setClojureLspCommand(com.editora.install.InstallCatalog.quoteCommandPath(server));
            });
            FxTestSupport.runOnFx(() -> c.openAndNavigate(core, 1));
            LspCoordinator lsp = FxTestSupport.field(c, "lspCoordinator");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(lsp, "applySupport", new Class<?>[] {}));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (true) {
                FxTestSupport.drainFx();
                Map<Path, List<LspDiagnostic>> problems =
                        FxTestSupport.callOnFx(() -> FxTestSupport.field(lsp, "problems"));
                List<LspDiagnostic> here =
                        problems.values().stream().flatMap(List::stream).toList();
                if (here.stream().anyMatch(d -> d.message().contains("whom"))) {
                    System.out.println("clojure-lsp problems: " + here);
                    return;
                }
                assertTrue(System.nanoTime() < deadline, "no unresolved-symbol diagnostic from clojure-lsp: " + here);
                Thread.sleep(200);
            }
        }
    }
}
