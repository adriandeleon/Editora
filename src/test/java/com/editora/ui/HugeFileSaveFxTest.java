package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A file at or over {@link EditorBuffer#HUGE_FILE_BYTES} is loaded <b>partially</b> — the first 50 MB, or (for
 * a log) the <em>last</em> 50 MB. The buffer is read-only, but read-only only stops <em>typing</em>:
 * {@code file.save} (Ctrl/Cmd-S) needs neither an edit nor a dirty flag, and it went straight to
 * {@code Files.write(file, bufferContent)} — truncating the user's file on disk to whichever slice happened to
 * be loaded, irreversibly. This pins that every write path refuses such a buffer.
 *
 * <p>The buffers here are marked truncated directly rather than by loading a real 50 MB file: pushing 50 M
 * characters into a {@code CodeArea} takes far longer than an FX test may block the toolkit for. The flag is
 * set by the loader from exactly the condition that decides the partial read
 * ({@code setTruncatedLoad(size >= HUGE_FILE_BYTES)} in {@code loadInto}), and everything downstream of it —
 * the part that actually destroyed files — is what these tests drive, through the real command.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HugeFileSaveFxTest {

    private FxWindowFixture fx;

    @TempDir
    Path tmp;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** A buffer standing in for a partially-loaded huge file: it holds a slice, its path is the whole file. */
    private EditorBuffer openTruncated(Path file, String loadedSlice) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(loadedSlice);
            buffer.setReadOnly(true); // what loadInto does for a huge file
            buffer.setTruncatedLoad(true); // ...and the flag that says the content is only a slice
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    @Test
    void ctrlSOnAPartiallyLoadedHugeFileDoesNotTruncateItOnDisk() throws Exception {
        Path file = tmp.resolve("huge.txt");
        String whole = "line one\nline two\nline three — the part beyond the load cap\n";
        Files.writeString(file, whole);
        openTruncated(file, "line one\n"); // the loader only got the first slice

        // The reflex: Ctrl/Cmd-S. No edit, not dirty — this used to overwrite the file with the slice.
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> registry.run("file.save"));
        FxTestSupport.runOnFx(() -> {});

        assertEquals(whole, Files.readString(file), "the file on disk must be untouched — a save would destroy it");
    }

    @Test
    void saveAsOfAPartiallyLoadedFileIsAlsoRefused() throws Exception {
        Path file = tmp.resolve("huge2.txt");
        Files.writeString(file, "aaa\nbbb\nccc\n");
        EditorBuffer buffer = openTruncated(file, "aaa\n");

        // Save-As wouldn't destroy the original, but it would write a SLICE of the file to a new path and
        // present it as a copy — a corrupt file the user has no reason to distrust. Refused too.
        boolean wrote = FxTestSupport.callOnFx(() ->
                (Boolean) FxTestSupport.call(fx.controller, "saveAs", new Class<?>[] {EditorBuffer.class}, buffer));
        assertFalse(wrote, "Save-As is refused for a partially-loaded buffer");
        assertEquals("aaa\nbbb\nccc\n", Files.readString(file), "and the original is untouched");
    }

    @Test
    void aNormalFileStillSaves() throws Exception {
        // The guard must not break the ordinary path.
        Path file = tmp.resolve("small.txt");
        Files.writeString(file, "hello\n");

        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(file);
            b.setContent("hello\nworld\n");
            FxTestSupport.call(fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
        assertFalse(buffer.isTruncatedLoad(), "a normal buffer is not a truncated load");

        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> registry.run("file.save"));
        FxTestSupport.runOnFx(() -> {});

        assertEquals("hello\nworld\n", Files.readString(file), "a normal save still writes");
    }
}
