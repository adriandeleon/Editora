package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work a debugger view or a theme reload does on text of the user's size runs off the FX thread and lands back on it
 * (PERF-01, Pauta 6.2.0): View As JSON/XML, a data table's page, Reload User Themes.
 */
@Tag("fx")
class LaterLayoutsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void stopListening() {
        BlockingSites.listenForTest(null);
    }

    @Test
    void viewAsJsonIsLaidOutOffFxAndABadOneSaysWhy() throws Exception {
        CompletableFuture<String> good = new CompletableFuture<>();
        CompletableFuture<Boolean> goodOnFx = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> StringViews.layOutLater(
                "{\"a\":[1,2]}",
                true,
                s -> {
                    goodOnFx.complete(javafx.application.Platform.isFxApplicationThread());
                    good.complete(s);
                },
                why -> good.completeExceptionally(new AssertionError(why))));
        assertEquals(StringViews.prettyJson("{\"a\":[1,2]}"), good.get(10, TimeUnit.SECONDS));
        assertTrue(goodOnFx.get(), "the answer lands on the FX thread");

        CompletableFuture<String> why = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> StringViews.layOutLater("<a>", false, why::complete, why::complete));
        assertFalse(why.get(10, TimeUnit.SECONDS).isEmpty(), "not XML: said why");
    }

    @Test
    void aDataTablePageIsParsedOffFx() throws Exception {
        CompletableFuture<PythonDataTable.Page> page = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> PythonDataTable.parseLater(null, page::complete));
        assertEquals(PythonDataTable.parse(null), page.get(10, TimeUnit.SECONDS));
    }

    @Test
    void reloadUserThemesReadsTheFoldersOffFx(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("themes"));
        Files.writeString(
                dir.resolve("themes").resolve("my-dusk.css"),
                ".root { -color-bg-default: #101010; -color-fg-default: #eeeeee; }\n");
        Thread fx = FxTestSupport.callOnFx(Thread::currentThread);
        List<String> onFx = Collections.synchronizedList(new ArrayList<>());
        BlockingSites.listenForTest((site, thread) -> {
            if (thread == fx) {
                onFx.add(site);
            }
        });
        CompletableFuture<Boolean> landed = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> UserThemes.reload(dir, () -> landed.complete(true)));
        assertTrue(landed.get(10, TimeUnit.SECONDS));
        assertTrue(UserThemes.controlThemes().containsKey("My Dusk"), "the theme is listed when it lands");
        assertFalse(onFx.contains("UserThemes.load"), "the folders were not read on the FX thread: " + onFx);
        CompletableFuture<Boolean> cleared = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> UserThemes.reload(dir.resolve("none"), () -> cleared.complete(true)));
        cleared.get(10, TimeUnit.SECONDS);
    }
}
