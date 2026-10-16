package com.editora.container;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.editora.process.ExecTarget;
import com.editora.process.ExecTargets;

/**
 * The containers folders of this machine are bound to, shared by every window: the {@link ExecTargets}
 * resolver for a bind-mounted folder. Learning a binding asks the engine ({@link #bind}), off the FX thread;
 * answering which container a path's tools run in ({@link #forPath}) is a lookup.
 *
 * <p>A folder is bound to the container the user named for it, or else to the running dev container started
 * for it (its {@value ContainerInfo#LOCAL_FOLDER_LABEL} label), provided one of that container's mounts covers
 * the folder.
 */
public final class ContainerBindings implements ExecTargets.Resolver {

    /** What the engine is asked; replaceable in tests. */
    public interface Lookup {
        /** {@code inspect} of a container, or null. */
        ContainerInfo inspect(String container);

        /** The running dev container started for {@code folder}, or null. */
        String devContainerFor(Path folder);

        /** The engine that runs commands in what it found. */
        ContainerEngine engine();
    }

    private static final ContainerBindings SHARED = new ContainerBindings();

    /** Bound folders (absolute, normalized) → the target of their container. */
    private final Map<Path, ContainerExecTarget> byFolder = new ConcurrentHashMap<>();

    public static ContainerBindings shared() {
        return SHARED;
    }

    /** A lookup that asks {@code engine}. */
    public static Lookup lookup(ContainerEngine engine) {
        return new Lookup() {
            @Override
            public ContainerInfo inspect(String container) {
                return engine.inspect(container);
            }

            @Override
            public String devContainerFor(Path folder) {
                return engine.devContainerFor(folder);
            }

            @Override
            public ContainerEngine engine() {
                return engine;
            }
        };
    }

    /**
     * Finds and records the container {@code folder}'s tools run in: {@code named} when given, else the
     * folder's running dev container. Null, and any earlier binding of the folder forgotten, when there is
     * none or it does not mount the folder. Asks the engine: call it off the FX thread.
     */
    public ContainerExecTarget bind(Path folder, String named, Lookup lookup) {
        Path key = folder.toAbsolutePath().normalize();
        String container = named != null && !named.isBlank() ? named.strip() : lookup.devContainerFor(key);
        ContainerInfo info = container == null ? null : lookup.inspect(container);
        if (info == null || !info.mounts().covers(key)) {
            byFolder.remove(key);
            return null;
        }
        ContainerExecTarget target = new ContainerExecTarget(lookup.engine(), info);
        byFolder.put(key, target);
        return target;
    }

    /** Forgets {@code folder}'s container: its tools run here again. */
    public void unbind(Path folder) {
        byFolder.remove(folder.toAbsolutePath().normalize());
    }

    /** The container {@code folder} is bound to, or null. No I/O. */
    public ContainerExecTarget boundTo(Path folder) {
        return folder == null ? null : byFolder.get(folder.toAbsolutePath().normalize());
    }

    /** The target of the bound folder that holds {@code path} (the deepest), or null. No I/O. */
    @Override
    public ExecTarget forPath(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        ContainerExecTarget best = null;
        int depth = -1;
        for (Map.Entry<Path, ContainerExecTarget> e : byFolder.entrySet()) {
            if (abs.startsWith(e.getKey())
                    && e.getKey().getNameCount() > depth
                    && e.getValue().covers(abs)) {
                best = e.getValue();
                depth = e.getKey().getNameCount();
            }
        }
        return best;
    }
}
