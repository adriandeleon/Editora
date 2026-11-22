package com.editora.secrets;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** An in-memory keychain for tests — the real one is never touched. Can be told to refuse or garble. */
public final class FakeSecretStore implements SecretStore {

    public final Map<String, String> items = new ConcurrentHashMap<>();
    public volatile boolean available = true;
    public volatile boolean refuseWrites;
    /** Hands back something other than what was written (a keychain that mangles text). */
    public volatile boolean garbleReads;
    /** The thread each write and delete ran on. */
    public final java.util.List<String> threads = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public String name() {
        return "Fake Keychain";
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public Optional<String> read(String account) {
        String v = items.get(account);
        return v == null ? Optional.empty() : Optional.of(garbleReads ? v + "?" : v);
    }

    @Override
    public void write(String account, String secret) throws SecretStoreException {
        threads.add(Thread.currentThread().getName());
        if (refuseWrites) {
            throw new SecretStoreException("refused");
        }
        items.put(account, secret);
    }

    @Override
    public void delete(String account) {
        threads.add(Thread.currentThread().getName());
        items.remove(account);
    }
}
