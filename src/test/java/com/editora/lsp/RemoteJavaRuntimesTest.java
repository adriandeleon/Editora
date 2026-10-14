package com.editora.lsp;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link RemoteJavaRuntimes#parse}: the host scan's {@code path<TAB>JAVA_VERSION=…} lines. */
class RemoteJavaRuntimesTest {

    @Test
    void eachLineIsAJdkByItsRealPathAndMajor() {
        String out = "/usr/lib/jvm/java-21-openjdk\tJAVA_VERSION=\"21.0.4\"\n"
                + "/home/u/.sdkman/candidates/java/8.0.402-tem\tJAVA_VERSION=\"1.8.0_402\"\n";

        assertEquals(
                List.of(
                        new JavaRuntimes.Jdk(21, "/usr/lib/jvm/java-21-openjdk"),
                        new JavaRuntimes.Jdk(8, "/home/u/.sdkman/candidates/java/8.0.402-tem")),
                RemoteJavaRuntimes.parse(out));
    }

    @Test
    void whatTheShellPrintsBesideIsSkipped() {
        String out = "Welcome to the host!\n\tJAVA_VERSION=\"17\"\nrelative/jdk\tJAVA_VERSION=\"17\"\n"
                + "/opt/jdk\tJAVA_VERSION=\"\"\n/opt/ok\tJAVA_VERSION=\"25\"";

        assertEquals(List.of(new JavaRuntimes.Jdk(25, "/opt/ok")), RemoteJavaRuntimes.parse(out));
        assertEquals(List.of(), RemoteJavaRuntimes.parse(null));
    }
}
