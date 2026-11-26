package com.editora.vfs;

import java.io.IOException;
import java.net.ConnectException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectFailureTest {

    @Test
    void aRefusedConnectIsUnreachableWhicheverWayTheSystemSaysIt() {
        // Linux and macOS: the socket layer throws a ConnectException.
        assertEquals(
                ConnectFailure.Kind.UNREACHABLE,
                ConnectFailure.of(new IOException("connect", new ConnectException("Connection refused")))
                        .kind());
        // Windows: MINA's asynchronous transport passes the system's text in a plain IOException. It was OTHER,
        // so a host nobody answers at was reported as an unknown failure.
        for (String windows : new String[] {
            "Failed (IOException) to execute: The remote computer refused the network connection",
            "Failed (IOException) to execute: The network location cannot be reached. For information about"
                    + " network troubleshooting, see Windows Help.",
            "Failed (IOException) to execute: The remote system is not reachable by the transport."
        }) {
            ConnectFailure failure = ConnectFailure.of(new IOException(windows));
            assertEquals(ConnectFailure.Kind.UNREACHABLE, failure.kind(), windows);
            assertEquals(windows, failure.detail());
        }
        assertEquals(
                ConnectFailure.Kind.OTHER,
                ConnectFailure.of(new IOException("Broken pipe")).kind());
    }
}
