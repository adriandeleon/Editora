package com.editora.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code App.main} sets {@code jdk.httpclient.allowRestrictedHeaders=host} for the whole process, so a {@code .http}
 * request can test a virtual host by IP. That also lets every other {@code java.net.http} client in Editora send a
 * {@code Host} header; this keeps it to the HTTP Client, the one place that takes headers from user content.
 */
class RestrictedHostHeaderTest {

    private static final Pattern SETS_HOST = Pattern.compile("(?i)\\.(header|setHeader|headers)\\(\\s*\"host\"");

    @Test
    void onlyTheHttpClientSetsAHostHeader() throws IOException {
        Path root = Path.of("src/main/java/com/editora");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.startsWith(root.resolve("http"))) {
                    continue;
                }
                if (SETS_HOST.matcher(Files.readString(f)).find()) {
                    offenders.add(root.relativize(f).toString());
                }
            }
        }
        assertEquals(List.of(), offenders, "only com.editora.http may set Host (allowRestrictedHeaders=host)");
    }
}
