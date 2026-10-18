package com.editora.secrets;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

/**
 * The Windows Credential Manager through {@code CredReadW}/{@code CredWriteW}/{@code CredDeleteW}, via JNA. Each
 * secret is a generic credential named {@code <service>/<account>}, kept for the local machine (it is not roamed).
 */
public final class WindowsCredentialStore implements SecretStore {

    static final int CRED_TYPE_GENERIC = 1;
    static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    static final int ERROR_NOT_FOUND = 1168;

    /** {@code FILETIME}. */
    @Structure.FieldOrder({"dwLowDateTime", "dwHighDateTime"})
    public static class FileTime extends Structure {
        public int dwLowDateTime;
        public int dwHighDateTime;
    }

    /** {@code CREDENTIALW}. */
    @Structure.FieldOrder({
        "Flags",
        "Type",
        "TargetName",
        "Comment",
        "LastWritten",
        "CredentialBlobSize",
        "CredentialBlob",
        "Persist",
        "AttributeCount",
        "Attributes",
        "TargetAlias",
        "UserName"
    })
    public static class Credential extends Structure {
        public int Flags;
        public int Type;
        public WString TargetName;
        public WString Comment;
        public FileTime LastWritten;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias;
        public WString UserName;

        public Credential() {}

        public Credential(Pointer p) {
            super(p);
            read();
        }
    }

    interface Advapi32 extends StdCallLibrary {
        boolean CredReadW(WString target, int type, int flags, PointerByReference credential) throws LastErrorException;

        boolean CredWriteW(Credential credential, int flags) throws LastErrorException;

        boolean CredDeleteW(WString target, int type, int flags) throws LastErrorException;

        void CredFree(Pointer buffer);
    }

    private final String service;
    private final Advapi32 advapi;

    /** A store filing secrets under {@code service}; unavailable when advapi32 cannot be loaded. */
    public WindowsCredentialStore(String service) {
        this.service = service;
        Advapi32 a = null;
        try {
            a = Native.load("Advapi32", Advapi32.class);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            a = null;
        }
        this.advapi = a;
    }

    @Override
    public String name() {
        return "Windows Credential Manager";
    }

    @Override
    public boolean available() {
        return advapi != null;
    }

    private WString target(String account) {
        return new WString(service + "/" + account);
    }

    @Override
    public Optional<String> read(String account) throws SecretStoreException {
        requireLoaded();
        PointerByReference out = new PointerByReference();
        try {
            advapi.CredReadW(target(account), CRED_TYPE_GENERIC, 0, out);
        } catch (LastErrorException e) {
            if (e.getErrorCode() == ERROR_NOT_FOUND) {
                return Optional.empty();
            }
            throw new SecretStoreException("CredRead failed (error " + e.getErrorCode() + ")", e);
        }
        try {
            Credential c = new Credential(out.getValue());
            byte[] blob =
                    c.CredentialBlobSize == 0 ? new byte[0] : c.CredentialBlob.getByteArray(0, c.CredentialBlobSize);
            return Optional.of(new String(blob, StandardCharsets.UTF_8));
        } finally {
            advapi.CredFree(out.getValue());
        }
    }

    @Override
    public void write(String account, String secret) throws SecretStoreException {
        requireLoaded();
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        Memory blob = new Memory(Math.max(1, bytes.length));
        blob.write(0, bytes, 0, bytes.length);
        Credential c = new Credential();
        c.Type = CRED_TYPE_GENERIC;
        c.TargetName = target(account);
        c.CredentialBlobSize = bytes.length;
        c.CredentialBlob = blob;
        c.Persist = CRED_PERSIST_LOCAL_MACHINE;
        c.UserName = new WString(account);
        try {
            advapi.CredWriteW(c, 0);
        } catch (LastErrorException e) {
            throw new SecretStoreException("CredWrite failed (error " + e.getErrorCode() + ")", e);
        } finally {
            blob.clear();
        }
    }

    @Override
    public void delete(String account) throws SecretStoreException {
        requireLoaded();
        try {
            advapi.CredDeleteW(target(account), CRED_TYPE_GENERIC, 0);
        } catch (LastErrorException e) {
            if (e.getErrorCode() != ERROR_NOT_FOUND) {
                throw new SecretStoreException("CredDelete failed (error " + e.getErrorCode() + ")", e);
            }
        }
    }

    private void requireLoaded() throws SecretStoreException {
        if (advapi == null) {
            throw new SecretStoreException("advapi32 could not be loaded");
        }
    }
}
