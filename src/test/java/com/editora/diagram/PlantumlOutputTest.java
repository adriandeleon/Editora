package com.editora.diagram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlantUML names its output after the <b>diagram</b>, not the input file: {@code @startuml myclassdiagram}
 * writes {@code myclassdiagram.png}. Reading back a fixed {@code diagram.<fmt>} meant a perfectly valid named
 * diagram rendered as "render failed", and exporting one silently produced no file while reporting success.
 * Naming a diagram is idiomatic PlantUML — it's required for a multi-diagram file.
 *
 * <p>Needs the real {@code plantuml} on PATH; skipped where it isn't.
 */
@EnabledIf("plantumlInstalled")
class PlantumlOutputTest {

    static boolean plantumlInstalled() {
        try {
            return ProcessRunner.run(null, Duration.ofSeconds(20), List.of("plantuml", "-version"))
                            .exit()
                    != -1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final List<String> CMD = List.of("plantuml");
    private static final String NAMED = "@startuml myclassdiagram\nAlice -> Bob: hi\n@enduml\n";
    private static final String UNNAMED = "@startuml\nAlice -> Bob: hi\n@enduml\n";

    @Test
    void aNamedDiagramRenders() {
        DiagramRenderer.Render r = DiagramRenderer.renderPng(DiagramKind.PLANTUML, CMD, NAMED, false);
        assertTrue(r.ok(), "a named @startuml must render, not report: " + r.error());
        assertNotNull(r.image());
        assertTrue(r.image().length > 500, "a real PNG, not an empty file");
    }

    @Test
    void anUnnamedDiagramStillRenders() {
        DiagramRenderer.Render r = DiagramRenderer.renderPng(DiagramKind.PLANTUML, CMD, UNNAMED, false);
        assertTrue(r.ok(), r.error());
        assertNotNull(r.image());
    }

    /** A genuinely broken source must still fail — and say why. */
    @Test
    void aBrokenSourceStillFailsWithAMessage() {
        DiagramRenderer.Render r = DiagramRenderer.renderPng(
                DiagramKind.PLANTUML, CMD, "@startuml\nthis is not plantuml at all !!!\n@enduml\n", false);
        assertTrue(!r.ok(), "must not be reported as a successful render");
        assertNull(r.image());
        assertNotNull(r.error());
    }

    /** Exporting a named diagram must write the file — it reported "Exported" while writing nothing. */
    @Test
    void exportingANamedDiagramActuallyWritesTheFile(@TempDir Path tmp) throws IOException {
        Path dest = tmp.resolve("out.png");
        ProcessRunner.Result r = DiagramRenderer.exportTo(DiagramKind.PLANTUML, CMD, NAMED, dest, false);
        assertTrue(r.ok(), r.err());
        assertTrue(Files.isRegularFile(dest), "the export claimed success but wrote no file");
        assertTrue(Files.size(dest) > 500);
    }

    @Test
    void exportingAnUnnamedDiagramStillWritesTheFile(@TempDir Path tmp) throws IOException {
        Path dest = tmp.resolve("out.svg");
        ProcessRunner.Result r = DiagramRenderer.exportTo(DiagramKind.PLANTUML, CMD, UNNAMED, dest, false);
        assertTrue(r.ok(), r.err());
        assertTrue(Files.isRegularFile(dest));
    }

    /** An export that produces nothing must report failure rather than "Exported" over a missing file. */
    @Test
    void anExportThatProducesNothingIsNotReportedAsSuccess(@TempDir Path tmp) {
        Path dest = tmp.resolve("out.png");
        ProcessRunner.Result r = DiagramRenderer.exportTo(
                DiagramKind.PLANTUML, CMD, "@startuml\nthis is not plantuml at all !!!\n@enduml\n", dest, false);
        assertTrue(!r.ok(), "no file was written, so this must not read as success");
        assertTrue(!Files.isRegularFile(dest));
    }
}
