package com.editora.install;

import com.editora.io.LazyHttpClient;

/** Lets tests in other packages build an {@link InstallService} through its package-private test seam. */
public final class InstallTestAccess {

    private InstallTestAccess() {}

    /** An install service whose downloads go through {@code client}, each capped at {@code maxDownloadBytes}. */
    public static InstallService service(LazyHttpClient client, long maxDownloadBytes) {
        return new InstallService(client, maxDownloadBytes);
    }

    /** Makes {@code service} install pinned tools from {@code recipes} (hashes matching a test's own files). */
    public static void pinnedTools(
            InstallService service,
            java.util.function.Function<String, java.util.Optional<InstallCatalog.PinnedTool>> recipes) {
        service.setPinnedToolsForTest(recipes);
    }
}
