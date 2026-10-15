package com.editora.http;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the secrets a request would read from outside its file: {@code {{$processEnv.NAME}}} (any environment
 * variable of the editor process) and {@code {{$dotenv.NAME}}} (the {@code .env} beside the request).
 *
 * <p>A {@code .http} file is content, often one that arrived in a cloned repository, and one click sends what it
 * resolves to whatever host it names — so a request that reads {@code $processEnv.AWS_SECRET_ACCESS_KEY} is the
 * one case where running it needs the folder to be trusted first. References are followed through the file's
 * {@code @var} declarations and the environment's values, so hiding the read behind a variable does not hide it.
 * Pure, so it is unit-tested.
 */
public final class SecretReads {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([^}]*?)\\s*\\}\\}");

    private SecretReads() {}

    /**
     * The secret reads of a request, in the order found (e.g. {@code $processEnv.TOKEN}); empty when it reads none.
     *
     * @param requestText the request's own text — URL, headers, body, multipart parts and any {@code <@} body
     *     file content, as written (unsubstituted)
     * @param definitions the raw (unsubstituted) values a {@code {{name}}} can stand for: the file's {@code @var}
     *     declarations and the selected environment's values, in any order
     */
    public static List<String> find(String requestText, Map<String, List<String>> definitions) {
        Set<String> found = new LinkedHashSet<>();
        Set<String> seen = new java.util.HashSet<>();
        Deque<String> texts = new ArrayDeque<>();
        texts.add(requestText == null ? "" : requestText);
        while (!texts.isEmpty()) {
            Matcher m = VAR.matcher(texts.removeFirst());
            while (m.find()) {
                String name = m.group(1).strip();
                if (isSecretRead(name)) {
                    found.add(normalize(name));
                } else if (seen.add(name) && definitions != null) {
                    List<String> values = definitions.get(name);
                    if (values != null) {
                        texts.addAll(values); // a variable that stands for another reference
                    }
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** Whether a {@code {{…}}} reference reads the process environment or a {@code .env} file. */
    public static boolean isSecretRead(String reference) {
        String n = reference == null ? "" : reference.strip();
        return n.startsWith("$processEnv") || n.startsWith("$dotenv") || n.startsWith("$env.");
    }

    private static String normalize(String name) {
        return name.replaceAll("\\s+", "");
    }
}
