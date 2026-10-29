package com.editora.install;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.io.LazyHttpClient;

/** Lets tests in other packages build an {@link InstallService} through its package-private test seam. */
public final class InstallTestAccess {

    private InstallTestAccess() {}

    /** An install service whose downloads go through {@code client}, each capped at {@code maxDownloadBytes}. */
    public static InstallService service(LazyHttpClient client, long maxDownloadBytes) {
        return new InstallService(client, maxDownloadBytes);
    }

    /**
     * Makes {@code service} install the JVM lemminx-maven bundle as it comes: no extra jars downloaded beside it
     * and no request sent to the extracted server — for tests whose "bundle" is a few text files in a zip.
     */
    public static void bareJvmLspZip(InstallService service) {
        service.setJvmLspExtraJarsForTest(id -> java.util.List.of());
        service.setJvmLspSmokeForTest((argv, dir) -> java.util.Optional.empty());
    }

    /** Makes {@code service} install pinned tools from {@code recipes} (hashes matching a test's own files). */
    public static void pinnedTools(
            InstallService service,
            java.util.function.Function<String, java.util.Optional<InstallCatalog.PinnedTool>> recipes) {
        service.setPinnedToolsForTest(recipes);
    }

    /**
     * Makes {@code service} report {@code present} as the prerequisites on PATH, {@code nodeVersion} as what
     * {@code node --version} prints, and run package-manager command lines through {@code commands} instead of a
     * real npm — for tests of the npm installs, which never run npm.
     */
    public static void packageManagers(
            InstallService service,
            java.util.Set<InstallCatalog.Prereq> present,
            java.util.function.Supplier<String> nodeVersion,
            java.util.function.Function<java.util.List<String>, com.editora.process.ProcessRunner.Result> commands) {
        service.setPrereqsForTest(present);
        service.setNodeVersionForTest(nodeVersion);
        service.setCommandsForTest(commands);
    }

    /**
     * What {@code npm install --prefix <prefix> vscode-langservers-extracted@4.10.0} leaves (measured with npm 11):
     * the package, TypeScript hoisted beside it, and the {@code .bin} launchers; {@code hoisted} false nests
     * TypeScript inside the package instead.
     */
    public static Path webServersLayout(Path prefix, boolean hoisted) throws Exception {
        Path modules = Files.createDirectories(prefix.resolve("node_modules"));
        Path pkg = Files.createDirectories(modules.resolve(InstallCatalog.WEB_LS_PACKAGE));
        Files.writeString(
                pkg.resolve("package.json"),
                "{\n  \"name\": \"vscode-langservers-extracted\",\n  \"version\": \"4.10.0\"\n}\n");
        Path bin = Files.createDirectories(pkg.resolve("bin"));
        Path ts = hoisted
                ? modules.resolve("typescript")
                : pkg.resolve("node_modules").resolve("typescript");
        Path lib = Files.createDirectories(ts.resolve("lib"));
        Files.writeString(lib.resolve("lib.es2020.full.d.ts"), "/// <reference lib=\"dom\" />\n");
        Files.writeString(lib.resolve("lib.dom.d.ts"), "interface Document {}\n");
        Files.writeString(lib.resolve("typescript.js"), "big");
        Path dotBin = Files.createDirectories(modules.resolve(".bin"));
        for (String id : List.of("html", "css", "json")) {
            Path launcher = bin.resolve(WebServerInstall.program(id));
            Files.writeString(launcher, "#!/usr/bin/env node\n");
            Files.writeString(dotBin.resolve(WebServerInstall.program(id)), "#!/usr/bin/env node\n");
            Files.writeString(dotBin.resolve(WebServerInstall.program(id) + ".cmd"), "@node %*\r\n");
        }
        return pkg;
    }
}
