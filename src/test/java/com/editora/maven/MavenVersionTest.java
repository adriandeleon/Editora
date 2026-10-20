package com.editora.maven;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MavenVersion} against the orderings {@code org.apache.maven.artifact.versioning.ComparableVersion}
 * produces — including the real version lists that broke the semver comparator it replaces.
 */
class MavenVersionTest {

    private static void assertOlder(String older, String newer) {
        assertTrue(MavenVersion.compare(older, newer) < 0, older + " < " + newer);
        assertTrue(MavenVersion.compare(newer, older) > 0, newer + " > " + older);
        assertTrue(MavenVersion.isNewer(newer, older));
        assertFalse(MavenVersion.isNewer(older, newer));
    }

    private static void assertSame(String a, String b) {
        assertEquals(0, MavenVersion.compare(a, b), a + " = " + b);
        assertEquals(0, MavenVersion.compare(b, a), b + " = " + a);
        assertEquals(MavenVersion.of(a), MavenVersion.of(b));
        assertEquals(MavenVersion.of(a).hashCode(), MavenVersion.of(b).hashCode());
    }

    /** Guava's real history: the pre-Maven {@code r0N} releases must sit below every numbered one. */
    @Test
    void guavasAlphabeticEarlyReleasesSortBelowEveryNumberedOne() {
        List<String> guava = List.of(
                "r03",
                "r05",
                "r06",
                "r07",
                "r08",
                "r09",
                "10.0",
                "10.0.1",
                "11.0",
                "14.0.1",
                "19.0",
                "20.0",
                "23.0",
                "23.0-android",
                "23.0-jre",
                "33.0.0-jre",
                "33.0.0-android",
                "33.7.0-jre",
                "33.7.2-jre");
        assertOlder("r09", "33.0.0-jre");
        assertOlder("r09", "10.0");
        assertOlder("r08", "r09");
        assertOlder("33.0.0-jre", "33.7.2-jre");
        assertOlder("33.7.0-jre", "33.7.2-jre");
        assertEquals("33.7.2-jre", guava.stream().max(MavenVersion.ORDER).orElseThrow());
        assertEquals("r03", guava.stream().min(MavenVersion.ORDER).orElseThrow());
    }

    /** maven-surefire-plugin published 3.0.0-M<n> for years; the final 3.0.0 is newer than all of them. */
    @Test
    void milestonesSortBelowTheirRelease() {
        assertOlder("3.0.0-M9", "3.0.0");
        assertOlder("3.0.0-M1", "3.0.0-M9");
        assertOlder("3.0.0-M9", "3.0.0-RC1");
        assertOlder("2.22.2", "3.0.0-M1");
        assertOlder("7.1.0-M2", "7.1.0");
        assertOlder("8.0.0.Beta3", "8.0.0");
        assertOlder("5.0.0.Alpha2", "5.0.0");
    }

    @Test
    void knownQualifiersOrderAsMavenDoes() {
        List<String> ladder =
                List.of("1.0-alpha", "1.0-beta", "1.0-milestone", "1.0-rc", "1.0-SNAPSHOT", "1.0", "1.0-sp");
        for (int i = 1; i < ladder.size(); i++) {
            assertOlder(ladder.get(i - 1), ladder.get(i));
        }
        assertSame("1.0-cr1", "1.0-rc1");
        assertSame("1.0a1", "1.0-alpha-1");
        assertSame("1.0b2", "1.0-beta-2");
        assertSame("1.0m3", "1.0-milestone-3");
        assertOlder("1.0-alpha-1", "1.0-alpha-2");
        assertOlder("1.0-alpha-2", "1.0-alpha-10");
    }

    @Test
    void finalGaAndReleaseMeanNoQualifier() {
        assertSame("2.0.0.Final", "2.0.0");
        assertSame("2.0.0.GA", "2.0.0");
        assertSame("2.0.0-RELEASE", "2.0.0");
        assertOlder("2.0.0.Final", "2.0.1");
        assertOlder("2.0.0-RC1", "2.0.0.Final");
    }

    @Test
    void snapshotsSortBelowTheirRelease() {
        assertOlder("1.0-SNAPSHOT", "1.0");
        assertOlder("1.0-rc1", "1.0-SNAPSHOT");
        assertOlder("1.0-SNAPSHOT", "1.0.1-SNAPSHOT");
    }

    @Test
    void unknownQualifiersSortAboveKnownOnesByText() {
        assertOlder("1.0-rc1", "1.0-jre");
        assertOlder("1.0-android", "1.0-jre");
        assertOlder("1.0-sp1", "1.0-jre");
        // and a number still beats any of them
        assertOlder("1.0-jre", "1.0.1");
        assertOlder("1.0-jre", "1.1");
    }

    @Test
    void trailingZerosAreInsignificant() {
        assertSame("1", "1.0");
        assertSame("1.0", "1.0.0");
        assertSame("1.0-ga", "1");
        assertSame("1.0.0-jre", "1-jre");
        assertOlder("1", "1.0.1");
    }

    @Test
    void numbersCompareNumericallyNotLexically() {
        assertOlder("1.9", "1.10");
        assertOlder("3.9.0", "3.14.0");
        assertOlder("5.13.4", "6.0.0");
        assertOlder("1.78", "1.78.1");
        assertOlder("1.0.0", "1.0.0.1");
        assertOlder("99999999999999999999", "100000000000000000000");
    }

    @Test
    void caseDoesNotMatter() {
        assertSame("1.0-SNAPSHOT", "1.0-snapshot");
        assertSame("1.0.0.FINAL", "1.0.0.final");
        assertSame("3.0.0-M9", "3.0.0-m9");
    }

    @Test
    void blankAndNullAreTheEmptyVersion() {
        assertSame(null, "");
        assertSame("", "0");
        assertOlder("", "1");
        assertOlder(null, "0.1");
    }

    @Test
    void theComparatorSortsAWholeListTheWayMavenWould() {
        List<String> shuffled = new ArrayList<>(
                List.of("3.0.0", "3.0.0-M9", "2.22.2", "3.0.0-M1", "3.5.4", "3.0.0-RC1", "3.0.0-SNAPSHOT"));
        shuffled.sort(MavenVersion.ORDER);
        assertEquals(
                List.of("2.22.2", "3.0.0-M1", "3.0.0-M9", "3.0.0-RC1", "3.0.0-SNAPSHOT", "3.0.0", "3.5.4"), shuffled);
    }

    @Test
    void leadingNumberIsTheFirstDigitRun() {
        assertEquals(33, MavenVersion.leadingNumber("33.7.2-jre"));
        assertEquals(3, MavenVersion.leadingNumber("3.0.0-M9"));
        assertEquals(1, MavenVersion.leadingNumber("1"));
        assertEquals(-1, MavenVersion.leadingNumber("r09"));
        assertEquals(-1, MavenVersion.leadingNumber("RELEASE"));
        assertEquals(-1, MavenVersion.leadingNumber("${surefire.version}"));
        assertEquals(-1, MavenVersion.leadingNumber(""));
        assertEquals(-1, MavenVersion.leadingNumber(null));
    }
}
