package com.editora.vfs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Map;

import org.apache.sshd.agent.common.AbstractAgentProxy;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;

/**
 * A connection to the user's SSH agent — {@code ssh-agent}, 1Password, Secretive, gpg-agent, Windows' OpenSSH
 * agent — over its Unix-domain socket or Windows named pipe. MINA's {@link AbstractAgentProxy} speaks the agent
 * protocol; this is only the transport: a request goes out as its frame (length, then payload), and the reply
 * comes back as its payload. Everything it needs is in {@code java.base}: no native library, no MINA APR glue.
 *
 * <p>A signature may wait for a person (a security key's touch, a password manager's approval), so it is timed
 * as answering time and left out of the login's deadline.
 */
final class AgentConnection extends AbstractAgentProxy {

    /** Larger than any real reply; a corrupt length must not make us allocate gigabytes. */
    private static final int MAX_REPLY = 256 * 1024;

    /** The bytes to and from the agent. */
    interface Transport extends AutoCloseable {
        void write(byte[] bytes) throws IOException;

        void readFully(byte[] into) throws IOException;

        @Override
        void close() throws IOException;
    }

    private final Transport transport;
    private final PromptClock clock;

    AgentConnection(Transport transport, PromptClock clock) {
        super(null);
        this.transport = transport;
        this.clock = clock;
    }

    /** Connects to the agent at {@code location}; throws if nothing answers there. */
    static AgentConnection open(AgentLocation location, PromptClock clock) throws IOException {
        Transport transport = switch (location.kind()) {
            case UNIX_SOCKET -> unixSocket(location.where());
            case NAMED_PIPE -> namedPipe(location.where());
        };
        return new AgentConnection(transport, clock);
    }

    @Override
    protected synchronized Buffer request(Buffer frame) throws IOException {
        transport.write(Arrays.copyOfRange(frame.array(), frame.rpos(), frame.wpos()));
        byte[] length = new byte[4];
        transport.readFully(length);
        int n = ((length[0] & 0xff) << 24)
                | ((length[1] & 0xff) << 16)
                | ((length[2] & 0xff) << 8)
                | (length[3] & 0xff);
        if (n <= 0 || n > MAX_REPLY) {
            throw new IOException("SSH agent sent a reply of " + n + " bytes");
        }
        byte[] payload = new byte[n];
        transport.readFully(payload);
        return new ByteArrayBuffer(payload);
    }

    @Override
    public Map.Entry<String, byte[]> sign(SessionContext session, PublicKey key, String algo, byte[] data)
            throws IOException {
        IOException[] failed = new IOException[1];
        Map.Entry<String, byte[]> signed = clock == null
                ? super.sign(session, key, algo, data)
                : clock.timed(() -> {
                    try {
                        return super.sign(session, key, algo, data);
                    } catch (IOException e) {
                        failed[0] = e;
                        return null;
                    }
                });
        if (failed[0] != null) {
            throw failed[0];
        }
        return signed;
    }

    private volatile boolean open = true;

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        open = false;
        try {
            super.close();
        } finally {
            transport.close();
        }
    }

    private static Transport unixSocket(String path) throws IOException {
        SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(path));
        return new Transport() {
            @Override
            public void write(byte[] bytes) throws IOException {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }

            @Override
            public void readFully(byte[] into) throws IOException {
                ByteBuffer buffer = ByteBuffer.wrap(into);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        throw new IOException("SSH agent closed the connection");
                    }
                }
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }

    /** A Windows named pipe opens as a file: {@code CreateFile} underneath, which {@code RandomAccessFile} calls. */
    private static Transport namedPipe(String name) throws IOException {
        RandomAccessFile pipe = new RandomAccessFile(name, "rw");
        return new Transport() {
            @Override
            public void write(byte[] bytes) throws IOException {
                pipe.write(bytes);
            }

            @Override
            public void readFully(byte[] into) throws IOException {
                pipe.readFully(into);
            }

            @Override
            public void close() throws IOException {
                pipe.close();
            }
        };
    }
}
