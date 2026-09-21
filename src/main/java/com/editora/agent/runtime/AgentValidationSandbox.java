package com.editora.agent.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import com.editora.process.ProcessRunner;

/** Linux namespace/mount isolation with offline caches. Never silently falls back to host execution. */
public final class AgentValidationSandbox {
    private AgentValidationSandbox() {}

    public static boolean available() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")
                && Files.isExecutable(Path.of("/usr/bin/bwrap"));
    }

    public static List<String> command(AgentWorkspace workspace, AgentValidationProfile.Plan plan) throws IOException {
        if (plan.isolation() == AgentValidationProfile.Isolation.HOST_REDUCED) return plan.argv();
        if (!available())
            throw new IOException(
                    "ISOLATED validation is unavailable. Select HOST_REDUCED explicitly only after reviewing its lower isolation, or install/configure bubblewrap.");
        List<String> resolved = ProcessRunner.resolveExecutable(plan.argv());
        Path executable = Path.of(resolved.getFirst()).toAbsolutePath().normalize();
        if (!Files.isExecutable(executable)) throw new IOException("Build executable unavailable");
        executable = executable.toRealPath();
        Path javaHome = Path.of(System.getProperty("java.home")).toRealPath();
        List<String> command = new ArrayList<>(
                List.of("/usr/bin/bwrap", "--die-with-parent", "--new-session", "--unshare-all", "--clearenv"));
        for (String system : List.of("/usr", "/bin", "/lib", "/lib64")) {
            if (Files.exists(Path.of(system))) command.addAll(List.of("--ro-bind", system, system));
        }
        // Mount only tool distributions, never the user's home, SSH directory or settings.xml.
        Path toolRoot = executable.getParent().getParent();
        if (!executable.startsWith(workspace.root())
                && !executable.startsWith(Path.of("/usr"))
                && (toolRoot.equals(Path.of(System.getProperty("user.home")))
                        || !Files.isDirectory(toolRoot.resolve("lib"))))
            throw new IOException(
                    "ISOLATED validation requires a build-tool distribution; refusing to mount an unrelated home directory");
        if (!executable.startsWith(workspace.root()) && !executable.startsWith(Path.of("/usr")))
            command.addAll(List.of("--ro-bind", toolRoot.toString(), toolRoot.toString()));
        if (!javaHome.startsWith(Path.of("/usr")))
            command.addAll(List.of("--ro-bind", javaHome.toString(), javaHome.toString()));
        command.addAll(List.of(
                "--proc",
                "/proc",
                "--dev",
                "/dev",
                "--tmpfs",
                "/tmp",
                "--dir",
                "/tmp/agent-home",
                "--bind",
                workspace.root().toString(),
                workspace.root().toString(),
                "--chdir",
                plan.directory().toString(),
                "--setenv",
                "HOME",
                "/tmp/agent-home",
                "--setenv",
                "JAVA_HOME",
                javaHome.toString(),
                "--setenv",
                "PATH",
                javaHome.resolve("bin") + ":" + executable.getParent() + ":/usr/bin:/bin",
                "--setenv",
                "LANG",
                "C.UTF-8",
                "--setenv",
                "LC_ALL",
                "C.UTF-8"));
        Path mavenCache = Path.of(System.getProperty("user.home"), ".m2", "repository");
        var argv = new ArrayList<>(resolved);
        argv.set(0, executable.toString());
        if (plan.system().equals("MAVEN") && Files.isDirectory(mavenCache)) {
            command.addAll(List.of("--ro-bind", mavenCache.toRealPath().toString(), "/tmp/maven-cache"));
            argv.add("-Dmaven.repo.local=/tmp/maven-cache");
        }
        // No network namespace sharing. Offline flags alone are not claimed as network isolation.
        command.add("--");
        command.addAll(argv);
        return List.copyOf(command);
    }
}
