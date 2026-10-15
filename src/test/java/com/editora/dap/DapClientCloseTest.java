package com.editora.dap;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ending a socket transport never holds the caller, and frees the thread blocked reading it — what the reader
 * of every DAP session is doing when Stop is pressed. A plain close never returned on the macOS CI runner.
 */
class DapClientCloseTest {

    @Test
    void closingFreesTheBlockedReaderAndReturnsPromptly() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
            try (Socket peer = server.accept()) {
                CompletableFuture<Integer> read = CompletableFuture.supplyAsync(() -> {
                    try (InputStream in = client.getInputStream()) {
                        return in.read(); // blocks: the peer sends nothing
                    } catch (java.io.IOException e) {
                        return -2;
                    }
                });
                Thread.sleep(200); // the reader is in its read
                long t0 = System.nanoTime();

                DapClient.closeSocket(client);

                long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                assertTrue(took < DapClient.CLOSE_WAIT_MILLIS + 500, "returned in " + took + " ms");
                assertTrue(client.isClosed() || client.isOutputShutdown());
                assertTrue(peer.getInputStream().read() == -1, "the adapter was told: end of stream");
                int got = read.get(5, TimeUnit.SECONDS);
                assertTrue(got < 0, "the reader was freed: " + got);
            }
        }
    }

    @Test
    void nothingToCloseIsNothing() {
        DapClient.closeSocket(null);
    }
}
