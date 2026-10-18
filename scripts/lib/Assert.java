package lib;

import java.util.Collection;
import java.util.Objects;

/** The assertions of the script tests. A failed one throws {@link AssertionError}. */
public final class Assert {

    /** Thrown by {@link #assumeTrue} to skip the rest of a test, or every test of a class from its constructor. */
    public static final class Skipped extends RuntimeException {
        Skipped(String reason) {
            super(reason, null, false, false);
        }
    }

    /** A test body that may throw anything. */
    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    private Assert() {}

    public static void assumeTrue(boolean condition, String reason) {
        if (!condition) {
            throw new Skipped(reason);
        }
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            fail(message);
        }
    }

    public static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    public static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            fail((message == null ? "" : message + ": ") + "expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void assertNotEquals(Object unexpected, Object actual, String message) {
        if (Objects.equals(unexpected, actual)) {
            fail((message == null ? "" : message + ": ") + "did not expect <" + actual + ">");
        }
    }

    public static void assertNull(Object actual, String message) {
        assertEquals(null, actual, message);
    }

    public static void assertNotNull(Object actual, String message) {
        if (actual == null) {
            fail(message);
        }
    }

    public static void assertContains(String haystack, String needle) {
        if (haystack == null || !haystack.contains(needle)) {
            fail("expected to find <" + needle + "> in <" + haystack + ">");
        }
    }

    public static void assertNotContains(String haystack, String needle) {
        if (haystack != null && haystack.contains(needle)) {
            fail("did not expect <" + needle + "> in <" + haystack + ">");
        }
    }

    public static void assertContains(Collection<?> collection, Object item, String message) {
        if (!collection.contains(item)) {
            fail((message == null ? "" : message + ": ") + "expected <" + item + "> in " + collection);
        }
    }

    public static void assertMatches(String regex, String actual) {
        if (actual == null || !java.util.regex.Pattern.compile(regex).matcher(actual).find()) {
            fail("expected <" + actual + "> to match /" + regex + "/");
        }
    }

    /** Runs {@code body} and returns what it threw, failing if it threw nothing or something else. */
    public static <T extends Throwable> T assertThrows(Class<T> type, Body body) {
        try {
            body.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return type.cast(thrown);
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + thrown, thrown);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }
}
