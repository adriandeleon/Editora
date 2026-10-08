package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The File Information tool window: what it reads from disk, what it counts in the buffer, and what it says
 * about the character under the caret — including when there is no file, no buffer, or no such character.
 */
@Tag("fx")
class FileInformationPanelFxTest {

    private static final String DASH = "–";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A panel in a shown stage (so it refreshes) and a buffer to attach. */
    private static final class Rig {
        final FileInformationPanel panel = new FileInformationPanel();
        final EditorBuffer buffer = new EditorBuffer();
        final Stage stage = new Stage();

        Rig() {
            stage.setScene(new Scene(panel, 400, 700));
            stage.show();
        }

        String value(String field) {
            return FxTestSupport.<TextField>field(panel, field + "Value").getText();
        }

        void dispose() {
            panel.attach(null);
            stage.close();
            buffer.dispose();
        }
    }

    @Test
    void withNoBufferEveryValueIsADash() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.buffer.setContent("some text");
            r.panel.attach(r.buffer);
            assertEquals("9", r.value("chars"));

            r.panel.attach(null);

            for (String field : new String[] {
                "created",
                "size",
                "permissions",
                "fullPath",
                "encoding",
                "mode",
                "lines",
                "chars",
                "words",
                "location",
                "codePoint",
                "charName",
                "charBlock",
                "charCategory"
            }) {
                assertEquals(DASH, r.value(field), field);
            }
            r.dispose();
        });
    }

    @Test
    void anUnsavedBufferHasCountsAndACaretButNoDiskFacts() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.buffer.setContent("Alpha beta\ngamma, delta!\n");
            r.buffer.getArea().moveTo(0);
            r.panel.attach(r.buffer);

            assertEquals(tr("common.untitled"), r.value("fullPath"));
            assertEquals(DASH, r.value("created"));
            assertEquals(DASH, r.value("modified"));
            assertEquals(DASH, r.value("size"));
            assertEquals(DASH, r.value("permissions"));
            assertEquals(DASH, r.value("owner"));
            assertEquals(tr("fileinfo.encoding.utf8"), r.value("encoding"));
            assertEquals(r.buffer.getLineEnding(), r.value("lineEndings"));
            assertEquals(tr("fileinfo.mode.general"), r.value("mode"));

            assertEquals("3", r.value("lines"));
            assertEquals("25", r.value("chars"));
            assertEquals("4", r.value("words"));
            assertEquals("0", r.value("location"));
            assertEquals("1", r.value("line"));
            assertEquals("1", r.value("column"));
            assertEquals("U+0041", r.value("codePoint"));
            assertEquals("LATIN CAPITAL LETTER A", r.value("charName"));
            assertEquals("Basic Latin", r.value("charBlock"));
            assertEquals(tr("char.cat.Lu"), r.value("charCategory"));
            r.dispose();
        });
    }

    @Test
    void movingTheCaretUpdatesItsPositionTheSelectionAndTheCharacter() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.buffer.setContent("Alpha beta\ngamma, delta!\n");
            r.panel.attach(r.buffer);

            r.buffer.getArea().moveTo(1, 5);
            assertEquals("16", r.value("location"));
            assertEquals("2", r.value("line"));
            assertEquals("6", r.value("column"));
            assertEquals("U+002C", r.value("codePoint"));
            assertEquals("COMMA", r.value("charName"));
            assertEquals(tr("char.cat.Po"), r.value("charCategory"));

            r.buffer.getArea().selectRange(0, 17);
            assertEquals("3 (2)", r.value("lines"), "the selection's line count rides beside the total");
            assertEquals("25 (17)", r.value("chars"));
            r.buffer.getArea().selectRange(0, 11);
            assertEquals("3 (1)", r.value("lines"), "a selection ending in a line break is still one line");

            r.buffer.getArea().moveTo(r.buffer.getArea().getLength());
            assertEquals("3", r.value("lines"));
            assertEquals(DASH, r.value("codePoint"), "past the last character there is none to describe");
            assertEquals(DASH, r.value("charName"));
            assertEquals(DASH, r.value("charBlock"));
            assertEquals(DASH, r.value("charCategory"));
            r.dispose();
        });
    }

    @ParameterizedTest
    @CsvSource({
        "0061,Ll", "01C5,Lt", "02B0,Lm", "00AA,Lo", "0301,Mn", "0903,Mc", "20DD,Me", "0031,Nd", "2167,Nl", "00BD,No",
        "0020,Zs", "0009,Cc", "200B,Cf", "E000,Co", "0378,Cn", "002D,Pd", "0028,Ps", "0029,Pe", "005F,Pc", "00AB,Pi",
        "00BB,Pf", "002B,Sm", "0024,Sc", "005E,Sk", "00A9,So",
    })
    void theCharacterUnderTheCaretIsNamedByItsUnicodeCategory(String hex, String category) throws Exception {
        String ch = new String(Character.toChars(Integer.parseInt(hex, 16)));
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.buffer.setContent("x" + ch + "x");
            r.buffer.getArea().moveTo(0);
            r.panel.attach(r.buffer);

            r.buffer.getArea().moveTo(1);

            assertEquals("U+" + hex, r.value("codePoint"));
            assertEquals(tr("char.cat." + category), r.value("charCategory"));
            r.dispose();
        });
    }

    @Test
    void aCharacterOutsideTheBasicPlaneIsReadWholeAndALoneSurrogateIsNamedAsOne() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.buffer.setContent("x😀x"); // U+1F600
            r.buffer.getArea().moveTo(0);
            r.panel.attach(r.buffer);
            r.buffer.getArea().moveTo(1);
            assertEquals("U+1F600", r.value("codePoint"));
            assertEquals("GRINNING FACE", r.value("charName"));
            assertEquals("Emoticons", r.value("charBlock"));
            assertEquals(tr("char.cat.So"), r.value("charCategory"));

            r.buffer.getArea().moveTo(2); // between the two halves
            assertEquals("U+DE00", r.value("codePoint"));
            assertEquals(tr("char.cat.Cs"), r.value("charCategory"));
            r.dispose();
        });
    }

    @Test
    void aFileOnDiskShowsItsPathSizeTimesAndMode(@TempDir Path dir) throws Exception {
        Path small = Files.writeString(dir.resolve("Small.java"), "class Small {}\n");
        Path medium = Files.write(dir.resolve("medium.bin"), new byte[5000]);
        Path large = Files.write(dir.resolve("large.bin"), new byte[3 * 1024 * 1024 / 2]);
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.buffer.setPath(small);
            r.buffer.setContent("class Small {}\n");
            r.panel.attach(r.buffer);

            assertEquals(small.toString(), r.value("fullPath"));
            assertEquals("15 bytes", r.value("size"));
            assertTrue(r.value("modified").matches(".*\\d.*"), "a real time: " + r.value("modified"));
            assertFalse(DASH.equals(r.value("created")));
            assertEquals("Java", r.value("mode"), "the language, capitalised");

            EditorBuffer other = new EditorBuffer();
            other.setPath(medium);
            r.panel.attach(other);
            assertEquals("4 kB (5,000 bytes)", r.value("size"));
            other.setPath(large);
            r.panel.attach(other);
            assertEquals("1.5 MB (1,572,864 bytes)", r.value("size"));

            other.setPath(dir.resolve("deleted-meanwhile.txt"));
            r.panel.attach(other);
            assertEquals(dir.resolve("deleted-meanwhile.txt").toString(), r.value("fullPath"));
            assertEquals(DASH, r.value("size"), "a file that is gone has no size to show");
            assertEquals(DASH, r.value("created"));
            assertEquals(DASH, r.value("modified"));
            assertEquals(DASH, r.value("permissions"));
            assertEquals(DASH, r.value("owner"));
            r.panel.attach(null);
            other.dispose();
            r.dispose();
        });
    }

    @Test
    void posixPermissionsAreShownInOctalAndSymbols(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("script.sh"), "#!/bin/sh\n");
        Assumptions.assumeTrue(
                Files.getFileStore(file).supportsFileAttributeView("posix"), "a POSIX filesystem is needed");
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.buffer.setPath(file);
            setPermissions(file, "rwxr-x--x");
            r.panel.attach(r.buffer);
            assertEquals("751 (-rwxr-x--x)", r.value("permissions"));
            assertFalse(r.value("owner").isBlank() || DASH.equals(r.value("owner")));

            setPermissions(file, "rw--w-rw-");
            r.panel.attach(r.buffer);
            assertEquals("626 (-rw--w-rw-)", r.value("permissions"));

            setPermissions(file, "rw-r--r--");
            r.dispose();
        });
    }

    @Test
    void aClosedPanelDoesNoWorkUntilItIsShownAgain() throws Exception {
        FxTestSupport.runOnFx(() -> {
            FileInformationPanel hidden = new FileInformationPanel();
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent("one two three");
            hidden.attach(buffer);
            buffer.getArea().moveTo(4);
            TextField words = FxTestSupport.field(hidden, "wordsValue");
            TextField column = FxTestSupport.field(hidden, "columnValue");
            assertEquals(DASH, words.getText(), "not in a scene: nothing on screen to keep current");
            assertEquals(DASH, column.getText());

            Stage stage = new Stage();
            stage.setScene(new Scene(hidden, 300, 500));
            assertEquals("3", words.getText(), "opened: brought up to date at once");
            assertEquals("5", column.getText());

            hidden.focusFirstItem();
            assertTrue(hidden.isFocusTraversable(), "the read-only panel can still take the focus");
            hidden.attach(null);
            buffer.dispose();
        });
    }

    @Test
    void editingTheTextUpdatesTheWordCount() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Rig r = FxTestSupport.callOnFx(() -> {
                Rig rig = new Rig();
                rig.buffer.setContent("one two");
                rig.panel.attach(rig.buffer);
                return rig;
            });
            async.onClose(() -> FxTestSupport.runOnFx(r::dispose));
            assertEquals("2", FxTestSupport.callOnFx(() -> r.value("words")));

            FxTestSupport.runOnFx(() -> r.buffer.getArea().appendText(" three four"));

            SaveGuardsFxTest.awaitOnFx(async, "the recount after the edit settles", () -> "4".equals(r.value("words")));
            assertEquals("18", FxTestSupport.callOnFx(() -> r.value("chars")));
        }
    }

    private static void setPermissions(Path file, String permissions) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
