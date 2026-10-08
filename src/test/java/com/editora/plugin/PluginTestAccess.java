package com.editora.plugin;

import java.security.PublicKey;
import java.util.function.Supplier;

import com.editora.io.LazyHttpClient;

/**
 * Lets tests in other packages build a {@link PluginRegistry} and a {@link PluginInstaller} through their
 * package-private test seams.
 */
public final class PluginTestAccess {

    private PluginTestAccess() {}

    /** A registry client fetching through {@code client} and checking signatures against {@code key}. */
    public static PluginRegistry registry(LazyHttpClient client, Supplier<PublicKey> key) {
        return new PluginRegistry(client, key);
    }

    /** An installer downloading through {@code client}, each archive capped at {@code maxArchiveBytes}. */
    public static PluginInstaller installer(PluginManager manager, LazyHttpClient client, long maxArchiveBytes) {
        return new PluginInstaller(manager, client, maxArchiveBytes);
    }

    /** The checksum a registry entry carries for {@code archive}. */
    public static String sha256(byte[] archive) {
        return PluginInstaller.sha256(archive);
    }
}
