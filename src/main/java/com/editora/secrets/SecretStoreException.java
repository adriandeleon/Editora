package com.editora.secrets;

/** A keychain call that failed: the store's own message (an OS status code, a D-Bus error). */
public final class SecretStoreException extends Exception {

    public SecretStoreException(String message) {
        super(message);
    }

    public SecretStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
