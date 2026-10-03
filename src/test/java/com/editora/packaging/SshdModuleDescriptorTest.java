package com.editora.packaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sshd.client.SshClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the hand-maintained JPMS descriptor for {@code sshd-osgi}
 * ({@code src/moditect/module-info-sshd-osgi.java}) against the jar it describes.
 *
 * <p>The descriptor is only <em>used</em> by the {@code -Pdist} jlink build, and an export that is missing
 * is not a link error — the package is simply inaccessible, and the failure is an {@code IllegalAccessError}
 * the first time SSHD (or Editora) touches it in a packaged app. It had already drifted once: 2.16.0 added
 * {@code org.apache.sshd.common.net} and the list stayed at the 2.15.0 set. So the rule is mechanical: the
 * descriptor exports exactly the packages the resolved jar contains, and a version bump that changes the set
 * fails here, on every platform, instead of in a release build.
 */
class SshdModuleDescriptorTest {

    private static final Path DESCRIPTOR =
            Path.of(System.getProperty("user.dir"), "src", "moditect", "module-info-sshd-osgi.java");

    private static Set<String> exportedPackages() throws IOException {
        Set<String> exports = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^\\s*exports\\s+([\\w.]+)\\s*;").matcher(Files.readString(DESCRIPTOR));
        while (m.find()) {
            exports.add(m.group(1));
        }
        return exports;
    }

    /** The packages that hold classes in the sshd-osgi jar actually on the test classpath. */
    private static Set<String> packagesInTheJar() throws Exception {
        Path jar = Path.of(SshClient.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        assertTrue(jar.getFileName().toString().startsWith("sshd-osgi-"), "SshClient was loaded from " + jar);
        Set<String> packages = new TreeSet<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            file.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.endsWith(".class") && !name.startsWith("META-INF/") && name.contains("/"))
                    .forEach(name -> packages.add(
                            name.substring(0, name.lastIndexOf('/')).replace('/', '.')));
        }
        return packages;
    }

    @Test
    void theDescriptorExportsExactlyThePackagesOfTheResolvedJar() throws Exception {
        Set<String> exports = exportedPackages();
        Set<String> packages = packagesInTheJar();

        Set<String> notExported = new TreeSet<>(packages);
        notExported.removeAll(exports);
        Set<String> notPresent = new TreeSet<>(exports);
        notPresent.removeAll(packages);

        assertEquals(
                Set.of(),
                notExported,
                "packages in the sshd-osgi jar that module-info-sshd-osgi.java does not export (add them)");
        assertEquals(
                Set.of(),
                notPresent,
                "module-info-sshd-osgi.java exports packages the sshd-osgi jar no longer has (jlink rejects "
                        + "an export of an absent package — remove them)");
    }

    @Test
    void theDescriptorKeepsTheRequiresThatJdepsDrops() throws IOException {
        String text = Files.readString(DESCRIPTOR);
        // jdeps omits this under --ignore-missing-deps; without it every SSHD class fails to initialise.
        assertTrue(text.contains("requires org.slf4j;"), "module-info-sshd-osgi.java lost `requires org.slf4j`");
        assertTrue(
                text.contains("org.apache.sshd.common.file.root.RootedFileSystemProvider"),
                "module-info-sshd-osgi.java lost its FileSystemProvider service declaration");
    }
}
