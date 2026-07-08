package com.editora.maven;

import java.util.List;

/** The Maven phases users invoke directly: the one-phase {@code clean} lifecycle followed by the default
 *  lifecycle's phases, in execution order (running one runs every preceding phase in its own lifecycle). */
public final class MavenLifecycle {

    public static final List<String> PHASES =
            List.of("clean", "validate", "compile", "test", "package", "verify", "install", "site", "deploy");

    private MavenLifecycle() {}
}
