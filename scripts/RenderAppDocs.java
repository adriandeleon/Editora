import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps a local build's bundled user guide ({@code .docs-site/}) up to date, so {@code javafx:run} and a local
 * {@code -Pdist} open Help ▸ Documentation from the app the way a release does.
 *
 * <p>Run by the Maven build at {@code generate-resources} (the {@code exec-maven-plugin} execution
 * {@code render-app-docs} in {@code pom.xml}), before the resources step copies {@code docs.site.dir} into the
 * jar. The guide's source is this repository's {@code user-docs/}; the HTML comes from the website's own Astro
 * build ({@code scripts/build-app-docs.mjs} in editora-website), so this needs an editora-website checkout with
 * its {@code node_modules} ({@code npm ci}) and Node on the PATH. Without them it says so in one line and the
 * build carries whatever copy it has (none: the docs links go to the website, and the app says why).
 *
 * <p>It renders only when the copy is stale: a fingerprint of everything the render reads that changes in
 * practice (the contents of {@code user-docs/} and {@code pauta.json}, the version in {@code pom.xml}, the
 * website checkout's commit) is kept beside the folder in {@code <docs.site.dir>.stamp}, and a render runs when
 * it differs. Command titles come from the sources too but are not part of it, or every string change would cost
 * a render (about 30 s); {@code -Ddocs.render=force} renders anyway. The render goes to a sibling folder that
 * replaces the old copy only once complete, under a file lock, so two builds of one checkout don't render twice
 * or read half a folder. It never fails the build.
 *
 * <p>Usage: {@code java scripts/RenderAppDocs.java <Editora checkout> <docs.site.dir> [--website=<dir>]
 * [--mode=auto|force|skip]} (the pom passes {@code editora.website.dir} and {@code docs.render}). The website is the given directory, else {@code $EDITORA_WEBSITE}, else the first
 * {@code editora-website} folder beside the checkout or beside one of its parent folders (so
 * {@code ~/src/adl/test/Editora} finds {@code ~/src/adl/editora-website}). Skipped on CI ({@code CI} set): the
 * release workflow renders its own copy in its {@code docs-site} job.
 */
public final class RenderAppDocs {

    private static final String TAG = "[docs] ";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println(
                    "usage: RenderAppDocs <Editora checkout> <docs.site.dir> [--website=<dir>] [--mode=auto|force|skip]");
            System.exit(2);
        }
        Path editora = Path.of(args[0]).toAbsolutePath().normalize();
        Path out = Path.of(args[1]).toAbsolutePath().normalize();
        String websiteArg = "";
        String mode = "auto";
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("--website=")) {
                websiteArg = args[i].substring("--website=".length()).strip();
            } else if (args[i].startsWith("--mode=")) {
                mode = args[i].substring("--mode=".length()).strip().toLowerCase(Locale.ROOT);
            }
        }
        try {
            run(editora, out, websiteArg, mode);
        } catch (Exception e) {
            // Never fail the build over the guide: the app falls back to the website and says why.
            System.out.println(TAG + "Could not render the user guide (" + e + "); the build keeps the copy it has.");
        }
    }

    private static void run(Path editora, Path out, String websiteArg, String mode) throws Exception {
        if (mode.equals("skip") || mode.equals("false") || mode.equals("off")) {
            return;
        }
        if (!mode.equals("force") && System.getenv("CI") != null) {
            return;
        }
        Path website = website(editora, websiteArg);
        if (website == null) {
            System.out.println(TAG + "No editora-website checkout beside this one, so the build carries "
                    + (Files.isDirectory(out) ? "the user guide it has" : "no user guide (Help opens the website)")
                    + ". See docs/building-and-packaging.md.");
            return;
        }
        if (!Files.isDirectory(website.resolve("node_modules"))) {
            System.out.println(TAG + "Run `npm ci` in " + website + " to render the user guide into this build.");
            return;
        }
        String node = onPath("node");
        if (node == null) {
            System.out.println(TAG + "Node.js is not on the PATH, so the user guide is not rendered into this build.");
            return;
        }

        Path stampFile = out.resolveSibling(out.getFileName() + ".stamp");
        String stamp = fingerprint(editora, website);
        if (!mode.equals("force") && Files.isDirectory(out) && stamp.equals(read(stampFile))) {
            return; // up to date: the common case, and quiet
        }

        Path lockFile = out.resolveSibling(out.getFileName() + ".lock");
        try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = ch.lock()) {
            // Another build may have rendered it while this one waited.
            if (!mode.equals("force") && Files.isDirectory(out) && stamp.equals(read(stampFile))) {
                return;
            }
            System.out.println(TAG + "Rendering the user guide from " + website + " (about 30 s)…");
            Path fresh = out.resolveSibling(out.getFileName() + ".rendering");
            Path log = out.resolveSibling(out.getFileName() + ".log");
            deleteTree(fresh);
            ProcessBuilder pb = new ProcessBuilder(
                            node,
                            "scripts/build-app-docs.mjs",
                            "--editora",
                            editora.toString(),
                            "--out",
                            fresh.toString())
                    .directory(website.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            // The generators run `java`: this build's own JDK, whatever else is on the PATH.
            Path javaBin = Path.of(System.getProperty("java.home"), "bin");
            pb.environment().merge("PATH", javaBin.toString(), (old, bin) -> bin + java.io.File.pathSeparator + old);
            Process p = pb.start();
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("timed out; see " + log);
            }
            if (p.exitValue() != 0 || !Files.isRegularFile(fresh.resolve("app-docs.json"))) {
                deleteTree(fresh);
                System.out.println(TAG + "Rendering the user guide failed (exit " + p.exitValue()
                        + "); the build keeps the copy it has. Last lines of " + log + ":");
                List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
                lines.subList(Math.max(0, lines.size() - 15), lines.size())
                        .forEach(l -> System.out.println(TAG + "  " + l));
                return;
            }
            Path old = out.resolveSibling(out.getFileName() + ".old");
            deleteTree(old);
            if (Files.exists(out)) {
                Files.move(out, old, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.move(fresh, out, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(stampFile, stamp, StandardCharsets.UTF_8);
            deleteTree(old);
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            String summary = lines.isEmpty() ? "done" : lines.getLast();
            System.out.println(TAG + summary.replace(fresh.getFileName().toString(), out.getFileName().toString()));
        }
    }

    /** The website checkout to render with, or null when there is none that can. */
    static Path website(Path editora, String given) {
        List<Path> candidates = new ArrayList<>();
        if (!given.isEmpty()) {
            candidates.add(Path.of(given));
        }
        String env = System.getenv("EDITORA_WEBSITE");
        if (env != null && !env.isBlank()) {
            candidates.add(Path.of(env.strip()));
        }
        for (Path dir = editora.getParent(); dir != null; dir = dir.getParent()) {
            candidates.add(dir.resolve("editora-website"));
        }
        for (Path c : candidates) {
            if (Files.isRegularFile(c.resolve("scripts/build-app-docs.mjs"))) {
                return c.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /** What the render depends on, as one hash: see the class comment for what is in it and what is not. */
    static String fingerprint(Path editora, Path website) throws IOException, InterruptedException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        md.update(("version " + pomVersion(editora) + "\n").getBytes(StandardCharsets.UTF_8));
        md.update(("website " + gitHead(website) + "\n").getBytes(StandardCharsets.UTF_8));
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(editora.resolve("user-docs"))) {
            s.filter(Files::isRegularFile).forEach(files::add);
        }
        files.add(editora.resolve("src/main/resources/com/editora/pauta.json"));
        files.sort(null);
        for (Path f : files) {
            if (!Files.isRegularFile(f)) {
                continue;
            }
            md.update(editora.relativize(f).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(Files.readAllBytes(f));
            md.update((byte) 0);
        }
        return HexFormat.of().formatHex(md.digest()) + "\n";
    }

    private static String pomVersion(Path editora) throws IOException {
        String pom = Files.readString(editora.resolve("pom.xml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(pom);
        return m.find() ? m.group(1).strip() : "";
    }

    /** The website checkout's commit; a checkout git can't read counts as changed every time. */
    private static String gitHead(Path website) throws InterruptedException {
        try {
            Process p = new ProcessBuilder("git", "-C", website.toString(), "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String head = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return p.waitFor() == 0 ? head : "unknown " + System.nanoTime();
        } catch (IOException e) {
            return "unknown " + System.nanoTime();
        }
    }

    private static String onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : windows ? List.of(tool + ".exe", tool + ".cmd") : List.of(tool)) {
                Path p = Path.of(dir, name);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                    return p.toString();
                }
            }
        }
        return null;
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
