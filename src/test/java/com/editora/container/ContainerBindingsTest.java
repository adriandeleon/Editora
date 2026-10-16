package com.editora.container;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link ContainerBindings}: which container a folder's tools run in, from what the engine says. */
@DisabledOnOs(OS.WINDOWS) // POSIX paths
class ContainerBindingsTest {

    private final ContainerBindings bindings = new ContainerBindings();

    private static ContainerInfo info(String name, String source, String destination) {
        return new ContainerInfo(
                name + "-id",
                name,
                true,
                new ContainerMounts(List.of(new ContainerMounts.Mount(Path.of(source), destination))),
                "",
                Map.of());
    }

    /** An engine with these containers, and these dev containers by folder. */
    private static ContainerBindings.Lookup engine(Map<String, ContainerInfo> containers, Map<Path, String> dev) {
        return new ContainerBindings.Lookup() {
            @Override
            public ContainerInfo inspect(String container) {
                return containers.get(container);
            }

            @Override
            public String devContainerFor(Path folder) {
                return dev.get(folder);
            }

            @Override
            public ContainerEngine engine() {
                return ContainerEngine.of("docker");
            }
        };
    }

    @AfterEach
    void forget() {
        bindings.unbind(Path.of("/home/ana/app"));
    }

    @Test
    void aFolderIsBoundToItsDevContainerAndItsFilesRunThere() {
        var lookup = engine(
                Map.of("app-dev", info("app-dev", "/home/ana/app", "/workspaces/app")),
                Map.of(Path.of("/home/ana/app"), "app-dev"));

        ContainerExecTarget target = bindings.bind(Path.of("/home/ana/app"), null, lookup);

        assertEquals("container:app-dev", target.id());
        assertSame(target, bindings.forPath(Path.of("/home/ana/app/src/A.java")));
        assertSame(target, bindings.boundTo(Path.of("/home/ana/app")));
        assertNull(bindings.forPath(Path.of("/home/ana/other/B.java")), "not in the folder");
    }

    @Test
    void aContainerTheUserNamedWinsOverTheDevContainer() {
        var lookup = engine(
                Map.of(
                        "app-dev", info("app-dev", "/home/ana/app", "/workspaces/app"),
                        "mine", info("mine", "/home/ana", "/home/ana")),
                Map.of(Path.of("/home/ana/app"), "app-dev"));

        assertEquals(
                "container:mine",
                bindings.bind(Path.of("/home/ana/app"), "mine", lookup).id());
    }

    @Test
    void aContainerThatDoesNotMountTheFolderBindsNothingAndForgetsTheOldBinding() {
        var good = engine(
                Map.of("app-dev", info("app-dev", "/home/ana/app", "/w")), Map.of(Path.of("/home/ana/app"), "app-dev"));
        bindings.bind(Path.of("/home/ana/app"), null, good);
        var elsewhere = engine(Map.of("other", info("other", "/srv/other", "/w")), Map.of());

        assertNull(bindings.bind(Path.of("/home/ana/app"), "other", elsewhere));
        assertNull(bindings.forPath(Path.of("/home/ana/app/a.txt")), "the old binding is gone");
        assertNull(bindings.bind(Path.of("/home/ana/app"), null, engine(Map.of(), Map.of())), "no dev container");
    }
}
