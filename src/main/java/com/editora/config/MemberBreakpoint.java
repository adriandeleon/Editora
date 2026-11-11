package com.editora.config;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * A breakpoint on a member of a class rather than on a line: a <em>method breakpoint</em> stops when the method is
 * entered, a <em>field watchpoint</em> when the field is written (or read). IntelliJ, Eclipse and NetBeans have
 * both; java-debug takes them as DAP function and data breakpoints named {@code pkg.Class#member}.
 *
 * <p>{@code className} is the binary name ({@code pkg.Outer$Inner}); a method breakpoint holds every overload of
 * {@code member} in that class, not in its subclasses. {@code access} applies to a field only: {@code write},
 * {@code read} or {@code readWrite}. It can be in a named {@code group}, as a line breakpoint can. Persisted per project in {@link BreakpointStore} ({@code breakpoints.json}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberBreakpoint(
        Kind kind,
        String className,
        String member,
        String access,
        String condition,
        String hitCondition,
        boolean enabled,
        String group) {

    /** A breakpoint in no group. */
    public MemberBreakpoint(
            Kind kind,
            String className,
            String member,
            String access,
            String condition,
            String hitCondition,
            boolean enabled) {
        this(kind, className, member, access, condition, hitCondition, enabled, "");
    }

    /** This breakpoint in the group {@code newGroup} (blank: in none). */
    public MemberBreakpoint withGroup(String newGroup) {
        return new MemberBreakpoint(kind, className, member, access, condition, hitCondition, enabled, newGroup);
    }

    /** A method's entry or a field's access. */
    public enum Kind {
        METHOD,
        FIELD
    }

    /** A field watchpoint stops when the field is assigned. */
    public static final String WRITE = "write";

    /** A field watchpoint stops when the field is read. */
    public static final String READ = "read";

    /** A field watchpoint stops when the field is read or assigned. */
    public static final String READ_WRITE = "readWrite";

    public MemberBreakpoint {
        kind = kind == null ? Kind.METHOD : kind;
        className = className == null ? "" : className.strip();
        member = member == null ? "" : member.strip();
        condition = condition == null ? "" : condition;
        hitCondition = hitCondition == null ? "" : hitCondition;
        group = Breakpoint.groupName(group);
        access = kind == Kind.METHOD ? "" : normalAccess(access);
    }

    /** A method breakpoint on every overload of {@code method} in {@code className}. */
    public static MemberBreakpoint method(String className, String method) {
        return new MemberBreakpoint(Kind.METHOD, className, method, "", "", "", true);
    }

    /** A watchpoint on {@code className}'s {@code field} that stops when {@code access} happens. */
    public static MemberBreakpoint field(String className, String field, String access) {
        return new MemberBreakpoint(Kind.FIELD, className, field, access, "", "", true);
    }

    private static String normalAccess(@Nullable String access) {
        if (READ.equals(access)) {
            return READ;
        }
        return READ_WRITE.equals(access) ? READ_WRITE : WRITE;
    }

    /** The name the debugger takes: {@code pkg.Class#member}. */
    public String qualifiedName() {
        return className + "#" + member;
    }

    /** Whether this and {@code other} are on the same member (the same breakpoint, whatever its settings). */
    public boolean sameMember(MemberBreakpoint other) {
        return other != null && kind == other.kind && className.equals(other.className) && member.equals(other.member);
    }

    /** {@code Class.member()} or {@code Class.field}: the class's simple name, nested ones joined by dots. */
    public String shortName() {
        String simple = className.substring(className.lastIndexOf('.') + 1).replace('$', '.');
        return simple + "." + member + (kind == Kind.METHOD ? "()" : "");
    }

    /**
     * A member typed by the user: {@code pkg.Class#member}, {@code pkg.Class::member} or {@code pkg.Class.member},
     * with an optional {@code ()} after a method. Null when it names no class and member.
     */
    public static @Nullable Named parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        if (t.endsWith("()")) {
            t = t.substring(0, t.length() - 2).strip();
        }
        int cut = t.indexOf('#');
        int skip = 1;
        if (cut < 0) {
            cut = t.indexOf("::");
            skip = 2;
        }
        if (cut < 0) {
            cut = t.lastIndexOf('.');
            skip = 1;
        }
        if (cut <= 0 || cut + skip >= t.length()) {
            return null;
        }
        String cls = t.substring(0, cut).strip();
        String name = t.substring(cut + skip).strip();
        if (!javaName(cls, true) || !(javaName(name, false) || "<init>".equals(name))) {
            return null;
        }
        return new Named(cls, name);
    }

    /** A class and one of its members, as {@link #parse} read them. */
    public record Named(String className, String member) {}

    /** A Java identifier, or — {@code dotted} — a dotted name of them (a {@code $} counts as a letter). */
    private static boolean javaName(String s, boolean dotted) {
        if (s.isEmpty()) {
            return false;
        }
        for (String part : dotted ? s.split("\\.", -1) : new String[] {s}) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
                return false;
            }
            for (int i = 1; i < part.length(); i++) {
                if (!Character.isJavaIdentifierPart(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {@code readWrite} → {@code read_write}: how an access is named in a message key. */
    public String accessKey() {
        return access.isEmpty() ? "" : access.replace("W", "_w").toLowerCase(Locale.ROOT);
    }

    public MemberBreakpoint withEnabled(boolean newEnabled) {
        return new MemberBreakpoint(kind, className, member, access, condition, hitCondition, newEnabled, group);
    }

    public MemberBreakpoint withCondition(String newCondition) {
        return new MemberBreakpoint(kind, className, member, access, newCondition, hitCondition, enabled, group);
    }

    public MemberBreakpoint withHitCondition(String newHitCondition) {
        return new MemberBreakpoint(kind, className, member, access, condition, newHitCondition, enabled, group);
    }

    public MemberBreakpoint withAccess(String newAccess) {
        return new MemberBreakpoint(kind, className, member, newAccess, condition, hitCondition, enabled, group);
    }
}
