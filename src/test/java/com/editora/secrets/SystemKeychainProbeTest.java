package com.editora.secrets;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real keychain of the machine running the test — skipped unless {@code EDITORA_REAL_KEYCHAIN_PROBE=1} is set,
 * because it writes to it. It files one throw-away item under the service "Editora probe" (never the "Editora"
 * service the app uses), reads it, overwrites it and deletes it.
 */
@EnabledIfEnvironmentVariable(named = "EDITORA_REAL_KEYCHAIN_PROBE", matches = "1")
class SystemKeychainProbeTest {

    @Test
    void writeReadOverwriteAndDeleteAThrowAwayItem() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String service = "Editora probe";
        SecretStore store = os.contains("mac")
                ? new MacKeychainStore(service)
                : os.contains("win") ? new WindowsCredentialStore(service) : new LinuxSecretServiceStore(service);
        assertTrue(store.available(), store.name() + " does not answer");
        String account = "probe-" + UUID.randomUUID();
        try {
            assertEquals(Optional.empty(), store.read(account));
            store.write(account, "FAKE-secret ünïcode");
            assertEquals(Optional.of("FAKE-secret ünïcode"), store.read(account));
            store.write(account, "FAKE-2");
            assertEquals(Optional.of("FAKE-2"), store.read(account));
        } finally {
            store.delete(account);
        }
        assertEquals(Optional.empty(), store.read(account));
        store.delete(account); // deleting what is not there is not an error
    }
}
