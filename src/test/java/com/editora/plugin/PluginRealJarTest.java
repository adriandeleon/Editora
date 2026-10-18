package com.editora.plugin;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plugins as they are shipped: Java code in a jar (and a {@code lib/} jar) in the plugin's folder, loaded by
 * the plugin's own class loader. Every other plugin test loads its classes from the test class path.
 */
class PluginRealJarTest {

    @TempDir
    Path sandbox;

    private Path pluginsDir() throws Exception {
        return Files.createDirectories(sandbox.resolve("plugins"));
    }

    /** A context that records what the plugin logged and answers nothing else. */
    private static PluginContext recording(List<String> log) {
        return (PluginContext) Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(), new Class<?>[] {PluginContext.class}, (proxy, m, args) -> {
                    if (m.getName().equals("log")) {
                        log.add((String) args[0]);
                    }
                    return null;
                });
    }

    private byte[] helloZip(String version, String message) {
        byte[] jar = PluginJars.jar(sandbox, Map.of("demo.Hello", PluginJars.loggingPlugin("demo.Hello", message)));
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(
                "plugin.json",
                "{\"id\":\"hello\",\"name\":\"Hello\",\"version\":\"" + version + "\",\"main\":\"demo.Hello\"}");
        entries.put("hello.jar", jar);
        return PluginJars.zip(entries);
    }

    @Test
    void aJavaPluginRunsFromItsOwnJarAndItsLibJar() throws Exception {
        Path dir = Files.createDirectories(pluginsDir().resolve("tool"));
        Files.createDirectories(dir.resolve("lib"));
        Files.write(dir.resolve("lib/helper.jar"), PluginJars.jar(sandbox, Map.of("demo.lib.Helper", """
                        package demo.lib;
                        public final class Helper { public static String greet() { return "from lib"; } }
                        """)));
        Files.write(dir.resolve("tool.jar"), PluginJars.jar(sandbox, Map.of("demo.Tool", """
                package demo;
                public final class Tool implements com.editora.plugin.Plugin {
                  public void start(com.editora.plugin.PluginContext ctx) { ctx.log(demo.lib.Helper.greet()); }
                }
                """)));
        Files.writeString(dir.resolve("plugin.json"), "{\"id\":\"tool\",\"main\":\"demo.Tool\"}");

        PluginManager pm = new PluginManager(pluginsDir(), id -> true);
        pm.discover();
        PluginDescriptor d = pm.launchSet().get(0);
        assertEquals(List.of("tool.jar", "lib/helper.jar"), d.jars());
        Plugin plugin = pm.instantiate(d);
        assertNotNull(plugin);
        assertTrue(plugin.getClass().getClassLoader() == d.classLoader(), "its own loader, not the app's");
        List<String> log = new ArrayList<>();
        plugin.start(recording(log));
        assertEquals(List.of("from lib"), log);
        assertNull(pm.runtimeProblem("tool"));
        pm.closeAll();
    }

    /** E7: a main class the jar does not have is recorded for the Settings row, not only logged. */
    @Test
    void aMainClassMissingFromTheJarIsRecordedAsWhyNothingRuns() throws Exception {
        Path dir = Files.createDirectories(pluginsDir().resolve("typo"));
        Files.write(
                dir.resolve("typo.jar"),
                PluginJars.jar(sandbox, Map.of("demo.Real", PluginJars.loggingPlugin("demo.Real", "x"))));
        Files.writeString(dir.resolve("plugin.json"), "{\"id\":\"typo\",\"main\":\"demo.DoesNotExist\"}");
        Path other = Files.createDirectories(pluginsDir().resolve("object"));
        Files.writeString(other.resolve("plugin.json"), "{\"id\":\"object\",\"main\":\"java.lang.Object\"}");

        PluginManager pm = new PluginManager(pluginsDir(), id -> true);
        pm.discover();
        assertNull(pm.instantiate(pm.launchSet().get(1)));
        assertEquals(
                PluginDescriptor.LoadProblem.Kind.MAIN_NOT_FOUND,
                pm.runtimeProblem("typo").kind());
        assertEquals(List.of("demo.DoesNotExist"), pm.runtimeProblem("typo").args());
        assertNull(pm.instantiate(pm.launchSet().get(0)));
        assertEquals(
                PluginDescriptor.LoadProblem.Kind.MAIN_NOT_A_PLUGIN,
                pm.runtimeProblem("object").kind());
        pm.closeAll();
    }

    /**
     * E3: an update of a plugin that runs in this session leaves its folder — and open jar — alone: it waits as
     * {@code .pending-<id>} and is swapped in by the next launch, before anything is loaded, keeping data/.
     */
    @Test
    void anUpdateOfARunningPluginIsAppliedAtTheNextStart() throws Exception {
        Path plugins = pluginsDir();
        PluginManager first = new PluginManager(plugins, id -> true);
        assertTrue(
                new PluginInstaller(first).installBytes(helloZip("1.0", "v1")).ok());
        first = new PluginManager(plugins, id -> true); // a launch with it installed
        first.discover();
        Plugin v1 = first.instantiate(first.running("hello"));
        Files.createDirectories(plugins.resolve("hello/data"));
        Files.writeString(plugins.resolve("hello/data/notes.txt"), "kept");

        PluginInstaller installer = new PluginInstaller(first);
        PluginInstaller.Staging staged = installer.stageBytes(helloZip("2.0", "v2"), null);
        assertTrue(staged.ok(), String.valueOf(staged.problem()));
        assertEquals("1.0", staged.staged().previous().version);
        PluginInstaller.Commit c = installer.commit(staged.staged(), true);
        assertTrue(c.ok() && c.pending());
        assertTrue(Files.isDirectory(plugins.resolve(".pending-hello")));
        first.discover();
        assertEquals("2.0", first.pendingUpdate("hello"));
        assertEquals("1.0", first.descriptors().get(0).manifest().version, "the running version is untouched");
        List<String> log = new ArrayList<>();
        v1.start(recording(log)); // still loads its classes from the jar it opened
        assertEquals(List.of("v1"), log);
        first.closeAll();

        PluginManager next = new PluginManager(plugins, id -> true);
        next.discover();
        assertFalse(Files.exists(plugins.resolve(".pending-hello")));
        assertEquals("2.0", next.running("hello").manifest().version);
        assertEquals("kept", Files.readString(plugins.resolve("hello/data/notes.txt")), "data/ carried over");
        log.clear();
        next.instantiate(next.running("hello")).start(recording(log));
        assertEquals(List.of("v2"), log);
        next.closeAll();
    }

    /** E3: removing a running plugin marks it; the next start deletes it before discovery. */
    @Test
    void aRemovalOfARunningPluginIsAppliedAtTheNextStart() throws Exception {
        Path plugins = pluginsDir();
        PluginManager pm = new PluginManager(plugins, id -> true);
        assertTrue(new PluginInstaller(pm).installBytes(helloZip("1.0", "v1")).ok());
        assertTrue(PluginInstaller.markForRemoval(plugins, "hello"));
        pm.discover();
        assertTrue(pm.pendingRemoval("hello"));
        assertTrue(Files.isDirectory(plugins.resolve("hello")), "not touched while it may be running");

        PluginManager next = new PluginManager(plugins, id -> true);
        next.discover();
        assertFalse(Files.exists(plugins.resolve("hello")));
        assertFalse(Files.exists(plugins.resolve(".remove-hello")));
        assertEquals(List.of(), next.descriptors());
        assertFalse(PluginInstaller.markForRemoval(plugins, "../evil"), "only a plugin's own name");
    }
}
