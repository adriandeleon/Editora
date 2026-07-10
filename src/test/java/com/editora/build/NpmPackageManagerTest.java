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
}
