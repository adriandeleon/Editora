package com.editora.dap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for an external debug adapter's program (Delve, CodeLLDB, lldb-dap) in tests: started as its own
 * process, it carries DAP between how such an adapter is reached and a {@link FakeDebugAdapter} listening on
 * {@code target} in the test's JVM.
 *
 * <ul>
 *   <li>{@code stdio <target>}: over its standard input and output.
 *   <li>{@code announce <target>}: on a port it picks, printed as Delve prints it
 *       ({@code DAP server listening at: 127.0.0.1:<port>}) after a line of other output.
 *   <li>{@code announce-rdbg <target>}: the same, announced on its standard error as rdbg announces it.
 *   <li>{@code listen <port> <target>}: on the port it was given.
 * </ul>
 */
public final class RelayAdapterMain {

    private RelayAdapterMain() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "stdio" -> {
                try (Socket target = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(args[1]))) {
                    relay(System.in, System.out, target);
                }
            }
            case "announce" -> {
                try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
                    out.println("Type 'dlv help' for list of commands.");
                    out.println("DAP server listening at: 127.0.0.1:" + server.getLocalPort());
                    serveOne(server, Integer.parseInt(args[1]));
                }
            }
            case "announce-rdbg" -> {
                try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
                    err.println("DEBUGGER: Debugger can attach via TCP/IP (127.0.0.1:" + server.getLocalPort() + ")");
                    err.println("DEBUGGER: wait for debugger connection...");
                    serveOne(server, Integer.parseInt(args[1]));
                }
            }
            case "listen" -> {
                try (ServerSocket server =
                        new ServerSocket(Integer.parseInt(args[1]), 1, InetAddress.getLoopbackAddress())) {
                    serveOne(server, Integer.parseInt(args[2]));
                }
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static void serveOne(ServerSocket server, int targetPort) throws IOException, InterruptedException {
        try (Socket client = server.accept();
                Socket target = new Socket(InetAddress.getLoopbackAddress(), targetPort)) {
            relay(client.getInputStream(), client.getOutputStream(), target);
        }
    }

    /** Copies {@code in} to {@code target} and {@code target} to {@code out} until either side ends. */
    private static void relay(InputStream in, OutputStream out, Socket target)
            throws IOException, InterruptedException {
        Thread up = Thread.ofPlatform().daemon().start(() -> copy(in, socketOut(target)));
        copy(target.getInputStream(), out);
        up.join(1000);
    }

    private static OutputStream socketOut(Socket s) {
        try {
            return s.getOutputStream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copy(InputStream from, OutputStream to) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = from.read(buf)) >= 0) {
                to.write(buf, 0, n);
                to.flush();
            }
        } catch (IOException e) {
            // one side ended
        }
    }
}
