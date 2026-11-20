package com.editora.toolchain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * JDK vendor names. Editora speaks jkit's names ({@code temurin}, {@code zulu}, {@code corretto},
 * {@code sapmachine}, {@code graalvm-ce}, …), which are foojay's distribution names with a few friendlier
 * aliases; this maps SDKMAN's identifier suffixes ({@code tem}, {@code amzn}, …) and a {@code release} file's
 * {@code IMPLEMENTOR} to them. Pure.
 */
public final class Vendors {

    private Vendors() {}

    /** foojay distribution → jkit's name, where they differ. */
    private static final Map<String, String> FRIENDLY = Map.of(
            "sap_machine", "sapmachine",
            "graalvm_community", "graalvm-ce",
            "liberica_native", "liberica-nik",
            "oracle_open_jdk", "openjdk");

    /** SDKMAN identifier suffix → jkit vendor. Ordered: the first suffix of a vendor is the one written back. */
    static final Map<String, String> SDKMAN = new LinkedHashMap<>();

    static {
        SDKMAN.put("tem", "temurin");
        SDKMAN.put("zulu", "zulu");
        SDKMAN.put("amzn", "corretto");
        SDKMAN.put("ms", "microsoft");
        SDKMAN.put("librca", "liberica");
        SDKMAN.put("nik", "liberica-nik");
        SDKMAN.put("oracle", "oracle");
        SDKMAN.put("open", "openjdk");
        SDKMAN.put("sapmchn", "sapmachine");
        SDKMAN.put("sem", "semeru");
        SDKMAN.put("graalce", "graalvm-ce");
        SDKMAN.put("graal", "graalvm");
        SDKMAN.put("mandrel", "mandrel");
        SDKMAN.put("albba", "dragonwell");
        SDKMAN.put("dragonwell", "dragonwell");
        SDKMAN.put("kona", "kona");
        SDKMAN.put("jbr", "jetbrains");
        SDKMAN.put("bisheng", "bisheng");
        SDKMAN.put("trava", "trava");
        SDKMAN.put("gln", "gluon-graalvm");
        SDKMAN.put("zulufx", "zulu"); // older SDKMAN wrote JavaFX builds as a vendor of their own
        SDKMAN.put("librcafx", "liberica");
    }

    /** jkit's name for foojay's {@code distribution} ({@code sap_machine} → {@code sapmachine}). */
    public static String fromFoojay(String distribution) {
        String d = distribution.strip().toLowerCase(Locale.ROOT);
        return FRIENDLY.getOrDefault(d, d.replace('_', '-'));
    }

    /** jkit's vendor for an SDKMAN suffix, or null for one Editora does not know. */
    public static @Nullable String fromSdkman(String suffix) {
        return SDKMAN.get(suffix.strip().toLowerCase(Locale.ROOT));
    }

    /** SDKMAN's suffix for a jkit vendor, or null when SDKMAN has none. */
    public static @Nullable String toSdkman(@Nullable String vendor) {
        if (vendor == null) {
            return null;
        }
        for (Map.Entry<String, String> e : SDKMAN.entrySet()) {
            if (e.getValue().equals(vendor)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Whether {@code name} is a vendor Editora knows by jkit's name or an SDKMAN suffix. */
    public static boolean known(String name) {
        String n = name.strip().toLowerCase(Locale.ROOT);
        return SDKMAN.containsKey(n)
                || SDKMAN.containsValue(n)
                || n.equals("redhat")
                || n.equals("openlogic")
                || n.equals("zulu-prime")
                || n.equals("adoptium");
    }

    /** jkit's vendor for a name typed by jkit's convention or SDKMAN's ({@code tem} and {@code temurin} alike). */
    public static String normalize(String name) {
        String n = name.strip().toLowerCase(Locale.ROOT);
        if (n.equals("adoptium")) {
            return "temurin";
        }
        String fromSuffix = SDKMAN.get(n);
        return fromSuffix != null ? fromSuffix : fromFoojay(n);
    }

    /**
     * The vendor a JDK's {@code release} file names in {@code IMPLEMENTOR} (and, for GraalVM, its
     * {@code GRAALVM_VERSION} / {@code IMPLEMENTOR_VERSION}), or null when it is not one Editora recognises.
     */
    public static @Nullable String fromRelease(@Nullable String implementor, @Nullable String implementorVersion) {
        String i = implementor == null ? "" : implementor.toLowerCase(Locale.ROOT);
        String v = implementorVersion == null ? "" : implementorVersion.toLowerCase(Locale.ROOT);
        if (v.contains("graalvm ce") || i.contains("graalvm community")) {
            return "graalvm-ce";
        }
        if (v.contains("corretto") || i.contains("amazon")) {
            return "corretto";
        }
        if (v.contains("zulu") || i.contains("azul")) {
            return "zulu";
        }
        if (v.contains("temurin") || i.contains("adoptium")) {
            return "temurin";
        }
        if (v.contains("microsoft") || i.contains("microsoft")) {
            return "microsoft";
        }
        if (v.contains("sapmachine") || i.contains("sap se")) {
            return "sapmachine";
        }
        if (i.contains("bellsoft")) {
            return "liberica";
        }
        if (i.contains("international business machines") || i.contains("ibm") || v.contains("semeru")) {
            return "semeru";
        }
        if (i.contains("jetbrains")) {
            return "jetbrains";
        }
        if (i.contains("alibaba")) {
            return "dragonwell";
        }
        if (i.contains("tencent")) {
            return "kona";
        }
        if (i.contains("red hat")) {
            return "redhat";
        }
        if (v.contains("graalvm") || i.contains("graalvm")) {
            return "graalvm";
        }
        if (i.contains("oracle")) {
            return v.contains("oracle") || v.isEmpty() ? "oracle" : "openjdk";
        }
        return null;
    }
}
