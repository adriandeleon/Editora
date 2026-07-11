package com.editora.dockerfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Distills a parsed {@link Dockerfile} stage into a plain-English digest for the preview — the base image,
 * exposed ports, working directory, user, env count, volumes, the effective entrypoint/command, a health
 * check, and a build-step count — instead of glossing every instruction. Pure, java.base-only, unit-tested.
 */
public final class DockerfileDescribe {

    private DockerfileDescribe() {}

    /** A one-line stage title: "Stage N — name (from image)" / "Final stage (from image)". */
    public static String title(Dockerfile.Stage stage, boolean isFinal) {
        StringBuilder sb = new StringBuilder();
        sb.append(isFinal ? "Final stage" : "Stage " + stage.index());
        if (stage.name() != null) {
            sb.append(" — ").append(stage.name());
        }
        sb.append(" (from ").append(stage.baseImage()).append(")");
        return sb.toString();
    }

    /** The curated "what this stage does" facts, in reading order. */
    public static List<String> summaryLines(Dockerfile.Stage stage) {
        List<String> out = new ArrayList<>();
        int steps = 0;
        int envCount = 0;
        String workdir = null;
        String user = null;
        String entrypoint = null;
        String cmd = null;
        String healthcheck = null;
        List<String> ports = new ArrayList<>();
        List<String> volumes = new ArrayList<>();
        for (Dockerfile.Instruction ins : stage.instructions()) {
            switch (ins.keyword()) {
                case "RUN", "COPY", "ADD" -> steps++;
                case "EXPOSE" -> ports.add(ins.args());
                case "WORKDIR" -> workdir = ins.args();
                case "USER" -> user = ins.args();
                case "ENV" -> envCount++;
                case "VOLUME" -> volumes.add(ins.args());
                case "ENTRYPOINT" -> entrypoint = ins.args();
                case "CMD" -> cmd = ins.args();
                case "HEALTHCHECK" -> healthcheck = ins.args();
                default -> {}
            }
        }
        if (!ports.isEmpty()) {
            out.add("Exposes port " + String.join(", ", ports));
        }
        if (workdir != null) {
            out.add("Working directory: " + workdir);
        }
        if (user != null) {
            out.add("Runs as user: " + user);
        }
        if (envCount > 0) {
            out.add("Sets " + envCount + (envCount == 1 ? " environment variable" : " environment variables"));
        }
        if (!volumes.isEmpty()) {
            out.add("Volume: " + String.join(", ", volumes));
        }
        if (entrypoint != null) {
            out.add("Entrypoint: " + entrypoint);
        }
        if (cmd != null) {
            out.add((entrypoint != null ? "Default arguments: " : "Runs: ") + cmd);
        }
        if (healthcheck != null) {
            out.add("Health check: " + (healthcheck.equalsIgnoreCase("NONE") ? "disabled" : "configured"));
        }
        out.add(steps + (steps == 1 ? " build step" : " build steps") + " (RUN/COPY/ADD)");
        return out;
    }
}
