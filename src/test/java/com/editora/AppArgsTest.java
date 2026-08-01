package com.editora;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import com.editora.ui.MainController.OpenTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests parsing the command-line program arguments (pure; no JavaFX launch). */
class AppArgsTest {

    @Test
    void equalsForm() {
        assertEquals("/opt/cfg", App.configDirArg(List.of("--config-dir=/opt/cfg")));
    }

    @Test
    void spaceSeparatedForm() {
        assertEquals("/opt/cfg", App.configDirArg(List.of("--config-dir", "/opt/cfg")));
    }

    @Test
    void trimsValue() {
        assertEquals("/opt/cfg", App.configDirArg(List.of("--config-dir=  /opt/cfg  ")));
    }

    @Test
    void absentOrEmptyYieldsNull() {
        assertNull(App.configDirArg(List.of()));
        assertNull(App.configDirArg(List.of("--other", "x")));
        assertNull(App.configDirArg(List.of("--config-dir=")));
        assertNull(App.configDirArg(List.of("--config-dir"))); // no following value
    }

    @Test
    void projectArgBothForms() {
        assertEquals("/repo", App.projectArg(List.of("--project=/repo")));
        assertEquals("/repo", App.projectArg(List.of("--project", "/repo")));
        assertNull(App.projectArg(List.of("README.md")));
    }

    @Test
    void zenFlag() {
        assertTrue(App.zenFlag(List.of("--zen")));
        assertFalse(App.zenFlag(List.of("--config-dir", "/x", "f.txt")));
    }

    @Test
    void expertFlag() {
        assertTrue(App.expertFlag(List.of("--expert")));
        assertFalse(App.expertFlag(List.of("--zen")));
        assertFalse(App.expertFlag(List.of("--config-dir", "/x", "f.txt")));
    }

    @Test
    void simpleFlag() {
        assertTrue(App.simpleFlag(List.of("--simple")));
        assertFalse(App.simpleFlag(List.of("--config-dir", "/x", "f.txt")));
    }

    @Test
    void noSessionFlag() {
        assertTrue(App.noSessionFlag(List.of("--no-session")));
        assertTrue(App.noSessionFlag(List.of("--expert", "--single-window", "--no-session", "cv.typ")));
        assertFalse(App.noSessionFlag(List.of("--expert", "--single-window", "cv.typ")));
    }

    @Test
    void devFlag() {
        assertTrue(App.devFlag(List.of("--dev")));
        assertFalse(App.devFlag(List.of("--zen", "f.txt")));
    }

    @Test
    void devIsNotTreatedAsAFileTarget() {
        assertEquals(List.of(new OpenTarget(Path.of("a.txt"), 0, 0)), App.fileTargets(List.of("--dev", "a.txt")));
    }

    @Test
    void parseTargetPositions() {
        assertEquals(new OpenTarget(Path.of("foo.txt"), 0, 0), App.parseTarget("foo.txt"));
        assertEquals(new OpenTarget(Path.of("foo.txt"), 42, 0), App.parseTarget("foo.txt:42"));
        assertEquals(new OpenTarget(Path.of("foo.txt"), 42, 5), App.parseTarget("foo.txt:42:5"));
    }

    @Test
    void parseTargetHandlesWindowsPaths() {
        assertEquals(new OpenTarget(Path.of("C:\\dir\\f.txt"), 0, 0), App.parseTarget("C:\\dir\\f.txt"));
        assertEquals(new OpenTarget(Path.of("C:\\dir\\f.txt"), 10, 0), App.parseTarget("C:\\dir\\f.txt:10"));
        assertEquals(new OpenTarget(Path.of("C:\\dir\\f.txt"), 10, 3), App.parseTarget("C:\\dir\\f.txt:10:3"));
    }

    @Test
    void fileTargetsSkipsOptionsAndTheirValues() {
        List<OpenTarget> targets =
                App.fileTargets(List.of("--config-dir", "/cfg", "--project", "/repo", "--zen", "a.txt", "b.txt:7:2"));
        assertEquals(2, targets.size());
        assertEquals(Path.of("a.txt"), targets.get(0).file());
        assertEquals(new OpenTarget(Path.of("b.txt"), 7, 2), targets.get(1));
    }

    @Test
    void fileTargetsSkipsEqualsFormOptions() {
        List<OpenTarget> targets = App.fileTargets(List.of("--config-dir=/cfg", "--project=/repo", "x.md"));
        assertEquals(List.of(new OpenTarget(Path.of("x.md"), 0, 0)), targets);
    }

    // --- #791: a foreign two-token flag's value must not be opened as a file --------------------------

    /** Nothing exists on disk — the strictest setting for the "is this a leaked flag value?" decision. */
    private static final Predicate<Path> NOTHING_EXISTS = p -> false;

    @Test
    void foreignTwoTokenFlagValueIsNotAFileTarget() {
        // The reported case: --add-exports' value doesn't start with a dash, so it used to be resolved
        // against the working directory and opened ("Failed to open: <cwd>/javafx.graphics/...=com.editora").
        assertEquals(
                List.of(),
                App.fileTargets(
                        List.of("--add-exports", "javafx.graphics/com.sun.glass.ui=com.editora"), NOTHING_EXISTS));
        assertEquals(
                List.of(), App.fileTargets(List.of("--add-opens", "java.base/java.lang=ALL-UNNAMED"), NOTHING_EXISTS));
        assertEquals(List.of(), App.fileTargets(List.of("-p", "/some/module/path"), NOTHING_EXISTS));
    }

    @Test
    void aRealFileAfterAForeignFlagIsStillOpened() {
        // Existence is what distinguishes a leaked value from a genuine argument, so a file that is really
        // there wins even in the ambiguous position.
        assertEquals(
                List.of(new OpenTarget(Path.of("a.txt"), 0, 0)),
                App.fileTargets(List.of("--add-exports", "a.txt"), p -> true));
    }

    @Test
    void aMissingFileTheUserTypedIsStillATargetSoItReportsAsMissing() {
        // The whole point of not using a blanket must-exist rule: `editora typo.txt` has to keep reaching
        // openPath, which is what produces "Failed to open: typo.txt". Silently dropping it would be worse
        // than the bug being fixed.
        assertEquals(
                List.of(new OpenTarget(Path.of("typo.txt"), 0, 0)),
                App.fileTargets(List.of("typo.txt"), NOTHING_EXISTS));
        // ...including after one of Editora's own flags, whose arity we know.
        assertEquals(
                List.of(new OpenTarget(Path.of("typo.txt"), 0, 0)),
                App.fileTargets(List.of("--zen", "typo.txt"), NOTHING_EXISTS));
        assertEquals(
                List.of(new OpenTarget(Path.of("typo.txt"), 0, 0)),
                App.fileTargets(List.of("--new-file", "typo.txt"), NOTHING_EXISTS));
        // ...and after a value option, where the value is consumed and the file follows.
        assertEquals(
                List.of(new OpenTarget(Path.of("typo.txt"), 0, 0)),
                App.fileTargets(List.of("--project", "/repo", "typo.txt"), NOTHING_EXISTS));
    }

    @Test
    void onlyTheTokenDirectlyAfterAForeignFlagIsSuspect() {
        // The suspicion must not carry past the value it belongs to, or one stray JVM flag would swallow
        // every later argument.
        assertEquals(
                List.of(new OpenTarget(Path.of("b.txt"), 0, 0)),
                App.fileTargets(List.of("--add-exports", "m/p=x", "b.txt"), NOTHING_EXISTS));
    }

    @Test
    void theReportedArgvThroughTheOriginalSignature() {
        // Exactly the argv from #791, through the one-arg overload that existed before the fix, so this
        // fails against the old "anything without a leading dash is a file" rule rather than only against
        // the injected-predicate helper added alongside it.
        assertEquals(
                List.of(), App.fileTargets(List.of("--add-exports", "javafx.graphics/com.sun.glass.ui=com.editora")));
    }

    @Test
    void anUnrepresentablePathIsSkippedRatherThanFailingTheLaunch() {
        // A leaked value need not be a legal path at all; parseTarget's Path.of would otherwise throw
        // InvalidPathException straight out of App.start and the app would not come up.
        String nul = "bad\0name"; // a NUL is illegal in a path on every supported OS
        assertEquals(List.of(), App.fileTargets(List.of("--add-exports", nul), NOTHING_EXISTS));
        assertEquals(List.of(), App.fileTargets(List.of(nul), NOTHING_EXISTS));
    }

    @Test
    void editoraOwnsItsOptionSpellings() {
        assertTrue(App.isEditoraOption("--zen"));
        assertTrue(App.isEditoraOption("--no-session"));
        assertTrue(App.isEditoraOption("--new-file"));
        assertTrue(App.isEditoraOption("--new-file=foo.txt"));
        assertTrue(App.isEditoraOption("--config-dir"));
        assertTrue(App.isEditoraOption("--config-dir=/cfg"));
        assertTrue(App.isEditoraOption("--single-window=MyProj"));
        assertTrue(App.isEditoraOption("-V"));
        assertFalse(App.isEditoraOption("--add-exports"));
        assertFalse(App.isEditoraOption("-p"));
        assertFalse(App.isEditoraOption(null));
    }

    @Test
    void theDefaultOverloadChecksTheRealFilesystem(@TempDir Path dir) throws Exception {
        // Proves the injected predicate is wired to Files::exists, not merely that the pure decision is right.
        Path real = dir.resolve("real.txt");
        Files.writeString(real, "x");
        Path missing = dir.resolve("missing.txt");

        assertEquals(List.of(new OpenTarget(real, 0, 0)), App.fileTargets(List.of("--add-exports", real.toString())));
        assertEquals(List.of(), App.fileTargets(List.of("--add-exports", missing.toString())));
    }

    @Test
    void newFileArgAbsentIsNull() {
        assertNull(App.newFileArg(List.of("README.md")));
        assertNull(App.newFileArg(List.of()));
    }

    @Test
    void newFileArgBareIsEmptyString() {
        assertEquals("", App.newFileArg(List.of("--new-file")));
        assertEquals("", App.newFileArg(List.of("--dev", "--new-file")));
    }

    @Test
    void newFileArgNamedReturnsName() {
        assertEquals("foo.txt", App.newFileArg(List.of("--new-file=foo.txt")));
        assertEquals("a b.md", App.newFileArg(List.of("--zen", "--new-file=a b.md")));
    }

    @Test
    void newFileArgIsNotAFileTarget() {
        assertEquals(List.of(), App.fileTargets(List.of("--new-file=foo.txt")));
        assertEquals(List.of(), App.fileTargets(List.of("--new-file")));
    }

    @Test
    void singleWindowArgAbsentIsNull() {
        assertNull(App.singleWindowArg(List.of("README.md")));
        assertNull(App.singleWindowArg(List.of()));
    }

    @Test
    void singleWindowArgBareIsEmptyString() {
        assertEquals("", App.singleWindowArg(List.of("--single-window")));
        assertEquals("", App.singleWindowArg(List.of("--dev", "--single-window")));
    }

    @Test
    void singleWindowArgNamedReturnsProjectName() {
        assertEquals("MyProj", App.singleWindowArg(List.of("--single-window=MyProj")));
        assertEquals("My App", App.singleWindowArg(List.of("--zen", "--single-window=My App")));
    }

    @Test
    void singleWindowArgIsNotAFileTarget() {
        assertEquals(List.of(), App.fileTargets(List.of("--single-window")));
        assertEquals(List.of(), App.fileTargets(List.of("--single-window=MyProj")));
    }
}
