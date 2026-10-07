package com.editora.http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overwrite policy of a {@code .http} response redirect. A {@code >>!} used to replace any file under the
 * request file's folder with whatever came back — a 404 page, half a download — keeping no copy and saying
 * nothing. It still replaces, but only with a complete successful response, only after the previous content
 * was offered to the guard (Local History), and every file written or refused is reported.
 */
class ResponseRedirectsTest {

    @TempDir
    Path dir;

    private final List<String> written = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<Path> guarded = new ArrayList<>();

    private static List<HttpFile.Redirect> redirects(String request) {
        return HttpFile.parseRequest(request).redirects();
    }

    private ResponseRedirects.Guard guard(ResponseRedirects.Verdict verdict) {
        return target -> {
            guarded.add(target);
            try {
                return new ResponseRedirects.Decision(verdict, Files.readAllBytes(target));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
    }

    private void write(String operators, int status, String body, boolean truncated, ResponseRedirects.Guard guard) {
        ResponseRedirects.write(
                redirects("GET http://x.test/\n\n" + operators + "\n"),
                dir,
                status,
                body.getBytes(UTF_8),
                truncated,
                guard,
                written,
                warnings);
    }

    @Test
    void anErrorResponseNeverReplacesAnExistingFile() throws Exception {
        Path source = Files.createDirectories(dir.resolve("src")).resolve("Main.java");
        Files.writeString(source, "class Main {}");

        write(">>! src/Main.java", 404, "404 page not found", false, guard(ResponseRedirects.Verdict.KEPT_IN_HISTORY));

        assertEquals("class Main {}", Files.readString(source), "a 404 body must not replace the user's file");
        assertTrue(guarded.isEmpty(), "nothing is replaced, so nothing needs preserving");
        assertTrue(written.isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("src/Main.java") && warnings.get(0).contains("404"), warnings.get(0));
    }

    @Test
    void anIncompleteResponseNeverReplacesAnExistingFile() throws Exception {
        Path out = Files.writeString(dir.resolve("data.json"), "{\"complete\":true}");

        write(">>! data.json", 200, "{\"compl", true, guard(ResponseRedirects.Verdict.KEPT_IN_HISTORY));

        assertEquals("{\"complete\":true}", Files.readString(out));
        assertTrue(written.isEmpty());
        assertTrue(warnings.get(0).contains("incomplete"), warnings.get(0));
    }

    @Test
    void aSuccessfulForcedRedirectReplacesOnlyAfterTheGuardPreservedTheFile() throws Exception {
        Path out = Files.writeString(dir.resolve("out.json"), "previous");

        write(">>! out.json", 200, "fresh", false, guard(ResponseRedirects.Verdict.KEPT_IN_HISTORY));

        assertEquals(List.of(out), guarded, "the guard sees the file before it is replaced");
        assertEquals("fresh", Files.readString(out));
        assertEquals(1, written.size());
        assertTrue(written.get(0).contains("out.json") && written.get(0).contains("Local History"), written.get(0));
        assertTrue(warnings.isEmpty(), warnings.toString());
        try (var files = Files.list(dir)) {
            assertEquals(List.of(out), files.toList(), "no staging file is left behind");
        }
    }

    @Test
    void theReportSaysWhenThePreviousVersionWasNotKept() throws Exception {
        Path out = Files.writeString(dir.resolve("out.bin"), "previous");

        write(">>! out.bin", 200, "fresh", false, guard(ResponseRedirects.Verdict.NOT_KEPT));

        assertEquals("fresh", Files.readString(out));
        assertTrue(written.get(0).contains("not kept"), written.get(0));
    }

    @Test
    void aRefusingGuardLeavesTheFileAlone() throws Exception {
        Path out = Files.writeString(dir.resolve("open.txt"), "on disk");

        write(">>! open.txt", 200, "fresh", false, guard(ResponseRedirects.Verdict.REFUSED_UNSAVED_CHANGES));
        write(">>! open.txt", 200, "fresh", false, guard(ResponseRedirects.Verdict.REFUSED_NOT_PRESERVED));
        write(">>! open.txt", 200, "fresh", false, target -> null);

        assertEquals("on disk", Files.readString(out));
        assertTrue(written.isEmpty());
        assertEquals(3, warnings.size());
        assertTrue(warnings.get(0).contains("unsaved changes"), warnings.get(0));
        assertTrue(warnings.get(1).contains("Local History"), warnings.get(1));
    }

    @Test
    void aFileThatChangedAfterItWasPreservedIsNotReplaced() throws Exception {
        Path out = Files.writeString(dir.resolve("racing.txt"), "captured");

        write(">>! racing.txt", 200, "fresh", false, target -> {
            ResponseRedirects.Decision decision = new ResponseRedirects.Decision(
                    ResponseRedirects.Verdict.KEPT_IN_HISTORY, "captured".getBytes(UTF_8));
            try {
                Files.writeString(target, "edited since the capture");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return decision;
        });

        assertEquals("edited since the capture", Files.readString(out), "content nobody preserved is not replaced");
        assertTrue(written.isEmpty());
        assertTrue(warnings.get(0).contains("changed"), warnings.get(0));
    }

    @Test
    void aPlainRedirectCreatesAFileButNeverTouchesAnExistingOne() throws Exception {
        Path existing = Files.writeString(dir.resolve("kept.txt"), "kept");

        write(">> kept.txt\n>> new/created.txt", 200, "body", false, guard(ResponseRedirects.Verdict.KEPT_IN_HISTORY));

        assertEquals("kept", Files.readString(existing));
        assertEquals("body", Files.readString(dir.resolve("new/created.txt")));
        assertTrue(guarded.isEmpty());
        assertEquals(List.of("response saved to new/created.txt"), written);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("kept.txt") && warnings.get(0).contains("already exists"), warnings.get(0));
    }

    @Test
    void aNewFileIsStillWrittenForAnErrorResponseAndTheBytesAreVerbatim() throws Exception {
        byte[] body = {0, (byte) 0xFF, 10, 13, (byte) 0x80};
        ResponseRedirects.write(
                redirects("GET http://x.test/\n\n>>! error.bin\n"), dir, 500, body, false, null, written, warnings);

        assertArrayEquals(body, Files.readAllBytes(dir.resolve("error.bin")), "nothing existed, so nothing is lost");
        assertEquals(List.of("response saved to error.bin"), written);
    }

    @Test
    void aTargetOutsideTheFolderIsReportedNotSilentlySkipped() throws Exception {
        Path outside = dir.resolveSibling("outside-" + dir.getFileName() + ".txt");
        try {
            write(">>! ../" + outside.getFileName(), 200, "body", false, null);

            assertFalse(Files.exists(outside));
            assertTrue(written.isEmpty());
            assertTrue(warnings.get(0).contains("inside the request file's folder"), warnings.get(0));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void withoutAGuardAForcedRedirectStillWorksAndSaysNothingWasKept() throws Exception {
        Path out = Files.writeString(dir.resolve("out.txt"), "previous");

        write(">>! out.txt", 201, "fresh", false, null);

        assertEquals("fresh", Files.readString(out));
        assertTrue(written.get(0).contains("not kept"), written.get(0));
    }

    @Test
    void anUnsavedRequestFileWritesNothing() {
        ResponseRedirects.write(
                redirects("GET http://x.test/\n\n>>! out.txt\n"),
                null,
                200,
                new byte[0],
                false,
                null,
                written,
                warnings);
        assertTrue(written.isEmpty() && warnings.isEmpty());
    }
}
