package com.editora.config;

import java.nio.file.Path;
import java.util.List;

import com.editora.vfs.RemoteConnection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aPartialEntryLoadsWithTheSafeDefaults() throws Exception {
        Deployment d = MAPPER.readValue(
                "{\"name\":\"dev\",\"host\":\" box \",\"remoteRoot\":\"/srv/app/\"}", Deployment.class);
        assertEquals("box", d.host());
        assertEquals(22, d.port());
        assertEquals(RemoteConnection.AuthMethod.DEFAULT_KEYS, d.auth());
        assertTrue(d.useGitignore(), "absent means on");
        assertTrue(d.deleteRemoved(), "absent means on");
        assertFalse(d.uploadOnSave());
        assertFalse(d.complete(), "no user");
    }

    @Test
    void itRoundTripsAndNamesItsHostLikeASavedSite() throws Exception {
        Deployment d = new Deployment(
                "dev",
                "box",
                2222,
                "me",
                RemoteConnection.AuthMethod.KEY,
                "/k",
                "/srv/app",
                "*.log",
                false,
                false,
                true);
        Deployment back = MAPPER.readValue(MAPPER.writeValueAsString(d), Deployment.class);
        assertEquals(d, back);
        assertEquals("me@box:2222", d.connectionId(), "the id consent and host tools are kept by");
        assertEquals("me@box:2222:/srv/app", d.displayTarget());
        assertEquals("/srv/app", d.connection().lastPath());
        assertTrue(d.complete());
    }

    @Test
    void aConfigurationsRunOnRoundTripsAndOlderShapesRunHere() throws Exception {
        RunConfiguration c =
                new RunConfiguration("a", "shell", "x.sh", "", "", "", "", "", "", "", "").withDeployment("dev");
        assertTrue(c.isDeployed());
        RunConfiguration back = MAPPER.readValue(MAPPER.writeValueAsString(c), RunConfiguration.class);
        assertEquals("dev", back.deployment());
        RunConfiguration old = MAPPER.readValue("{\"name\":\"a\",\"type\":\"shell\"}", RunConfiguration.class);
        assertFalse(old.isDeployed());
        assertEquals("dev", c.withName("b").deployment(), "a rename keeps every other field");
    }

    @Test
    void workspaceStateFindsADeploymentByName() {
        WorkspaceState s = new WorkspaceState();
        s.setDeployments(List.of(Deployment.named("dev"), Deployment.named("prod")));
        assertEquals("prod", s.deployment(" prod ").name());
        assertNull(s.deployment(""));
        assertNull(s.deployment("staging"));
    }

    @Test
    void theSharedFileCarriesDeploymentsSoAnImportedConfigurationFindsItsOwn(@TempDir Path root) throws Exception {
        RunConfiguration c =
                new RunConfiguration("a", "shell", "x.sh", "", "", "", "", "", "", "", "").withDeployment("dev");
        Deployment d = Deployment.named("dev");
        SharedRunConfigs.save(MAPPER, root, List.of(c), List.of(d));
        assertEquals(List.of(c), SharedRunConfigs.load(MAPPER, root));
        assertEquals(List.of(d), SharedRunConfigs.loadDeployments(MAPPER, root));

        SharedRunConfigs.save(MAPPER, root, List.of(c));
        assertFalse(
                java.nio.file.Files.readString(SharedRunConfigs.fileFor(root)).contains("deployments"),
                "a project without deployments writes what it always did");
        assertEquals(
                List.of("dev", "prod"),
                SharedRunConfigs.mergeDeployments(List.of(d), List.of(Deployment.named("prod"), d)).stream()
                        .map(Deployment::name)
                        .toList());
    }
}
