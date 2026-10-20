package com.editora.crypto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

/**
 * The GnuPG command line, run on <b>this</b> machine (never a remote host: the keys and gpg-agent are here),
 * bytes in and bytes out, so plaintext never touches a temporary file. Every run adds {@code --batch} and
 * {@code --status-fd 2}; decisions are taken from the status lines ({@link GpgStatus}), never from gpg's
 * translated messages.
 *
 * <p>Passphrases: with {@code null}, gpg-agent asks through its own pinentry dialog and Editora never sees the
 * passphrase. With a passphrase, it is handed over by loopback pinentry ({@code --passphrase-fd 0}) as the first
 * line of stdin, followed by the data — Editora's own prompt, used for passphrase-only files and when the agent
 * has no graphical way to ask. The bytes are wiped after the run. Blocking: never call on the FX thread.
 */
public final class Gpg {

    /** How long a run that cannot be waiting for the user may take. */
    static final Duration QUICK = Duration.ofSeconds(60);

    /** How long a run that may show the agent's passphrase prompt may take (the user is typing). */
    static final Duration INTERACTIVE = Duration.ofMinutes(5);

    private final String executable;
    private final Map<String, String> env;

    /**
     * @param executable the gpg program (see {@link #resolve})
     * @param env        extra environment for every run ({@code GNUPGHOME} in tests), usually empty
     */
    public Gpg(String executable, Map<String, String> env) {
        this.executable = executable;
        this.env = Map.copyOf(env);
    }

    public String executable() {
        return executable;
    }

    /**
     * The gpg program to run: {@code configured} when it is set, else {@code gpg} found on the augmented PATH or
     * in the places the installers put it (GPG Suite, Gpg4win, Git for Windows), else the bare name, so a
     * missing gpg fails to start and says so.
     */
    public static String resolve(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String onPath = ProcessRunner.resolveExecutable(List.of("gpg")).get(0);
        if (!"gpg".equals(onPath)) {
            return onPath;
        }
        for (Path candidate : installLocations(windows)) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return windows ? "gpg.exe" : "gpg";
    }

    /** Where the GnuPG installers put the program, for a GUI-launched app whose PATH does not include it. */
    static List<Path> installLocations(boolean windows) {
        List<Path> out = new ArrayList<>();
        if (windows) {
            for (String var : List.of("ProgramFiles(x86)", "ProgramFiles", "ProgramW6432")) {
                String base = System.getenv(var);
                if (base != null && !base.isBlank()) {
                    out.add(Path.of(base, "GnuPG", "bin", "gpg.exe")); // Gpg4win
                    out.add(Path.of(base, "Git", "usr", "bin", "gpg.exe")); // Git for Windows
                }
            }
        } else {
            out.add(Path.of("/usr/local/MacGPG2/bin/gpg")); // GPG Suite
            out.add(Path.of("/opt/homebrew/bin/gpg"));
            out.add(Path.of("/usr/local/bin/gpg"));
            out.add(Path.of("/usr/bin/gpg"));
        }
        return out;
    }

    /** One finished run: gpg's stdout, its parsed status, and why it failed (null when it succeeded). */
    public record Outcome(
            byte[] out, GpgStatus status, @Nullable GpgFailure failure) {
        public boolean ok() {
            return failure == null;
        }

        public String text() {
            return new String(out, StandardCharsets.UTF_8);
        }
    }

    /** The first line of {@code gpg --version} ("gpg (GnuPG) 2.4.7"), or null when gpg does not run. */
    public @Nullable String version() {
        Outcome o = run(List.of("--version"), null, false, null);
        if (!o.ok()) {
            return null;
        }
        String text = o.text().strip();
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl).strip();
    }

    /** Every public key, marked with whether its secret key is here; empty when gpg does not run. */
    public List<GpgKey> keys() {
        Outcome pub = run(
                List.of("--with-colons", "--fixed-list-mode", "--with-subkey-fingerprints", "--list-keys"),
                null,
                false,
                null);
        if (!pub.ok() && pub.out().length == 0) {
            return List.of();
        }
        Outcome sec = run(
                List.of("--with-colons", "--fixed-list-mode", "--with-subkey-fingerprints", "--list-secret-keys"),
                null,
                false,
                null);
        return KeyListing.parse(pub.text(), sec.text());
    }

    /** Who {@code message} is encrypted for, read without decrypting it. */
    public PacketInfo inspect(byte[] message) {
        Outcome o = run(List.of("--list-only", "--list-packets"), message, false, null);
        return PacketInfo.parse(o.text());
    }

    /**
     * Decrypts {@code message}. {@code passphrase} null lets gpg-agent ask (its pinentry); otherwise it is
     * handed over by loopback. The status says who signed it, when it was signed.
     */
    public Outcome decrypt(byte[] message, char[] passphrase, ProcessRunner.Cancellation cancel) {
        List<String> args = new ArrayList<>(passphraseArgs(passphrase));
        args.addAll(List.of("--output", "-", "--decrypt"));
        return run(args, withPassphrase(passphrase, message), passphrase == null, cancel);
    }

    /**
     * Encrypts {@code plain} for {@code recipients} (fingerprints). {@code --trust-model always}: the picker has
     * already shown each key's validity and warned about unverified ones, and in batch mode gpg would otherwise
     * refuse them with no way to ask.
     */
    public Outcome encrypt(byte[] plain, List<String> recipients, boolean armor) {
        List<String> args = new ArrayList<>(List.of("--yes", "--trust-model", "always"));
        if (armor) {
            args.add("--armor");
        }
        for (String r : recipients) {
            args.add("--recipient");
            args.add(r);
        }
        args.addAll(List.of("--output", "-", "--encrypt"));
        return run(args, plain, false, null);
    }

    /** Encrypts {@code plain} with a passphrase only. */
    public Outcome encryptSymmetric(byte[] plain, char[] passphrase, boolean armor) {
        List<String> args = new ArrayList<>(passphraseArgs(passphrase));
        args.add("--yes");
        if (armor) {
            args.add("--armor");
        }
        args.addAll(List.of("--output", "-", "--symmetric"));
        return run(args, withPassphrase(passphrase, plain), passphrase == null, null);
    }

    /**
     * Decrypts into {@code output} — the user's explicit choice of a plain file, for content too large or not
     * text enough for the editor. Reads {@code input} (a local file) when given, else {@code message} on stdin.
     */
    public Outcome decryptToFile(Path input, byte[] message, Path output, char[] passphrase) {
        List<String> args = new ArrayList<>(passphraseArgs(passphrase));
        args.addAll(List.of("--yes", "--output", output.toString(), "--decrypt"));
        if (input != null && passphrase == null) {
            args.add(input.toString());
            return run(args, null, true, null);
        }
        byte[] data = message;
        if (input != null) {
            try {
                data = Files.readAllBytes(input);
            } catch (IOException e) {
                return new Outcome(
                        new byte[0],
                        GpgStatus.parse(""),
                        new GpgFailure(GpgFailure.Kind.OTHER, List.of(), e.getMessage()));
            }
        }
        return run(args, withPassphrase(passphrase, data), passphrase == null, null);
    }

    /** Wraps {@code text} in a clear-text signature by {@code signer} (null: gpg's default key). */
    public Outcome clearSign(byte[] text, String signer, char[] passphrase) {
        List<String> args = new ArrayList<>(passphraseArgs(passphrase));
        addSigner(args, signer);
        args.addAll(List.of("--output", "-", "--clearsign"));
        return run(args, withPassphrase(passphrase, text), passphrase == null, null);
    }

    /** A detached signature over {@code data} by {@code signer} (null: gpg's default key). */
    public Outcome detachSign(byte[] data, String signer, char[] passphrase, boolean armor) {
        List<String> args = new ArrayList<>(passphraseArgs(passphrase));
        addSigner(args, signer);
        if (armor) {
            args.add("--armor");
        }
        args.addAll(List.of("--output", "-", "--detach-sign"));
        return run(args, withPassphrase(passphrase, data), passphrase == null, null);
    }

    /** Checks a clear-signed or inline-signed text. */
    public Outcome verify(byte[] signed) {
        return run(List.of("--verify"), signed, false, null);
    }

    /**
     * Checks a detached {@code signature} over {@code data}. The signature goes through a temporary file (gpg
     * reads the data from stdin, and a signature holds nothing secret); the data never touches the disk.
     */
    public Outcome verifyDetached(byte[] signature, byte[] data) throws IOException {
        Path sig = Files.createTempFile("editora-sig-", ".sig");
        try {
            Files.write(sig, signature);
            return run(List.of("--verify", sig.toString(), "-"), data, false, null);
        } finally {
            Files.deleteIfExists(sig);
        }
    }

    /** Imports the keys in {@code keys} (armored or binary). */
    public Outcome importKeys(byte[] keys) {
        return run(List.of("--import"), keys, false, null);
    }

    private static List<String> passphraseArgs(char[] passphrase) {
        return passphrase == null ? List.of() : List.of("--pinentry-mode", "loopback", "--passphrase-fd", "0");
    }

    private static void addSigner(List<String> args, String signer) {
        if (signer != null && !signer.isBlank()) {
            args.add("--local-user");
            args.add(signer);
        }
    }

    /**
     * stdin for a loopback run: the passphrase's UTF-8, a newline, then the data. gpg reads the first line as the
     * passphrase and the rest as its input.
     */
    static byte[] withPassphrase(char[] passphrase, byte[] data) {
        if (passphrase == null) {
            return data;
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(passphrase));
        int n = encoded.remaining();
        byte[] out = new byte[n + 1 + (data == null ? 0 : data.length)];
        encoded.get(out, 0, n);
        out[n] = '\n';
        if (data != null) {
            System.arraycopy(data, 0, out, n + 1, data.length);
        }
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        return out;
    }

    /** Whether {@code passphrase} can be handed over as one line (a newline would end it early). */
    public static boolean acceptable(char[] passphrase) {
        if (passphrase == null || passphrase.length == 0) {
            return false;
        }
        for (char c : passphrase) {
            if (c == '\n' || c == '\r') {
                return false;
            }
        }
        return true;
    }

    private Outcome run(
            List<String> args,
            byte @Nullable [] stdin,
            boolean interactive,
            ProcessRunner.@Nullable Cancellation cancel) {

        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.addAll(List.of("--batch", "--no-tty", "--status-fd", "2"));
        cmd.addAll(args);
        boolean loopback = args.contains("--passphrase-fd");
        try {
            ProcessRunner.BytesResult r = ProcessRunner.runBytesWithInput(
                    null, interactive ? INTERACTIVE : QUICK, cmd, env, stdin, interactive, cancel);
            if (ProcessRunner.cancelled(r)) {
                return new Outcome(
                        new byte[0], GpgStatus.parse(""), new GpgFailure(GpgFailure.Kind.CANCELLED, List.of(), ""));
            }
            boolean timedOut = ProcessRunner.timedOut(r);
            String startError = r.exit() == -1 && !timedOut ? r.err() : null;
            if (r.outTruncated()) {
                return new Outcome(
                        new byte[0],
                        GpgStatus.parse(r.err()),
                        new GpgFailure(GpgFailure.Kind.TOO_LARGE, List.of(), ""));
            }
            GpgStatus status = GpgStatus.parse(r.err());
            if (r.exit() == 0) {
                return new Outcome(r.out(), status, null);
            }
            return new Outcome(r.out(), status, GpgFailure.classify(r.exit(), r.err(), startError, timedOut));
        } finally {
            if (loopback && stdin != null) {
                Arrays.fill(stdin, (byte) 0); // the passphrase line
            }
        }
    }
}
