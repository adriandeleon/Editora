package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Run Context at Caret (IntelliJ's Ctrl+Shift+F10) in a Java class it has nothing to run in. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunContextFxTest {

    private FxWindowFixture fx;

    @TempDir
    Path dir;

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

    /** It says why, not that Java is not runnable (fifth review EV7). */
    @Test
    void aJavaClassWithNoMainOrTestSaysSo() throws Exception {
        Path file = Files.writeString(dir.resolve("Shelf.java"), "class Shelf {\n    void checkout() {}\n}\n");
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        String said = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(Files.readString(file));
            b.setPath(file);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            registry.run("run.context");
            Object statusBar = FxTestSupport.field(fx.controller, "statusBar");
            MessageLog log = FxTestSupport.field(statusBar, "messageLog");
            List<MessageLog.Entry> entries = log.entries();
            return entries.isEmpty() ? null : entries.get(0).text();
        });
        assertEquals(tr("status.run.noContext", "Shelf.java"), said);
    }
}
