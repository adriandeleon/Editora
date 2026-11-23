package com.editora;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The performance probe that {@code scripts/perf-ab.sh} runs before every release compiles against the current
 * tree. Nothing else builds it (it lives in {@code src/perf/java}, outside Maven's source roots, because it paces
 * input with real sleeps), so without this a rename in the editor or the test fixtures would only surface on
 * release day.
 */
class PerfProbeCompilesTest {

    @Test
    void probeCompilesAgainstTheCurrentClasses(@TempDir Path out) throws Exception {
        Path probe = Path.of("src/perf/java/com/editora/ui/PerfProbe.java");
        assertTrue(Files.isRegularFile(probe), "Run from the Maven project root");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "needs a JDK, not a JRE");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = javac.run(
                null,
                null,
                err,
                List.of(
                                "-nowarn",
                                "-proc:none",
                                "-encoding",
                                "UTF-8",
                                "-d",
                                out.toString(),
                                "-classpath",
                                System.getProperty("java.class.path"),
                                probe.toString())
                        .toArray(new String[0]));
        assertEquals(
                0,
                rc,
                () -> "src/perf/java/com/editora/ui/PerfProbe.java no longer compiles; fix it (reach an API that"
                        + " moved by reflection, so the probe still builds against the previous release too):\n"
                        + err.toString(StandardCharsets.UTF_8));
    }
}
