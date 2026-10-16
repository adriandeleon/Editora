package com.editora.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Which {@code .env} a {@code $dotenv} read may use (G3): only a regular file inside the request's folder, of at
 * most {@link DynamicVars#MAX_DOTENV_BYTES} — the containment body files already had. A cloned repository could
 * otherwise link {@code .env} to {@code ~/.aws/credentials}, or to {@code /dev/zero}, which filled the heap as soon as
 * the editor checked the file's {@code {{…}}} references.
 */
class DynamicVarsDotenvTest {

    private static final ZonedDateTime NOW = ZonedDateTime.now();

    private static String read(String key, Path dir, String[] why) {
        return DynamicVars.resolve("$dotenv " + key, NOW, dir, why);
    }

    private static void link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException noLinks) {
            assumeTrue(false, "symbolic links are not available here");
        }
    }

    @Test
    void aRegularDotenvBesideTheFileIsRead(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), "KEY=one\n");
        assertEquals("one", read("KEY", dir, null));
        Files.writeString(dir.resolve(".env"), "KEY=second value\n"); // changed: read again, not served stale
        assertEquals("second value", read("KEY", dir, null));
        String[] why = new String[1];
        assertNull(read("OTHER", dir, why));
        assertEquals(tr("http.unresolved.reason.dotenv"), why[0]);
    }

    @Test
    void aLinkInsideTheFolderIsFollowed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("shared.env"), "KEY=inside\n");
        link(dir.resolve(".env"), dir.resolve("shared.env"));
        assertEquals("inside", read("KEY", dir, null));
    }

    @Test
    void aLinkOutsideTheFolderIsNotRead(@TempDir Path dir, @TempDir Path outside) throws Exception {
        Files.writeString(outside.resolve("credentials"), "aws_secret_access_key = fake-outside\n");
        link(dir.resolve(".env"), outside.resolve("credentials"));
        String[] why = new String[1];
        assertNull(read("aws_secret_access_key", dir, why));
        assertEquals(tr("http.unresolved.reason.dotenvRefused"), why[0]);
        assertNull(DynamicVars.dotenvValues(dir));
    }

    @Test
    void aLinkToADeviceIsNotRead(@TempDir Path dir) {
        Path zero = Path.of("/dev/zero");
        assumeTrue(Files.exists(zero), "needs /dev/zero");
        link(dir.resolve(".env"), zero);
        String[] why = new String[1];
        assertNull(read("KEY", dir, why), "nothing is read from a device (it used to fill the heap)");
        assertEquals(tr("http.unresolved.reason.dotenvRefused"), why[0]);
        // The editor's reference check goes through the same read: it reports the reference instead of failing.
        var problems = HttpVariableIndex.unresolved(
                "GET http://h.test/?k={{$dotenv KEY}}\n", new HttpVariableIndex.Context(Map.of(), dir), NOW);
        assertEquals(1, problems.size());
        assertEquals(tr("http.unresolved.reason.dotenvRefused"), problems.get(0).reason());
    }

    @Test
    void aDotenvLargerThanTheCapIsNotRead(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), "KEY=v\n" + "#".repeat(DynamicVars.MAX_DOTENV_BYTES) + "\n");
        assertNull(read("KEY", dir, new String[1]));
    }

    @Test
    void noDotenvIsNoValue(@TempDir Path dir) {
        assertEquals(Map.of(), DynamicVars.dotenvValues(dir));
        assertEquals(Map.of(), DynamicVars.dotenvValues(null));
    }
}
