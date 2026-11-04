package com.editora.lsp;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Closes every {@link LspManager} a test made and left open: after each test the ones made during it (its
 * {@code @BeforeEach} included), after each class the ones its {@code @BeforeAll} made. Registered for every test
 * class through {@code META-INF/services} and {@code junit-platform.properties}.
 *
 * <p>A manager watches each server's workspace with a {@link RootWatcher}: on Linux an inotify instance, of which
 * a user has 128 across every program they run. Only {@link LspManager#close} stops those watchers, and the
 * watcher's thread keeps an unclosed manager reachable, so it is never collected either. Most of the 88 managers
 * the LSP and DAP tests make were never closed: one full run ended holding 326 inotify instances. Past 128 every
 * watcher in the tests, and in the user's editors and file managers, failed or fell back to polling.
 */
public final class ClosedLspManagers
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(ClosedLspManagers.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        remember(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        remember(context);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        closeMadeSince(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        closeMadeSince(context);
    }

    /** Notes the managers open before this test or class, which are not its to close. */
    private static void remember(ExtensionContext context) {
        Set<LspManager> open = Collections.newSetFromMap(new IdentityHashMap<>());
        open.addAll(LspManager.openManagersForTest());
        context.getStore(NS).put(context.getUniqueId(), open);
    }

    @SuppressWarnings("unchecked")
    private static void closeMadeSince(ExtensionContext context) {
        Set<LspManager> before = (Set<LspManager>) context.getStore(NS).remove(context.getUniqueId());
        if (before == null) {
            return;
        }
        List<LspManager> made = LspManager.openManagersForTest().stream()
                .filter(m -> !before.contains(m))
                .toList();
        RuntimeException failure = null;
        for (LspManager m : made) {
            try {
                m.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
