package com.editora.container;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ContainerInfo#parse}: what {@code docker inspect} says, as far as running commands goes. */
@DisabledOnOs(OS.WINDOWS) // POSIX mount sources
class ContainerInfoTest {

    private static final String INSPECT = """
            [{"Id":"8a00204fe0a8","Name":"/app-dev",
              "State":{"Running":true},
              "Config":{"User":"1000","Labels":{
                "devcontainer.local_folder":"/home/ana/app",
                "devcontainer.metadata":"[{\\"remoteUser\\":\\"root\\"},{\\"remoteUser\\":\\"vscode\\"}]"}},
              "Mounts":[
                {"Type":"bind","Source":"/home/ana/app","Destination":"/workspaces/app","RW":true},
                {"Type":"volume","Name":"cache","Source":"/var/lib/docker/volumes/cache/_data","Destination":"/root/.cache"}
              ]}]
            """;

    @Test
    void aDevContainersMountsLabelsAndUser() {
        ContainerInfo info = ContainerInfo.parse(INSPECT);

        assertEquals("8a00204fe0a8", info.id());
        assertEquals("app-dev", info.name());
        assertTrue(info.running());
        assertEquals("vscode", info.user(), "the last remoteUser of the metadata, not the image's user");
        assertEquals("/home/ana/app", info.labels().get(ContainerInfo.LOCAL_FOLDER_LABEL));
        assertEquals(
                List.of(new ContainerMounts.Mount(Path.of("/home/ana/app"), "/workspaces/app")),
                info.mounts().mounts(),
                "bind mounts only: a volume is no folder of this machine");
    }

    @Test
    void aPlainContainerRunsAsItsImagesUser() {
        ContainerInfo info = ContainerInfo.parse("""
                [{"Id":"c1","Name":"/plain","State":{"Running":false},"Config":{"User":"app","Labels":null},"Mounts":[]}]
                """);

        assertEquals("app", info.user());
        assertEquals(false, info.running());
        assertEquals(List.of(), info.mounts().mounts());
        assertEquals("", ContainerInfo.remoteUser("not json"));
        assertEquals("node", ContainerInfo.remoteUser("[{\"containerUser\":\"node\"}]"));
    }

    @Test
    void anythingElseIsNoContainer() {
        assertNull(ContainerInfo.parse(""));
        assertNull(ContainerInfo.parse("Error: no such container"));
        assertNull(ContainerInfo.parse("[]"));
    }

    @Test
    void theEngineIsAskedAndRunsCommandsWithTheseArguments() {
        ContainerEngine podman = ContainerEngine.of("podman");
        assertEquals(
                List.of("podman", "exec", "-i", "-u", "vscode", "c1", "/bin/sh", "-c", "true"),
                podman.execArgv("c1", "vscode", List.of("/bin/sh", "-c", "true")));
        assertEquals(
                List.of("docker", "exec", "-i", "c1", "id"),
                ContainerEngine.of("").execArgv("c1", "", List.of("id")));
        assertEquals(
                List.of("flatpak-spawn", "--host", "docker"),
                ContainerEngine.of("flatpak-spawn --host docker").command());
    }

    @Test
    void aPortIsReachedWithTheRelayTheContainerHas() {
        assertEquals(List.of("nc", "127.0.0.1", "5005"), ContainerExecTarget.relayArgv("nc", "127.0.0.1", 5005));
        List<String> bash = ContainerExecTarget.relayArgv("bash", "127.0.0.1", 5005);
        assertEquals(List.of("bash", "-c"), bash.subList(0, 2));
        assertEquals(List.of("127.0.0.1", "5005"), bash.subList(3, 5), "host and port are arguments, not text");
        assertEquals(
                "python3",
                ContainerExecTarget.relayArgv("python3", "localhost", 9229).get(0));
        assertEquals(null, ContainerExecTarget.relayArgv("", "127.0.0.1", 1), "none: the port cannot be reached");
    }
}
