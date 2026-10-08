package com.editora.install;

import com.editora.io.LazyHttpClient;

/** Lets tests in other packages build an {@link InstallService} through its package-private test seam. */
public final class InstallTestAccess {

    private InstallTestAccess() {}

    /** An install service whose downloads go through {@code client}, each capped at {@code maxDownloadBytes}. */
    public static InstallService service(LazyHttpClient client, long maxDownloadBytes) {
        return new InstallService(client, maxDownloadBytes);
    }
}
