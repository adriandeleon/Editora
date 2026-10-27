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
        assertNull(
                JavaImportCheck.nonProjectMessage(
                        List.of(
                                warning(
                                        "Tool.java is not on the classpath of project shelf-debug-demo, only syntax errors are reported"))),
                "a real project's other folder");
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
