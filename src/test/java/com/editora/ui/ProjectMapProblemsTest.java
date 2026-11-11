package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The problems the Project Map marks: counted per file, added up into folders, stepped through (round 2). */
class ProjectMapProblemsTest {

    private final Path root = Path.of("/work/app").toAbsolutePath();

    private static LspDiagnostic problem(int line, int column, LspDiagnostic.Severity severity, String message) {
        return new LspDiagnostic(line, column, line, column + 1, severity, message, null, "javac", null, false);
    }

    private ProjectMapProblems sample() {
        return new ProjectMapProblems(
                Map.of(
                        root.resolve("src/a/A.java"),
                        List.of(
                                problem(9, 2, LspDiagnostic.Severity.ERROR, "cannot find symbol"),
                                problem(3, 0, LspDiagnostic.Severity.WARNING, "unused import"),
                                problem(5, 0, LspDiagnostic.Severity.INFO, "an info: not counted")),
                        root.resolve("src/b/B.java"),
                        List.of(problem(1, 4, LspDiagnostic.Severity.WARNING, "raw type")),
                        root.resolve("docs/notes.md"),
                        List.of(problem(0, 0, LspDiagnostic.Severity.HINT, "only a hint")),
                        Path.of("/elsewhere/X.java").toAbsolutePath(),
                        List.of(problem(0, 0, LspDiagnostic.Severity.ERROR, "outside the Map"))),
                root);
    }

    @Test
    void filesAndTheFoldersAboveThemAreCounted() {
        ProjectMapProblems problems = sample();
        assertEquals(new ProjectMapProblems.Count(1, 1), problems.of(root.resolve("src/a/A.java")));
        assertEquals(new ProjectMapProblems.Count(1, 1), problems.of(root.resolve("src/a")));
        assertEquals(new ProjectMapProblems.Count(1, 2), problems.of(root.resolve("src")));
        assertEquals(new ProjectMapProblems.Count(1, 2), problems.of(root));
        assertFalse(problems.has(root.resolve("docs/notes.md")), "hints and infos are not problems here");
        assertFalse(problems.has(root.resolve("docs")));
        assertFalse(problems.has(Path.of("/elsewhere/X.java").toAbsolutePath()), "outside the Map's folder");
        assertFalse(problems.has(null));
        assertEquals(List.of(root.resolve("src/a/A.java"), root.resolve("src/b/B.java")), problems.files());
        assertFalse(problems.isEmpty());
        assertTrue(ProjectMapProblems.NONE.isEmpty());
        assertEquals(
                List.of("unused import", "cannot find symbol", "raw type"),
                problems.places().stream()
                        .map(ProjectMapProblems.Place::message)
                        .toList(),
                "file by file, line by line");
    }

    @Test
    void stepsGoForwardAndBackRound() {
        ProjectMapProblems problems = sample();
        Path a = root.resolve("src/a/A.java");
        Path b = root.resolve("src/b/B.java");
        assertEquals("unused import", problems.step(null, 0, 0, true).message(), "from nowhere: the first");
        assertEquals("raw type", problems.step(null, 0, 0, false).message(), "from nowhere back: the last");
        assertEquals("cannot find symbol", problems.step(a, 3, 0, true).message());
        assertEquals("raw type", problems.step(a, 9, 2, true).message(), "on to the next file");
        assertEquals("unused import", problems.step(b, 1, 4, true).message(), "round to the first");
        assertEquals("cannot find symbol", problems.step(b, 1, 4, false).message());
        assertEquals("raw type", problems.step(a, 0, 0, false).message(), "round to the last");
        assertEquals(
                "unused import", problems.step(root.resolve("src"), -1, 0, true).message(), "a folder row");
        ProjectMapProblems.Place last = problems.step(b, 0, 0, true);
        assertEquals(3, problems.indexOf(last));
        assertTrue(last.file().equals(b) && !last.error());
        assertNull(ProjectMapProblems.NONE.step(a, 0, 0, true));
    }

    @Test
    void withoutARootEveryFolderButTheFileSystemsRootCounts() {
        ProjectMapProblems problems = new ProjectMapProblems(
                Map.of(root.resolve("A.java"), List.of(problem(0, 0, LspDiagnostic.Severity.ERROR, "x"))), null);
        assertTrue(problems.has(root));
        assertTrue(problems.has(root.getParent()));
    }

    @Test
    void theProblemsChipIsAnotherStatusAlternative() {
        ProjectMapModel.Entry java = new ProjectMapModel.Entry(root.resolve("A.java"), root, 1, false);
        var chip = new ProjectMapModel.Filters(
                "", false, false, false, false, false, true, ProjectMapModel.TypeFilter.ALL);
        assertTrue(chip.active());
        assertTrue(ProjectMapModel.matches(java, chip, false, false, false, false, false, true));
        assertFalse(ProjectMapModel.matches(java, chip, true, false, false, false, false, false));
    }
}
