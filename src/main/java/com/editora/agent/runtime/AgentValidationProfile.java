package com.editora.agent.runtime;

import java.nio.file.*;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Build operations are application-defined argv templates, never executable repository instructions. */
public final class AgentValidationProfile {
    public enum Operation {
        COMPILE,
        TEST,
        TARGETED_TEST,
        CHECK,
        PACKAGE
    }

    public enum Isolation {
        ISOLATED,
        HOST_REDUCED
    }

    public record Plan(
            String system, Operation operation, Path directory, String scope, List<String> argv, Isolation isolation) {
        public Plan {
            argv = List.copyOf(argv);
        }
    }

    private AgentValidationProfile() {}

    public static JsonNode discover(AgentWorkspace workspace, String module) throws Exception {
        Path root = workspace.resolve(module);
        if (!Files.isDirectory(root))
            throw new IllegalArgumentException(
                    "module must be a build directory such as '.', not pom.xml or build.gradle");
        var out = new ObjectMapper().createObjectNode();
        out.put(
                "module",
                root.equals(workspace.root())
                        ? "."
                        : workspace.root().relativize(root).toString());
        var profiles = out.putArray("profiles");
        for (String system : systems(workspace, root)) {
            var p = profiles.addObject().put("system", system);
            p.put(
                    "wrapper",
                    Files.isRegularFile(
                            workspace.resolve(root.resolve(wrapper(system)).toString())));
            p.putArray("operations")
                    .add("COMPILE")
                    .add("TEST")
                    .add("TARGETED_TEST")
                    .add("CHECK")
                    .add("PACKAGE");
        }
        var modules = out.putArray("childModules");
        if (Files.isDirectory(root))
            try (var children = Files.list(root)) {
                for (var child : children.limit(200).toList()) {
                    if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                            || child.getFileName().toString().startsWith(".")) continue;
                    if (!systems(workspace, child).isEmpty())
                        modules.add(workspace.root().relativize(child).toString());
                }
            }
        out.put("moduleDiscovery", "Immediate children only, at most 200 entries; use module for deeper discovery");
        out.put("isolationExecutablePresent", AgentValidationSandbox.available());
        out.put("network", "OFFLINE");
        out.put("approval", "ALWAYS_REQUIRED");
        out.put(
                "hint",
                "Choose a module directory with a build descriptor. ISOLATED is offline Linux isolation; unsupported hosts require an explicit HOST_REDUCED choice, which is not a sandbox and cannot enforce network denial.");
        return out;
    }

    private static String wrapper(String system) {
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return system.equals("MAVEN") ? windows ? "mvnw.cmd" : "mvnw" : windows ? "gradlew.bat" : "gradlew";
    }

    private static List<String> systems(AgentWorkspace workspace, Path root) throws Exception {
        var systems = new ArrayList<String>();
        if (Files.isRegularFile(workspace.resolve(root.resolve("pom.xml").toString()))) systems.add("MAVEN");
        if (Files.isRegularFile(workspace.resolve(root.resolve("build.gradle").toString()))
                || Files.isRegularFile(
                        workspace.resolve(root.resolve("build.gradle.kts").toString()))) systems.add("GRADLE");
        return systems;
    }

    public static Plan plan(AgentWorkspace workspace, JsonNode args) throws Exception {
        Path root = workspace.resolve(args.path("module").asText("."));
        if (!Files.isDirectory(root))
            throw new IllegalArgumentException(
                    "module must be a build directory such as '.', not pom.xml or build.gradle");
        var systems = systems(workspace, root);
        String system = args.path("system").asText(systems.size() == 1 ? systems.getFirst() : "");
        if (!systems.contains(system))
            throw new IllegalArgumentException(
                    "Choose a discovered build system with validation_profiles; no unambiguous build descriptor exists at this module");
        Operation operation = Operation.valueOf(args.path("type").asText());
        Isolation isolation = Isolation.valueOf(args.path("isolation").asText("ISOLATED"));
        String selector = args.path("test").asText();
        if (operation == Operation.TARGETED_TEST && !selector.matches("[A-Za-z_$][A-Za-z0-9_.$*?#]{0,199}"))
            throw new IllegalArgumentException(
                    "TARGETED_TEST needs a Java test class or class#method selector, not command options");
        if (operation != Operation.TARGETED_TEST && !selector.isEmpty())
            throw new IllegalArgumentException("test selector is only valid for TARGETED_TEST");
        String wrapper = wrapper(system);
        Path wrapperPath = workspace.resolve(root.resolve(wrapper).toString());
        String executable =
                Files.isRegularFile(wrapperPath) ? wrapperPath.toString() : system.equals("MAVEN") ? "mvn" : "gradle";
        var argv = new ArrayList<String>();
        argv.add(executable);
        if (system.equals("MAVEN")) {
            argv.addAll(List.of("-B", "-o", "-DskipTests=false", "-Dmaven.test.skip=false"));
            argv.add(
                    switch (operation) {
                        case COMPILE -> "compile";
                        case TEST, TARGETED_TEST -> "test";
                        case CHECK -> "verify";
                        case PACKAGE -> "package";
                    });
            if (operation == Operation.TARGETED_TEST) argv.add("-Dtest=" + selector);
        } else {
            argv.addAll(List.of("--offline", "--no-daemon", "--console=plain", "--rerun-tasks"));
            argv.add(
                    switch (operation) {
                        case COMPILE -> "classes";
                        case TEST, TARGETED_TEST -> "test";
                        case CHECK -> "check";
                        case PACKAGE -> "assemble";
                    });
            if (operation == Operation.TARGETED_TEST) argv.addAll(List.of("--tests", selector.replace('#', '.')));
        }
        return new Plan(system, operation, root, selector, argv, isolation);
    }
}
