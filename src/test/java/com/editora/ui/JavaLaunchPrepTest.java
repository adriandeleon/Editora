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

/** The decisions made before a Java debug launch (review A2, A16, B3). */
class JavaLaunchPrepTest {

    private static final Path P = Path.of("/w/proj").toAbsolutePath();

    private static LspDiagnostic diag(int line, LspDiagnostic.Severity severity, String message) {
        return new LspDiagnostic(line, 0, line, 1, severity, message, null, "Java", null, false, false);
    }

    @Test
    void aPlainFolderProjectIsTheWindowsWhenTheFileIsInIt() {
        Path file = P.resolve("app/Main.java");
        assertEquals(P.resolve("maven"), JavaLaunchPrep.projectOf(file, P.resolve("maven"), P));
        assertEquals(P, JavaLaunchPrep.projectOf(file, null, P));
        assertNull(JavaLaunchPrep.projectOf(Path.of("/elsewhere/Main.java").toAbsolutePath(), null, P));
        assertNull(JavaLaunchPrep.projectOf(file, null, null));
    }

    @Test
    void theProjectsUnsavedJavaFilesAreSavedAndNothingElse() {
        Path launched = P.resolve("src/demo/Main.java");
        assertTrue(JavaLaunchPrep.saveBeforeLaunch(P.resolve("src/demo/Helper.java"), "java", launched, P));
        assertFalse(JavaLaunchPrep.saveBeforeLaunch(P.resolve("README.md"), "markdown", launched, P));
        assertFalse(JavaLaunchPrep.saveBeforeLaunch(Path.of("/other/X.java").toAbsolutePath(), "java", launched, P));
        assertTrue(
                JavaLaunchPrep.saveBeforeLaunch(P.resolve("src/demo/sub/Y.java"), "java", launched, null),
                "no project: the launched file's folder tree");
        assertFalse(JavaLaunchPrep.saveBeforeLaunch(P.resolve("src/Z.java"), "java", launched, null));
    }

    @Test
    void compileErrorsInTheProjectAreConsoleLinesByFile() {
        Map<Path, List<LspDiagnostic>> errors = Map.of(
                P.resolve("src/B.java"),
                List.of(diag(4, LspDiagnostic.Severity.ERROR, "b"), diag(5, LspDiagnostic.Severity.WARNING, "w")),
                P.resolve("src/A.java"),
                List.of(diag(0, LspDiagnostic.Severity.ERROR, "a1"), diag(9, LspDiagnostic.Severity.ERROR, "a2")),
                Path.of("/other/C.java").toAbsolutePath(),
                List.of(diag(1, LspDiagnostic.Severity.ERROR, "not ours")));
        assertEquals(
                List.of(P.resolve("src/A.java") + ":1: error: a1", P.resolve("src/A.java") + ":10: error: a2"),
                JavaLaunchPrep.errorLines(errors, P, 2));
        assertEquals(3, JavaLaunchPrep.errorCount(errors, P), "warnings and other projects do not count");
    }

    // --- review 2, A101/A103/A104/A107 ----------------------------------------------------------------------

    @Test
    void aLooseFileIsStoppedOnlyByErrorsInItsOwnFolder() {
        Path loose = P.resolve("Hello.java");
        var scope = JavaLaunchPrep.errorScope(loose, null);
        assertTrue(scope.test(P.resolve("Other.java")));
        assertFalse(scope.test(P.resolve("other/Broken.java")), "a subfolder of the folder it was opened from");
        assertFalse(scope.test(Path.of("/elsewhere/X.java").toAbsolutePath()));
        var project = JavaLaunchPrep.errorScope(loose, P);
        assertTrue(project.test(P.resolve("other/Broken.java")), "a project's errors anywhere in it stop the launch");
    }

    @Test
    void compileErrorsAreJavaFilesInScopeShownFromTheWindowsProject() {
        Map<Path, List<LspDiagnostic>> errors = Map.of(
                P.resolve("src/main/java/app/Greeter.java"),
                List.of(diag(4, LspDiagnostic.Severity.ERROR, "Type mismatch")),
                P.resolve("web/app.ts"),
                List.of(diag(0, LspDiagnostic.Severity.ERROR, "TS2322")),
                P,
                List.of(diag(0, LspDiagnostic.Severity.ERROR, "import failed")));
        var scope = JavaLaunchPrep.errorScope(P.resolve("src/main/java/app/Main.java"), P);
        assertEquals(1, JavaLaunchPrep.javaErrorCount(errors, scope));
        assertEquals(
                List.of(Path.of("src/main/java/app/Greeter.java") + ":5: error: Type mismatch"),
                JavaLaunchPrep.javaErrorLines(errors, scope, P, 10));
        assertEquals(
                List.of(P.resolve("src/main/java/app/Greeter.java") + ":5: error: Type mismatch"),
                JavaLaunchPrep.javaErrorLines(errors, scope, null, 10),
                "outside a window project the path stays whole, so the link still opens it");
    }

    @Test
    void anImportFailureOnTheProjectFolderOrBuildFileIsNoCompileError() {
        Map<Path, List<LspDiagnostic>> errors = Map.of(
                P,
                List.of(
                        diag(0, LspDiagnostic.Severity.ERROR, "The supplied phased action failed with an exception."),
                        diag(0, LspDiagnostic.Severity.ERROR, "Can't use Java 27 and Gradle 9.7.1 to import")),
                P.resolve("build.gradle"),
                List.of(diag(2, LspDiagnostic.Severity.ERROR, "Can't use Java 27 and Gradle 9.7.1 to import")),
                P.resolve("README.md"),
                List.of(diag(2, LspDiagnostic.Severity.ERROR, "spelling")));
        assertEquals(
                List.of(
                        "The supplied phased action failed with an exception.",
                        "Can't use Java 27 and Gradle 9.7.1 to import"),
                JavaLaunchPrep.importErrors(errors, P));
        assertEquals(0, JavaLaunchPrep.javaErrorCount(errors, JavaLaunchPrep.errorScope(P.resolve("A.java"), P)));
    }

    @Test
    void anInvisibleProjectIsNamedWithoutItsHash() {
        assertEquals("project", JavaLaunchPrep.projectLabel("project_b3763c83"));
        assertEquals("my_app", JavaLaunchPrep.projectLabel("my_app"));
        assertEquals("shelf-debug-demo", JavaLaunchPrep.projectLabel("shelf-debug-demo"));
        assertEquals("", JavaLaunchPrep.projectLabel(null));
    }

    // --- review 3, A201 / A203 / choice 10 ----------------------------------------------------------------------

    @Test
    void withoutABuildToolJavacDecidesWheneverJdtlsDidNotBuildIt() {
        var o = com.editora.lsp.LspManager.BuildOutcome.class;
        for (var outcome : o.getEnumConstants()) {
            JavaLaunchPrep.AfterBuild loose = JavaLaunchPrep.afterBuild(outcome, true);
            JavaLaunchPrep.AfterBuild project = JavaLaunchPrep.afterBuild(outcome, false);
            if (outcome == com.editora.lsp.LspManager.BuildOutcome.SUCCEEDED) {
                assertEquals(JavaLaunchPrep.AfterBuild.LAUNCH_BUILT, loose, "jdtls's fresh output");
                assertEquals(JavaLaunchPrep.AfterBuild.LAUNCH_BUILT, project);
            } else {
                // never refused for jdtls's errors, never jdtls's "Unresolved compilation problem" classes
                assertEquals(JavaLaunchPrep.AfterBuild.COMPILE_WITH_JAVAC, loose, outcome.name());
                assertEquals(
                        outcome == com.editora.lsp.LspManager.BuildOutcome.WITH_ERRORS
                                ? JavaLaunchPrep.AfterBuild.REFUSE
                                : JavaLaunchPrep.AfterBuild.LAUNCH_BUILT,
                        project,
                        "a Maven or Gradle project: " + outcome);
            }
        }
    }
}
