package com.editora.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure coverage of package-manager detection precedence: packageManager field → lockfile → npm. */
class NpmPackageManagerTest {

    @Test
    void packageManagerFieldWinsOverLockfiles() {
        assertEquals("pnpm", NpmPackageManager.detect("pnpm@8", true, true, false, false));
        assertEquals("yarn", NpmPackageManager.detect("yarn@3.1.0", true, false, false, false));
    }

    @Test
    void lockfileUsedWhenNoField() {
        assertEquals("pnpm", NpmPackageManager.detect(null, false, false, true, false));
        assertEquals("yarn", NpmPackageManager.detect(null, false, true, false, false));
        assertEquals("bun", NpmPackageManager.detect(null, false, false, false, true));
        assertEquals("npm", NpmPackageManager.detect(null, true, false, false, false));
    }

    @Test
    void defaultsToNpmWithNothing() {
        assertEquals("npm", NpmPackageManager.detect(null, false, false, false, false));
        assertEquals("npm", NpmPackageManager.detect("  ", false, false, false, false));
    }

    @Test
    void fromFieldParsesTheNameBeforeTheAt() {
        assertEquals("yarn", NpmPackageManager.fromField("yarn@1.22.19"));
        assertEquals("bun", NpmPackageManager.fromField("bun"));
        assertNull(NpmPackageManager.fromField("something-else@1"));
        assertNull(NpmPackageManager.fromField(null));
    }

    // --- workspace walk (review C3) -------------------------------------------------------------

    private static NpmPackageManager.Detection walk(java.nio.file.Path dir, java.util.Map<String, String> files) {
        return NpmPackageManager.detect(
                dir,
                abs("/mono"),
                abs("/home/u"),
                p -> files.containsKey(key(p)),
                p -> java.util.Optional.ofNullable(files.get(key(p))));
    }

    /** A POSIX-style test path made absolute on this OS (a drive on Windows: the walk works on absolute paths). */
    private static java.nio.file.Path abs(String posix) {
        return java.nio.file.Path.of("/").toAbsolutePath().resolve(posix.substring(1));
    }

    /** The POSIX-style key of {@code p}, for the maps written below. */
    private static String key(java.nio.file.Path p) {
        java.nio.file.Path root = java.nio.file.Path.of("/").toAbsolutePath();
        return p.startsWith(root) ? "/" + root.relativize(p).toString().replace('\\', '/') : p.toString();
    }

    @Test
    void aWorkspaceMemberUsesItsRootsManager() {
        var files = java.util.Map.of(
                "/mono/package.json", "{\"packageManager\":\"pnpm@9.12.0\",\"workspaces\":[\"packages/*\"]}",
                "/mono/pnpm-lock.yaml", "",
                "/mono/packages/a/package.json", "{\"name\":\"a\"}");
        NpmPackageManager.Detection d = walk(abs("/mono/packages/a"), files);
        assertEquals("pnpm", d.manager());
        assertEquals("package.json", d.reason());
        assertEquals("pnpm (package.json)", d.describe());
        var yarnRoot = java.util.Map.of("/mono/yarn.lock", "", "/mono/packages/b/package.json", "{}");
        assertEquals("yarn (yarn.lock)", walk(abs("/mono/packages/b"), yarnRoot).describe());
    }

    @Test
    void theMembersOwnDecisionWinsAndTheWalkStopsAtTheProject() {
        var files = java.util.Map.of("/mono/pnpm-lock.yaml", "", "/mono/a/bun.lock", "", "/yarn.lock", "");
        assertEquals("bun", walk(abs("/mono/a"), files).manager());
        assertEquals("npm", walk(abs("/other/x"), files).manager(), "outside the project: its own");
        assertEquals("", walk(abs("/other/x"), files).reason());
    }

    @Test
    void anOverrideAppliesOnlyToItsOwnManager() {
        assertEquals(true, NpmPackageManager.overrideApplies("/opt/npm/bin/npm", "npm"));
        assertEquals(false, NpmPackageManager.overrideApplies("npm", "pnpm"), "npm must not replace pnpm");
        assertEquals(true, NpmPackageManager.overrideApplies("C:\\tools\\pnpm.cmd", "pnpm"));
        assertEquals(true, NpmPackageManager.overrideApplies("my-wrapper --quiet", "yarn"), "a wrapper: always");
        assertEquals(false, NpmPackageManager.overrideApplies("", "npm"));
    }
}
