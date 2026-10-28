package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the import verdict reads from text (eleventh review CO2, CM3, CM4; twelfth review CM3, CL4). */
class JavaImportCheckTest {

    private static LspDiagnostic warning(String message) {
        return new LspDiagnostic(0, 0, 0, 1, LspDiagnostic.Severity.WARNING, message, null, "jdtls");
    }

    @Test
    void aNonProjectFileIsReadInBothWordings() {
        String plain = "A.java is a non-project file, only syntax errors are reported";
        assertEquals(plain, JavaImportCheck.nonProjectMessage(List.of(warning(plain))));
        String invisible =
                "Main.java is not on the classpath of project project_d15135a6, only syntax errors are reported";
        assertEquals(invisible, JavaImportCheck.nonProjectMessage(List.of(warning(invisible))), "an invisible project");
        // a healthy project keeps a nested sample's or buildSrc's file off its classpath: no failed import (CJ1)
        String own = "Main.java is not on the classpath of project shelf-debug-demo, only syntax errors are reported";
        assertNull(JavaImportCheck.nonProjectMessage(List.of(warning(own))), "a real project's file off its classpath");
    }

    /** m2e's "Project build error" on a pom is a failed import; a missing artifact is not (fourteenth check CJ2). */
    @Test
    void aPomsModelErrorIsAFailedImport(@TempDir Path dir) {
        var error = new LspDiagnostic(
                0,
                0,
                0,
                1,
                LspDiagnostic.Severity.ERROR,
                "Project build error: Non-resolvable parent POM for demo:app:1.1\nmore",
                null,
                "jdtls");
        var missing = new LspDiagnostic(
                0, 0, 0, 1, LspDiagnostic.Severity.ERROR, "Missing artifact g:a:jar:1.0", null, "jdtls");
        var found = JavaImportCheck.modelError(dir.resolve("app/pom.xml"), List.of(missing, error));
        assertEquals(dir.resolve("app").toAbsolutePath().normalize(), found.folder());
        assertEquals("Project build error: Non-resolvable parent POM for demo:app:1.1", found.line());
        assertNull(JavaImportCheck.modelError(dir.resolve("app/pom.xml"), List.of(missing)));
        assertNull(JavaImportCheck.modelError(dir.resolve("app/Main.java"), List.of(error)), "only a pom's");
    }

    /** A pom-packaging module's sources are never compiled (fourteenth check CJ3). */
    @Test
    void aPomPackagingModulesSourcesAreTold(@TempDir Path dir) throws Exception {
        Path app = Files.createDirectories(dir.resolve("app/src/main/java"));
        Files.writeString(
                dir.resolve("app/pom.xml"),
                "<project><parent><packaging>jar</packaging></parent><packaging>pom</packaging></project>");
        Path main = app.resolve("Main.java");
        assertEquals(dir.resolve("app"), JavaImportCheck.pomPackagingModule(dir, main));
        Files.writeString(dir.resolve("app/pom.xml"), "<project><packaging>jar</packaging></project>");
        assertNull(JavaImportCheck.pomPackagingModule(dir, main));
        assertEquals("app/pom.xml", JavaImportCheck.pomOf(dir, dir.resolve("app")));
    }

    @Test
    void aGradleScriptErrorSaysWhatIsWrong() {
        String groovy = "build file '/x/build.gradle': 1: Unexpected input: '{' @ line 1, column 10.\n   foo {\n"
                + "       ^\n\n1 error\n";
        assertEquals(
                "build file 'build.gradle': 1: Unexpected input: '{' @ line 1, column 10.",
                JavaImportCheck.reasonLine(groovy),
                "the script by its file name");
        String kotlin = "Script compilation error:\n\n  Line 5: notAThing()\n          ^ Unresolved reference "
                + "'notAThing'.\n\n1 error\n";
        assertEquals("Line 5: Unresolved reference 'notAThing'.", JavaImportCheck.reasonLine(kotlin));
        assertEquals(
                "Could not find method nope()",
                JavaImportCheck.reasonLine("The supplied phased action failed.\nCould not find method nope()"));
    }

    @Test
    void aSettingsFileAndTheFirstOfTwoErrorsAreNamed() {
        String settings = "Could not compile settings file '/tmp/p/settings.gradle'.\n"
                + "startup failed:\nsettings file '/tmp/p/settings.gradle': 2: Unexpected character: '\\'' @ line 2, "
                + "column 9.\n   include 'a\n           ^\n\n1 error\n";
        assertEquals(
                "settings file 'settings.gradle': 2: Unexpected character: '\\'' @ line 2, column 9.",
                JavaImportCheck.reasonLine(settings),
                "the cause, not the wrapper");
        String two =
                "Script compilation errors:\n\n  Line 5: notAThing()\n          ^ Unresolved reference 'notAThing'.\n"
                        + "\n  Line 6: alsoNotAThing()\n          ^ Unresolved reference 'alsoNotAThing'.\n\n2 errors\n";
        assertEquals("Line 5: Unresolved reference 'notAThing'.", JavaImportCheck.reasonLine(two), "the first");
    }

    @Test
    void aPomThatDoesNotParseIsNamedWithWhere(@TempDir Path dir) throws Exception {
        Path good = Files.writeString(dir.resolve("good.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        assertNull(JavaImportCheck.unreadablePom(good));
        Path broken = Files.writeString(dir.resolve("broken.xml"), "<project>\n  <brokenTag>\n</project>\n");
        JavaImportCheck.PomError why = JavaImportCheck.unreadablePom(broken);
        assertTrue(why != null && why.line() == 3 && why.column() > 0, String.valueOf(why));
        assertTrue(!why.message().endsWith(".") && !why.message().contains("(line"), why.message());
        Path empty = Files.writeString(dir.resolve("empty.xml"), "");
        assertTrue(JavaImportCheck.unreadablePom(empty) != null, "an empty pom");
        assertNull(JavaImportCheck.unreadablePom(dir.resolve("none.xml")));
        Files.writeString(
                dir.resolve("pom.xml"),
                "<project><modules><module>core</module><!-- <module>old</module> --><module>app</module></modules>"
                        + "<profiles><profile><modules><module>extra</module></modules></profile></profiles></project>");
        assertEquals(List.of(dir.resolve("core"), dir.resolve("app")), JavaImportCheck.mavenModules(dir));
    }
}
