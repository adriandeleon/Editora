package org.adriandeleon.editora.editor;

public final class EmacsNavigation {

    private EmacsNavigation() {
    }

    public record CaretMove(int caretPosition, int goalColumn) {
    }

    public static int backwardChar(String text, int caretPosition) {
        return Math.max(0, clamp(text, caretPosition) - 1);
    }

    public static int forwardChar(String text, int caretPosition) {
        int caret = clamp(text, caretPosition);
        return Math.min(length(text), caret + 1);
    }

    public static int lineStart(String text, int caretPosition) {
        int caret = clamp(text, caretPosition);
        int previousNewline = safeText(text).lastIndexOf('\n', Math.max(0, caret - 1));
        return previousNewline < 0 ? 0 : previousNewline + 1;
    }

    public static int lineEnd(String text, int caretPosition) {
        int caret = clamp(text, caretPosition);
        int nextNewline = safeText(text).indexOf('\n', caret);
        return nextNewline < 0 ? length(text) : nextNewline;
    }

    public static CaretMove previousLine(String text, int caretPosition, Integer preferredGoalColumn) {
        int caret = clamp(text, caretPosition);
        int currentLineStart = lineStart(text, caret);
        int goalColumn = preferredGoalColumn == null ? caret - currentLineStart : Math.max(0, preferredGoalColumn);

        if (currentLineStart == 0) {
            return new CaretMove(Math.min(goalColumn, lineEnd(text, 0)), goalColumn);
        }

        int previousLineEnd = currentLineStart - 1;
        int previousLineStart = lineStart(text, previousLineEnd);
        int target = Math.min(previousLineStart + goalColumn, previousLineEnd);
        return new CaretMove(target, goalColumn);
    }

    public static CaretMove nextLine(String text, int caretPosition, Integer preferredGoalColumn) {
        int caret = clamp(text, caretPosition);
        int currentLineStart = lineStart(text, caret);
        int currentLineEnd = lineEnd(text, caret);
        int goalColumn = preferredGoalColumn == null ? caret - currentLineStart : Math.max(0, preferredGoalColumn);

        if (currentLineEnd >= length(text)) {
            return new CaretMove(currentLineEnd, goalColumn);
        }

        int nextLineStart = currentLineEnd + 1;
        int nextLineEnd = lineEnd(text, nextLineStart);
        int target = Math.min(nextLineStart + goalColumn, nextLineEnd);
        return new CaretMove(target, goalColumn);
    }

    public static int backwardWord(String text, int caretPosition) {
        String value = safeText(text);
        int index = clamp(value, caretPosition);

        if (index == 0) {
            return 0;
        }
        if (isWordCharacter(value.charAt(index - 1))) {
            while (index > 0 && isWordCharacter(value.charAt(index - 1))) {
                index--;
            }
            return index;
        }

        while (index > 0 && !isWordCharacter(value.charAt(index - 1))) {
            index--;
        }
        while (index > 0 && isWordCharacter(value.charAt(index - 1))) {
            index--;
        }
        return index;
    }

    public static int forwardWord(String text, int caretPosition) {
        String value = safeText(text);
        int index = clamp(value, caretPosition);
        int length = value.length();

        if (index >= length) {
            return length;
        }
        if (isWordCharacter(value.charAt(index))) {
            while (index < length && isWordCharacter(value.charAt(index))) {
                index++;
            }
            return index;
        }

        while (index < length && !isWordCharacter(value.charAt(index))) {
            index++;
        }
        while (index < length && isWordCharacter(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static boolean isWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    private static int clamp(String text, int caretPosition) {
        return Math.max(0, Math.min(caretPosition, length(text)));
    }

    private static int length(String text) {
        return safeText(text).length();
    }

    private static String safeText(String text) {
        return text == null ? "" : text;
    }
}

