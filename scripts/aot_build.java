// Build-time AOT-cache step for the `dist` profile (run by exec-maven-plugin with the build JDK).
//
// jpackage has already produced a jlink'd APP_IMAGE (phase 1). This helper:
//   1. trains a full-GUI AOT cache against the image's own runtime (so classes match the shipped
//      runtime exactly, path-independently) and drops it at $APPDIR/editora.aot — the launcher .cfg
//      already references it via -XX:AOTCache=$APPDIR/editora.aot;
//   2. strips the runtime's bin/ (the footprint we deferred from jLinkOptions so bin/java stayed
//      available for training — the jpackage native launcher uses libjli, never bin/java);
//   3. for an installer build, wraps the prepared image into the DMG/MSI/DEB via `jpackage
//      --app-image`; for an APP_IMAGE build, just copies the image to the destination.
//
// FAILURE-TOLERANT: training/inject/strip are best-effort — if the runner has no usable display and
// training fails, the build continues and the app simply ships without the cache (a missing
// -XX:AOTCache file degrades gracefully to a normal, uncached start). Only the installer wrap, the
// actual deliverable, fails the build on error.
//
// Args: <imageDir> <appName> <appVersion> <installerType> <iconPath|-> <destDir> <moduleMain>
//   Run via:  java scripts/aot_build.java target/aot-image Editora 1.0.0 DMG branding/editora.icns target/dist com.editora/com.editora.App

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class aot_build {

    public static void main(String[] rawArgs) throws Exception {
        if (rawArgs.length < 7) {
            System.err.println("[aot] usage: imageDir appName appVersion installerType iconPath destDir moduleMain");
            System.exit(2);
        }
        Path imageDir   = Path.of(rawArgs[0]);
        String appName  = rawArgs[1];
        String appVer   = rawArgs[2];
        String type     = rawArgs[3];
        String icon     = rawArgs[4];
        Path destDir    = Path.of(rawArgs[5]);
        String module   = rawArgs[6];

        if (!Files.isDirectory(imageDir)) {
            System.err.println("[aot] image dir not found: " + imageDir + " — skipping AOT step");
            return; // nothing to do; let the build proceed
        }

        // --- locate the image's java + its $APPDIR (the dir holding <appName>.cfg) ---
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path imageJava = find(imageDir, win ? "java.exe" : "java", p -> p.getParent() != null
                && p.getParent().getFileName().toString().equals("bin"));
        Path cfg = find(imageDir, appName + ".cfg", p -> true);

        // --- 1) train the AOT cache (best-effort) ---
        if (imageJava != null && cfg != null) {
            Path appDir = cfg.getParent();
            Path aot = appDir.resolve("editora.aot");
            trainBestEffort(imageJava, aot, module);
            if (Files.isRegularFile(aot)) {
                System.out.println("[aot] cache present: " + aot + " (" + (Files.size(aot) / (1024 * 1024)) + " MB)");
            } else {
                System.out.println("[aot] no cache produced — app will start uncached (graceful)");
            }
        } else {
            System.out.println("[aot] could not locate image java/cfg (java=" + imageJava + ", cfg=" + cfg
                    + ") — skipping training; app will start uncached");
        }

        // --- 2) strip the runtime bin/ (reclaim the footprint; the jpackage launcher uses libjli,
        //        never bin/java, so the whole dir can go) ---
        if (imageJava != null) {
            deleteRecursive(imageJava.getParent());
            System.out.println("[aot] stripped runtime bin/: " + imageJava.getParent());
        }

        // --- 3) deliver: copy (APP_IMAGE) or wrap into one or more installers ---
        // `type` may be a comma list (e.g. "DEB,RPM" on Linux) — train once, wrap each. Per-type
        // failures are non-fatal so a flaky RPM can't sink the DEB; only an all-failed result (or a
        // single-type platform's failure) fails the build.
        Files.createDirectories(destDir);
        Path imageRoot = imageRoot(imageDir, appName, win); // Editora.app (mac) or Editora dir

        // macOS "Open With": declare the SYSTEM UTIs public.text / public.source-code in the .app's
        // Info.plist so Finder offers Editora for any text/source file (see fixMacDocumentTypes).
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        if (mac) {
            fixMacDocumentTypes(imageRoot);
        }

        // Ensure the Linux app image carries OUR icon. The jpackage app-image phase can leave the
        // default JavaApp.png at lib/<name>.png (the panteleyev plugin's <icon> didn't take), and the
        // installer wrap runs `jpackage --app-image`, which IGNORES --icon — so the .deb would ship the
        // generic Java icon (the .desktop's Icon= points at this file). Overwrite it explicitly here.
        boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
        if (linux && icon != null && !icon.isBlank() && !"-".equals(icon) && icon.endsWith(".png")) {
            Path iconSrc = Path.of(icon);
            Path iconDst = imageRoot.resolve("lib").resolve(appName + ".png");
            if (Files.isRegularFile(iconSrc) && Files.isDirectory(iconDst.getParent())) {
                Files.copy(iconSrc, iconDst, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[aot] set Linux app icon -> " + iconDst);
            }
        }

        if ("APP_IMAGE".equalsIgnoreCase(type.trim())) {
            Path out = destDir.resolve(imageRoot.getFileName());
            deleteRecursive(out);
            copyRecursive(imageRoot, out);
            System.out.println("[aot] app image -> " + out);
        } else {
            int ok = 0, attempted = 0;
            for (String one : type.split(",")) {
                one = one.trim();
                if (one.isEmpty()) continue;
                attempted++;
                if (wrapInstaller(imageRoot, appName, appVer, one, icon, destDir)) ok++;
            }
            if (attempted > 0 && ok == 0) {
                System.err.println("[aot] every installer wrap failed — failing the build");
                System.exit(1);
            }
            if (ok < attempted) {
                System.out.println("[aot] " + ok + "/" + attempted
                        + " installers built; the rest failed (see log above)");
            }
        }
    }

    /** Run the GUI app against the image runtime with -XX:AOTCacheOutput; render one frame then exit. */
    private static void trainBestEffort(Path imageJava, Path aot, String module) {
        Path tmpCfg = null;
        try {
            tmpCfg = Files.createTempDirectory("editora-aot-train");
            List<String> cmd = new ArrayList<>();
            // On a headless Linux runner, a virtual X server is needed for JavaFX; wrap with xvfb-run.
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            boolean noDisplay = System.getenv("DISPLAY") == null || System.getenv("DISPLAY").isBlank();
            if (os.contains("linux") && noDisplay && which("xvfb-run") != null) {
                cmd.add("xvfb-run");
                cmd.add("-a");
            }
            cmd.add(imageJava.toString());
            cmd.addAll(List.of(
                    "-Xmx2g", "-XX:+UseSerialGC",
                    "--enable-native-access=javafx.graphics",
                    // Mirror the packaged launcher's javaOptions (see pom.xml dist profile): lets the macOS
                    // "Open With" handler reach the internal com.sun.glass.ui API. Harmless on other OSes.
                    // (com.editora.MacOpenFiles degrades gracefully without it, but include it so the cache
                    // matches the shipped app and covers the handler's classes.)
                    "--add-exports=javafx.graphics/com.sun.glass.ui=com.editora",
                    "-Dprism.maxvram=2G", "-Dprism.maxTextureSize=16384",
                    "-Deditora.aotTrainExit=true",
                    "-XX:AOTCacheOutput=" + aot,
                    "-m", module,
                    "--config-dir", tmpCfg.toString(), "--new-file"));
            System.out.println("[aot] training: " + String.join(" ", cmd));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).inheritIO().start();
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                System.out.println("[aot] training timed out — continuing without cache");
            }
        } catch (Exception e) {
            System.out.println("[aot] training failed (" + e + ") — continuing without cache");
        } finally {
            if (tmpCfg != null) deleteRecursive(tmpCfg);
        }
    }

    /** jpackage --app-image <image> --type <installer> ... (build JDK's jpackage). Returns true on
     *  success; the caller decides whether a failure is fatal (it is for a single-type platform). */
    private static boolean wrapInstaller(Path imageRoot, String appName, String appVer, String type,
                                      String icon, Path destDir) throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path jpackage = Path.of(System.getProperty("java.home"), "bin", win ? "jpackage.exe" : "jpackage");
        List<String> cmd = new ArrayList<>(List.of(
                jpackage.toString(),
                "--type", type,
                "--name", appName,
                "--app-version", appVer,
                "--vendor", "Editora",
                "--app-image", imageRoot.toString(),
                "--dest", destDir.toString()));
        if (icon != null && !icon.isBlank() && !"-".equals(icon) && Files.exists(Path.of(icon))) {
            cmd.add("--icon");
            cmd.add(icon);
        }
        // Without these, a Windows MSI installs to Program Files but creates NO Start Menu entry,
        // NO desktop shortcut, and NO install wizard — so it looks like "nothing installed". Add a
        // real wizard (dir chooser) + a Start Menu group + a desktop shortcut. Linux .deb/.rpm
        // likewise need --linux-shortcut for an application-menu entry. macOS DMG needs nothing
        // (drag-to-Applications).
        String t = type.toUpperCase(Locale.ROOT);
        if (t.equals("MSI") || t.equals("EXE")) {
            cmd.addAll(List.of("--win-menu", "--win-menu-group", appName,
                    "--win-shortcut", "--win-dir-chooser"));
        } else if (t.equals("DEB") || t.equals("RPM")) {
            cmd.add("--linux-shortcut");
            // DEB: ship an `editora` command on PATH via custom maintainer scripts (postinst/postrm in
            // packaging/linux). jpackage installs only /opt/<pkg>/bin/Editora, which isn't on PATH. The
            // RPM bundler uses a .spec (it ignores postinst/postrm), so the resource dir is DEB-only.
            if (t.equals("DEB")) {
                Path resDir = Path.of(System.getProperty("user.dir"), "packaging", "linux");
                if (Files.isDirectory(resDir)) {
                    cmd.add("--resource-dir");
                    cmd.add(resDir.toString());
                }
            }
        }
        System.out.println("[aot] wrapping installer: " + String.join(" ", cmd));
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        int code = p.waitFor();
        if (code != 0) {
            System.err.println("[aot] jpackage " + type + " wrap failed with exit " + code);
            return false;
        }
        System.out.println("[aot] installer -> " + destDir + " (" + type + ")");
        return true;
    }

    // ---- helpers ----

    /**
     * Rewrites the macOS app image's Info.plist so Editora appears in Finder's "Open With" menu for text
     * and source files. jpackage's --file-associations would declare per-extension CUSTOM UTIs
     * (com.editora.txt, …) conforming to public.data — but Launch Services never offers an app for a file
     * already typed as a system UTI (a .txt is public.plain-text, which does not conform to com.editora.txt),
     * so it wouldn't show up. Instead we declare a single CFBundleDocumentTypes entry using the SYSTEM UTIs
     * public.text + public.source-code: every text/source file conforms to one of those, so the app becomes
     * an eligible (Alternate-rank, so non-default) editor for all of them. Best-effort — a PlistBuddy failure
     * logs and continues (the app still runs, just without the association).
     *
     * <p><b>Must re-codesign afterward.</b> jpackage ad-hoc-signs the .app during the app-image build (before
     * this runs); editing Info.plist invalidates that seal (its hash is part of the signature), and macOS then
     * rejects the app as tampered ("could not verify … free of malware", spctl: "invalid Info.plist") and
     * won't launch it. So we re-run `codesign --force --deep --sign -` to re-seal the bundle with the modified
     * plist. (arm64 apps must be signed to run at all; ad-hoc is fine for a locally-built, un-notarized app.)
     */
    private static void fixMacDocumentTypes(Path imageRoot) {
        Path plist = imageRoot.resolve("Contents").resolve("Info.plist");
        if (!Files.isRegularFile(plist)) {
            System.out.println("[aot] no Info.plist at " + plist + " — skipping Open-With association");
            return;
        }
        String pb = "/usr/libexec/PlistBuddy";
        // Drop anything jpackage may have written (harmless no-ops if absent); tolerate failure.
        runQuiet(pb, "-c", "Delete :UTExportedTypeDeclarations", plist.toString());
        runQuiet(pb, "-c", "Delete :UTImportedTypeDeclarations", plist.toString());
        runQuiet(pb, "-c", "Delete :CFBundleDocumentTypes", plist.toString());
        boolean ok = runQuiet(
                pb,
                "-c", "Add :CFBundleDocumentTypes array",
                "-c", "Add :CFBundleDocumentTypes:0 dict",
                "-c", "Add :CFBundleDocumentTypes:0:CFBundleTypeName string Text or source file",
                "-c", "Add :CFBundleDocumentTypes:0:CFBundleTypeRole string Editor",
                "-c", "Add :CFBundleDocumentTypes:0:LSHandlerRank string Alternate",
                "-c", "Add :CFBundleDocumentTypes:0:LSItemContentTypes array",
                "-c", "Add :CFBundleDocumentTypes:0:LSItemContentTypes:0 string public.text",
                "-c", "Add :CFBundleDocumentTypes:0:LSItemContentTypes:1 string public.source-code",
                plist.toString());
        if (!ok) {
            System.out.println("[aot] Open-With: PlistBuddy edit failed — app ships without the file association");
            return;
        }
        System.out.println("[aot] Open-With: declared CFBundleDocumentTypes (public.text, public.source-code)");
        // Re-seal the bundle: the plist edit broke jpackage's ad-hoc signature, which macOS would reject.
        boolean signed = runQuiet("codesign", "--force", "--deep", "--sign", "-", imageRoot.toString());
        System.out.println(signed
                ? "[aot] Open-With: re-codesigned the app image (ad-hoc)"
                : "[aot] Open-With: codesign re-seal FAILED — the app may be rejected by Gatekeeper");
    }

    /** Runs a command, discarding output; returns true on exit 0. Never throws. */
    private static boolean runQuiet(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path imageRoot(Path imageDir, String appName, boolean win) throws IOException {
        // macOS: <imageDir>/Editora.app ; Linux/Windows: <imageDir>/Editora
        Path mac = imageDir.resolve(appName + ".app");
        if (Files.isDirectory(mac)) return mac;
        Path plain = imageDir.resolve(appName);
        if (Files.isDirectory(plain)) return plain;
        // fall back to the single child dir jpackage created
        try (Stream<Path> s = Files.list(imageDir)) {
            return s.filter(Files::isDirectory).findFirst().orElse(imageDir);
        }
    }

    private static Path find(Path root, String name, java.util.function.Predicate<Path> extra) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(name))
                    .filter(extra)
                    .findFirst().orElse(null);
        }
    }

    private static String which(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path p = Path.of(dir, exe);
            if (Files.isExecutable(p)) return p.toString();
        }
        return null;
    }

    private static void deleteRecursive(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> s = Files.walk(path)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static void copyRecursive(Path src, Path dst) throws IOException {
        // Files.walk does not descend into symlinks, but must copy them VERBATIM: the jlink runtime dedups
        // its legal/* license files as symlinks, and following them (turning each into a regular file) breaks
        // the app's codesign seal — macOS then rejects the delivered app as tampered. So recreate symlinks as
        // links, not copies. (The DMG path is unaffected: jpackage does its own symlink-preserving copy.)
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isSymbolicLink(p)) {
                    Files.createDirectories(target.getParent());
                    Files.deleteIfExists(target);
                    Files.createSymbolicLink(target, Files.readSymbolicLink(p));
                } else if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
