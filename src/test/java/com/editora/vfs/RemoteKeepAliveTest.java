package com.editora.vfs;

import java.time.Duration;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.core.CoreModuleProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Keep-alives: asked every interval, and a connection whose server stops answering is given up on. Without the
 * limit a silently dead connection was never noticed (checked against OpenSSH by {@code RealSshdProbeFxTest}).
 */
class RemoteKeepAliveTest {

    @Test
    void anUnansweredServerIsGivenUpOnAfterAFewKeepAlives() {
        SshClient client = SshClient.setUpDefaultClient();

        RemoteFileSystems.keepAlive(client, Duration.ofSeconds(30));

        assertEquals(Duration.ofSeconds(30), CoreModuleProperties.HEARTBEAT_INTERVAL.getRequired(client));
        assertEquals("keepalive@openssh.com", CoreModuleProperties.HEARTBEAT_REQUEST.getRequired(client));
        assertEquals(
                RemoteFileSystems.HEARTBEAT_MISSES, CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.getRequired(client));
    }
}
