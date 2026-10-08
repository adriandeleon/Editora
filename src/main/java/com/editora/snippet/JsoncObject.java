package com.editora.snippet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * The top-level object of a JSONC text, with where each member sits in the source — so a member can be
 * replaced, added or removed by splicing the text instead of re-serializing the file. That is what keeps a
 * user snippet file's comments, key order and formatting when Settings saves one snippet: only the member
 * that changed is rewritten. Pure (no file access).
 *
 * <p>A text holding nothing but whitespace and comments, or the literal {@code null}, is an empty object
 * that has no braces yet ({@link #open} is -1); the first {@link #with} writes them.
 */
final class JsoncObject {

    /** One {@code "name": value} member: {@code [keyStart, valueEnd)} is its whole source text. */
    record Member(String name, int keyStart, int valueStart, int valueEnd) {}

    /** The text is valid JSONC but its top level is not an object (an array, a string, …). */
    static final class NotAnObjectException extends IOException {
        private static final long serialVersionUID = 1L;

        NotAnObjectException() {
            super("not an object");
        }
    }

    final String text;
    /** Offset of the opening brace, or -1 when the text has no object yet. */
    final int open;
    /** Offset of the closing brace, or -1. */
    final int close;

    final List<Member> members;

    private JsoncObject(String text, int open, int close, List<Member> members) {
        this.text = text;
        this.open = open;
        this.close = close;
        this.members = members;
    }

    /** Parses {@code text} with {@code factory} (which decides whether comments and trailing commas are allowed). */
    static JsoncObject parse(String text, JsonFactory factory) throws IOException {
        String s = text == null ? "" : text;
        List<Member> members = new ArrayList<>();
        try (JsonParser p = factory.createParser(s)) {
            JsonToken first = p.nextToken();
            if (first == null || first == JsonToken.VALUE_NULL) {
                return new JsoncObject(s, -1, -1, members);
            }
            if (first != JsonToken.START_OBJECT) {
                throw new NotAnObjectException();
            }
            int open = (int) p.currentTokenLocation().getCharOffset();
            int close = -1;
            for (JsonToken t = p.nextToken(); t != null; t = p.nextToken()) {
                if (t == JsonToken.END_OBJECT) {
                    close = (int) p.currentTokenLocation().getCharOffset();
                    break;
                }
                String name = p.currentName();
                int keyStart = (int) p.currentTokenLocation().getCharOffset();
                JsonToken value = p.nextToken();
                int valueStart = (int) p.currentTokenLocation().getCharOffset();
                int valueEnd;
                if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                    p.skipChildren();
                    valueEnd = (int) p.currentTokenLocation().getCharOffset() + 1;
                } else {
                    valueEnd = scalarEnd(s, valueStart);
                }
                members.add(new Member(name, keyStart, valueStart, valueEnd));
            }
            p.nextToken(); // trailing garbage after the object is a syntax error like any other
            return new JsoncObject(s, open, close, members);
        }
    }

    /** End of the scalar starting at {@code from}: past the closing quote of a string, else the token's last char. */
    private static int scalarEnd(String s, int from) {
        if (from < s.length() && s.charAt(from) == '"') {
            for (int k = from + 1; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c == '\\') {
                    k++;
                } else if (c == '"') {
                    return k + 1;
                }
            }
            return s.length();
        }
        int k = from;
        while (k < s.length() && ",}]/ \t\r\n".indexOf(s.charAt(k)) < 0) {
            k++;
        }
        return k;
    }

    /** The last member called {@code name} (a duplicate key's last value is the one JSON readers keep), or null. */
    Member member(String name) {
        for (int i = members.size() - 1; i >= 0; i--) {
            if (members.get(i).name().equals(name)) {
                return members.get(i);
            }
        }
        return null;
    }

    /** The source text of {@code m}'s value. */
    String valueText(Member m) {
        return text.substring(m.valueStart(), m.valueEnd());
    }

    /** 1-based line of {@code offset}. */
    int lineOf(int offset) {
        int line = 1;
        for (int k = 0; k < offset && k < text.length(); k++) {
            if (text.charAt(k) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * The text with member {@code oldName} replaced in place by {@code "name": valueJson} — or, when there is
     * no such member ({@code oldName} null included), with the new member appended after the last one.
     * {@code quotedName} is the JSON string literal of the key; {@code valueJson} is pretty-printed from
     * column 0 and is re-indented to sit where it lands.
     */
    String with(String oldName, String quotedName, String valueJson) {
        String indent = memberIndent();
        String member = quotedName + ": " + valueJson.replace("\n", "\n" + indent);
        Member old = oldName == null ? null : member(oldName);
        if (old != null) {
            return text.substring(0, old.keyStart()) + member + text.substring(old.valueEnd());
        }
        if (open < 0) {
            String lead = text.isBlank() || text.strip().equals("null") ? "" : text.stripTrailing() + "\n";
            return lead + "{\n" + indent + member + "\n}\n";
        }
        if (members.isEmpty()) {
            String head = text.substring(0, close).stripTrailing();
            return head + "\n" + indent + member + "\n" + text.substring(close);
        }
        Member last = members.get(members.size() - 1);
        int comma = commaBetween(last.valueEnd(), close);
        if (comma >= 0) { // a trailing comma: the new member goes after it and needs none of its own
            return text.substring(0, comma + 1) + "\n" + indent + member + text.substring(comma + 1);
        }
        return text.substring(0, last.valueEnd()) + ",\n" + indent + member + text.substring(last.valueEnd());
    }

    /** The text without member {@code name} (and the comma that separated it); unchanged when absent. */
    String without(String name) {
        Member m = member(name);
        if (m == null) {
            return text;
        }
        int index = members.indexOf(m);
        int from = lineStartIfBlank(m.keyStart());
        int next = index + 1 < members.size() ? members.get(index + 1).keyStart() : close;
        int comma = commaBetween(m.valueEnd(), next);
        int to = comma >= 0 ? comma + 1 : m.valueEnd();
        to = throughLineEnd(to);
        String head = text.substring(0, from);
        if (comma < 0 && index > 0) { // it was the last member: the comma before it goes too
            int before = commaBetween(members.get(index - 1).valueEnd(), m.keyStart());
            if (before >= 0) {
                head = text.substring(0, before) + text.substring(before + 1, from);
            }
        }
        return head + text.substring(to);
    }

    /** The indentation members use: the first member's, else two spaces. */
    private String memberIndent() {
        if (!members.isEmpty()) {
            int key = members.get(0).keyStart();
            int start = lineStartIfBlank(key);
            if (start < key) {
                return text.substring(start, key);
            }
        }
        return "  ";
    }

    /** The start of {@code offset}'s line when only blanks precede it there; else {@code offset}. */
    private int lineStartIfBlank(int offset) {
        int k = offset;
        while (k > 0 && (text.charAt(k - 1) == ' ' || text.charAt(k - 1) == '\t')) {
            k--;
        }
        return k == 0 || text.charAt(k - 1) == '\n' ? k : offset;
    }

    /** Past the line break after {@code offset} when only blanks stand between; else {@code offset}. */
    private int throughLineEnd(int offset) {
        int k = offset;
        while (k < text.length() && (text.charAt(k) == ' ' || text.charAt(k) == '\t' || text.charAt(k) == '\r')) {
            k++;
        }
        return k < text.length() && text.charAt(k) == '\n' ? k + 1 : offset;
    }

    /** The first comma in {@code [from, to)} that is not inside a comment, or -1. */
    private int commaBetween(int from, int to) {
        for (int k = from; k < to && k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == ',') {
                return k;
            }
            if (c == '/' && k + 1 < to) {
                char n = text.charAt(k + 1);
                if (n == '/') {
                    int nl = text.indexOf('\n', k);
                    k = nl < 0 ? to : nl;
                } else if (n == '*') {
                    int end = text.indexOf("*/", k + 2);
                    k = end < 0 ? to : end + 1;
                }
            }
        }
        return -1;
    }
}
