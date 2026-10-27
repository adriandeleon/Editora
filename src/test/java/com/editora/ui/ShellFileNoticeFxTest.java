package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import javafx.scene.control.Button;
import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.template.NewFileCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B6: a shell script that opens with CRLF line endings or a byte-order mark gets a notice whose action makes
 * it runnable again, and a new shell file stays LF even under {@code [*] end_of_line = crlf}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShellFileNoticeFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer open(AsyncTestScope async, FxWindowFixture fx, Path file) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        CountDownLatch loaded = new CountDownLatch(1);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            workflows.openPath(file);
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            Tab selected = area.selectedTab();
            EditorBuffer opening = (EditorBuffer) selected.getUserData();
            workflows.afterBufferLoad(opening, loaded::countDown);
            return opening;
        });
        async.await(loaded, "document load");
        async.awaitFx();
        return buffer;
    }

    private static void save(AsyncTestScope async, FxWindowFixture fx, EditorBuffer buffer) throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> {
            buffer.setViewMode(false);
            registry.run("file.save");
        });
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
        async.awaitWorker(worker);
        async.awaitFx();
    }

    /** Presses the notice's action button, as a click would. */
    private static void pressNoticeAction(EditorBuffer buffer) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Button action = (Button) buffer.getNode().lookup(".file-notice-bar .accent");
            assertNotNull(action, "the notice's action button");
            action.fire();
        });
    }

    @Test
    void aCrlfOrBomScriptGetsANoticeThatMakesItRunnable(@TempDir Path dir) throws Exception {
        Path crlf = Files.writeString(dir.resolve("win.sh"), "#!/bin/bash\r\nset -euo pipefail\r\necho hi\r\n");
        Path bom = Files.write(dir.resolve("bom.sh"), "﻿#!/bin/bash\necho hi\n".getBytes(StandardCharsets.UTF_8));
        Path ok = Files.writeString(dir.resolve("ok.sh"), "#!/bin/bash\necho hi\n");
        Path dos = Files.writeString(dir.resolve("notes.txt"), "a\r\nb\r\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());

            EditorBuffer w = open(async, fx, crlf);
            String text = FxTestSupport.callOnFx(w::fileNoticeText);
            assertNotNull(text, "a CRLF script gets the notice");
            assertTrue(text.contains("win.sh") && text.contains("CRLF"), text);
            pressNoticeAction(w);
            assertNull(FxTestSupport.callOnFx(w::fileNoticeText), "the action closes the notice");
            save(async, fx, w);
            assertEquals("#!/bin/bash\nset -euo pipefail\necho hi\n", Files.readString(crlf));

            EditorBuffer b = open(async, fx, bom);
            assertTrue(FxTestSupport.callOnFx(b::fileNoticeText).contains("BOM"));
            pressNoticeAction(b);
            save(async, fx, b);
            byte[] bytes = Files.readAllBytes(bom);
            assertEquals('#', bytes[0], "the byte-order mark is gone");

            assertNull(FxTestSupport.callOnFx(open(async, fx, ok)::fileNoticeText), "nothing to say about LF");
            assertNull(FxTestSupport.callOnFx(open(async, fx, dos)::fileNoticeText), "only shell scripts");
        }
    }

    /** A clean load hides only the shell notice: another feature's notice on the file stays (XML/pom review 2). */
    @Test
    void aCleanLoadHidesOnlyItsOwnNotice() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer pom = new EditorBuffer();
            pom.showFileNotice("Maven-aware editing is off", "Trust Folder", "Not now", () -> {});
            ShellFileNotice.offer(pom, "pom.xml", com.editora.editops.LineEndings.LF, "UTF-8", s -> {});
            assertEquals("Maven-aware editing is off", pom.fileNoticeText());

            EditorBuffer sh = new EditorBuffer();
            ShellFileNotice.offer(sh, "w.sh", com.editora.editops.LineEndings.CRLF, "UTF-8", s -> {});
            assertNotNull(sh.fileNoticeText());
            ShellFileNotice.offer(sh, "w.sh", com.editora.editops.LineEndings.LF, "UTF-8", s -> {});
            assertNull(sh.fileNoticeText(), "fixed on reload: its own notice goes");
        });
    }

    @Test
    void aNewShellFileStaysLfUnderACrlfEditorConfig(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\nend_of_line = crlf\ncharset = utf-8-bom\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FxTestSupport.runOnFx(() ->
                    fx.controller.templateActions.createFileOfType(dir, NewFileCatalog.byId("scripts.shell"), "tool"));
            Path tool = dir.resolve("tool.sh");
            assertEquals("#!/usr/bin/env bash\nset -euo pipefail\n\n", Files.readString(tool), "written with LF");

            EditorBuffer buffer = open(async, fx, tool);
            assertNull(FxTestSupport.callOnFx(buffer::fileNoticeText));
            assertEquals("LF", FxTestSupport.callOnFx(buffer::getLineEnding), "the CRLF rule is not honoured");
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("echo hi\n"));
            save(async, fx, buffer);
            byte[] bytes = Files.readAllBytes(tool);
            assertEquals('#', bytes[0], "no byte-order mark either");
            assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("\r"), "a save keeps it LF");

            // An extension-less script becomes shell only once its shebang is read, after the rules were applied
            // on open: the language change re-applies them, so it is kept LF too.
            Path bare = Files.writeString(dir.resolve("deploy"), "#!/bin/bash\necho hi\n");
            EditorBuffer script = open(async, fx, bare);
            assertEquals("shell", FxTestSupport.callOnFx(script::getLanguage));
            assertEquals("LF", FxTestSupport.callOnFx(script::getLineEnding), "a shebang script is not made CRLF");

            // Other files still follow the rule.
            Path txt = Files.writeString(dir.resolve("a.txt"), "x\n");
            EditorBuffer other = open(async, fx, txt);
            assertEquals("CRLF", FxTestSupport.callOnFx(other::getLineEnding));
        }
    }
}
