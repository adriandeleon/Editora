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
}
