package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for "Add to Dictionary is ignored and no file is written": the buffer's spell user-word
 * set is the very same set as {@code SharedConfig.userDictionary}, and {@code addUserWord} only writes
 * {@code dictionary.txt} when the word is newly added to that set. The buffer used to add the word to the
 * shared set *before* invoking the persist callback, so the callback saw it as already present and skipped
 * the file write entirely — the word applied for the session but never persisted. The callback now runs
 * first.
 */
@Tag("fx")
class SpellDictionaryFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void addToDictionaryPersistsToDiskWhenWiredToTheSharedSet(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        EditorBuffer buffer = FxTestSupport.callOnFx(EditorBuffer::new);

        // Wire exactly as MainController.addBuffer does: the buffer's user-word set IS the shared dictionary,
        // and "Add to Dictionary" persists through ConfigManager.addUserWord.
        FxTestSupport.runOnFx(() -> {
            buffer.setSpellUserWords(config.getUserDictionary());
            buffer.setOnAddToDictionary(config::addUserWord);
        });

        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(buffer, "addToDictionary", new Class<?>[] {String.class}, "zzqqx"));

        Path dictionary = config.getUserDictionaryFile();
        assertTrue(Files.isReadable(dictionary), "dictionary.txt should be created on the first add");
        assertTrue(
                Files.readString(dictionary).contains("zzqqx"), "the added word should be written to dictionary.txt");
        assertTrue(config.getUserDictionary().contains("zzqqx"), "the added word should be in the in-memory set");
    }
}
