import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Editora plugin-registry signing tool (Ed25519, JDK-only — no dependencies). Run with the single-file
 * launcher, e.g. {@code java scripts/PluginSigningTool.java <cmd> ...}. The encodings match the in-app
 * verifier ({@code com.editora.plugin.PluginSignature}): public key = base64 X.509, private key = base64
 * PKCS#8, signature = base64 Ed25519 over the exact file bytes.
 *
 * <pre>
 *   keygen &lt;public-out&gt; &lt;private-out&gt;    generate a keypair (KEEP the private file SECRET)
 *   sign   &lt;private-key&gt; &lt;file&gt;           write &lt;file&gt;.sig (detached signature over the file bytes)
 *   verify &lt;public-key&gt; &lt;file&gt; &lt;sig&gt;      print OK/FAIL
 * </pre>
 */
public class PluginSigningTool {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "keygen" -> keygen(args[1], args[2]);
            case "sign" -> sign(args[1], args[2]);
            case "verify" -> verify(args[1], args[2], args[3]);
            default -> usage();
        }
    }

    private static void keygen(String pubOut, String privOut) throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Base64.Encoder b64 = Base64.getEncoder();
        Files.writeString(Path.of(pubOut), b64.encodeToString(kp.getPublic().getEncoded()) + "\n");
        Files.writeString(Path.of(privOut), b64.encodeToString(kp.getPrivate().getEncoded()) + "\n");
        try {
            Files.setPosixFilePermissions(Path.of(privOut),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX FS (Windows); the caller must protect the file themselves
        }
        System.out.println("Wrote public key  -> " + pubOut + "  (bundle this in the app + registry)");
        System.out.println("Wrote private key -> " + privOut + "  (KEEP SECRET — never commit)");
    }

    private static void sign(String privKeyFile, String file) throws Exception {
        byte[] der = Base64.getDecoder().decode(Files.readString(Path.of(privKeyFile)).strip());
        PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(Files.readAllBytes(Path.of(file)));
        String sig = Base64.getEncoder().encodeToString(s.sign());
        Path out = Path.of(file + ".sig");
        Files.writeString(out, sig + "\n");
        System.out.println("Wrote " + out);
    }

    private static void verify(String pubKeyFile, String file, String sigFile) throws Exception {
        byte[] der = Base64.getDecoder().decode(Files.readString(Path.of(pubKeyFile)).strip());
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        Signature s = Signature.getInstance("Ed25519");
        s.initVerify(key);
        s.update(Files.readAllBytes(Path.of(file)));
        boolean ok = s.verify(Base64.getDecoder().decode(Files.readString(Path.of(sigFile)).strip()));
        System.out.println(ok ? "OK — signature is valid" : "FAIL — signature does NOT verify");
        if (!ok) {
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("""
                Editora plugin signing tool (Ed25519)
                  java scripts/PluginSigningTool.java keygen <public-out> <private-out>
                  java scripts/PluginSigningTool.java sign   <private-key> <file>
                  java scripts/PluginSigningTool.java verify <public-key> <file> <sig>""");
    }
}
