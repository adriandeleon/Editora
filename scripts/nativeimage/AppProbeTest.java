package nativeimage;

import static lib.Assert.assertEquals;
import static lib.Assert.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import lib.Json;
import lib.Test;
import lib.TempDir;

class AppProbeTest implements AutoCloseable {

    private final TempDir temp = new TempDir();

    @Override
    public void close() {
        temp.close();
    }

    @Test
    void discoveryWaitsForCompleteJson() throws Exception {
        Path path = temp.resolve("endpoint.json");
        assertNull(AppProbe.publishedJson(path), "missing file");
        for (String partial : List.of("", "{", "{\"url\":")) {
            Files.writeString(path, partial);
            assertNull(AppProbe.publishedJson(path), "partial JSON <" + partial + ">");
        }
        var expected = new LinkedHashMap<String, Object>();
        expected.put("url", "http://127.0.0.1:1234/mcp");
        expected.put("token", "fixture");
        Files.writeString(path, Json.write(expected));
        assertEquals(expected, AppProbe.publishedJson(path));
    }
}
