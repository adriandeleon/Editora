package com.editora;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.editora.config.ConfigManager;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The config-prefetch thread also parses the message catalog, so {@code App.start} does not do it on the FX
 * thread — the prefetch already has the setting that selects the language.
 */
class AppConfigPrefetchTest {

    @AfterEach
    void restoreEnglish() {
        Messages.init("en"); // the catalog is process-wide; leave it as the other tests expect
    }

    @Test
    void thePrefetchLoadsTheCatalogForTheConfiguredLanguage(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"uiLanguage\":\"es\"}");
        Messages.init("en");
        int before = Messages.generation();
        String english = Messages.tr("menu.save");

        Method prefetch = App.class.getDeclaredMethod("prefetchConfig", Path.class);
        prefetch.setAccessible(true);
        Object boot = ((CompletableFuture<?>) prefetch.invoke(null, dir)).get(30, TimeUnit.SECONDS);
        try {
            Method language = boot.getClass().getDeclaredMethod("language");
            language.setAccessible(true);
            assertEquals("es", language.invoke(boot), "the bootstrap says which catalog it loaded");
            assertEquals("es", Messages.current());
            assertEquals(before + 1, Messages.generation(), "loaded exactly once, by the prefetch");
            assertNotEquals(english, Messages.tr("menu.save"), "the Spanish overlay is active");
        } finally {
            Method manager = boot.getClass().getDeclaredMethod("manager");
            manager.setAccessible(true);
            ((ConfigManager) manager.invoke(boot)).shared().shutdown();
        }
    }
}
