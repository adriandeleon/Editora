package com.editora.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;

/**
 * The secrets' place when no system keychain answers: one file encrypted with AES-256-GCM,
 * {@code <config>/keys/secrets.enc}, under a random 256-bit key Editora generates for it and keeps beside it in
 * {@code keys/secrets.key}. The folder and both files are owner-only where the file system has POSIX modes; each is
 * written beside itself and moved into place, so a crash leaves the old file or the new one, never half of one.
 *
 * <p>The generated key lives the way Pauta's SET-13 lets a key the app generates live (Torrenta's
 * {@code keys/<name>.key}): a file of its own, outside the settings files, readable by its owner alone. What the
 * encryption adds is that the secrets are in no settings file, no backup of one, no export and no sync: a copy of
 * {@code secrets.enc} alone reads as noise. It does not protect them from a program running as the same user, which
 * can read the key file as well; a system keychain is what does that.
 *
 * <p>A file that does not decrypt (the key file lost or replaced, the data damaged) is reported by every read and
 * never written over: the next write first moves both files aside, to {@code secrets.enc.unreadable} and
 * {@code secrets.key.unreadable}, and says where ({@link #setAside}).
 */
public final class EncryptedFileStore implements SecretStore {

    private static final Logger LOG = Logger.getLogger(EncryptedFileStore.class.getName());

    /** The folder under the config directory, as Torrenta names its own. */
    public static final String DIR = "keys";

    public static final String DATA_FILE = "secrets.enc";
    public static final String KEY_FILE = "secrets.key";
    /** The suffix a file that would not decrypt is kept under when a new one is started. */
    public static final String UNREADABLE = ".unreadable";

    /** The first bytes of the data file: its format; also the AES-GCM associated data, so a different format fails. */
    private static final byte[] MAGIC = "EDITORA-SECRETS-1\n".getBytes(StandardCharsets.US_ASCII);

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_DIR = PosixFilePermissions.fromString("rwx------");

    private final Path dir;
    private final SecureRandom random = new SecureRandom();
    private volatile @Nullable Path setAside;

    /** @param configDir the folder the {@code keys} folder is in */
    public EncryptedFileStore(Path configDir) {
        this.dir = configDir.resolve(DIR);
    }

    public Path dir() {
        return dir;
    }

    public Path dataFile() {
        return dir.resolve(DATA_FILE);
    }

    public Path keyFile() {
        return dir.resolve(KEY_FILE);
    }

    @Override
    public String name() {
        return "encrypted file";
    }

    /** Usable unless something that is not a folder stands where the folder goes. */
    @Override
    public boolean available() {
        return !Files.exists(dir) || Files.isDirectory(dir);
    }

    /** The file a write moved aside because it did not decrypt, once one did; null otherwise. */
    public @Nullable Path setAside() {
        return setAside;
    }

    @Override
    public synchronized Optional<String> read(String account) throws SecretStoreException {
        return Optional.ofNullable(load().get(account));
    }

    /** Every secret in the file, by account. Throws when there is a file and it does not decrypt. */
    public synchronized Map<String, String> readAll() throws SecretStoreException {
        return new TreeMap<>(load());
    }

    @Override
    public synchronized void write(String account, String secret) throws SecretStoreException {
        Map<String, String> all = loadOrSetAside();
        all.put(account, secret);
        save(all);
    }

    /** Removes {@code account}; a file that does not decrypt is left as it is and reported. */
    @Override
    public synchronized void delete(String account) throws SecretStoreException {
        Map<String, String> all = load();
        if (all.remove(account) != null) {
            save(all);
        }
    }

    // ---- the files ----------------------------------------------------------------------------------------------

    private Map<String, String> load() throws SecretStoreException {
        Path data = dataFile();
        if (!Files.exists(data)) {
            return new TreeMap<>();
        }
        byte[] key = readKey();
        if (key == null) {
            throw new SecretStoreException("the key file " + keyFile() + " is missing");
        }
        try {
            byte[] bytes = Files.readAllBytes(data);
            return decode(decrypt(key, bytes));
        } catch (IOException e) {
            throw new SecretStoreException("cannot read " + data + ": " + e.getMessage(), e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** As {@link #load}, but a file that does not decrypt is moved aside (and said) so a new one can start. */
    private Map<String, String> loadOrSetAside() throws SecretStoreException {
        try {
            return load();
        } catch (SecretStoreException unreadable) {
            Path data = dataFile();
            Path keptAs = data.resolveSibling(DATA_FILE + UNREADABLE);
            try {
                Files.move(data, keptAs, StandardCopyOption.REPLACE_EXISTING);
                if (Files.exists(keyFile())) {
                    Files.move(
                            keyFile(),
                            keyFile().resolveSibling(KEY_FILE + UNREADABLE),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new SecretStoreException(
                        unreadable.getMessage() + "; it could not be moved aside: " + e.getMessage(), e);
            }
            setAside = keptAs;
            LOG.log(
                    Level.WARNING,
                    "The encrypted key file did not decrypt (" + unreadable.getMessage() + "); kept as " + keptAs
                            + " and a new one started");
            return new TreeMap<>();
        }
    }

    private void save(Map<String, String> all) throws SecretStoreException {
        try {
            ensureDir();
            byte[] key = readKey();
            if (key == null) {
                key = new byte[KEY_BYTES];
                random.nextBytes(key);
                writeOwnerOnly(keyFile(), encodeKey(key).getBytes(StandardCharsets.UTF_8));
            }
            try {
                writeOwnerOnly(dataFile(), encrypt(key, encode(all)));
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (IOException e) {
            throw new SecretStoreException("cannot write " + dataFile() + ": " + e.getMessage(), e);
        }
    }

    private byte @Nullable [] readKey() throws SecretStoreException {
        Path f = keyFile();
        if (!Files.exists(f)) {
            return null;
        }
        try {
            return decodeKey(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            throw new SecretStoreException("the key file " + f + " cannot be read: " + e.getMessage(), e);
        }
    }

    private void ensureDir() throws IOException {
        if (posix()) {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir.getParent());
                try {
                    Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_DIR));
                } catch (FileAlreadyExistsException raced) {
                    // made in the meantime (or not a folder: refused below)
                }
            }
            if (!Files.isDirectory(dir)) {
                throw new IOException(dir + " is not a folder");
            }
            Files.setPosixFilePermissions(dir, OWNER_DIR);
        } else {
            Files.createDirectories(dir);
        }
    }

    /** Writes beside {@code file}, owner-only from the start where there are POSIX modes, then moves it in place. */
    private void writeOwnerOnly(Path file, byte[] bytes) throws IOException {
        FileAttribute<?>[] attrs = posix()
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_FILE)}
                : new FileAttribute<?>[0];
        Path tmp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp", attrs);
        try {
            Files.write(tmp, bytes);
            try (var channel = java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private boolean posix() {
        Path existing = dir;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        return (existing == null ? dir : existing)
                .getFileSystem()
                .supportedFileAttributeViews()
                .contains("posix");
    }

    // ---- the formats, pure --------------------------------------------------------------------------------------

    /** {@code MAGIC}, a fresh nonce, then the AES-256-GCM ciphertext and tag of {@code plain}. */
    byte[] encrypt(byte[] key, byte[] plain) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(MAGIC);
            byte[] sealed = c.doFinal(plain);
            byte[] out = new byte[MAGIC.length + NONCE_BYTES + sealed.length];
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
            System.arraycopy(nonce, 0, out, MAGIC.length, NONCE_BYTES);
            System.arraycopy(sealed, 0, out, MAGIC.length + NONCE_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is part of every JDK", e);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** The plain bytes of {@code file}; a wrong key, a damaged file or another format is refused. */
    static byte[] decrypt(byte[] key, byte[] file) throws SecretStoreException {
        if (file.length < MAGIC.length + NONCE_BYTES + TAG_BITS / 8
                || !Arrays.equals(Arrays.copyOf(file, MAGIC.length), MAGIC)) {
            throw new SecretStoreException("not an Editora secrets file, or one of a newer format");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, file, MAGIC.length, NONCE_BYTES));
            c.updateAAD(MAGIC);
            int at = MAGIC.length + NONCE_BYTES;
            return c.doFinal(file, at, file.length - at);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new SecretStoreException(
                    "it does not decrypt with the key beside it (a wrong key or a damaged file)");
        } catch (GeneralSecurityException e) {
            throw new SecretStoreException("it does not decrypt: " + e.getMessage(), e);
        }
    }

    /** One {@code account=hex(UTF-8 secret)} line each: no escaping to get wrong. */
    static byte[] encode(Map<String, String> all) {
        HexFormat hex = HexFormat.of();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(all).entrySet()) {
            sb.append(e.getKey())
                    .append('=')
                    .append(hex.formatHex(e.getValue().getBytes(StandardCharsets.UTF_8)))
                    .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, String> decode(byte[] plain) throws SecretStoreException {
        Map<String, String> out = new TreeMap<>();
        HexFormat hex = HexFormat.of();
        try {
            for (String line : new String(plain, StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("a line without an account");
                }
                out.put(
                        line.substring(0, eq),
                        new String(hex.parseHex(line.substring(eq + 1)), StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException e) {
            throw new SecretStoreException("it decrypts to something that is not a list of secrets", e);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        return out;
    }

    /** The key file: a warning for whoever opens it, the format, and the key in hex (Torrenta's layout). */
    static String encodeKey(byte[] key) {
        return "# Editora: the key of secrets.enc beside it, where the AI API keys are kept when no system keychain\n"
                + "# is available. Without this file they cannot be read. Never share it.\n"
                + "format=1\n"
                + "key=" + HexFormat.of().formatHex(key) + "\n";
    }

    static byte[] decodeKey(String text) {
        String format = null;
        String key = null;
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            int eq = l.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("not a key=value line");
            }
            String name = l.substring(0, eq).strip();
            String value = l.substring(eq + 1).strip();
            if (name.equals("format")) {
                format = value;
            } else if (name.equals("key")) {
                key = value;
            }
        }
        if (!"1".equals(format)) {
            throw new IllegalArgumentException("unknown format");
        }
        if (key == null) {
            throw new IllegalArgumentException("no key");
        }
        byte[] bytes = HexFormat.of().parseHex(key);
        if (bytes.length != KEY_BYTES) {
            throw new IllegalArgumentException("not a 256-bit key");
        }
        return bytes;
    }
}
