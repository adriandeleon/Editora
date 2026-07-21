package com.editora;

import java.nio.file.Path;
import java.util.List;

import com.editora.ui.MainController.OpenTarget;
import org.junit.jupiter.api.Test;

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
