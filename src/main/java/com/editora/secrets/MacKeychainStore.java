package com.editora.secrets;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/**
 * The macOS login keychain through the Security framework's generic-password calls, via JNA. Each secret is a
 * generic password item with the service and account names. Items Editora creates are readable by Editora without
 * a prompt; macOS may ask once when another build of the app reads them.
 */
public final class MacKeychainStore implements SecretStore {

    /** {@code errSecItemNotFound}. */
    static final int ITEM_NOT_FOUND = -25300;

    // Native bindings: nullness is the C API's, so NullAway does not check them.
    @NullUnmarked
    interface Security extends Library {
        int SecKeychainFindGenericPassword(
                Pointer keychainOrArray,
                int serviceNameLength,
                byte[] serviceName,
                int accountNameLength,
                byte[] accountName,
                IntByReference passwordLength,
                PointerByReference passwordData,
                PointerByReference itemRef);

        int SecKeychainAddGenericPassword(
                Pointer keychain,
                int serviceNameLength,
                byte[] serviceName,
                int accountNameLength,
                byte[] accountName,
                int passwordLength,
                byte[] passwordData,
                PointerByReference itemRef);

        int SecKeychainItemModifyAttributesAndData(Pointer itemRef, Pointer attrList, int length, byte[] data);

        int SecKeychainItemDelete(Pointer itemRef);

        int SecKeychainItemFreeContent(Pointer attrList, Pointer data);
    }

    @NullUnmarked
    interface CoreFoundation extends Library {
        void CFRelease(Pointer cf);
    }

    private final byte[] service;
    /** Both frameworks a Keychain call needs, loaded together: one without the other is no store. */
    private record Frameworks(Security security, CoreFoundation cf) {}

    private final @Nullable Frameworks frameworks;

    /** A store filing secrets under {@code service}; unavailable when the Security framework cannot be loaded. */
    public MacKeychainStore(String service) {
        this.service = service.getBytes(StandardCharsets.UTF_8);
        Frameworks loaded;
        try {
            loaded = new Frameworks(
                    Native.load("Security", Security.class), Native.load("CoreFoundation", CoreFoundation.class));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            loaded = null;
        }
        this.frameworks = loaded;
    }

    @Override
    public String name() {
        return "macOS Keychain";
    }

    @Override
    public boolean available() {
        return frameworks != null;
    }

    @Override
    public Optional<String> read(String account) throws SecretStoreException {
        Security security = loaded().security();
        byte[] acct = account.getBytes(StandardCharsets.UTF_8);
        IntByReference length = new IntByReference();
        PointerByReference data = new PointerByReference();
        int status = security.SecKeychainFindGenericPassword(
                null, service.length, service, acct.length, acct, length, data, null);
        if (status == ITEM_NOT_FOUND) {
            return Optional.empty();
        }
        check(status, "read");
        try {
            return Optional.of(new String(data.getValue().getByteArray(0, length.getValue()), StandardCharsets.UTF_8));
        } finally {
            security.SecKeychainItemFreeContent(null, data.getValue());
        }
    }

    @Override
    public void write(String account, String secret) throws SecretStoreException {
        Frameworks loaded = loaded();
        Security security = loaded.security();
        CoreFoundation cf = loaded.cf();
        byte[] acct = account.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        PointerByReference item = new PointerByReference();
        int status = security.SecKeychainFindGenericPassword(
                null, service.length, service, acct.length, acct, null, null, item);
        if (status == ITEM_NOT_FOUND) {
            check(
                    security.SecKeychainAddGenericPassword(
                            null, service.length, service, acct.length, acct, bytes.length, bytes, null),
                    "add");
            return;
        }
        check(status, "find");
        try {
            check(
                    security.SecKeychainItemModifyAttributesAndData(item.getValue(), null, bytes.length, bytes),
                    "modify");
        } finally {
            cf.CFRelease(item.getValue());
        }
    }

    @Override
    public void delete(String account) throws SecretStoreException {
        Frameworks loaded = loaded();
        Security security = loaded.security();
        CoreFoundation cf = loaded.cf();
        byte[] acct = account.getBytes(StandardCharsets.UTF_8);
        PointerByReference item = new PointerByReference();
        int status = security.SecKeychainFindGenericPassword(
                null, service.length, service, acct.length, acct, null, null, item);
        if (status == ITEM_NOT_FOUND) {
            return;
        }
        check(status, "find");
        try {
            check(security.SecKeychainItemDelete(item.getValue()), "delete");
        } finally {
            cf.CFRelease(item.getValue());
        }
    }

    private Frameworks loaded() throws SecretStoreException {
        Frameworks loaded = frameworks;
        if (loaded == null) {
            throw new SecretStoreException("the Security framework could not be loaded");
        }
        return loaded;
    }

    private static void check(int status, String what) throws SecretStoreException {
        if (status != 0) {
            throw new SecretStoreException("Keychain " + what + " failed (OSStatus " + status + ")");
        }
    }
}
