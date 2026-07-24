package com.editora.run;

import java.util.List;

/**
 * The resolved inputs needed to launch a Java main class: the java executable and the module/class paths, or
 * an {@code error} message when resolution failed. Build-tool-neutral — produced from jdtls today and from a
 * build-tool classpath probe later — so the Run path doesn't care where the classpath came from.
 */
public record JavaLaunchInfo(String javaExec, List<String> modulePaths, List<String> classPaths, String error) {

    public static JavaLaunchInfo failed(String error) {
        return new JavaLaunchInfo(null, List.of(), List.of(), error);
    }

    public boolean ok() {
        return error == null;
    }
}
