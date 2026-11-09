package com.editora.dap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * How a C# file is built for debugging on netcoredbg, and which assembly that makes — as {@code dotnet run} picks it:
 *
 * <ul>
 *   <li><b>A project</b> (a {@code .csproj} in the file's folder or one above it): {@code dotnet build <project>};
 *       the program runs in the project's folder.
 *   <li><b>A lone file</b> (no project above it): a .NET 10 file-based app, {@code dotnet build <file>}.
 * </ul>
 *
 * Then {@code dotnet build <target> -getProperty:TargetPath}, which only evaluates the project, names the assembly.
 * Planning and reading are pure; {@link #projectFile} lists folders.
 */
public final class DotnetLaunch {

    private DotnetLaunch() {}

    /**
     * The plan for a file: the project or file built ({@code target}), the folder the build and the program run in, the
     * build's and the assembly query's arguments (after {@code dotnet}), and what the session is called.
     */
    public record Plan(Path target, Path root, List<String> buildArgs, List<String> pathArgs, String sessionName) {

        /** Whether it builds a project, whose MSBuild files are code (a lone file is built by the SDK's own). */
        public boolean project() {
            return target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csproj");
        }
    }

    /**
     * The project {@code file} belongs to: the {@code .csproj} in its folder or the nearest one above — when a folder
     * has several, the one named after the folder, else the first by name; null outside every project.
     */
    public static @Nullable Path projectFile(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        while (dir != null) {
            List<Path> projects = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName()
                                .toString()
                                .toLowerCase(Locale.ROOT)
                                .endsWith(".csproj"))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .forEach(projects::add);
            } catch (IOException | RuntimeException e) {
                // unreadable: look further up
            }
            Path picked = pick(projects, dir);
            if (picked != null) {
                return picked;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /** Among {@code projects} of folder {@code dir} (sorted), the one named after it, else the first; null for none. */
    static @Nullable Path pick(List<Path> projects, Path dir) {
        if (projects.isEmpty()) {
            return null;
        }
        String folder = dir.getFileName() == null ? "" : dir.getFileName().toString();
        for (Path p : projects) {
            String name = p.getFileName().toString();
            if (name.substring(0, name.length() - ".csproj".length()).equalsIgnoreCase(folder)) {
                return p;
            }
        }
        return projects.getFirst();
    }

    /** The plan for {@code file}, built as part of {@code project} — or, when null, as a file-based app. */
    public static Plan plan(Path file, @Nullable Path project) {
        Path target = project != null ? project : file.toAbsolutePath();
        Path root = target.toAbsolutePath().getParent();
        if (root == null) {
            root = target.toAbsolutePath();
        }
        String name = target.getFileName().toString();
        String session = project != null ? name.substring(0, name.length() - ".csproj".length()) : name;
        return new Plan(
                target,
                root,
                List.of("build", target.toString()),
                List.of("build", target.toString(), "-getProperty:TargetPath"),
                session);
    }

    /**
     * Runs {@code dotnet} with {@code plan}'s assembly query in its folder (an evaluation: nothing is built), in the C
     * locale since its output is parsed. Blocking.
     */
    public static com.editora.process.ProcessRunner.Result queryTargetPath(
            List<String> dotnet, Plan plan, java.time.Duration timeout) {
        List<String> argv = new ArrayList<>(dotnet);
        argv.addAll(plan.pathArgs());
        return com.editora.process.ProcessRunner.run(plan.root(), timeout, argv);
    }

    /**
     * The assembly {@code dotnet build … -getProperty:TargetPath} printed: its last line naming an absolute
     * {@code .dll} (or a Windows {@code .exe}); null when none does.
     */
    public static @Nullable Path targetPath(String out) {
        List<String> lines = out.lines().map(String::strip).toList();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".dll") && !lower.endsWith(".exe")) {
                continue;
            }
            try {
                Path p = Path.of(line);
                if (p.isAbsolute()) {
                    return p;
                }
            } catch (java.nio.file.InvalidPathException e) {
                // not a path
            }
        }
        return null;
    }

    /**
     * netcoredbg's {@code launch} for {@code assembly}: it runs the assembly on {@code dotnet} with {@code args}, in
     * {@code cwd}, user code only ({@code justMyCode}), not stopping at entry.
     */
    public static Map<String, Object> launch(Path assembly, List<String> args, Path cwd) {
        return ExternalAdapter.launchRequest(
                DebugLanguages.CSHARP.launchType(),
                assembly.toString(),
                args,
                cwd.toString(),
                Map.of(),
                false,
                Map.of("stopAtEntry", false, "justMyCode", true));
    }

    /** netcoredbg's {@code attach} to the .NET process {@code pid}. */
    public static Map<String, Object> attach(long pid) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", DebugLanguages.CSHARP.launchType());
        m.put("request", "attach");
        m.put("processId", pid);
        return m;
    }

    /** The environment {@code dotnet} builds in: no first-run banner in the Debug console. */
    public static final Map<String, String> BUILD_ENV = Map.of("DOTNET_NOLOGO", "1");
}
