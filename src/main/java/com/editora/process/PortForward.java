package com.editora.process;

/**
 * A port on this machine that reaches a port on an {@link ExecTarget}. A debug adapter started on a remote
 * host listens there; the client connects here. Closing it stops the listening; on the local target there
 * is nothing to stop.
 */
public interface PortForward extends AutoCloseable {

    /** The loopback port to connect to on this machine. */
    int localPort();

    @Override
    void close();
}
