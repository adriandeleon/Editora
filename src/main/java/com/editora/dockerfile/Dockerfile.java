package com.editora.dockerfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses a Dockerfile into build stages for the preview. Handles trailing-backslash line continuations and
 * {@code #} comments (a {@code # syntax=} parser directive is just a comment here). Each {@code FROM} starts
 * a stage ({@code FROM image [AS name]}); {@code ARG}s before the first {@code FROM} are global build args.
 * Pure, java.base-only, unit-tested.
 */
public final class Dockerfile {

    public record Instruction(String keyword, String args, int line) {}

    public record Stage(String baseImage, String name, int index, List<Instruction> instructions) {}

    private final List<Instruction> globalArgs;
    private final List<Stage> stages;

    private Dockerfile(List<Instruction> globalArgs, List<Stage> stages) {
        this.globalArgs = globalArgs;
        this.stages = stages;
    }

    public List<Instruction> globalArgs() {
        return globalArgs;
    }

    public List<Stage> stages() {
        return stages;
    }

    public static Dockerfile parse(String text) {
        List<Instruction> globalArgs = new ArrayList<>();
        List<Stage> stages = new ArrayList<>();
        if (text == null) {
            return new Dockerfile(globalArgs, stages);
        }
        Stage current = null;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            StringBuilder joined = new StringBuilder(stripEol(lines[i]));
            int startLine = i;
            while (endsWithContinuation(joined) && i + 1 < lines.length) {
                joined.setLength(joined.length() - 1);
                i++;
                joined.append('\n').append(stripEol(lines[i]));
            }
            // Strip comment-only lines (after continuation join, a leading # means the whole logical line is a
            // comment).
            String first = firstNonBlankLine(joined.toString());
            if (first.isEmpty() || first.startsWith("#")) {
                continue;
            }
            String logical = joined.toString().strip();
            int sp = indexOfWhitespace(logical);
            if (sp < 0) {
                continue;
            }
            String keyword = logical.substring(0, sp).toUpperCase(Locale.ROOT);
            String args = logical.substring(sp).strip();
            Instruction ins = new Instruction(keyword, args, startLine + 1);
            if (keyword.equals("FROM")) {
                current = fromStage(args, stages.size() + 1, startLine + 1);
                stages.add(current);
            } else if (current == null) {
                if (keyword.equals("ARG")) {
                    globalArgs.add(ins);
                }
                // other instructions before the first FROM are invalid — ignore
            } else {
                current.instructions().add(ins);
            }
        }
        return new Dockerfile(globalArgs, stages);
    }

    private static Stage fromStage(String args, int index, int line) {
        String[] tok = args.split("\\s+");
        String baseImage = tok.length > 0 ? tok[0] : "";
        String name = null;
        for (int i = 1; i + 1 < tok.length; i++) {
            if (tok[i].equalsIgnoreCase("AS")) {
                name = tok[i + 1];
                break;
            }
        }
        return new Stage(baseImage, name, index, new ArrayList<>());
    }

    private static boolean endsWithContinuation(CharSequence s) {
        return s.length() > 0 && s.charAt(s.length() - 1) == '\\';
    }

    private static String firstNonBlankLine(String s) {
        for (String l : s.split("\n", -1)) {
            String t = l.strip();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return "";
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String stripEol(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
