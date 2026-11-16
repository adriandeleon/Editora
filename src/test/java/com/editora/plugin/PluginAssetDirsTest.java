package com.editora.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PERF-01: which plugin folders hold snippets or templates is learnt when the launch discovers the plugins (off
 * the FX thread), so a window applying them looks at no disk.
 */
class PluginAssetDirsTest {

    @Test
    void theLaunchSetsFoldersAreKnownFromDiscovery(@TempDir Path pluginsDir) throws Exception {
        Path alpha = Files.createDirectories(pluginsDir.resolve("alpha"));
        Files.writeString(alpha.resolve("plugin.json"), "{ \"id\": \"alpha\", \"name\": \"Alpha\" }");
        Path snippets = Files.createDirectories(alpha.resolve("snippets"));
        PluginManager manager = new PluginManager(pluginsDir, id -> true);
        manager.discover();
        PluginDescriptor d = manager.launchSet().get(0);

        Thread self = Thread.currentThread();
        List<String> heard = new CopyOnWriteArrayList<>();
        BlockingSites.listenForTest((site, thread) -> {
            if (thread == self) {
                heard.add(site);
            }
        });
        try {
            Files.createDirectories(alpha.resolve("templates")); // after discovery: the launch did not see it
            assertEquals(snippets, manager.assetDir(d, "snippets"));
            assertNull(manager.assetDir(d, "templates"), "as discovered");
            assertEquals(List.of(), heard, "no look at the disk");

            // Any other folder is looked up when asked.
            assertNull(manager.assetDir(d, "data"), "no such folder");
            assertEquals(List.of("PluginManager.assetDir"), heard);
        } finally {
            BlockingSites.listenForTest(null);
            manager.closeAll();
        }
    }

    @Test
    void aDescriptorOutsideTheLaunchSetIsLookedUp(@TempDir Path pluginsDir) throws Exception {
        Path beta = Files.createDirectories(pluginsDir.resolve("beta"));
        Files.writeString(beta.resolve("plugin.json"), "{ \"id\": \"beta\", \"name\": \"Beta\" }");
        PluginManager launched = new PluginManager(pluginsDir, id -> true);
        launched.discover();
        PluginDescriptor fromAnother = launched.launchSet().get(0);
        PluginManager notLaunched = new PluginManager(pluginsDir, id -> true);
        try {
            Path templates = Files.createDirectories(beta.resolve("templates"));
            assertEquals(templates, notLaunched.assetDir(fromAnother, "templates"), "no launch set: the disk");
        } finally {
            launched.closeAll();
            notLaunched.closeAll();
        }
    }
}
