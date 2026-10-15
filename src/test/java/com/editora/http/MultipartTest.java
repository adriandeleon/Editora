package com.editora.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the multipart/form-data parse + build. */
class MultipartTest {

    private static final String BODY = """
            --X
            Content-Disposition: form-data; name="field"

            hello {{who}}
            --X
            Content-Disposition: form-data; name="file"; filename="a.txt"

            < ./a.txt
            --X--
            """;

    @Test
    void boundaryOfReadsTheContentType() {
        assertEquals("X", Multipart.boundaryOf("multipart/form-data; boundary=X"));
        assertEquals("abc", Multipart.boundaryOf("multipart/form-data; boundary=\"abc\""));
        assertTrue(Multipart.isMultipart("multipart/form-data; boundary=X"));
    }

    @Test
    void parsesInlineAndFileParts() {
        List<Multipart.Part> parts = Multipart.parse(BODY, "X");
        assertEquals(2, parts.size());
        assertEquals("form-data; name=\"field\"", parts.get(0).headers().get("Content-Disposition"));
        assertEquals("hello {{who}}", parts.get(0).inlineBody());
        assertEquals("./a.txt", parts.get(1).filePath());
    }

    @Test
    void buildSubstitutesInlineAndSlurpsFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "FILE-CONTENT");
        List<Multipart.Part> parts = Multipart.parse(BODY, "X");
        byte[] bytes = Multipart.build(parts, "X", dir, t -> t.replace("{{who}}", "world"));
        String wire = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(wire.contains("--X\r\n"), wire);
        assertTrue(wire.contains("hello world"), wire);
        assertTrue(wire.contains("FILE-CONTENT"), wire);
        assertTrue(wire.endsWith("--X--\r\n"), wire);
    }

    @Test
    void partHeadersAndFilePathsTakeVariables(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("up.txt"), "UP");
        String body = "--X\nContent-Disposition: form-data; name=\"{{field}}\"; filename=\"{{fname}}\"\n\n"
                + "< ./{{fname}}\n--X--";
        String wire = new String(
                Multipart.build(
                        Multipart.parse(body, "X"),
                        "X",
                        dir,
                        s -> s.replace("{{field}}", "doc").replace("{{fname}}", "up.txt")),
                StandardCharsets.UTF_8);
        assertTrue(wire.contains("name=\"doc\"; filename=\"up.txt\""), wire);
        assertTrue(wire.contains("\r\n\r\nUP\r\n"), wire);
    }

    @Test
    void blankLinesBeforeTheNextBoundaryAreNotPartOfTheValue() {
        List<Multipart.Part> parts =
                Multipart.parse("--X\nContent-Disposition: form-data; name=\"a\"\n\nhello\n\n\n--X--", "X");
        assertEquals("hello", parts.get(0).inlineBody());
    }

    @Test
    void aSubstitutedFilePartIsMarked() {
        List<Multipart.Part> parts =
                Multipart.parse("--X\nContent-Disposition: form-data; name=\"a\"\n\n<@ ./t.json\n--X--", "X");
        assertTrue(parts.get(0).substituteFile());
        assertEquals("./t.json", parts.get(0).filePath());
    }

    @Test
    void aQuotedBoundaryMayHoldASemicolon() {
        assertEquals("a;b", Multipart.boundaryOf("multipart/form-data; boundary=\"a;b\"; charset=utf-8"));
        assertEquals("plain", Multipart.boundaryOf("multipart/form-data; boundary=plain; charset=utf-8"));
    }

    @Test
    void aFilePartIsStreamedFromTheFileAndTheLengthIsTheSumOfThePieces(@TempDir Path dir) throws Exception {
        byte[] big = new byte[3 * 1024 * 1024 + 7];
        new java.util.Random(3).nextBytes(big);
        Files.write(dir.resolve("a.txt"), big);
        List<String> problems = new java.util.ArrayList<>();
        Multipart.Body body =
                Multipart.assemble(Multipart.parse(BODY, "X"), "X", dir, t -> t.replace("{{who}}", "world"), problems);

        assertEquals(List.of(), problems);
        assertEquals(
                List.of(dir.resolve("a.txt").toAbsolutePath().normalize()),
                body.streamedFiles().stream()
                        .map(f -> f.toAbsolutePath().normalize())
                        .toList(),
                "the file part is a reference, not bytes held in memory");
        byte[] all = body.toBytes();
        assertEquals(all.length, body.length());
        assertEquals(body.length(), body.publisher().contentLength(), "Content-Length stays known");
        // The wire form is the one build() always produced.
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                Multipart.build(Multipart.parse(BODY, "X"), "X", dir, t -> t.replace("{{who}}", "world")), all);
        String head = new String(all, 0, 120, StandardCharsets.ISO_8859_1);
        assertTrue(head.contains("hello world"), head);
    }

    @Test
    void theDigestFeedHashesTheWholeStreamedBody(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "file content");
        Multipart.Body body =
                Multipart.assemble(Multipart.parse(BODY, "X"), "X", dir, t -> t, new java.util.ArrayList<>());
        java.security.MessageDigest fed = java.security.MessageDigest.getInstance("SHA-256");
        body.feed(fed);
        assertEquals(
                java.util.HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(body.toBytes())),
                java.util.HexFormat.of().formatHex(fed.digest()));
    }

    @Test
    void theResolvedPartsSayWhatWentOut(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("t.json"), "{\"who\":\"{{who}}\"}");
        Files.writeString(dir.resolve("img.png"), "png");
        String form = "--X\nContent-Disposition: form-data; name=\"{{n}}\"\n\n<@ ./t.json\n"
                + "--X\nContent-Disposition: form-data; name=\"pic\"\n\n< ./{{f}}\n--X--";
        java.util.Map<String, String> vars = java.util.Map.of("who", "ada", "n", "meta", "f", "img.png");
        Multipart.Body body = Multipart.assemble(
                Multipart.parse(form, "X"),
                "X",
                dir,
                t -> HttpVars.substitute(t, vars, java.time.LocalDateTime.now()),
                new java.util.ArrayList<>());
        assertEquals("form-data; name=\"meta\"", body.parts().get(0).headers().get("Content-Disposition"));
        assertEquals("{\"who\":\"ada\"}", body.parts().get(0).text());
        assertEquals(
                dir.resolve("img.png").toAbsolutePath().normalize(),
                body.parts().get(1).file().toAbsolutePath().normalize());
    }

    @Test
    void aMissingFilePartIsAProblemAndLeavesThePartEmpty(@TempDir Path dir) throws Exception {
        List<String> problems = new java.util.ArrayList<>();
        Multipart.Body body = Multipart.assemble(Multipart.parse(BODY, "X"), "X", dir, t -> t, problems);
        assertEquals(List.of("multipart file not found: ./a.txt"), problems);
        assertEquals(List.of(), body.streamedFiles());
        assertEquals(body.toBytes().length, body.length());
    }
}
