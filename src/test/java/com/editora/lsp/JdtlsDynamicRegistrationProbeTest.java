package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in probe against a real jdtls: the capabilities it only registers <em>dynamically</em> must end up in
 * the session's effective capabilities with their options intact. Run with
 * {@code ./mvnw test -Dtest=JdtlsDynamicRegistrationProbeTest -Dgroups=probe -Dlsp.probe=true}.
 *
 * <p>These are the regressions advertising dynamic registration caused: jdtls registers on-type formatting
 * only while {@code java.format.onType.enabled} is set, and registers rename with {@code prepareProvider},
 * which a Boolean cannot hold.
 */
@Tag("probe")
class JdtlsDynamicRegistrationProbeTest {

    private static String jdtls() {
        for (String candidate : List.of(
                System.getProperty("user.home") + "/.editora/plugins/lsp/java/bin/jdtls",
                System.getProperty("user.home") + "/.editora-dev/plugins/lsp/java/bin/jdtls",
                "/opt/homebrew/bin/jdtls",
                "/usr/local/bin/jdtls")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "";
    }

    /** Signalled on every registration change, so the probe waits on the event instead of polling. */
    private final Object registrations = new Object();

    private boolean eventually(BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        synchronized (registrations) {
            while (!condition.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(registrations, remaining);
            }
            return true;
        }
    }

    @Test
    void jdtlsRegistersOnTypeFormattingAndRenameWithPrepare() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Assumptions.assumeFalse(jdtls().isBlank(), "needs a local jdtls");
        Path root = Files.createTempDirectory("editora-lsp-registration-").toRealPath();
        Path project = Files.createDirectories(root.resolve("project"));
        Path file = project.resolve("App.java");
        String source = "public class App { void run() { int n = 1; } }\n";
        Files.writeString(file, source);
        CountDownLatch ready = new CountDownLatch(1);
        var session = new LanguageServerSession(
                new LspServerRegistry.ServerSpec(
                        "java", List.of(jdtls(), "-data", root.resolve("data").toString()), List.of()),
                project,
                diagnostics -> {},
                (type, message) -> {
                    if ("ServiceReady".equals(type)) {
                        ready.countDown();
                    }
                },
                LspManager.javaInitOptions(List.of(), true));
        session.setJavaOnTypeFormatting(true);
        session.setOnRefresh(kind -> {
            synchronized (registrations) {
                registrations.notifyAll();
            }
        });
        CountDownLatch exited = new CountDownLatch(1);
        session.setOnDead(exited::countDown);
        try {
            assertTrue(session.start());
            assertTrue(ready.await(90, TimeUnit.SECONDS), "real handshake");
            session.didOpen(file.toUri().toString(), "java", source);

            assertTrue(
                    eventually(() -> session.capabilities().getDocumentOnTypeFormattingProvider() != null, 60),
                    "jdtls must register on-type formatting once java.format.onType.enabled is set");
            assertTrue(
                    eventually(() -> LspManager.renameProvider(session.capabilities()), 60),
                    "jdtls must register rename");
            var rename = session.capabilities().getRenameProvider();
            assertTrue(rename.isRight(), "the rename registration must keep its options: " + rename);
            assertTrue(Boolean.TRUE.equals(rename.getRight().getPrepareProvider()), "prepareProvider was dropped");
            assertNotNull(LspManager.semanticTokensProvider(session.capabilities()), "semantic tokens");

            session.setJavaOnTypeFormatting(false);
            assertTrue(
                    eventually(() -> session.capabilities().getDocumentOnTypeFormattingProvider() == null, 60),
                    "switching the setting off must unregister the provider");
        } finally {
            session.dispose();
        }
        assertTrue(exited.await(30, TimeUnit.SECONDS), "jdtls must end after shutdown/exit");
    }
}
