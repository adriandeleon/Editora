package com.editora.config;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.ai.AiProvider;
import com.editora.secrets.SecretStore;
import com.editora.secrets.SecretStoreException;

/**
 * Where the AI providers' API keys live: the system keychain when one answers, else {@code settings.json}.
 *
 * <p>{@link #open} runs once at startup, off the FX thread (a keychain may prompt). It reads the keys the keychain
 * holds and moves any key still in {@code settings.json} into it — the field is blanked only after the keychain
 * accepted the key and gave the same text back, so a failure never loses one. From then on {@link Settings} asks
 * the vault for a key and hands it every change. A keychain write that fails puts every key back into the settings
 * fields and the vault into {@link Storage#SETTINGS_FILE} mode for the rest of the session.
 */
public final class ApiKeyVault {

    private static final Logger LOG = Logger.getLogger(ApiKeyVault.class.getName());

    /** Where keys are kept, as the AI Actions page says it. */
    public enum Storage {
        /** In the system keychain; {@code settings.json} holds none. */
        KEYCHAIN,
        /** In {@code settings.json}: there is no keychain to use (or it failed). */
        SETTINGS_FILE,
        /** In {@code settings.json} for now: keys saved earlier are in a keychain that does not answer. */
        KEYCHAIN_UNREACHABLE
    }

    /** The providers that take a key (Codex uses its own login). */
    static final List<AiProvider> PROVIDERS = List.of(AiProvider.ANTHROPIC, AiProvider.OPENAI, AiProvider.LMSTUDIO);

    private final SecretStore store;
    private final Map<AiProvider, String> keys = new ConcurrentHashMap<>();
    private volatile Storage storage;
    private volatile String failure = "";
    private volatile Settings settings;

    private ApiKeyVault(SecretStore store, Storage storage) {
        this.store = store;
        this.storage = storage;
    }

    /** The keychain account a provider's key is filed under. */
    static String account(AiProvider provider) {
        return "ai-api-key." + provider.id();
    }

    /**
     * Opens the vault on {@code settings} and attaches it ({@link Settings#useApiKeyVault}). Blocking — call it off
     * the FX thread. Returns the vault; {@link #settingsChanged()} says whether {@code settings} must be saved (a key
     * moved out of it, or back into it after a failure).
     */
    public static ApiKeyVault open(SecretStore store, Settings settings) {
        boolean answers;
        try {
            answers = store.available();
        } catch (RuntimeException | LinkageError e) {
            answers = false;
        }
        ApiKeyVault vault;
        if (!answers) {
            vault = new ApiKeyVault(
                    store, settings.isAiApiKeysInKeychain() ? Storage.KEYCHAIN_UNREACHABLE : Storage.SETTINGS_FILE);
            settings.useApiKeyVault(vault);
            return vault;
        }
        vault = new ApiKeyVault(store, Storage.KEYCHAIN);
        vault.settings = settings;
        try {
            for (AiProvider p : PROVIDERS) {
                String raw = settings.rawApiKey(p);
                if (!raw.isBlank()) {
                    vault.writeChecked(p, raw);
                } else {
                    store.read(account(p)).filter(v -> !v.isBlank()).ifPresent(v -> vault.keys.put(p, v));
                }
            }
        } catch (SecretStoreException | RuntimeException e) {
            vault.fallBack(e);
            settings.useApiKeyVault(vault);
            return vault;
        }
        // Every key is in the keychain and read back: only now do the settings fields let go of them.
        for (AiProvider p : PROVIDERS) {
            if (!settings.rawApiKey(p).isBlank()) {
                settings.setRawApiKey(p, "");
                vault.changed = true;
            }
        }
        if (!settings.isAiApiKeysInKeychain()) {
            settings.setAiApiKeysInKeychain(true);
            vault.changed = true;
        }
        settings.useApiKeyVault(vault);
        return vault;
    }

    private boolean changed;

    /** Whether {@link #open} or a later call changed the settings fields, so {@code settings.json} needs saving. */
    public boolean settingsChanged() {
        boolean c = changed;
        changed = false;
        return c;
    }

    public Storage storage() {
        return storage;
    }

    /** The keychain's name ("macOS Keychain", …). */
    public String storeName() {
        return store.name();
    }

    /** Why the keychain is not used, when it failed (empty otherwise). */
    public String failure() {
        return failure;
    }

    /** Whether keys are read from and written to the keychain (not the settings fields). */
    boolean inKeychain() {
        return storage == Storage.KEYCHAIN;
    }

    void bind(Settings s) {
        this.settings = s;
    }

    String get(AiProvider provider) {
        return keys.getOrDefault(provider, "");
    }

    /**
     * Files {@code key} for {@code provider} (blank removes it). Returns false when the keychain refused: the vault
     * has then fallen back and the caller keeps the key in the settings field.
     */
    boolean put(AiProvider provider, String key) {
        String value = key == null ? "" : key;
        if (value.equals(get(provider))) {
            return true;
        }
        try {
            if (value.isBlank()) {
                store.delete(account(provider));
                keys.remove(provider);
            } else {
                writeChecked(provider, value);
            }
            return true;
        } catch (SecretStoreException | RuntimeException e) {
            fallBack(e);
            return false;
        }
    }

    /**
     * Moves a key that appeared in the settings fields (a hand-edited {@code settings.json} re-read) into the
     * keychain. Returns whether the fields changed.
     */
    boolean absorb(Settings s) {
        if (!inKeychain()) {
            return false;
        }
        boolean moved = false;
        for (AiProvider p : PROVIDERS) {
            String raw = s.rawApiKey(p);
            if (!raw.isBlank() && put(p, raw) && inKeychain()) {
                s.setRawApiKey(p, "");
                moved = true;
            }
        }
        return moved;
    }

    /** Writes, then reads back: a key counts as stored only when the keychain gives the same text back. */
    private void writeChecked(AiProvider provider, String value) throws SecretStoreException {
        store.write(account(provider), value);
        String back = store.read(account(provider)).orElse("");
        if (!back.equals(value)) {
            throw new SecretStoreException("the keychain gave back a different value");
        }
        keys.put(provider, value);
    }

    /** Keys go back to the settings fields; the keychain is not used again this session. */
    private void fallBack(Exception e) {
        LOG.log(Level.WARNING, "System keychain not used for API keys: " + e.getMessage());
        failure = String.valueOf(e.getMessage());
        storage = Storage.SETTINGS_FILE;
        Settings s = settings;
        if (s != null) {
            for (AiProvider p : PROVIDERS) {
                String held = keys.get(p);
                if (held != null && !held.isBlank() && s.rawApiKey(p).isBlank()) {
                    s.setRawApiKey(p, held);
                    changed = true;
                }
            }
        }
        keys.clear();
    }
}
