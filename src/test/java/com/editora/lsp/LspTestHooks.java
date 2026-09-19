package com.editora.lsp;

import org.eclipse.lsp4j.ServerCapabilities;

/**
 * Test-only bridge exposing {@code com.editora.lsp}'s package-private test seams to tests in other packages
 * (notably {@code com.editora.ui}, where {@code LspCoordinator} lives). Test sources only — no production
 * class references it.
 */
public final class LspTestHooks {

    private LspTestHooks() {}

    /**
     * Makes {@code manager} attach an in-process fake to every session it creates instead of forking a real
     * language server. Without this a coordinator test would fork whatever happens to be installed on the
     * machine running it.
     */
    public static java.util.List<FakeLanguageServer> useFakeSessions(LspManager manager) {
        return useFakeSessions(manager, caps());
    }

    public static java.util.List<FakeLanguageServer> useFakeSessions(
            LspManager manager, ServerCapabilities capabilities) {
        java.util.List<FakeLanguageServer> created = new java.util.concurrent.CopyOnWriteArrayList<>();
        manager.setSessionStarterForTest(session -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            created.add(fake);
            session.attachForTest(fake, capabilities);
        });
        return created; // grows as sessions are created, so a test can set canned responses on one
    }

    /** Measures the production pure sync calculation without transport or server latency. */
    public static void computeSyncDiff(String before, String after) {
        var delta = TextSyncDiff.diff(before, after);
        if (delta != null) TextSyncDiff.rangeOf(before, delta.start(), delta.end());
    }

    /** Real-server launch for opt-in Gradle probes with a Gradle-compatible JVM. */
    public static void useLiveGradleServer(
            LspManager manager, String command, java.nio.file.Path workspace, String gradleJava) {
        manager.setSessionStarterForTest(session -> Thread.ofVirtual().start(() -> {
            var options = new java.util.HashMap<>(LspManager.javaInitOptions(java.util.List.of()));
            if (gradleJava != null) {
                @SuppressWarnings("unchecked")
                var settings = (java.util.Map<String, Object>) options.get("settings");
                @SuppressWarnings("unchecked")
                var javaSettings = new java.util.HashMap<>((java.util.Map<String, Object>) settings.get("java"));
                javaSettings.put(
                        "import",
                        java.util.Map.of("gradle", java.util.Map.of("java", java.util.Map.of("home", gradleJava))));
                options.put("settings", java.util.Map.of("java", javaSettings));
            }
            session.configureStart(java.util.List.of(command, "-data", workspace.toString()), () -> options);
            session.start();
        }));
    }

    /** Server capabilities advertising the providers the coordinator gates features on. */
    public static ServerCapabilities caps() {
        var caps = new ServerCapabilities();
        caps.setDocumentFormattingProvider(true);
        caps.setDocumentRangeFormattingProvider(true);
        caps.setCodeActionProvider(true);
        caps.setRenameProvider(true);
        caps.setDocumentHighlightProvider(true);
        caps.setDocumentSymbolProvider(true);
        return caps;
    }
}
