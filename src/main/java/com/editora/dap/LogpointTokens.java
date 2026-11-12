package com.editora.dap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Pure: Visual Studio's tracepoint tokens in a logpoint's message — {@code $FUNCTION}, {@code $CALLER},
 * {@code $CALLSTACK}, {@code $TID} and {@code $TNAME}. No adapter knows them, so a logpoint that uses one is sent as a
 * plain breakpoint (its condition and hit count kept), and Editora writes its message itself at the stop: the
 * {@code {expression}} parts evaluated in the stopped frame, the tokens from the stack and the thread, and the program
 * goes on.
 */
public final class LogpointTokens {

    private LogpointTokens() {}

    private static final Pattern TOKEN = Pattern.compile("\\$(FUNCTION|CALLSTACK|CALLER|TID|TNAME)\\b");

    /** Whether {@code message} uses a token, so Editora has to write it (the adapter would print it as typed). */
    public static boolean uses(@Nullable String message) {
        return message != null && TOKEN.matcher(message).find();
    }

    /** What a token stands for at one stop. */
    public record Stop(String function, String caller, List<String> callStack, int threadId, String threadName) {}

    /** A message cut into its text and its {@code {expression}} parts, in order. */
    public record Part(String text, boolean expression) {}

    /**
     * {@code message} cut at its {@code {…}} parts; braces inside one pair up ({@code {new int[]{1}.length}}), even
     * in a string literal. An unclosed {@code {} is text.
     */
    public static List<Part> parts(String message) {
        List<Part> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < message.length()) {
            char c = message.charAt(i);
            if (c == '{') {
                int depth = 0;
                int end = -1;
                for (int j = i; j < message.length(); j++) {
                    char d = message.charAt(j);
                    if (d == '{') {
                        depth++;
                    } else if (d == '}' && --depth == 0) {
                        end = j;
                        break;
                    }
                }
                if (end > i + 1) {
                    if (!text.isEmpty()) {
                        out.add(new Part(text.toString(), false));
                        text.setLength(0);
                    }
                    out.add(new Part(message.substring(i + 1, end).strip(), true));
                    i = end + 1;
                    continue;
                }
            }
            text.append(c);
            i++;
        }
        if (!text.isEmpty()) {
            out.add(new Part(text.toString(), false));
        }
        return out;
    }

    /** The {@code {expression}} parts of {@code message}, in order. */
    public static List<String> expressions(String message) {
        return parts(message).stream().filter(Part::expression).map(Part::text).toList();
    }

    /**
     * The line logged: {@code message} with each {@code {expression}} replaced by {@code values} (its value, or what
     * the adapter said instead) and each token by what it stands for at {@code stop}. {@code $CALLSTACK} is the frames,
     * one per line below the message's own.
     */
    public static String expand(String message, Function<String, String> values, Stop stop) {
        StringBuilder out = new StringBuilder();
        for (Part part : parts(message)) {
            out.append(part.expression() ? values.apply(part.text()) : tokens(part.text(), stop));
        }
        return out.toString();
    }

    private static String tokens(String text, Stop stop) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = switch (m.group(1)) {
                case "FUNCTION" -> stop.function();
                case "CALLER" -> stop.caller();
                case "CALLSTACK" -> stop.callStack().isEmpty() ? "" : "\n\t" + String.join("\n\t", stop.callStack());
                case "TID" -> String.valueOf(stop.threadId());
                default -> stop.threadName();
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
