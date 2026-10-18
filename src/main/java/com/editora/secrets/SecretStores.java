package com.editora.secrets;

import java.util.Locale;

/** Picks the keychain of the machine Editora runs on. */
public final class SecretStores {

    /** The service name Editora's secrets are filed under. */
    public static final String SERVICE = "Editora";

    /** {@code -Deditora.secretStore=none} keeps every secret in {@code settings.json}, as without a keychain. */
    public static final String PROPERTY = "editora.secretStore";

    private SecretStores() {}

    /**
     * This machine's keychain: the macOS Keychain, the Windows Credential Manager, or the Secret Service on Linux
     * and the BSDs. {@link SecretStore#NONE} when the property says so or the platform has none. Loading a store
     * only loads its library; whether it answers is {@link SecretStore#available}.
     */
    public static SecretStore forThisMachine() {
        if ("none".equalsIgnoreCase(System.getProperty(PROPERTY, ""))) {
            return SecretStore.NONE;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                return new MacKeychainStore(SERVICE);
            }
            if (os.contains("win")) {
                return new WindowsCredentialStore(SERVICE);
            }
            return new LinuxSecretServiceStore(SERVICE);
        } catch (LinkageError | RuntimeException e) {
            return SecretStore.NONE; // JNA itself missing or refused native access
        }
    }
}
