package com.editora;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot-version helpers. Between releases the pom carries a {@code -SNAPSHOT} suffix, so these
 * decide both what the UI shows (the toolbar badge) and what must be stripped before a version reaches
 * a versioned docs URL.
 */
class AppInfoTest {

    @Test
    void detectsTheSnapshotSuffix() {
        assertTrue(AppInfo.isSnapshot("0.9.8-SNAPSHOT"));
        assertTrue(AppInfo.isSnapshot("1.0.0-SNAPSHOT"));
        // Maven writes it upper-case, but don't be brittle about a hand-edited pom.
        assertTrue(AppInfo.isSnapshot("0.9.8-snapshot"));
        assertTrue(AppInfo.isSnapshot("  0.9.8-SNAPSHOT  "));
    }

    @Test
    void aReleaseVersionIsNotASnapshot() {
        assertFalse(AppInfo.isSnapshot("0.9.7"));
        assertFalse(AppInfo.isSnapshot("1.0.0"));
        // A pre-release tag is still a real release, not a development build.
        assertFalse(AppInfo.isSnapshot("0.9.8-rc1"));
        // The unfiltered/IDE fallback.
        assertFalse(AppInfo.isSnapshot("0.0.0"));
        assertFalse(AppInfo.isSnapshot(""));
        assertFalse(AppInfo.isSnapshot(null));
    }

    @Test
    void releaseVersionStripsOnlyTheSuffix() {
        assertEquals("0.9.8", AppInfo.releaseVersion("0.9.8-SNAPSHOT"));
        assertEquals("0.9.8", AppInfo.releaseVersion("0.9.8-snapshot"));
        assertEquals("0.9.8", AppInfo.releaseVersion("  0.9.8-SNAPSHOT  "));
    }

    @Test
    void releaseVersionLeavesANonSnapshotAlone() {
        assertEquals("0.9.7", AppInfo.releaseVersion("0.9.7"));
        // An rc must survive intact — it names a real published release.
        assertEquals("0.9.8-rc1", AppInfo.releaseVersion("0.9.8-rc1"));
        assertEquals("", AppInfo.releaseVersion(null));
    }

    @Test
    void theLiveVersionAgreesWithItsOwnHelpers() {
        // Whatever this build was filtered with, the two views must stay consistent.
        assertEquals(AppInfo.isSnapshot(AppInfo.VERSION), AppInfo.isSnapshot());
        assertEquals(AppInfo.releaseVersion(AppInfo.VERSION), AppInfo.releaseVersion());
        assertFalse(AppInfo.releaseVersion().isEmpty());
    }
}
