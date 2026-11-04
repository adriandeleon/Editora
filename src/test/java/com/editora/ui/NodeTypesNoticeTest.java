package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "no Node.js types" hint takes the place of the server's ready line, where it can be read: posted while the
 * file opened, "Opened …" and "Loaded and ready" replaced it at once (JS review B108).
 */
class NodeTypesNoticeTest {

    @BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    @Test
    void theHintWaitsForTheServersReadyLineOncePerFolder(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("a.js"), "const fs = require('fs');\n");
        Path b = Files.writeString(dir.resolve("b.js"), "const os = require('os');\n");
        NodeTypesNotice notice = new NodeTypesNotice();
        List<String> said = new ArrayList<>();
        notice.opened("javascript", Files.readString(a), a, dir, false, false, said::add);
        notice.opened("javascript", Files.readString(b), b, dir, false, false, said::add);
        assertTrue(said.isEmpty(), "nothing said while the file opens");
        String atReady = notice.atReady(dir, "typescript-language-server: Loaded and ready");
        assertTrue(atReady.startsWith("No types for Node.js APIs"), atReady);
        assertEquals("ready", notice.atReady(dir, "ready"), "said once");
    }

    @Test
    void onlyJavascriptThatUsesNodeWithoutTypesAndWithoutTypeAcquisition(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("a.js"), "console.log(process.argv);\n");
        assertTrue(NodeTypesNotice.wanted("javascript", Files.readString(a), a, false));
        assertFalse(NodeTypesNotice.wanted("javascript", Files.readString(a), a, true), "acquisition on");
        assertFalse(NodeTypesNotice.wanted("typescript", Files.readString(a), a, false), "TS says it itself");
        assertFalse(NodeTypesNotice.wanted("javascript", "console.log(1);\n", a, false), "no Node.js use");
        Files.createDirectories(dir.resolve("node_modules/@types/node"));
        Files.writeString(dir.resolve("node_modules/@types/node/package.json"), "{}");
        assertFalse(NodeTypesNotice.wanted("javascript", Files.readString(a), a, false), "types installed");
    }

    @Test
    void anIgnoredFolderTypescriptIsSaidOnceAtReady(@TempDir Path dir) {
        NodeTypesNotice notice = new NodeTypesNotice();
        notice.folderTypeScriptIgnored(dir, "4.9.5", "5.9.3");
        notice.folderTypeScriptIgnored(dir, "4.9.5", "5.9.3");
        String said = notice.atReady(dir, "ready");
        assertTrue(said.contains("4.9.5") && said.contains("5.9.3") && said.contains("Trust"), said);
        assertEquals("ready", notice.atReady(dir, "ready"));
    }
}
