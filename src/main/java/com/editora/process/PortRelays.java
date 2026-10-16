package com.editora.process;

import java.util.List;

/**
 * The commands that join a process's stdin and stdout to a TCP port, run where the port is ({@code nc}, else
 * {@code bash}'s {@code /dev/tcp}, else {@code python3}): how {@link RelayPortForward} reaches a port in a
 * container or a WSL distribution through its launcher. Pure.
 */
public final class PortRelays {

    /** The script that names the first relay tool present ({@code nc}, {@code bash}, {@code python3}), or nothing. */
    public static final String DETECT =
            "for t in nc bash python3; do command -v $t >/dev/null 2>&1 && { echo $t; exit; }; done";

    private PortRelays() {}

    /** The relay that joins its stdin and stdout to {@code host:port} with {@code tool}; null for none. */
    public static List<String> argv(String tool, String host, int port) {
        String p = String.valueOf(port);
        return switch (tool == null ? "" : tool.strip()) {
            case "nc" -> List.of("nc", host, p);
            // The writer becomes the shell; the reader keeps stdout, so the port closing ends this side too. dd, not
            // cat: dd writes each read as it comes, while uutils' cat (Ubuntu 26.04's) holds what it reads from a
            // socket until the socket closes, so an adapter's replies, on a connection that stays open, never came.
            case "bash" ->
                List.of(
                        "bash",
                        "-c",
                        "exec 3<>\"/dev/tcp/$0/$1\" || exit 1; dd bs=65536 <&3 2>/dev/null & exec dd bs=65536 >&3 2>/dev/null",
                        host,
                        p);
            case "python3" ->
                List.of(
                        "python3",
                        "-c",
                        "import socket,sys,threading\n"
                                + "s=socket.create_connection((sys.argv[1],int(sys.argv[2])))\n"
                                + "def up():\n"
                                + "  while True:\n"
                                + "    b=sys.stdin.buffer.read1(65536)\n"
                                + "    if not b: s.shutdown(socket.SHUT_WR); return\n"
                                + "    s.sendall(b)\n"
                                + "threading.Thread(target=up,daemon=True).start()\n"
                                + "while True:\n"
                                + "  b=s.recv(65536)\n"
                                + "  if not b: break\n"
                                + "  sys.stdout.buffer.write(b); sys.stdout.buffer.flush()\n",
                        host,
                        p);
            default -> null;
        };
    }
}
