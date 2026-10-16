package com.editora.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A folder of this machine whose tools "run elsewhere", for tests: the tools know it by another name (a
 * symlink to it), as a container knows a bind-mounted folder by its mount point or WSL its distribution's
 * paths. Commands still run here, under that other name. Every command line and environment value handed to
 * it is checked for this machine's own spelling of the folder: a path given to a tool as {@code toString()}
 * rather than through {@link ExecTarget#toTargetPath} is recorded as a leak.
 */
public final class MappedTestTarget implements ExecTarget, ExecTargets.Resolver {

    private final Path local;
    private final Path alias;
    private final List<String> leaks = new CopyOnWriteArrayList<>();
    private final List<List<String>> started = new CopyOnWriteArrayList<>();

    private MappedTestTarget(Path local, Path alias) {
        this.local = local;
        this.alias = alias;
    }

    /** Maps {@code folder} (its real path) to a new symlink {@code alias} that points at it, and registers it. */
    public static MappedTestTarget register(Path folder, Path alias) throws IOException {
        Path real = folder.toRealPath();
        Files.createSymbolicLink(alias, real);
        MappedTestTarget target = new MappedTestTarget(real, alias.toAbsolutePath());
        ExecTargets.registerMapped(target);
        return target;
    }

    public void unregister() {
        ExecTargets.unregisterMapped(this);
    }

    /** What was handed to a tool with this machine's spelling of the folder in it. */
    public List<String> leaks() {
        return List.copyOf(leaks);
    }

    /** Every command started here, its argv as given. */
    public List<List<String>> started() {
        return List.copyOf(started);
    }

    private boolean ours(Path path) {
        return path.toAbsolutePath().normalize().startsWith(local);
    }

    @Override
    public ExecTarget forPath(Path path) {
        return ours(path) ? this : null;
    }

    @Override
    public String id() {
        return "mapped:test";
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Process start(ExecSpec spec) throws IOException {
        started.add(spec.argv());
        String mine = local.toString();
        spec.argv().stream().filter(a -> a.contains(mine)).forEach(a -> leaks.add("argv: " + a));
        spec.env().forEach((name, value) -> {
            if (value != null && value.contains(mine)) {
                leaks.add("env " + name + "=" + value);
            }
        });
        Path cwd = spec.workingDir() == null ? null : Path.of(toTargetPath(spec.workingDir()));
        return ExecTargets.local()
                .start(new ExecSpec(
                        spec.argv(),
                        cwd,
                        spec.env(),
                        spec.locale(),
                        spec.longLived(),
                        spec.discardStdout(),
                        spec.discardStderr(),
                        spec.scrubSecrets()));
    }

    @Override
    public PortForward forwardLocal(String host, int port) throws IOException {
        return ExecTargets.local().forwardLocal(host, port);
    }

    @Override
    public String toTargetPath(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        return abs.startsWith(local) ? alias.resolve(local.relativize(abs)).toString() : abs.toString();
    }

    @Override
    public Path toPath(String targetPath) {
        Path p = Path.of(targetPath);
        return p.startsWith(alias) ? local.resolve(alias.relativize(p)) : p;
    }
}
