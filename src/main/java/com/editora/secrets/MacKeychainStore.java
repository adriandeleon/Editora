package com.editora.secrets;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * The macOS login keychain through the Security framework's generic-password calls, via JNA. Each secret is a
 * generic password item with the service and account names. Items Editora creates are readable by Editora without
 * a prompt; macOS may ask once when another build of the app reads them.
 */
public final class MacKeychainStore implements SecretStore {

    /** {@code errSecItemNotFound}. */
    static final int ITEM_NOT_FOUND = -25300;

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

    interface CoreFoundation extends Library {
        void CFRelease(Pointer cf);
    }

    private final byte[] service;
    private final Security security;
    private final CoreFoundation cf;

    /** A store filing secrets under {@code service}; unavailable when the Security framework cannot be loaded. */
    public MacKeychainStore(String service) {
        this.service = service.getBytes(StandardCharsets.UTF_8);
        Security s = null;
        CoreFoundation c = null;
        try {
            s = Native.load("Security", Security.class);
            c = Native.load("CoreFoundation", CoreFoundation.class);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            s = null;
        }
        this.security = s;
        this.cf = c;
    }

    @Override
    public String name() {
        return "macOS Keychain";
    }

    @Override
    public boolean available() {
        return security != null;
    }

    @Override
    public Optional<String> read(String account) throws SecretStoreException {
        requireLoaded();
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
        requireLoaded();
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
        requireLoaded();
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

    private void requireLoaded() throws SecretStoreException {
        if (security == null) {
            throw new SecretStoreException("the Security framework could not be loaded");
        }
    }

    private static void check(int status, String what) throws SecretStoreException {
        if (status != 0) {
            throw new SecretStoreException("Keychain " + what + " failed (OSStatus " + status + ")");
        }
    }
}
