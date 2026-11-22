package com.editora.dap;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.run.DartRun;
import org.jspecify.annotations.Nullable;

/**
 * How a Dart file is debugged, as VS Code's Dart extension starts it: a plain Dart program on {@code dart
 * debug_adapter} ({@link DebugLanguages#DART}), a Flutter app's file on {@code flutter debug_adapter} ({@link
 * DebugLanguages#FLUTTER}) on a device ({@code toolArgs: -d <device>}), both over their standard streams. The program
 * runs in its package's folder ({@link DartRun#packageRoot}), else the file's. Stops stay in the user's own code: the
 * SDK's and other packages' libraries are not debugged ({@code debugSdkLibraries} and {@code
 * debugExternalPackageLibraries} off; on, a stop lands in SDK frames). Pure.
 */
public final class DartLaunch {

    private DartLaunch() {}

    /** What a Dart file is debugged on, with this {@code launch} request, in folder {@code cwd}. */
    public record Plan(DebugLanguage language, Map<String, Object> launch, Path cwd) {}

    /**
     * The plan for {@code file} with program arguments {@code args}, in package {@code root} (null: none) whose
     * {@code pubspec.yaml} reads {@code pubspec}; a Flutter app's file runs on {@code device}.
     */
    public static Plan plan(
            Path file, @Nullable Path root, @Nullable String pubspec, List<String> args, String device) {
        Path abs = file.toAbsolutePath();
        Path folder = abs.getParent() == null ? abs : abs.getParent();
        boolean flutter = root != null && DartRun.isFlutter(pubspec);
        Path cwd = root != null ? root : folder;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", DebugLanguages.DART.launchType());
        m.put("request", "launch");
        m.put("program", abs.toString());
        m.put("cwd", cwd.toString());
        m.put("args", List.copyOf(args));
        if (flutter) {
            m.put("toolArgs", List.of("-d", device));
        }
        m.put("debugSdkLibraries", false);
        m.put("debugExternalPackageLibraries", false);
        return new Plan(flutter ? DebugLanguages.FLUTTER : DebugLanguages.DART, m, cwd);
    }
}
