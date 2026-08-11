package com.editora.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the AOT trainer's JVM options against the shipped launcher's.
 *
 * <p>{@code scripts/aot_build.java} launches the freshly jlinked image's own {@code bin/java} with a
 * hand-built option list, and that list has to mirror the {@code <javaOption>}s the jpackage launcher
 * ships — otherwise the cache is trained under a configuration the app never runs under. There is no
 * compiler linking the two: the trainer is a compact source file outside {@code src/main} and the
 * launcher's options are XML, so nothing but a test can notice them drifting apart.
 *
 * <p>It has drifted once already. The switch to G1 updated both pom option lists and missed the
 * trainer, which kept training under {@code -XX:+UseSerialGC} for a release. That one turned out to
 * cost little (measured: a GC mismatch does not invalidate the cache, and the archived heap data
 * follows the runtime GC rather than the training one), but it was invisible — the cache is present
 * and the right size either way, so nothing downstream reports it.
 */
class AotTrainerOptionsTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir"));

    /**
     * Options that are deliberately NOT mirrored, each mapped to why. Anything else appearing on one
     * side and not the other fails the test, so a new divergence has to be argued for here rather
     * than just happening.
     */
    private static final Map<String, String> EXPECTED_DIVERGENCE = Map.of(
            "-Dprism.order",
                    "the trainer pins a software-capable pipeline (es2,sw) because CI runners have "
                            + "virtualized GPUs; the macOS ARM runner's paravirtual Metal device aborts the "
                            + "process outright, which no Prism fallback can catch (see #399)",
            "-XX:AOTCache", "the launcher CONSUMES the cache; the trainer PRODUCES it via -XX:AOTCacheOutput",
            "-XX:AOTCacheOutput", "the trainer's counterpart to the launcher's -XX:AOTCache",
            "-Deditora.aotTrainExit", "trainer-only: renders one frame and exits, rather than waiting for a user");

    @Test
    void theTrainerAndTheShippedLauncherAgreeOnEveryOptionButTheDocumentedExceptions() throws IOException {
        Set<String> launcher = optionKeys(shippedJavaOptions());
        Set<String> trainer = optionKeys(trainerOptions());

        Set<String> exceptions = EXPECTED_DIVERGENCE.keySet();
        Set<String> launcherOnly = new LinkedHashSet<>(launcher);
        launcherOnly.removeAll(trainer);
        launcherOnly.removeAll(exceptions);
        Set<String> trainerOnly = new LinkedHashSet<>(trainer);
        trainerOnly.removeAll(launcher);
        trainerOnly.removeAll(exceptions);

        assertEquals(
                Set.of(),
                launcherOnly,
                "the packaged launcher passes these but the AOT trainer does not, so the cache is trained "
                        + "under a configuration the app never runs under. Add them to the cmd.addAll(...) list "
                        + "in scripts/aot_build.java, or record the divergence in EXPECTED_DIVERGENCE with a "
                        + "reason. Launcher=" + launcher + " trainer=" + trainer);
        assertEquals(
                Set.of(),
                trainerOnly,
                "the AOT trainer passes these but the packaged launcher does not. Trainer=" + trainer);
    }

    /**
     * The GC specifically, since that is the one that drifted and the one whose name is a whole option
     * rather than a key=value pair (so a set comparison of KEYS alone would not catch -XX:+UseSerialGC
     * against -XX:+UseG1GC).
     */
    @Test
    void theTrainerUsesTheSameGarbageCollectorAsTheShippedLauncher() throws IOException {
        Set<String> launcherGc = gcOptions(shippedJavaOptions());
        Set<String> trainerGc = gcOptions(trainerOptions());

        assertFalse(launcherGc.isEmpty(), "expected the dist profile to name a collector explicitly");
        assertEquals(
                launcherGc,
                trainerGc,
                "scripts/aot_build.java must train under the collector the packaged app runs under "
                        + "(pom.xml dist <javaOptions>)");
    }

    /** The pom's own two lists — jpackage's and javafx:run's — must not disagree either. */
    @Test
    void theDevRunAndThePackagedLauncherAgreeOnHeapAndGc() throws IOException {
        String pom = stripXmlComments(Files.readString(REPO.resolve("pom.xml"), StandardCharsets.UTF_8));
        Set<String> jpackage = heapAndGc(values(pom, "javaOption"));
        Set<String> devRun = heapAndGc(values(pom, "option"));

        assertFalse(jpackage.isEmpty(), "expected heap/GC options in the dist profile");
        assertEquals(
                jpackage,
                devRun,
                "mvn javafx:run and the packaged app should behave alike; CLAUDE.md pins dev == prod for "
                        + "heap and GC");
    }

    // --- sources -------------------------------------------------------------------------------

    /** The active {@code <javaOption>} values from the dist profile, XML comments removed. */
    private static Set<String> shippedJavaOptions() throws IOException {
        String pom = stripXmlComments(Files.readString(REPO.resolve("pom.xml"), StandardCharsets.UTF_8));
        Set<String> opts = values(pom, "javaOption");
        assertTrue(opts.size() >= 5, "expected the dist profile's javaOptions to be found, got " + opts);
        // The opt-in tunings are commented out and must not be read as shipped.
        assertFalse(opts.contains("-XX:+UseCompactObjectHeaders"), "an opt-in tuning leaked past the comment strip");
        return opts;
    }

    /** The option literals the trainer passes, Java comments removed. */
    private static Set<String> trainerOptions() throws IOException {
        String src = Files.readString(REPO.resolve("scripts/aot_build.java"), StandardCharsets.UTF_8);
        String body = stripJavaComments(src);
        int from = body.indexOf("cmd.addAll(List.of(");
        assertTrue(from >= 0, "could not find the trainer's option list in scripts/aot_build.java");
        int to = body.indexOf("));", from);
        assertTrue(to > from, "could not find the end of the trainer's option list");

        // "-m" starts the module and its program arguments (--config-dir, --new-file); everything
        // before it is a JVM option and everything after belongs to the app, not the launcher config.
        String list = body.substring(from, to);
        int module = list.indexOf("\"-m\"");
        if (module >= 0) {
            list = list.substring(0, module);
        }

        Set<String> opts = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"(-[^\"]*)\"").matcher(list);
        while (m.find()) {
            opts.add(m.group(1));
        }
        assertTrue(opts.size() >= 5, "expected the trainer's options to be found, got " + opts);
        assertFalse(opts.contains("--new-file"), "program arguments leaked into the JVM option list");
        return opts;
    }

    // --- pure helpers --------------------------------------------------------------------------

    private static Set<String> values(String xml, String tag) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(xml);
        while (m.find()) {
            String v = m.group(1).trim();
            if (v.startsWith("-")) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * An option reduced to its identity, so {@code -Dprism.order=es2,sw} and
     * {@code -Dprism.order=${prism.pipeline}} compare equal (the VALUE is allowed to differ; the
     * presence of the flag is not). {@code -XX:+UseG1GC} has no separator and stays whole, which is
     * why the GC gets its own assertion above.
     */
    private static String key(String option) {
        int eq = option.indexOf('=');
        return eq < 0 ? option : option.substring(0, eq);
    }

    private static Set<String> optionKeys(Set<String> options) {
        Set<String> out = new LinkedHashSet<>();
        for (String o : options) {
            out.add(key(o));
        }
        return out;
    }

    private static Set<String> gcOptions(Set<String> options) {
        Set<String> out = new LinkedHashSet<>();
        for (String o : options) {
            if (o.matches("-XX:[+-]Use\\w*GC") || o.startsWith("-XX:G1")) {
                out.add(o);
            }
        }
        return out;
    }

    private static Set<String> heapAndGc(Set<String> options) {
        Set<String> out = new LinkedHashSet<>(gcOptions(options));
        for (String o : options) {
            if (o.startsWith("-Xmx") || o.startsWith("-Xms")) {
                out.add(o);
            }
        }
        return out;
    }

    private static String stripXmlComments(String s) {
        return Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(s).replaceAll("");
    }

    private static String stripJavaComments(String s) {
        String noBlock =
                Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(s).replaceAll("");
        return noBlock.replaceAll("(?m)//.*$", "");
    }
}
