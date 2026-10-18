package com.editora.secrets;

import java.util.Optional;

/**
 * A place for small secrets (the AI providers' API keys) outside {@code settings.json}: the operating system's
 * keychain. Each secret is filed under a service name and an account name. Calls may block (D-Bus, a keychain
 * prompt), so callers run them off the FX thread where they can. Implementations: {@link MacKeychainStore},
 * {@link WindowsCredentialStore}, {@link LinuxSecretServiceStore}; {@link SecretStores#forThisMachine} picks one.
 */
public interface SecretStore {

    /** A short name for messages ("macOS Keychain", "Windows Credential Manager", "Secret Service"). */
    String name();

    /** Whether the store can be used now: its library loaded and, where that can be asked, its service answers. */
    boolean available();

    /** The secret filed under {@code account}, or empty when there is none. */
    Optional<String> read(String account) throws SecretStoreException;

    /** Files {@code secret} under {@code account}, replacing what was there. */
    void write(String account, String secret) throws SecretStoreException;

    /** Removes what is filed under {@code account}; nothing there is not an error. */
    void delete(String account) throws SecretStoreException;

    /** A store that is never available: what a machine without a keychain (or {@code -Deditora.secretStore=none}) gets. */
    SecretStore NONE = new SecretStore() {
        @Override
        public String name() {
            return "none";
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Optional<String> read(String account) {
            return Optional.empty();
        }

        @Override
        public void write(String account, String secret) throws SecretStoreException {
            throw new SecretStoreException("no keychain");
        }

        @Override
        public void delete(String account) {}
    };
}
