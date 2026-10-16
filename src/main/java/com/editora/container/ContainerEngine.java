package com.editora.container;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.editora.process.ConfiguredCommand;
import com.editora.process.ProcessRunner;

/**
 * The container engine's command line: {@code docker}, or {@code podman}, which takes the same arguments.
 * Everything here runs the engine on this machine, off the FX thread.
 */
public record ContainerEngine(List<String> command) {

    /** How long a question to the engine ({@code inspect}, {@code ps}) may take. */
    static final Duration QUICK = Duration.ofSeconds(15);

    public ContainerEngine {
        command = List.copyOf(command);
    }

    /** The engine the user configured ({@code docker} when blank). */
    public static ContainerEngine of(String configured) {
        return new ContainerEngine(ConfiguredCommand.tokens(configured, "docker"));
    }

    /**
     * {@code exec -i [-u user] <container> <inner…>}: {@code inner} run in the container, its stdin, stdout and
     * stderr this process's. The working directory is the script's to set ({@link com.editora.process.PosixShell}).
     */
    public List<String> execArgv(String container, String user, List<String> inner) {
        List<String> argv = new ArrayList<>(command);
        argv.add("exec");
        argv.add("-i");
        if (user != null && !user.isBlank()) {
            argv.add("-u");
            argv.add(user);
        }
        argv.add(container);
        argv.addAll(inner);
        return List.copyOf(argv);
    }

    /** {@code inspect <container>}, parsed; null when the engine does not answer or knows no such container. */
    public ContainerInfo inspect(String container) {
        List<String> argv = new ArrayList<>(command);
        argv.addAll(List.of("inspect", "--type", "container", container));
        ProcessRunner.Result r = ProcessRunner.run(null, QUICK, argv);
        return r.ok() ? ContainerInfo.parse(r.out()) : null;
    }

    /**
     * The running container the Dev Containers CLI or VS Code started for {@code folder} (its
     * {@value ContainerInfo#LOCAL_FOLDER_LABEL} label), or null.
     */
    public String devContainerFor(Path folder) {
        List<String> argv = new ArrayList<>(command);
        argv.addAll(List.of(
                "ps",
                "-q",
                "--filter",
                "label=" + ContainerInfo.LOCAL_FOLDER_LABEL + "="
                        + folder.toAbsolutePath().normalize()));
        ProcessRunner.Result r = ProcessRunner.run(null, QUICK, argv);
        if (!r.ok()) {
            return null;
        }
        return r.out()
                .lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty())
                .findFirst()
                .orElse(null);
    }
}
