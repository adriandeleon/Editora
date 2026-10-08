package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Tab;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A restored tab's header is first rendered from its loading shell, which is non-editable by design.
 * {@code finishSessionBuffer} must re-render it once the file lands, or every restored tab keeps the
 * muted/italic read-only title for a perfectly editable file.
 */
@Tag("fx")
class SessionRestoreTabHeaderFxTest {

    @TempDir
    Path dir;

    @Test
    void restoredEditableTabsDoNotKeepTheReadOnlyHeader() throws Exception {
        FxTestSupport.bootToolkit();
        Path cfg = Files.createDirectories(dir.resolve("config"));
        Path work = Files.createDirectories(dir.resolve("work"));
        String[] names = {"a.txt", "b.txt", "c.txt"};
        Path[] files = new Path[names.length];
        StringBuilder open = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            files[i] = work.resolve(names[i]);
            Files.writeString(files[i], "x = " + i + "\n");
            open.append(i > 0 ? "," : "")
                    .append("{\"path\":\"")
                    .append(files[i].toAbsolutePath().toString().replace("\\", "\\\\"))
                    .append("\"}");
        }
        Files.writeString(
                cfg.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":[" + open + "],\"activeFile\":\""
                        + files[0].toAbsolutePath().toString().replace("\\", "\\\\") + "\"}");

        FxWindowFixture fx = FxWindowFixture.create(cfg, false, false, false, List.of(), controller -> {});
        try {
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (!allLoaded(fx, files) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                int seen = 0;
                for (Path file : files) {
                    Tab tab = (Tab) FxTestSupport.invokeWith(fx.controller, "tabForPath", Path.class, file);
                    if (tab == null) {
                        continue;
                    }
                    seen++;
                    assertFalse(
                            tab.getGraphic().getStyleClass().contains("read-only"),
                            "a restored editable tab kept its loading shell's read-only header: " + file);
                }
                assertEquals(files.length, seen, "every session file should have been restored");
            });
        } finally {
            fx.dispose();
        }
    }

    private static boolean allLoaded(FxWindowFixture fx, Path[] files) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            for (Path file : files) {
                Tab tab = (Tab) FxTestSupport.invokeWith(fx.controller, "tabForPath", Path.class, file);
                if (tab == null || !(tab.getUserData() instanceof com.editora.editor.EditorBuffer b) || b.isLoading()) {
                    return false;
                }
            }
            return true;
        });
    }
}
