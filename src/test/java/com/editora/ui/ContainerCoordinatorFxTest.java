package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.config.Settings;
import com.editora.container.ContainerBindings;
import com.editora.container.ContainerEngine;
import com.editora.container.ContainerInfo;
import com.editora.container.ContainerMounts;
import com.editora.process.ExecTargets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window's project and the container its tools run in: its dev container, once the user agreed; a container
 * the user picked, at once; and back to this machine. The engine is a stand-in.
 */
@Tag("fx")
class ContainerCoordinatorFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private Path root;
    private final Settings settings = new Settings();
    private final Map<String, Boolean> allowed = new HashMap<>();
    private final AtomicInteger changes = new AtomicInteger();
    private final AtomicInteger asked = new AtomicInteger();
    private final List<String> status = new CopyOnWriteArrayList<>();
    private boolean answer;
    private ContainerCoordinator coordinator;

    private ContainerInfo info(String name) {
        return new ContainerInfo(
                name + "-id",
                name,
                true,
                new ContainerMounts(List.of(new ContainerMounts.Mount(root, "/workspaces/app"))),
                "",
                Map.of());
    }

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(dir.toRealPath().resolve("app"));
        Files.createDirectories(root.resolve(".devcontainer")); // a folder with a dev container configuration
        settings.setRemoteExecution(true);
        ExecTargets.setRemoteEnabled(() -> true);
        ExecTargets.setRemoteHostAllowed(id -> allowed.getOrDefault(id, false));
        ExecTargets.registerMapped(ContainerBindings.shared());
        CoordinatorHostStub host = new CoordinatorHostStub() {
            @Override
            public Settings settings() {
                return settings;
            }

            @Override
            public void setStatus(String message) {
                status.add(message);
            }
        };
        coordinator = new ContainerCoordinator(host, new ContainerCoordinator.Ops() {
            @Override
            public Path projectRoot() {
                return root;
            }

            @Override
            public boolean execAllowed(String targetId) {
                return allowed.getOrDefault(targetId, false);
            }

            @Override
            public void setExecAllowed(String targetId, boolean value) {
                allowed.put(targetId, value);
            }

            @Override
            public void execChanged() {
                changes.incrementAndGet();
            }
        });
        Map<String, ContainerInfo> containers = Map.of("app-dev", info("app-dev"), "mine", info("mine"));
        coordinator.lookups = engine -> new ContainerBindings.Lookup() {
            @Override
            public ContainerInfo inspect(String container) {
                return containers.get(container);
            }

            @Override
            public String devContainerFor(Path folder) {
                return folder.equals(root) ? "app-dev" : null;
            }

            @Override
            public ContainerEngine engine() {
                return engine;
            }
        };
        coordinator.consent = target -> {
            asked.incrementAndGet();
            return answer;
        };
    }

    @AfterEach
    void tearDown() {
        ContainerBindings.shared().unbind(root);
        ExecTargets.unregisterMapped(ContainerBindings.shared());
        ExecTargets.setRemoteHostAllowed(null);
        ExecTargets.setRemoteEnabled(null);
    }

    private void settle(Runnable action) throws Exception {
        FxTestSupport.runOnFx(action);
        for (int i = 0; i < 100; i++) {
            Thread.sleep(20);
            FxTestSupport.runOnFx(() -> {}); // the binding's answer is posted back to the FX thread
        }
    }

    @Test
    void aProjectsDevContainerIsUsedOnceTheUserAgrees() throws Exception {
        answer = true;

        settle(() -> coordinator.projectChanged(true));

        assertEquals(1, asked.get());
        assertEquals("container:app-dev", FxTestSupport.callOnFx(coordinator::boundId));
        assertTrue(allowed.get("container:app-dev"), "remembered");
        assertEquals(1, changes.get(), "the features that run tools looked again");
        assertSame(ContainerBindings.shared().boundTo(root), ExecTargets.forPath(root.resolve("a.py")));
        assertFalse(ExecTargets.runsHere(root.resolve("a.py")));

        settle(() -> coordinator.projectChanged(true));
        assertEquals(1, asked.get(), "asked once");
        assertEquals(1, changes.get(), "nothing moved");
    }

    @Test
    void notNowLeavesTheToolsHereAndIsNotAskedAgainThisSession() throws Exception {
        answer = false;

        settle(() -> coordinator.projectChanged(true));
        settle(() -> coordinator.projectChanged(true));

        assertEquals(1, asked.get());
        assertNull(FxTestSupport.callOnFx(coordinator::boundId));
        assertEquals(0, changes.get());
        assertTrue(ExecTargets.runsHere(root.resolve("a.py")), "not allowed: here, as before");
    }

    @Test
    void aContainerTheUserPickedIsUsedAtOnceAndRunToolsHereUndoesIt() throws Exception {
        settle(() -> coordinator.attachTo(root, "mine"));

        assertEquals(0, asked.get(), "picking it is the agreement");
        assertEquals("container:mine", FxTestSupport.callOnFx(coordinator::boundId));
        assertEquals("mine", settings.getContainerBindings().get(root.toString()), "remembered for the folder");

        settle(coordinator::detach);

        assertNull(FxTestSupport.callOnFx(coordinator::boundId));
        assertEquals(Map.of(), settings.getContainerBindings());
        assertFalse(allowed.get("container:mine"), "the agreement is withdrawn");
        assertTrue(ExecTargets.runsHere(root.resolve("a.py")));
        assertEquals(2, changes.get(), "moved there, and back");
    }

    @Test
    void withRemoteExecutionOffNothingIsAskedOrBound() throws Exception {
        settings.setRemoteExecution(false);

        settle(() -> coordinator.projectChanged(true));

        assertEquals(0, asked.get());
        assertNull(FxTestSupport.callOnFx(coordinator::boundId));
    }

    /** A folder without a dev container configuration, and no container picked for it, does not ask the engine. */
    @Test
    void aFolderWithoutADevContainerConfigurationDoesNotAskTheEngine() throws Exception {
        Files.delete(root.resolve(".devcontainer"));
        java.util.List<String> devAsked = new CopyOnWriteArrayList<>();
        var before = coordinator.lookups;
        coordinator.lookups = engine -> {
            var lookup = before.apply(engine);
            return new ContainerBindings.Lookup() {
                @Override
                public ContainerInfo inspect(String container) {
                    devAsked.add("inspect " + container);
                    return lookup.inspect(container);
                }

                @Override
                public String devContainerFor(Path folder) {
                    devAsked.add("ps " + folder);
                    return lookup.devContainerFor(folder);
                }

                @Override
                public ContainerEngine engine() {
                    return engine;
                }
            };
        };

        settle(() -> coordinator.projectChanged(true));

        assertEquals(List.of(), devAsked, "no engine question for a plain project");
        assertNull(FxTestSupport.callOnFx(coordinator::boundId));
        assertEquals(0, asked.get());
    }

    /** A project inside a WSL distribution: asked once for the distribution, its tools then run there. */
    @Test
    void aProjectInsideAWslDistributionIsAskedAboutOnceAndRunsThere() throws Exception {
        root = Path.of("\\\\wsl.localhost\\Ubuntu\\home\\ana\\app");
        ExecTargets.registerMapped(com.editora.wsl.WslTargets.shared());
        try {
            answer = true;

            settle(() -> coordinator.projectChanged(true));

            assertEquals(1, asked.get());
            assertEquals("wsl:Ubuntu", FxTestSupport.callOnFx(coordinator::boundId));
            assertEquals("/home/ana/app", ExecTargets.forPath(root).toTargetPath(root));
            assertFalse(ExecTargets.runsHere(root));

            settle(coordinator::detach);

            assertNull(FxTestSupport.callOnFx(coordinator::boundId));
            assertFalse(allowed.get("wsl:Ubuntu"), "withdrawn");
            assertTrue(ExecTargets.runsHere(root), "Windows's tools again");
        } finally {
            ExecTargets.unregisterMapped(com.editora.wsl.WslTargets.shared());
        }
    }
}
