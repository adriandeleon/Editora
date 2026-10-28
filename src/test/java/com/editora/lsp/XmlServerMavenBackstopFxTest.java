package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The plain XML server is not started on a command that would load lemminx-maven (XML/pom review 3, A21): it serves
 * the poms folder trust keeps off the Maven server, so with that extension it would resolve them and run their
 * plugins anyway. Refused, said, and not a crash that would be restarted.
 */
@Tag("fx")
class XmlServerMavenBackstopFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void anXmlCommandOverTheWholePomBundleIsRefused(@TempDir Path dir) throws Exception {
        Path bundle = Files.createDirectories(dir.resolve("plugins/lsp/maven"));
        Files.writeString(bundle.resolve("org.eclipse.lemminx-0.30.1.jar"), "");
        Files.writeString(bundle.resolve("lemminx-maven-0.12.0.jar"), "");
        Path root = Files.createDirectories(dir.resolve("app"));
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project/>\n");
        LspManager manager = new LspManager((f, d) -> {}, (type, message) -> {});
        try {
            manager.configure(
                    true, Map.of("xml", "java -cp \"" + bundle + "/*\" org.eclipse.lemminx.XMLServerLauncher"));
            CompletableFuture<StartRefusal> refused = new CompletableFuture<>();
            AtomicInteger crashes = new AtomicInteger();
            manager.setOnStartRefused((serverId, sessionRoot, why) -> refused.complete(why));
            manager.setOnSessionCrashed((serverId, sessionRoot) -> crashes.incrementAndGet());

            Platform.runLater(() -> manager.openDocument(pom, root, "xml", "<project/>\n"));

            assertEquals(
                    StartRefusal.Kind.XML_LOADS_MAVEN,
                    refused.get(30, TimeUnit.SECONDS).kind());
            Thread.sleep(300);
            CompletableFuture<Void> drained = new CompletableFuture<>();
            Platform.runLater(() -> drained.complete(null));
            drained.get(5, TimeUnit.SECONDS);
            assertEquals(0, crashes.get(), "a refusal is not a crash, so nothing restarts it");
            assertFalse(manager.hasSession("xml", root), "no server process for it");
        } finally {
            manager.shutdownAll();
        }
    }
}
