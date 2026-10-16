package com.editora.vfs;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;

/**
 * Why a connect failed, in the few kinds a person acts on differently: the host could not be reached, it did
 * not answer in time, its host key was refused (or has changed), the login was not accepted, or the person
 * cancelled. {@code detail} is the underlying message, for the dialog's details.
 */
public record ConnectFailure(Kind kind, String detail) {

    public enum Kind {
        /** No such host, refused, or no route: nothing at that address speaks SSH to us. */
        UNREACHABLE,
        /** The host or the login took too long. */
        TIMED_OUT,
        /** The person declined an unknown host's key, or there was nobody to ask. */
        HOST_KEY_DECLINED,
        /** The host presented a different key from the one {@code known_hosts} has for it. */
        HOST_KEY_CHANGED,
        /** The server did not accept any way of logging in that was tried. */
        AUTH_FAILED,
        /** The person cancelled a password or login question. */
        CANCELLED,
        /** Anything else. */
        OTHER
    }

    /** A failure already known for what it is, raised by the engine with its kind. */
    static final class Signal extends IOException {
        final Kind kind;

        Signal(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    /** What {@code failure} means to the person. */
    static ConnectFailure of(Throwable failure) {
        String detail = detail(failure);
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof Signal s) {
                return new ConnectFailure(s.kind, detail(s.getCause() == null ? s : s.getCause()));
            }
            if (t instanceof UnknownHostException
                    || t instanceof UnresolvedAddressException
                    || t instanceof ConnectException
                    || t instanceof NoRouteToHostException) {
                return new ConnectFailure(Kind.UNREACHABLE, detail);
            }
            if (t instanceof SocketTimeoutException) {
                return new ConnectFailure(Kind.TIMED_OUT, detail);
            }
        }
        String text = detail.toLowerCase(Locale.ROOT);
        if (text.contains("within specified timeout") || text.contains("timed out") || text.contains("timeout")) {
            return new ConnectFailure(Kind.TIMED_OUT, detail);
        }
        if (text.contains("no more authentication methods")) {
            return new ConnectFailure(Kind.AUTH_FAILED, detail);
        }
        if (text.contains("server key did not validate")) {
            return new ConnectFailure(Kind.HOST_KEY_DECLINED, detail);
        }
        return new ConnectFailure(Kind.OTHER, detail);
    }

    private static String detail(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
