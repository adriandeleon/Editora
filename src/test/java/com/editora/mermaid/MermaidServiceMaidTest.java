package com.editora.mermaid;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MermaidService}'s maid detection: the default {@code npx -y @probelabs/maid} is decided by
 * {@link MaidLocator} (its files, with the Settings Node.js), never by running npx — which would fetch the
 * package from the npm registry on every start-up and Settings visit. A command of the user's own is not
 * located: it is run, as before.
 */
class MermaidServiceMaidTest {

    /** mmdc at a path that does not exist: its probe fails to launch at once, and nothing else runs. */
    private static final String NO_MMDC = "/nonexistent/editora-mermaid-test/mmdc";

    @Test
    void theDefaultMaidIsFoundByItsFilesWithTheSettingsNodeJs() {
        MermaidService service = new MermaidService();
        try {
            AtomicReference<String> askedWith = new AtomicReference<>();
            MaidLocator.Found found = new MaidLocator.Found(Path.of("/g/node_modules/@probelabs/maid"), "0.0.29");
            service.setMaidLocator(node -> {
                askedWith.set(node);
                return Optional.of(found);
            });
            service.setPaths(NO_MMDC, "");
            service.setNodePath("/opt/node/bin/node");

            assertTrue(service.maidIsDefault());
            MermaidService.Availability a = service.probe();
            assertTrue(a.maid());
            assertSame(found, a.maidInstall());
            assertFalse(a.mmdc());
            assertEquals("/opt/node/bin/node", askedWith.get());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void anUninstalledDefaultMaidIsAbsentWithoutRunningNpx() {
        MermaidService service = new MermaidService();
        try {
            service.setMaidLocator(node -> Optional.empty());
            service.setPaths(NO_MMDC, "");
            long start = System.nanoTime();
            MermaidService.Availability a = service.probe();
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertFalse(a.maid());
            assertNull(a.maidInstall());
            // `npx -y @probelabs/maid --version` takes seconds (and can download); a failed launch and a lookup don't.
            assertTrue(ms < 3_000, "probe took " + ms + " ms");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aCommandOfTheUsersOwnIsRunNotLocated() {
        MermaidService service = new MermaidService();
        try {
            service.setMaidLocator(node -> {
                throw new AssertionError("a configured maid command must not be located");
            });
            service.setPaths(NO_MMDC, "/nonexistent/editora-mermaid-test/maid");
            assertFalse(service.maidIsDefault());
            assertTrue(service.locateDefaultMaid().isEmpty());
            MermaidService.Availability a = service.probe();
            assertFalse(a.maid());
            assertNull(a.maidInstall());
            assertEquals(List.of("/nonexistent/editora-mermaid-test/maid"), service.maidCommand());
        } finally {
            service.shutdown();
        }
    }
}
