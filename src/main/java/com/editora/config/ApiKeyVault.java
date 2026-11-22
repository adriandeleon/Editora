package com.editora.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.ai.AiProvider;
import com.editora.secrets.EncryptedFileStore;
import com.editora.secrets.SecretStore;
import com.editora.secrets.SecretStoreException;
import org.jspecify.annotations.Nullable;

/**
 * Where the AI providers' API keys live: the system keychain when one answers, else an encrypted file of their own
 * ({@link EncryptedFileStore}, {@code keys/secrets.enc}); never {@code settings.json} (SET-13, Pauta 6.2.0).
 *
 * <p>{@link #open} runs once at startup, off the FX thread (a keychain may prompt). It reads the keys the keychain
 * (or the file) holds and moves any key still in {@code settings.json} — or, once the keychain answers again, in
 * the encrypted file — into it. A key counts as moved only when the store gave the same text back. From then on
 * {@link Settings} asks the vault for a key and hands it every change: the vault keeps it in memory at once and
 * writes the store on a thread of its own, never the caller's (the FX thread, from Settings). A keychain that
 * refuses a write sends every key to the encrypted file for the rest of the session; a file that cannot be written
 * either leaves them in memory for the session only, and the AI page says so.
 */
public final class ApiKeyVault {

    private static final Logger LOG = Logger.getLogger(ApiKeyVault.class.getName());

    /** Where keys are kept, as the AI page says it. */
    public enum Storage {
        /** In the system keychain. */
        KEYCHAIN,
        /** In the encrypted file: there is no keychain to use (or it failed). */
        ENCRYPTED_FILE,
        /** Keys saved earlier are in a keychain that does not answer; new ones go to the encrypted file. */
        KEYCHAIN_UNREACHABLE,
        /** In memory only: neither the keychain nor the encrypted file can be used. */
        SESSION_ONLY
    }

    /** What went wrong with the encrypted file, for the AI page to say. */
    public enum Problem {
        NONE,
        /** It exists and does not decrypt: no key is read from it, and it is not written over. */
        FILE_UNREADABLE,
        /** It did not decrypt, so a write moved it aside ({@link #problemDetail()} is where) and started a new one. */
        FILE_SET_ASIDE
    }

    /** The providers that take a key (Codex uses its own login). */
    static final List<AiProvider> PROVIDERS = List.of(AiProvider.ANTHROPIC, AiProvider.OPENAI, AiProvider.LMSTUDIO);

    /** One daemon thread for every vault, so the writes reach the store in the order they were made. */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "api-key-vault");
        t.setDaemon(true);
        return t;
    });

    private final SecretStore keychain;
    private final EncryptedFileStore file;
    private final Map<AiProvider, String> keys = new ConcurrentHashMap<>();
    /** The store writes go to: the keychain, the file, or null (session only). */
    private volatile @Nullable SecretStore active;

    private volatile Storage storage;
    private volatile String failure = "";
    private volatile Problem problem = Problem.NONE;
    private volatile String problemDetail = "";
    private volatile boolean changed;

    private CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);

    private ApiKeyVault(SecretStore keychain, EncryptedFileStore file, @Nullable SecretStore active, Storage storage) {
        this.keychain = keychain;
        this.file = file;
        this.active = active;
        this.storage = storage;
    }

    /** The account a provider's key is filed under, in the keychain and in the file alike. */
    static String account(AiProvider provider) {
        return "ai-api-key." + provider.id();
    }

    /**
     * Opens the vault on {@code settings} and attaches it ({@link Settings#useApiKeyVault}). Blocking — call it off
     * the FX thread. {@link #settingsChanged()} then says whether {@code settings.json} must be saved (it still held
     * a key, which the save leaves out, or the keychain flag changed).
     */
    public static ApiKeyVault open(SecretStore keychain, EncryptedFileStore file, Settings settings) {
        boolean answers;
        try {
            answers = keychain.available();
        } catch (RuntimeException | LinkageError e) {
            answers = false;
        }
        ApiKeyVault vault;
        if (answers) {
            vault = new ApiKeyVault(keychain, file, keychain, Storage.KEYCHAIN);
            try {
                vault.openOnKeychain(settings);
            } catch (SecretStoreException | RuntimeException e) {
                vault.toFile(e, settings);
            }
        } else {
            vault = new ApiKeyVault(
                    keychain,
                    file,
                    file,
                    settings.isAiApiKeysInKeychain() ? Storage.KEYCHAIN_UNREACHABLE : Storage.ENCRYPTED_FILE);
            vault.openOnFile(settings);
        }
        // The keys are in the store, or held for the session: settings.json lets go of them either way. Its fields
        // are read from the file, never written to it (Settings), so the save this asks for drops them.
        for (AiProvider p : PROVIDERS) {
            if (!settings.rawApiKey(p).isBlank()) {
                settings.setRawApiKey(p, "");
                vault.changed = true;
            }
        }
        if (vault.storage == Storage.SESSION_ONLY) {
            System.err.println("Editora: the AI API keys are kept for this session only: " + vault.failure);
        }
        settings.useApiKeyVault(vault);
        return vault;
    }

    /** Keychain first: what it holds, then the file's keys (newer: typed while it did not answer), then settings'. */
    private void openOnKeychain(Settings s) throws SecretStoreException {
        for (AiProvider p : PROVIDERS) {
            keychain.read(account(p)).filter(v -> !v.isBlank()).ifPresent(v -> keys.put(p, v));
        }
        Map<String, String> inFile = Map.of();
        try {
            inFile = file.readAll();
        } catch (SecretStoreException unreadable) {
            noteUnreadable(unreadable);
        }
        for (AiProvider p : PROVIDERS) {
            String fromFile = inFile.get(account(p));
            if (fromFile != null && !fromFile.isBlank()) {
                writeChecked(keychain, p, fromFile);
                keys.put(p, fromFile);
            }
        }
        for (AiProvider p : PROVIDERS) {
            String raw = s.rawApiKey(p);
            if (!raw.isBlank()) {
                writeChecked(keychain, p, raw);
                keys.put(p, raw);
            }
        }
        // Everything the file held is in the keychain now and read back: only then does the file let go of it.
        for (AiProvider p : PROVIDERS) {
            if (inFile.containsKey(account(p))) {
                file.delete(account(p));
            }
        }
        if (!s.isAiApiKeysInKeychain()) {
            s.setAiApiKeysInKeychain(true);
            changed = true;
        }
    }

    private void openOnFile(Settings s) {
        try {
            Map<String, String> inFile = file.readAll();
            for (AiProvider p : PROVIDERS) {
                String value = inFile.get(account(p));
                if (value != null && !value.isBlank()) {
                    keys.put(p, value);
                }
            }
        } catch (SecretStoreException unreadable) {
            noteUnreadable(unreadable);
        }
        for (AiProvider p : PROVIDERS) {
            String raw = s.rawApiKey(p);
            if (!raw.isBlank()) {
                keys.put(p, raw);
            }
        }
        try {
            for (AiProvider p : PROVIDERS) {
                if (!s.rawApiKey(p).isBlank()) {
                    writeChecked(file, p, s.rawApiKey(p));
                }
            }
            noteSetAside();
        } catch (SecretStoreException | RuntimeException e) {
            sessionOnly(e);
        }
    }

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
        return keychain.name();
    }

    /** The encrypted file, for the AI page to name. */
    public Path filePath() {
        return file.dataFile();
    }

    /** Why the keychain (or the file) is not used, when it failed (empty otherwise). */
    public String failure() {
        return failure;
    }

    public Problem problem() {
        return problem;
    }

    /** Why the file did not decrypt ({@link Problem#FILE_UNREADABLE}) or where it was kept ({@code FILE_SET_ASIDE}). */
    public String problemDetail() {
        return problemDetail;
    }

    String get(AiProvider provider) {
        return keys.getOrDefault(provider, "");
    }

    /**
     * Keeps {@code key} for {@code provider} (blank removes it) at once, and writes the store on the vault's own
     * thread: never the caller's, which is the FX thread when Settings saves a key.
     */
    void put(AiProvider provider, @Nullable String key) {
        String value = key == null ? "" : key;
        if (value.equals(get(provider))) {
            return;
        }
        if (value.isBlank()) {
            keys.remove(provider);
        } else {
            keys.put(provider, value);
        }
        enqueue(() -> store(provider, value));
    }

    /**
     * Moves a key that appeared in the settings fields (a hand-edited {@code settings.json} re-read) into the
     * vault. Returns whether the fields changed. Only {@code s} is changed; the writes go to the vault's thread.
     */
    boolean absorb(Settings s) {
        boolean moved = false;
        for (AiProvider p : PROVIDERS) {
            String raw = s.rawApiKey(p);
            if (!raw.isBlank()) {
                put(p, raw);
                s.setRawApiKey(p, "");
                moved = true;
            }
        }
        return moved;
    }

    /** Waits (up to a minute) for the store writes queued so far. */
    public boolean awaitWrites() {
        CompletableFuture<Void> last;
        synchronized (this) {
            last = lastWrite;
        }
        try {
            last.get(1, TimeUnit.MINUTES);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return false;
        }
    }

    private synchronized void enqueue(Runnable work) {
        lastWrite = lastWrite.thenRunAsync(work, WRITER);
    }

    /** On the vault's thread: one key to the active store; a refusal moves every key down to the next store. */
    private void store(AiProvider provider, String value) {
        SecretStore target = active;
        if (target == null) {
            return; // session only
        }
        try {
            if (value.isBlank()) {
                target.delete(account(provider));
            } else {
                writeChecked(target, provider, value);
            }
            if (target == file) {
                noteSetAside();
            }
        } catch (SecretStoreException | RuntimeException e) {
            if (target == keychain) {
                toFile(e, null);
            } else {
                sessionOnly(e);
            }
        }
    }

    /** Writes, then reads back: a key counts as stored only when the store gives the same text back. */
    private static void writeChecked(SecretStore store, AiProvider provider, String value) throws SecretStoreException {
        store.write(account(provider), value);
        String back = store.read(account(provider)).orElse("");
        if (!back.equals(value)) {
            throw new SecretStoreException("the store gave back a different value");
        }
    }

    /**
     * The keychain refused: every key held (and, at open, every key {@code opening} still has in its fields) goes
     * to the encrypted file, which serves the rest of the session.
     */
    private void toFile(Exception e, @Nullable Settings opening) {
        LOG.log(Level.WARNING, "System keychain not used for API keys: " + e.getMessage());
        failure = String.valueOf(e.getMessage());
        active = file;
        storage = Storage.ENCRYPTED_FILE;
        if (opening != null) {
            for (AiProvider p : PROVIDERS) {
                String raw = opening.rawApiKey(p);
                if (!raw.isBlank()) {
                    keys.put(p, raw);
                }
            }
        }
        try {
            for (AiProvider p : PROVIDERS) {
                String held = keys.get(p);
                if (held != null && !held.isBlank()) {
                    writeChecked(file, p, held);
                }
            }
            noteSetAside();
        } catch (SecretStoreException | RuntimeException fileFailed) {
            sessionOnly(fileFailed);
        }
    }

    private void sessionOnly(Exception e) {
        LOG.log(
                Level.WARNING,
                "The encrypted key file cannot be used either; API keys are kept for this session: " + e.getMessage());
        failure = String.valueOf(e.getMessage());
        active = null;
        storage = Storage.SESSION_ONLY;
    }

    private void noteUnreadable(SecretStoreException e) {
        LOG.log(
                Level.WARNING,
                "The encrypted API key file does not decrypt; no key is read from it: " + e.getMessage());
        problem = Problem.FILE_UNREADABLE;
        problemDetail = String.valueOf(e.getMessage());
    }

    private void noteSetAside() {
        Path kept = file.setAside();
        if (kept != null) {
            problem = Problem.FILE_SET_ASIDE;
            problemDetail = kept.toString();
        }
    }
}
