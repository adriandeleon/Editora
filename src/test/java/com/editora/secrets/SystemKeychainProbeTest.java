package com.editora.secrets;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The real keychain of the machine running the test — skipped unless {@code EDITORA_REAL_KEYCHAIN_PROBE=1} is set,
 * because it writes to it. CI sets it on the macOS and Windows pure-test jobs, so those two backends run for real
 * on every pull request; Linux lanes skip it (listed in {@code scripts/expected-skips-linux.txt}).
 *
 * <p>It files one throw-away item under its own service, "Editora probe" (never the "Editora" service the app
 * uses), reads it, overwrites it and deletes it, and deletes it again in {@code finally} whatever failed. It builds
 * the store directly, so the build's {@code -Deditora.secretStore=none} (which keeps the app's own tests off the
 * keychain) does not apply. On macOS and Windows a keychain always exists, so a store that does not load is a
 * broken binding and fails; only a Linux machine with no Secret Service at all skips, and says why.
 */
@EnabledIfEnvironmentVariable(named = "EDITORA_REAL_KEYCHAIN_PROBE", matches = "1")
class SystemKeychainProbeTest {

    private static final String SERVICE = "Editora probe";

    @Test
    void writeReadOverwriteAndDeleteAThrowAwayItem() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean always = os.contains("mac") || os.contains("win");
        SecretStore store = os.contains("mac")
                ? new MacKeychainStore(SERVICE)
                : os.contains("win") ? new WindowsCredentialStore(SERVICE) : new LinuxSecretServiceStore(SERVICE);
        if (!store.available()) {
            if (always) {
                fail(store.name() + " did not load on " + os + ": the JNA binding is broken");
            }
            String reason = "no keychain on this machine: " + store.name()
                    + " (libsecret) is missing or no Secret Service answers on the session bus";
            System.out.println("SystemKeychainProbeTest skipped — " + reason);
            Assumptions.abort(reason);
        }
        String account = "probe-" + UUID.randomUUID();
        try {
            assertEquals(Optional.empty(), read(store, account), store.name() + ": a new account must be empty");
            write(store, account, "FAKE-secret ünïcode");
            assertEquals(
                    Optional.of("FAKE-secret ünïcode"),
                    read(store, account),
                    store.name() + ": did not give back what was written");
            write(store, account, "FAKE-2");
            assertEquals(Optional.of("FAKE-2"), read(store, account), store.name() + ": an overwrite did not replace");
            store.delete(account);
            assertEquals(
                    Optional.empty(), read(store, account), store.name() + ": the item is still there after delete");
            store.delete(account); // deleting what is not there is not an error
        } finally {
            try {
                store.delete(account); // never leave the throw-away item behind
            } catch (SecretStoreException ignored) {
                // the assertion above already says what went wrong
            }
        }
    }

    private static Optional<String> read(SecretStore store, String account) {
        try {
            return store.read(account);
        } catch (SecretStoreException e) {
            return fail(store.name() + ": read failed — " + e.getMessage(), e);
        }
    }

    private static void write(SecretStore store, String account, String secret) {
        try {
            store.write(account, secret);
        } catch (SecretStoreException e) {
            fail(store.name() + ": write failed — " + e.getMessage(), e);
        }
    }
}
