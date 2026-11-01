package com.editora.install;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pom server bundle's jars lose their signature files (XML/pom review 4, A31): Eclipse's signature on the
 * lemminx jar, which adds classes to Jing's package, kept lemminx's RELAX NG plugin from loading in the JVM.
 */
class JarSignaturesTest {

    @TempDir
    Path dir;

    /** A jar shaped like Eclipse's: a manifest with digests, the signature pair, a class, and a stored entry. */
    static byte[] signedJar() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n\nName: a/B.class\nSHA-256-Digest: x\n");
            put(zip, "META-INF/ECLIPSE_.SF", "Signature-Version: 1.0\n");
            put(zip, "META-INF/ECLIPSE_.RSA", "signature block");
            put(zip, "META-INF/maven/org.eclipse.lemminx/pom.properties", "version=0.30.1\n");
            put(zip, "com/thaiopensource/relaxng/pattern/MySchemaPatternBuilder.class", "class bytes");
            byte[] stored = "stored".getBytes(StandardCharsets.UTF_8);
            ZipEntry entry = new ZipEntry("plain.txt");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(stored.length);
            CRC32 crc = new CRC32();
            crc.update(stored);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(stored);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static List<String> names(Path jar) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream().forEach(e -> names.add(e.getName()));
        }
        return names;
    }

    private static String read(Path jar, String name) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile());
                var in = zip.getInputStream(zip.getEntry(name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void onlyTopLevelSignatureFilesInMetaInfCount() {
        assertTrue(JarSignatures.isSignatureFile("META-INF/ECLIPSE_.SF"));
        assertTrue(JarSignatures.isSignatureFile("META-INF/ECLIPSE_.RSA"));
        assertTrue(JarSignatures.isSignatureFile("META-INF/X.DSA"));
        assertTrue(JarSignatures.isSignatureFile("META-INF/x.ec"));
        assertFalse(JarSignatures.isSignatureFile("META-INF/MANIFEST.MF"));
        assertFalse(JarSignatures.isSignatureFile("META-INF/maven/a/X.SF"), "not a signature: a nested file");
        assertFalse(JarSignatures.isSignatureFile("a/B.SF"));
    }

    @Test
    void aSignedJarIsRewrittenWithoutItsSignatureAndEverythingElseAsItWas() throws IOException {
        Path jar = Files.write(dir.resolve("org.eclipse.lemminx-0.30.1.jar"), signedJar());

        assertTrue(JarSignatures.signed(jar));
        assertTrue(JarSignatures.unsign(jar));

        assertFalse(JarSignatures.signed(jar));
        assertEquals(
                List.of(
                        "META-INF/MANIFEST.MF",
                        "META-INF/maven/org.eclipse.lemminx/pom.properties",
                        "com/thaiopensource/relaxng/pattern/MySchemaPatternBuilder.class",
                        "plain.txt"),
                names(jar),
                "the order is kept, the manifest first");
        assertEquals("class bytes", read(jar, "com/thaiopensource/relaxng/pattern/MySchemaPatternBuilder.class"));
        assertEquals("stored", read(jar, "plain.txt"));
        assertTrue(read(jar, "META-INF/MANIFEST.MF").startsWith("Manifest-Version: 1.0"));
        try (var list = Files.list(dir)) {
            assertEquals(List.of(jar), list.toList(), "no temporary file is left beside it");
        }
        assertFalse(JarSignatures.unsign(jar), "an unsigned jar is left alone");
    }

    /**
     * The JVM's own view: a jar without signature files has no signers, so it cannot clash with the unsigned Jing in
     * one package any more.
     */
    @Test
    void theRewrittenJarHasNoSigners() throws IOException {
        Path jar = Files.write(dir.resolve("a.jar"), signedJar());
        JarSignatures.unsign(jar);
        try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile(), true)) {
            java.util.jar.JarEntry entry =
                    file.getJarEntry("com/thaiopensource/relaxng/pattern/MySchemaPatternBuilder.class");
            try (var in = file.getInputStream(entry)) {
                in.readAllBytes(); // signers are known once the entry is read
            }
            assertEquals(null, entry.getCodeSigners());
        }
    }

    @Test
    void aBundleIsUnsignedOnceAndMarkedSoLaterStartsOnlyLookForTheMarker() throws IOException {
        Path bundle = Files.createDirectories(dir.resolve("plugins/lsp/maven"));
        Path lemminx = Files.write(bundle.resolve("org.eclipse.lemminx-0.30.1.jar"), signedJar());
        Path lsp4j = Files.write(bundle.resolve("org.eclipse.lsp4j-0.23.1.jar"), signedJar());
        Files.writeString(bundle.resolve("guava-33.7.2-jre.jar"), "PK not a zip"); // left as it is
        Files.writeString(bundle.resolve(InstallReceipt.FILE_NAME), "id=lemminx-maven\nversion=0.12.0\n");

        assertTrue(JarSignatures.ensureUnsigned(bundle));

        assertFalse(JarSignatures.signed(lemminx));
        assertFalse(JarSignatures.signed(lsp4j));
        assertEquals("PK not a zip", Files.readString(bundle.resolve("guava-33.7.2-jre.jar")));
        String marker = Files.readString(bundle.resolve(JarSignatures.MARKER));
        assertTrue(marker.contains("org.eclipse.lemminx-0.30.1.jar"), marker);

        Files.write(lemminx, signedJar()); // marked: not looked at again
        assertTrue(JarSignatures.ensureUnsigned(bundle));
        assertTrue(JarSignatures.signed(lemminx));
        assertFalse(JarSignatures.ensureUnsigned(dir.resolve("missing")));
    }

    @Test
    void onlyEditorasOwnBundleFoldersOnAClasspathAreRepaired() throws IOException {
        String sep = java.io.File.pathSeparator;
        Path bundle = dir.resolve("config/plugins/lsp/maven").toAbsolutePath();
        Path copy = dir.resolve("copy").toAbsolutePath();
        String main = InstallCatalog.LEMMINX_MAIN_CLASS;

        assertEquals(
                Set.of(bundle),
                JarSignatures.bundleFolders(List.of("java", "-cp", bundle + java.io.File.separator + "*", main)));
        assertEquals(
                Set.of(bundle),
                JarSignatures.bundleFolders(List.of(
                        "/jdk/bin/java",
                        "-Duser.home=/h",
                        "-cp",
                        bundle.resolve("org.eclipse.lemminx-0.30.1.jar") + sep + bundle.resolve("jing-20220510.jar"),
                        main)),
                "XML's Java fallback names the jars one by one");
        assertEquals(
                Set.of(),
                JarSignatures.bundleFolders(List.of("java", "-cp", copy + java.io.File.separator + "*", main)),
                "the user's own copy is not rewritten");
        assertEquals(Set.of(), JarSignatures.bundleFolders(List.of("/usr/bin/lemminx")));

        Path jar = Files.write(Files.createDirectories(bundle).resolve("org.eclipse.lemminx-0.30.1.jar"), signedJar());
        Path theirs = Files.write(Files.createDirectories(copy).resolve("org.eclipse.lemminx-0.30.1.jar"), signedJar());
        JarSignatures.repairBundleOf(List.of("java", "-cp", bundle + java.io.File.separator + "*", main));
        JarSignatures.repairBundleOf(List.of("java", "-cp", copy + java.io.File.separator + "*", main));
        assertFalse(JarSignatures.signed(jar));
        assertTrue(JarSignatures.signed(theirs));
    }
}
