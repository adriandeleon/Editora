package com.editora.dap.jvm;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.jsonrpc.Launcher;

/**
 * Serves one {@link JvmDebugAdapter} session on a loopback port, inside Editora's own process, so the Debug
 * panel's {@code DapClient} connects to it exactly as it connects to java-debug or js-debug: a socket and the
 * Debug Adapter Protocol. Nothing is installed and no second JVM runs the adapter; the program being debugged
 * is the only process started.
 *
 * <p>The port accepts a single connection and then closes; the session ends when that connection does.
 */
public final class JvmDebugServer {

    private static final Logger LOG = Logger.getLogger(JvmDebugServer.class.getName());

    private JvmDebugServer() {}

    /**
     * Opens a loopback port for {@code adapter} and returns it; a daemon thread accepts the client and runs the
     * session until the connection closes, then disposes the adapter.
     */
    public static int start(JvmDebugAdapter adapter) throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("jvm-debug-adapter").start(() -> serve(server, adapter));
        return server.getLocalPort();
    }

    private static void serve(ServerSocket server, JvmDebugAdapter adapter) {
        try (server) {
            server.setSoTimeout(30_000); // the client connects at once; a session nobody opens must not linger
            try (Socket socket = server.accept()) {
                server.close();
                Launcher<IDebugProtocolClient> launcher =
                        DSPLauncher.createServerLauncher(adapter, socket.getInputStream(), socket.getOutputStream());
                adapter.connect(launcher.getRemoteProxy());
                launcher.startListening().get();
            }
        } catch (java.util.concurrent.ExecutionException | IOException e) {
            LOG.log(Level.FINE, "JVM debug session ended", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            adapter.dispose();
        }
    }
}
