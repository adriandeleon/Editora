package com.editora.editor.markdown;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The TLS path of the preview's image fetch: it connects to the address it was given, and the certificate it
 * is shown there must be the <em>named host's</em>, signed by something the JVM trusts.
 *
 * <p>No network: the server is a loopback {@code HttpsServer} with a throwaway self-signed certificate for
 * the made-up name {@code pinned.test}, made by this JDK's {@code keytool} into a temporary keystore. That
 * name resolves nowhere — the fetch never looks it up, which is the point of pinning. Trust is the test's
 * alone: the JVM's default {@code SSLContext} is swapped for one that trusts the throwaway certificate, and
 * put back after each test.
 */
class PinnedHttpTlsTest {

    private static final String NAME = "pinned.test";
    private static final byte[] BODY = "0123456789".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    static Path dir;

    private static HttpsServer server;
    private static SSLContext trusting;
    private static SSLContext original;
    private static final AtomicReference<String> hostHeader = new AtomicReference<>();

    @BeforeAll
    static void start() throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        if (!Files.isExecutable(keytool)) {
            keytool = keytool.resolveSibling("keytool.exe");
        }
        Assumptions.assumeTrue(Files.isExecutable(keytool), "this runtime has no keytool to make a certificate with");
        char[] pass = UUID.randomUUID().toString().toCharArray(); // of the throwaway keystore; never leaves the test
        Path store = dir.resolve("throwaway.p12");
        Process made = new ProcessBuilder(
                        keytool.toString(),
                        "-genkeypair",
                        "-alias",
                        "pinned",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-dname",
                        "CN=" + NAME,
                        "-ext",
                        "SAN=dns:" + NAME,
                        "-validity",
                        "2",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        store.toString(),
                        "-storepass",
                        new String(pass),
                        "-keypass",
                        new String(pass))
                .redirectErrorStream(true)
                .start();
        String said = new String(made.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!made.waitFor(60, TimeUnit.SECONDS) || made.exitValue() != 0) {
            throw new IllegalStateException("keytool could not make the test certificate: " + said);
        }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keys.load(in, pass);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, pass);
        SSLContext serving = SSLContext.getInstance("TLS");
        serving.init(kmf.getKeyManagers(), null, null);

        // What the client side trusts in these tests: that one certificate, and nothing else.
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("pinned", keys.getCertificate("pinned"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        trusting = SSLContext.getInstance("TLS");
        trusting.init(null, tmf.getTrustManagers(), null);
        original = SSLContext.getDefault();

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serving));
        server.createContext("/sized", exchange -> {
            hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            exchange.sendResponseHeaders(200, BODY.length);
            exchange.getResponseBody().write(BODY);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void untrust() {
        if (original != null) {
            SSLContext.setDefault(original);
        }
        hostHeader.set(null);
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static PinnedHttp.Response get(String host) throws IOException {
        URI uri = URI.create("https://" + host + ":" + server.getAddress().getPort() + "/sized");
        return PinnedHttp.get(uri, InetAddress.getLoopbackAddress(), 10_000, 1 << 20);
    }

    @Test
    void aTrustedCertificateForTheNamedHostIsAcceptedAtThePinnedAddress() throws Exception {
        SSLContext.setDefault(trusting);
        PinnedHttp.Response response = get(NAME);
        assertEquals(200, response.status());
        assertArrayEquals(BODY, response.body());
        assertEquals(
                NAME + ":" + server.getAddress().getPort(),
                hostHeader.get(),
                "the request names the host; the connection went to the address that was checked");
    }

    @Test
    void aCertificateForAnotherNameIsRejected() {
        SSLContext.setDefault(trusting); // trusted, and still not this host's
        assertThrows(SSLException.class, () -> get("other.test"), "the certificate is pinned.test's");
        assertThrows(SSLException.class, () -> get("pinned.test.evil.example"));
        assertThrows(
                SSLException.class,
                () -> get("127.0.0.1"),
                "nor is it the address's own: the name asked for is what must match");
        assertEquals(null, hostHeader.get(), "no request was sent over a connection that failed its check");
    }

    @Test
    void aCertificateTheJvmDoesNotTrustIsRejected() {
        // The JVM's own trust (whatever it holds, it does not hold a certificate made a moment ago).
        assertThrows(SSLException.class, () -> get(NAME));
        assertEquals(null, hostHeader.get());
    }
}
