package com.editora.secrets;

import java.util.List;
import java.util.Optional;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/**
 * The freedesktop Secret Service (GNOME Keyring, KWallet) through libsecret, called through JNA. Each secret is an
 * item of the default collection with the attributes {@code service} and {@code account}. On a machine without
 * libsecret, or with no Secret Service on the session bus (a headless box, an SSH session), {@link #available}
 * is false.
 */
public final class LinuxSecretServiceStore implements SecretStore {

    /** {@code SecretSchemaAttribute}: an attribute name and its type (0 = string). */
    // Native bindings: nullness is the C API's, so NullAway does not check them.
    @NullUnmarked
    @Structure.FieldOrder({"name", "type"})
    public static class SchemaAttribute extends Structure {
        public String name;
        public int type;
    }

    /** {@code SecretSchema}: a name, flags, 32 attribute slots (a null name ends the list), reserved words. */
    @NullUnmarked
    @Structure.FieldOrder({
        "name",
        "flags",
        "attributes",
        "reserved",
        "reserved1",
        "reserved2",
        "reserved3",
        "reserved4",
        "reserved5",
        "reserved6",
        "reserved7"
    })
    public static class Schema extends Structure {
        public String name;
        public int flags;
        public SchemaAttribute[] attributes = (SchemaAttribute[]) new SchemaAttribute().toArray(32);
        public int reserved;
        public Pointer reserved1;
        public Pointer reserved2;
        public Pointer reserved3;
        public Pointer reserved4;
        public Pointer reserved5;
        public Pointer reserved6;
        public Pointer reserved7;
    }

    /** The libsecret calls used: the synchronous, varargs "password" API. */
    @NullUnmarked
    interface LibSecret extends Library {
        boolean secret_password_store_sync(
                Schema schema,
                String collection,
                String label,
                String password,
                Pointer cancellable,
                PointerByReference error,
                Object... attributes);

        Pointer secret_password_lookup_sync(
                Schema schema, Pointer cancellable, PointerByReference error, Object... attributes);

        boolean secret_password_clear_sync(
                Schema schema, Pointer cancellable, PointerByReference error, Object... attributes);

        void secret_password_free(Pointer password);
    }

    /** {@code g_error_free}, to release a {@code GError} libsecret hands back. */
    @NullUnmarked
    interface GLib extends Library {
        void g_error_free(Pointer error);
    }

    private final String service;
    private final @Nullable LibSecret lib;
    private final @Nullable GLib glib;
    private final Schema schema;
    private @Nullable Boolean reachable;

    /** A store filing secrets under {@code service}; unavailable when libsecret cannot be loaded. */
    public LinuxSecretServiceStore(String service) {
        this.service = service;
        LibSecret secret = null;
        GLib g = null;
        for (String name : List.of("secret-1", "libsecret-1.so.0")) {
            try {
                secret = Native.load(name, LibSecret.class);
                g = Native.load("glib-2.0", GLib.class);
                break;
            } catch (UnsatisfiedLinkError | RuntimeException e) {
                secret = null;
            }
        }
        this.lib = secret;
        this.glib = g;
        Schema s = new Schema();
        s.name = "dev.editora.Secret";
        s.flags = 0; // SECRET_SCHEMA_NONE
        s.attributes[0].name = "service";
        s.attributes[0].type = 0; // SECRET_SCHEMA_ATTRIBUTE_STRING
        s.attributes[1].name = "account";
        s.attributes[1].type = 0;
        s.write();
        this.schema = s;
    }

    @Override
    public String name() {
        return "Secret Service";
    }

    /**
     * Loaded, and the Secret Service answers on the session bus: a lookup of an account nobody files returns
     * nothing without an error. Asked once; the answer is kept.
     */
    @Override
    public synchronized boolean available() {
        if (lib == null) {
            return false;
        }
        if (reachable == null) {
            try {
                read("editora-availability-check");
                reachable = true;
            } catch (SecretStoreException | RuntimeException | Error e) {
                reachable = false;
            }
        }
        return reachable;
    }

    @Override
    public Optional<String> read(String account) throws SecretStoreException {
        LibSecret lib = loaded();
        PointerByReference error = new PointerByReference();
        Pointer found =
                lib.secret_password_lookup_sync(schema, null, error, "service", service, "account", account, null);
        check(error);
        if (found == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(found.getString(0, "UTF-8"));
        } finally {
            lib.secret_password_free(found);
        }
    }

    @Override
    public void write(String account, String secret) throws SecretStoreException {
        LibSecret lib = loaded();
        PointerByReference error = new PointerByReference();
        boolean ok = lib.secret_password_store_sync(
                schema,
                null, // the default collection
                "Editora: " + account,
                secret,
                null,
                error,
                "service",
                service,
                "account",
                account,
                null);
        check(error);
        if (!ok) {
            throw new SecretStoreException("Secret Service did not store the secret");
        }
    }

    @Override
    public void delete(String account) throws SecretStoreException {
        LibSecret lib = loaded();
        PointerByReference error = new PointerByReference();
        lib.secret_password_clear_sync(schema, null, error, "service", service, "account", account, null);
        check(error);
    }

    private LibSecret loaded() throws SecretStoreException {
        LibSecret loaded = lib;
        if (loaded == null) {
            throw new SecretStoreException("libsecret is not installed");
        }
        return loaded;
    }

    /** Turns a {@code GError} into an exception (its message), freeing it. */
    private void check(PointerByReference error) throws SecretStoreException {
        Pointer e = error.getValue();
        if (e == null) {
            return;
        }
        // struct GError { GQuark domain; gint code; gchar *message; }
        Pointer message = e.getPointer(8);
        String text = message == null ? "Secret Service error" : message.getString(0, "UTF-8");
        if (glib != null) {
            glib.g_error_free(e);
        }
        throw new SecretStoreException(text);
    }
}
