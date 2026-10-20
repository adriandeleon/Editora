package com.editora.maven;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

/**
 * What a {@code <profile>}'s {@code <activation>} says: whether it is active by default and the conditions
 * Maven evaluates — {@code <jdk>}, {@code <os>} (name/family/arch/version), {@code <property>}
 * (name, optional value, a {@code !} prefix for "absent") and {@code <file>} (exists/missing). One parse for
 * the pom summary preview (Maven review C12) and the build-actions tree (B7). Pure.
 *
 * @param conditions short notes in document order, each one condition: {@code "jdk [25,)"},
 *     {@code "property env.CI"}, {@code "property !skipTests"}, {@code "property env=prod"}, {@code "os linux"},
 *     {@code "file exists src/main/native"}, {@code "file missing target"}
 */
public record ProfileActivation(boolean activeByDefault, List<String> conditions) {

    public static final ProfileActivation NONE = new ProfileActivation(false, List.of());

    public ProfileActivation {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    /** Whether any condition is written (independently of {@code activeByDefault}). */
    public boolean conditional() {
        return !conditions.isEmpty();
    }

    /** The conditions joined for one line of text ({@code "jdk [25,), property env.CI"}); {@code ""} for none. */
    public String describe() {
        return String.join(", ", conditions);
    }

    /** Reads a {@code <activation>} element (null ⇒ {@link #NONE}). */
    public static ProfileActivation of(Element activation) {
        if (activation == null) {
            return NONE;
        }
        boolean byDefault = "true".equalsIgnoreCase(text(activation, "activeByDefault"));
        List<String> out = new ArrayList<>();
        String jdk = text(activation, "jdk");
        if (!jdk.isEmpty()) {
            out.add("jdk " + jdk);
        }
        Element os = PomParser.firstChildElement(activation, "os");
        if (os != null) {
            List<String> parts = new ArrayList<>();
            for (String tag : List.of("name", "family", "arch", "version")) {
                String v = text(os, tag);
                if (!v.isEmpty()) {
                    parts.add(v);
                }
            }
            if (!parts.isEmpty()) {
                out.add("os " + String.join(" ", parts));
            }
        }
        Element property = PomParser.firstChildElement(activation, "property");
        if (property != null) {
            String name = text(property, "name");
            String value = text(property, "value");
            if (!name.isEmpty()) {
                out.add("property " + name + (value.isEmpty() ? "" : "=" + value));
            }
        }
        Element file = PomParser.firstChildElement(activation, "file");
        if (file != null) {
            String exists = text(file, "exists");
            String missing = text(file, "missing");
            if (!exists.isEmpty()) {
                out.add("file exists " + exists);
            }
            if (!missing.isEmpty()) {
                out.add("file missing " + missing);
            }
        }
        return new ProfileActivation(byDefault, out);
    }

    private static String text(Element parent, String tag) {
        return PomParser.strip(PomParser.firstChildText(parent, tag));
    }
}
