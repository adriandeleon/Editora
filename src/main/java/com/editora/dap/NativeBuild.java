package com.editora.dap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

/**
 * How a C or C++ file is built for debugging, and which executable that makes — as VS Code's CMake Tools and C/C++
 * extension do it, without asking for a configuration first:
 *
 * <ul>
 *   <li><b>CMake</b> (a {@code CMakeLists.txt} above the file): a Debug build in {@code build/editora-debug} of the
 *       project ({@code cmake -S … -B … -DCMAKE_BUILD_TYPE=Debug}, then {@code cmake --build}); CMake's File API then
 *       says which executable target holds the file.
 *   <li><b>Make</b> (a {@code Makefile} above it): {@code make -C <folder>} (the default goal); the executable is the
 *       one named after the file, else the only one, else the newest, in the folder ({@code bin/} and
 *       {@code build/} too).
 *   <li><b>A lone file</b>: the C or C++ compiler ({@code cc} / {@code c++ -g -O0}) into a temporary folder.
 * </ul>
 *
 * Planning is pure; {@link #cmakeRoot}, {@link #makeRoot}, {@link #requestCodemodel}, {@link #cmakeExecutable} and
 * {@link #makeExecutable} touch files.
 */
public final class NativeBuild {

    private NativeBuild() {}

    /** What builds the file. */
    public enum Tool {
        CMAKE,
        MAKE,
        COMPILER
    }

    /** Where a CMake project's debug build goes, relative to its root. */
    public static final String CMAKE_BUILD_DIR = "build/editora-debug";

    /**
     * The plan for a file: the tool, the folder it runs in ({@code root}), the commands in order, the CMake build
     * folder or the compiler's output (else null), and what the session is called.
     */
    public record Plan(
            Tool tool,
            Path root,
            List<List<String>> steps,
            @Nullable Path buildDir,
            @Nullable Path output,
            String sessionName) {}

    /** The makefile names GNU make reads, in its order. */
    private static final List<String> MAKEFILES = List.of("GNUmakefile", "makefile", "Makefile");

    /** Whether {@code file} is a header, which is not built on its own. */
    public static boolean isHeader(Path file) {
        String name = String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT);
        return name.endsWith(".h")
                || name.endsWith(".hh")
                || name.endsWith(".hpp")
                || name.endsWith(".hxx")
                || name.endsWith(".h++");
    }

    /**
     * The CMake project {@code file} belongs to: the topmost folder of the run of folders above it that each have a
     * {@code CMakeLists.txt} (a subdirectory's is part of its parent's project); null outside one.
     */
    public static @Nullable Path cmakeRoot(Path file) {
        Path found = null;
        for (Path dir = file.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("CMakeLists.txt"))) {
                found = dir;
            } else if (found != null) {
                break;
            }
        }
        return found;
    }

    /** The nearest folder above {@code file} with a makefile; null when none has. */
    public static @Nullable Path makeRoot(Path file) {
        for (Path dir = file.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
            for (String name : MAKEFILES) {
                if (Files.isRegularFile(dir.resolve(name))) {
                    return dir;
                }
            }
        }
        return null;
    }

    /**
     * The plan for C or C++ file {@code file} ({@code cpp}: C++), given the project it is in — a CMake root, else a
     * make root (either may be null) — and the folder a lone file's program goes to. Null for a lone header.
     */
    public static @Nullable Plan plan(
            Path file, boolean cpp, @Nullable Path cmakeRoot, @Nullable Path makeRoot, Path outputDir) {
        String name = String.valueOf(file.getFileName());
        if (cmakeRoot != null) {
            Path build = cmakeRoot.resolve(CMAKE_BUILD_DIR);
            return new Plan(
                    Tool.CMAKE,
                    cmakeRoot,
                    List.of(
                            List.of(
                                    "cmake",
                                    "-S",
                                    cmakeRoot.toString(),
                                    "-B",
                                    build.toString(),
                                    "-DCMAKE_BUILD_TYPE=Debug"),
                            List.of("cmake", "--build", build.toString())),
                    build,
                    null,
                    name);
        }
        if (makeRoot != null) {
            return new Plan(Tool.MAKE, makeRoot, List.of(List.of("make", "-C", makeRoot.toString())), null, null, name);
        }
        if (isHeader(file)) {
            return null;
        }
        Path dir = file.toAbsolutePath().getParent();
        String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        String hash =
                Integer.toHexString(file.toAbsolutePath().normalize().toString().hashCode());
        boolean windows = java.io.File.pathSeparatorChar == ';';
        Path out = outputDir.resolve(stem + "-" + hash + (windows ? ".exe" : ""));
        return new Plan(
                Tool.COMPILER,
                dir == null ? file.toAbsolutePath() : dir,
                List.of(List.of(
                        cpp ? "c++" : "cc",
                        "-g",
                        "-O0",
                        "-o",
                        out.toString(),
                        file.toAbsolutePath().toString())),
                null,
                out,
                name);
    }

    /** Asks CMake (before it configures {@code buildDir}) to write its code model, read by {@link #cmakeExecutable}. */
    public static void requestCodemodel(Path buildDir) throws IOException {
        Path query = buildDir.resolve(".cmake/api/v1/query");
        Files.createDirectories(query);
        Path codemodel = query.resolve("codemodel-v2");
        if (!Files.exists(codemodel)) {
            Files.createFile(codemodel);
        }
    }

    /**
     * The executable of CMake build {@code buildDir} for {@code file}: the executable target whose sources hold it,
     * else the only executable target. Read from CMake's File API reply (the newest {@code index-*.json}, its
     * code model, the targets); null when there is none to read or none fits.
     */
    public static @Nullable Path cmakeExecutable(Path buildDir, Path file) {
        Path reply = buildDir.resolve(".cmake/api/v1/reply");
        try (Stream<Path> files = Files.list(reply)) {
            Path index = files.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("index-") && n.endsWith(".json");
                    })
                    .max(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
            if (index == null) {
                return null;
            }
            JsonObject idx = json(index);
            String codemodelFile = null;
            for (JsonElement o : idx.getAsJsonArray("objects")) {
                JsonObject obj = o.getAsJsonObject();
                if ("codemodel".equals(obj.get("kind").getAsString())) {
                    codemodelFile = obj.get("jsonFile").getAsString();
                }
            }
            if (codemodelFile == null) {
                return null;
            }
            JsonObject model = json(reply.resolve(codemodelFile));
            Path source = Path.of(model.getAsJsonObject("paths").get("source").getAsString());
            Map<String, JsonObject> targets = new LinkedHashMap<>();
            JsonArray configurations = model.getAsJsonArray("configurations");
            if (configurations.isEmpty()) {
                return null;
            }
            for (JsonElement t : configurations.get(0).getAsJsonObject().getAsJsonArray("targets")) {
                String jsonFile = t.getAsJsonObject().get("jsonFile").getAsString();
                targets.put(jsonFile, json(reply.resolve(jsonFile)));
            }
            return pickTarget(new ArrayList<>(targets.values()), source, buildDir, file);
        } catch (IOException | RuntimeException e) {
            return null; // no reply (CMake older than 3.14, or the configure failed), or one shaped otherwise
        }
    }

    /** {@link #cmakeExecutable}'s choice among the code model's {@code targets}. Pure. */
    static @Nullable Path pickTarget(List<JsonObject> targets, Path source, Path buildDir, Path file) {
        Path wanted = file.toAbsolutePath().normalize();
        List<Path> executables = new ArrayList<>();
        for (JsonObject t : targets) {
            if (!"EXECUTABLE".equals(text(t.get("type"))) || t.getAsJsonArray("artifacts") == null) {
                continue;
            }
            JsonArray artifacts = t.getAsJsonArray("artifacts");
            if (artifacts.isEmpty()) {
                continue;
            }
            Path artifact = buildDir.resolve(
                    artifacts.get(0).getAsJsonObject().get("path").getAsString());
            executables.add(artifact);
            JsonArray sources = t.getAsJsonArray("sources");
            if (sources == null) {
                continue;
            }
            for (JsonElement s : sources) {
                Path src = source.resolve(s.getAsJsonObject().get("path").getAsString())
                        .toAbsolutePath()
                        .normalize();
                if (src.equals(wanted)) {
                    return artifact;
                }
            }
        }
        return executables.size() == 1 ? executables.getFirst() : null;
    }

    /**
     * The program {@code make} built in {@code root} for {@code file}: among the native programs in the folder (and
     * its {@code bin/} and {@code build/}), the one named after the file, else the only one, else the most recently
     * written; null when there is none.
     */
    public static @Nullable Path makeExecutable(Path root, Path file) {
        List<Path> programs = new ArrayList<>();
        for (Path dir : List.of(root, root.resolve("bin"), root.resolve("build"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> list = Files.list(dir)) {
                list.filter(Files::isRegularFile)
                        .filter(Files::isExecutable)
                        .filter(NativeBuild::isNativeProgram)
                        .forEach(programs::add);
            } catch (IOException e) {
                // unreadable: skipped
            }
        }
        String name = String.valueOf(file.getFileName());
        String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        for (Path p : programs) {
            String n = p.getFileName().toString();
            if (n.equals(stem) || n.equals(stem + ".exe")) {
                return p;
            }
        }
        if (programs.size() == 1) {
            return programs.getFirst();
        }
        return programs.stream()
                .max(java.util.Comparator.comparingLong(NativeBuild::modified))
                .orElse(null);
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Whether {@code file} starts as an ELF, Mach-O or PE program does (not a script, which is executable too). */
    static boolean isNativeProgram(Path file) {
        byte[] head = new byte[4];
        try (var in = Files.newInputStream(file)) {
            if (in.readNBytes(head, 0, 4) < 4) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        boolean elf = head[0] == 0x7f && head[1] == 'E' && head[2] == 'L' && head[3] == 'F';
        boolean machO = (head[0] & 0xff) == 0xcf
                && (head[1] & 0xff) == 0xfa
                && (head[2] & 0xff) == 0xed
                && (head[3] & 0xff) == 0xfe;
        boolean pe = head[0] == 'M' && head[1] == 'Z';
        return elf || machO || pe;
    }

    /**
     * CodeLLDB's {@code launch} request for native {@code program} with {@code args}, in {@code cwd}: C and C++
     * ({@code sourceLanguages}), the program's output in the Debug console, or in a terminal Editora starts
     * ({@code terminal}, with its input).
     */
    public static Map<String, Object> launch(Path program, List<String> args, Path cwd, boolean terminal) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("sourceLanguages", List.of("cpp"));
        extra.put("terminal", terminal ? "integrated" : "console");
        return ExternalAdapter.launchRequest("lldb", program.toString(), args, cwd.toString(), Map.of(), false, extra);
    }

    /**
     * GDB's DAP {@code launch} request for native {@code program} with {@code args}, in {@code cwd}
     * (C and C++'s debugger set to GDB). Its output comes as the adapter's output events.
     */
    public static Map<String, Object> gdbLaunch(Path program, List<String> args, Path cwd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "gdb");
        m.put("request", "launch");
        m.put("program", program.toString());
        m.put("args", List.copyOf(args));
        m.put("cwd", cwd.toString());
        return m;
    }

    /** CodeLLDB's {@code attach} request for process {@code pid} of this machine, of {@code language} (cpp, rust). */
    public static Map<String, Object> attach(long pid, String language) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "lldb");
        m.put("request", "attach");
        m.put("pid", pid);
        m.put("sourceLanguages", List.of(language));
        return m;
    }

    private static JsonObject json(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private static @Nullable String text(@Nullable JsonElement e) {
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
