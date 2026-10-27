package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Format Document on an XML file in a real window with no language server (XML review C8): the built-in formatter
 * re-indents it with the file's own indent unit, as one undoable edit. Before, it said "LSP support is disabled".
 */
@Tag("fx")
class XmlFormatDocumentFxTest {

    @TempDir
    Path work;

    @Test
    void formatDocumentUsesTheBuiltInFormatter() throws Exception {
        FxTestSupport.bootToolkit();
        // A four-space file: the formatter keeps four spaces, not a global default.
        Path file = Files.writeString(
                work.resolve("note.xml"), "<note>\n    <to>Tove</to>\n<from><name>Jani</name></from></note>\n");
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {})) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(file, 1));
            FxTestSupport.drainFx();
            com.editora.command.CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("lsp.formatDocument"));
            String expected =
                    "<note>\n    <to>Tove</to>\n    <from>\n        <name>Jani</name>\n    </from>\n</note>\n";
            assertEquals(
                    expected,
                    FxTestSupport.callOnFx(() -> c.activeBuffer().getArea().getText()));
            FxTestSupport.runOnFx(() -> c.activeBuffer().getArea().undo());
            assertEquals(
                    "<note>\n    <to>Tove</to>\n<from><name>Jani</name></from></note>\n",
                    FxTestSupport.callOnFx(() -> c.activeBuffer().getArea().getText()),
                    "one undo step takes it back");
        }
    }
}
