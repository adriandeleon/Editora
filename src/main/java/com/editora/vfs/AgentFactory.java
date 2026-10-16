package com.editora.vfs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.agent.SshAgentFactory;
import org.apache.sshd.agent.SshAgentServer;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKey;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.Session;

/**
 * The shared client's agent: for an Automatic login ({@code DEFAULT_KEYS}), a connection to the user's SSH agent,
 * whose keys MINA offers before any key file — {@code ssh}'s order. A key-file or password login uses none.
 * An agent that is not there is passed over quietly, as {@code ssh} does. Agent forwarding is not offered.
 */
final class AgentFactory implements SshAgentFactory {

    private static final Logger LOG = Logger.getLogger(AgentFactory.class.getName());

    static final AgentFactory INSTANCE = new AgentFactory();

    private AgentFactory() {}

    @Override
    public List<ChannelFactory> getChannelForwardingFactories(FactoryManager manager) {
        return List.of(); // no agent forwarding: a server never reaches the user's agent through us
    }

    @Override
    public SshAgent createClient(Session session, FactoryManager manager) throws IOException {
        RemoteFileSystems engine = SshClients.engineOf(session);
        if (engine == null || RemoteFileSystems.authOf(session) != RemoteConnection.AuthMethod.DEFAULT_KEYS) {
            return null; // MINA then offers no agent keys
        }
        SshEnvironment ssh = engine.sshEnvironment();
        AgentLocation location = AgentLocation.find(
                session.getAttribute(UserAuthPublicKey.IDENTITY_AGENT),
                ssh.env(),
                ssh.windows(),
                home(ssh),
                System.getProperty("user.name", ""));
        if (location == null) {
            return null;
        }
        try {
            return AgentConnection.open(location, engine.promptClock());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "No SSH agent at " + location.where() + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public SshAgentServer createServer(ConnectionService service) {
        throw new UnsupportedOperationException("Editora does not forward the SSH agent");
    }

    private static Path home(SshEnvironment ssh) {
        Path parent = ssh.sshDir().toAbsolutePath().getParent();
        return parent == null ? ssh.sshDir() : parent;
    }
}
