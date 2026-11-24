package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogStreamSourceTest {

    private static final List<String> DOCKER = List.of("docker");

    @Test
    void eachSourceBuildsItsCommand() {
        assertEquals(
                List.of("docker", "compose", "logs", "-f", "my api"),
                LogStreamSource.COMMAND.argv("docker compose logs -f 'my api'", DOCKER));
        assertEquals(
                List.of("podman", "--remote", "logs", "-f", "--tail", "1000", "web"),
                LogStreamSource.CONTAINER.argv("web", List.of("podman", "--remote")));
        assertEquals(
                List.of("kubectl", "logs", "-f", "--tail=1000", "deploy/api", "-n", "shop"),
                LogStreamSource.KUBERNETES.argv("deploy/api -n shop", DOCKER));
        assertEquals(
                List.of("journalctl", "-f", "-n", "1000", "-o", "short-iso", "-u", "nginx"),
                LogStreamSource.JOURNAL.argv("nginx", DOCKER));
        assertEquals(
                List.of("ssh", "-o", "BatchMode=yes", "ada@web1", "tail -n 1000 -F '/var/log/app'\\''s.log'"),
                LogStreamSource.SSH_FILE.argv("ada@web1:/var/log/app's.log", DOCKER),
                "the path is one word for the remote shell, whatever it holds");
    }

    @Test
    void whatCannotBeStreamedIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> LogStreamSource.COMMAND.argv("  ", DOCKER));
        assertThrows(IllegalArgumentException.class, () -> LogStreamSource.COMMAND.argv("tail 'open", DOCKER));
        assertThrows(IllegalArgumentException.class, () -> LogStreamSource.SSH_FILE.argv("web1", DOCKER));
        assertThrows(IllegalArgumentException.class, () -> LogStreamSource.SSH_FILE.argv("web1:", DOCKER));
        assertThrows(IllegalArgumentException.class, () -> LogStreamSource.SSH_FILE.argv(":/var/log/x", DOCKER));
    }

    @Test
    void titlesNameWhatIsShown() {
        assertEquals("web (container)", LogStreamSource.CONTAINER.title(" web "));
        assertEquals("ada@web1:/var/log/app.log", LogStreamSource.SSH_FILE.title("ada@web1:/var/log/app.log"));
    }
}
