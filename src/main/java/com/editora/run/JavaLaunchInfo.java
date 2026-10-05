package com.editora.run;

import java.util.List;

/**
 * The resolved inputs needed to launch a Java main class: the java executable and the module/class paths, or
 * an {@code error} message when resolution failed. Build-tool-neutral — produced from jdtls today and from a
 * build-tool classpath probe later — so the Run path doesn't care where the classpath came from.
 */
public record JavaLaunchInfo(
        String javaExec, List<String> modulePaths, List<String> classPaths, String error, boolean enablePreview) {

    public JavaLaunchInfo(String javaExec, List<String> modulePaths, List<String> classPaths, String error) {
        this(javaExec, modulePaths, classPaths, error, false);
    }

    /**
     * {@code vmArgs} with {@code --enable-preview} in front when the project compiles with preview features
     * and the arguments do not already carry it — a class compiled that way does not load without the flag.
     */
    public List<String> vmArgs(List<String> vmArgs) {
        List<String> args = vmArgs == null ? List.of() : vmArgs;
        if (!enablePreview || args.contains("--enable-preview")) {
            return args;
        }
        List<String> out = new java.util.ArrayList<>(args.size() + 1);
        out.add("--enable-preview");
        out.addAll(args);
        return out;
    }

    public static JavaLaunchInfo failed(String error) {
        return new JavaLaunchInfo(null, List.of(), List.of(), error);
    }

    public boolean ok() {
        return error == null;
    }
}
