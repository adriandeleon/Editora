package com.editora.systemd;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a systemd unit file (INI-like: {@code [Section]} headers + {@code Key=value} directives, with
 * {@code #}/{@code ;} comments and trailing-backslash line continuations) into ordered sections. Directives
 * keep their order and duplicates (systemd allows repeated keys, e.g. several {@code ExecStartPre=}). Pure,
 * java.base-only, unit-tested.
 */
public final class SystemdUnit {

    /** One {@code Key=value} line. */
    public record Directive(String key, String value, int line) {}

    /** A {@code [Name]} section with its directives, in file order. */
    public record Section(String name, List<Directive> directives) {}

    private final List<Section> sections;

    private SystemdUnit(List<Section> sections) {
        this.sections = sections;
    }

    public List<Section> sections() {
        return sections;
    }

    /** The first directive with {@code key} (case-insensitive) in {@code section}, or {@code null}. */
    public String first(String section, String key) {
        for (Section s : sections) {
            if (s.name().equalsIgnoreCase(section)) {
                for (Directive d : s.directives()) {
                    if (d.key().equalsIgnoreCase(key)) {
                        return d.value();
                    }
                }
            }
        }
        return null;
    }

    /** All values for {@code key} (case-insensitive) in {@code section}, in order. */
    public List<String> all(String section, String key) {
        List<String> out = new ArrayList<>();
        for (Section s : sections) {
            if (s.name().equalsIgnoreCase(section)) {
                for (Directive d : s.directives()) {
                    if (d.key().equalsIgnoreCase(key)) {
                        out.add(d.value());
                    }
                }
            }
        }
        return out;
    }

    public boolean hasSection(String name) {
        return sections.stream().anyMatch(s -> s.name().equalsIgnoreCase(name));
    }

    public static SystemdUnit parse(String text) {
        List<Section> sections = new ArrayList<>();
        if (text == null) {
            return new SystemdUnit(sections);
        }
        Section current = null;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            // Join trailing-backslash continuation lines.
            StringBuilder joined = new StringBuilder(stripEol(lines[i]));
            int startLine = i;
            while (joined.length() > 0 && joined.charAt(joined.length() - 1) == '\\' && i + 1 < lines.length) {
                joined.setLength(joined.length() - 1);
                i++;
                joined.append(stripEol(lines[i]));
            }
            String line = joined.toString().strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                current = new Section(line.substring(1, line.length() - 1).strip(), new ArrayList<>());
                sections.add(current);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0 || current == null) {
                continue; // stray line outside a section, or no key=value — skip
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            current.directives().add(new Directive(key, value, startLine + 1));
        }
        return new SystemdUnit(sections);
    }

    private static String stripEol(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
