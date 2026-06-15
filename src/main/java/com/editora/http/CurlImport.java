package com.editora.http;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a {@code curl …} command line into a {@code .http} request block. Tokenizes the command
 * (single/double quotes, {@code \}-newline continuations), maps the common flags ({@code -X}/{@code --request},
 * {@code -H}/{@code --header}, {@code -d}/{@code --data*}, {@code -u}/{@code --user}, {@code -A},
 * {@code -b}, {@code --url}), and infers {@code POST} + a form Content-Type when a data flag is present and
 * no method/Content-Type was given. Pure, so it is unit-tested.
 */
public final class CurlImport {

    private CurlImport() {}

    /** Builds a {@code METHOD url} + headers + body request block from a {@code curl} command. */
    public static String toHttpRequest(String curl) {
        List<String> tokens = tokenize(curl);
        String method = null;
        String url = null;
        List<String[]> headers = new ArrayList<>();
        String data = null;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            switch (t) {
                case "curl" -> {}
                case "-X", "--request" -> {
                    if (i + 1 < tokens.size()) {
                        method = tokens.get(++i);
                    }
                }
                case "-H", "--header" -> {
                    if (i + 1 < tokens.size()) {
                        headers.add(splitHeader(tokens.get(++i)));
                    }
                }
                case "-d", "--data", "--data-raw", "--data-binary", "--data-ascii" -> {
                    if (i + 1 < tokens.size()) {
                        String d = tokens.get(++i);
                        data = data == null ? d : data + "&" + d;
                    }
                }
                case "-u", "--user" -> {
                    if (i + 1 < tokens.size()) {
                        headers.add(userHeader(tokens.get(++i)));
                    }
                }
                case "-A", "--user-agent" -> {
                    if (i + 1 < tokens.size()) {
                        headers.add(new String[] {"User-Agent", tokens.get(++i)});
                    }
                }
                case "-b", "--cookie" -> {
                    if (i + 1 < tokens.size()) {
                        headers.add(new String[] {"Cookie", tokens.get(++i)});
                    }
                }
                case "--url" -> {
                    if (i + 1 < tokens.size()) {
                        url = tokens.get(++i);
                    }
                }
                default -> {
                    if (!t.startsWith("-") && url == null) {
                        url = t;
                    }
                    // other flags (e.g. -L, -k, -s) are ignored — best effort
                }
            }
        }
        if (method == null) {
            method = data != null ? "POST" : "GET";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url == null ? "" : url).append('\n');
        boolean hasContentType = headers.stream().anyMatch(h -> h[0].equalsIgnoreCase("Content-Type"));
        if (data != null && !hasContentType) {
            headers.add(new String[] {"Content-Type", "application/x-www-form-urlencoded"});
        }
        for (String[] h : headers) {
            sb.append(h[0]).append(": ").append(h[1]).append('\n');
        }
        if (data != null) {
            sb.append('\n').append(data).append('\n');
        }
        return sb.toString();
    }

    private static String[] splitHeader(String h) {
        int colon = h.indexOf(':');
        return colon > 0
                ? new String[] {
                    h.substring(0, colon).strip(), h.substring(colon + 1).strip()
                }
                : new String[] {h.strip(), ""};
    }

    private static String[] userHeader(String userPass) {
        int colon = userPass.indexOf(':');
        String user = colon >= 0 ? userPass.substring(0, colon) : userPass;
        String pass = colon >= 0 ? userPass.substring(colon + 1) : "";
        return new String[] {"Authorization", HttpAuth.basic(user, pass)};
    }

    /** Splits a curl command into tokens, honoring quotes and {@code \}-newline continuations. */
    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inTok = false;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && (s.charAt(i + 1) == '\n' || s.charAt(i + 1) == '\r')) {
                i += 2; // line continuation
                continue;
            }
            if (c == '\'' || c == '"') {
                inTok = true;
                char q = c;
                i++;
                while (i < n && s.charAt(i) != q) {
                    if (q == '"' && s.charAt(i) == '\\' && i + 1 < n) {
                        cur.append(s.charAt(i + 1));
                        i += 2;
                    } else {
                        cur.append(s.charAt(i));
                        i++;
                    }
                }
                i++; // closing quote
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inTok) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inTok = false;
                }
                i++;
                continue;
            }
            inTok = true;
            cur.append(c);
            i++;
        }
        if (inTok) {
            out.add(cur.toString());
        }
        return out;
    }
}
